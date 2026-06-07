package com.yukimura.oogabooga.bot;

import com.yukimura.oogabooga.ai.MovementKind;
import com.yukimura.oogabooga.ai.PathStep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import static com.yukimura.oogabooga.bot.BotMath.cardinalToward;
import static com.yukimura.oogabooga.bot.BotTuning.BRIDGE_FORWARD;
import static com.yukimura.oogabooga.bot.BotTuning.BUILD_BLOCK;
import static com.yukimura.oogabooga.bot.BotTuning.LOOK_PITCH_DOWN;
import static com.yukimura.oogabooga.bot.BotTuning.PLACE_FAIL_LIMIT;
import static com.yukimura.oogabooga.bot.BotTuning.PLACE_REACH_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.SUPPORT_SEARCH_ORDER;

final class TerrainModifier {

    private final TerminatorBot bot;
    private @Nullable BlockPos miningTarget = null;
    private float miningProgress = 0f;
    private boolean breakModeActive = false;
    private int placeFailTicks = 0;

    TerrainModifier(TerminatorBot bot) {
        this.bot = bot;
    }

    boolean isBreakingOrMining() {
        return this.breakModeActive || this.miningTarget != null;
    }

    boolean isMidBreak() {
        return this.breakModeActive && this.miningTarget != null;
    }

    void clearPlaceFailTicks() {
        this.placeFailTicks = 0;
    }

    void breakBlockInFront(float bodyYaw) {
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos frontFeet = bot.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        BlockPos obstruction = this.isBreakableObstruction(frontHead) ? frontHead : frontFeet;
        this.progressMine(obstruction);
    }

    void runBreakStep(PathStep step) {
        bot.setSprinting(false);
        bot.setJumping(false);
        bot.wantedUpward = 0f;
        bot.wantedJumping = false;

        if (step.kind() == MovementKind.BREAK_DOWN) {
            bot.wantedForward = 0f;
            BlockPos floorCell = bot.blockPosition().below();
            if (this.isBreakableObstruction(floorCell)) {
                this.breakModeActive = true;
                bot.lookAtCell(floorCell);
                this.progressMine(floorCell);
            } else {
                this.breakModeActive = false;
                this.clearMiningProgress();
            }
            return;
        }

        BlockPos previous = bot.navigator.previousStepPosition();
        BlockPos destination = step.position();
        Direction facing = cardinalToward(previous, destination);
        bot.setYRot(facing.toYRot());
        bot.setYBodyRot(facing.toYRot());

        BlockPos destinationHead = destination.above();
        BlockPos jumpHeadroom = bot.blockPosition().above().above();
        BlockPos obstruction = null;
        if (step.kind() == MovementKind.BREAK_UP && this.isBreakableObstruction(jumpHeadroom)) {
            obstruction = jumpHeadroom;
        } else if (this.isBreakableObstruction(destinationHead)) {
            obstruction = destinationHead;
        } else if (this.isBreakableObstruction(destination)) {
            obstruction = destination;
        }

        if (obstruction != null) {
            this.breakModeActive = true;
            bot.wantedForward = 0f;
            bot.lookAtCell(obstruction);
            this.progressMine(obstruction);
            bot.dampCrossAxis(facing);
        } else {
            this.breakModeActive = false;
            this.clearMiningProgress();
            bot.wantedForward = 1.0f;
            bot.wantedJumping = step.kind() == MovementKind.BREAK_UP && bot.isBlockBelow();
        }
    }

    boolean isBreakableObstruction(BlockPos position) {
        BlockState state = bot.level().getBlockState(position);
        if (state.isAir() || state.getCollisionShape(bot.level(), position).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(bot.level(), position) >= 0.0f;
    }

    boolean progressMine(BlockPos target) {
        BlockState state = bot.level().getBlockState(target);
        if (state.isAir()
                || state.getCollisionShape(bot.level(), target).isEmpty()
                || state.getDestroySpeed(bot.level(), target) < 0.0f) {
            this.clearMiningProgress();
            return false;
        }
        if (!target.equals(this.miningTarget)) {
            this.clearMiningProgress();
            this.miningTarget = target;
        }
        bot.ensureHolding(selectMiningTool(state));
        this.miningProgress += state.getDestroyProgress(bot, bot.level(), target);
        bot.swing(InteractionHand.MAIN_HAND);
        int stage = Mth.clamp((int) (this.miningProgress * 10.0f), 0, 9);
        bot.level().destroyBlockProgress(bot.getId(), target, stage);
        if (this.miningProgress >= 1.0f) {
            bot.level().destroyBlock(target, false);
            this.clearMiningProgress();
            return true;
        }
        return false;
    }

    void clearMiningProgress() {
        if (this.miningTarget != null) {
            bot.level().destroyBlockProgress(bot.getId(), this.miningTarget, -1);
            this.miningTarget = null;
        }
        this.miningProgress = 0f;
    }

    private static Item selectMiningTool(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Items.DIAMOND_PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Items.DIAMOND_SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Items.DIAMOND_AXE;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return Items.DIAMOND_HOE;
        return Items.AIR;
    }

    boolean placeBlockSurvival(BlockPos cell, @Nullable ServerPlayer target) {
        if (bot.isSolid(cell)) {
            return true;
        }
        if (!this.canPlaceBuildBlock(cell, target)) {
            return false;
        }
        Direction towardSupport = this.findSupportDirection(cell);
        if (towardSupport == null) {
            return false;
        }
        BlockPos supportPos = cell.relative(towardSupport);
        Direction face = towardSupport.getOpposite();
        Vec3 hitVec = Vec3.atCenterOf(supportPos)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceToSqr(hitVec) > PLACE_REACH_SQ) {
            return false;
        }
        bot.lookAt(hitVec.x, hitVec.y, hitVec.z);
        BlockHitResult sightLine = bot.level().clip(new ClipContext(
                eye, hitVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        if (sightLine.getType() == HitResult.Type.BLOCK
                && !sightLine.getBlockPos().equals(supportPos)) {
            return false;
        }
        bot.holdBuildBlock();
        BlockHitResult hit = new BlockHitResult(hitVec, face, supportPos, false);
        bot.getMainHandItem().useOn(new UseOnContext(bot, InteractionHand.MAIN_HAND, hit));
        boolean placed = bot.level().getBlockState(cell).is(BUILD_BLOCK);
        if (placed) {
            bot.swing(InteractionHand.MAIN_HAND);
        }
        return placed;
    }

    private @Nullable Direction findSupportDirection(BlockPos cell) {
        for (Direction direction : SUPPORT_SEARCH_ORDER) {
            if (bot.isSolid(cell.relative(direction))) {
                return direction;
            }
        }
        return null;
    }

    private boolean canPlaceBuildBlock(BlockPos cell, @Nullable ServerPlayer target) {
        BlockState existing = bot.level().getBlockState(cell);
        if (!existing.canBeReplaced() && !existing.isAir()) {
            return false;
        }
        if (target != null
                && (cell.equals(target.blockPosition()) || cell.equals(target.blockPosition().above()))) {
            return false;
        }
        if (cell.equals(bot.blockPosition()) || cell.equals(bot.blockPosition().above())) {
            return false;
        }
        return bot.level().isUnobstructed(BUILD_BLOCK.defaultBlockState(), cell, CollisionContext.empty());
    }

    void runPlaceStep(PathStep step, ServerPlayer target) {
        bot.wantedUpward = 0f;
        bot.wantedJumping = false;
        bot.setJumping(false);
        bot.setSprinting(false);
        bot.setShiftKeyDown(true);

        BlockPos previous = bot.navigator.previousStepPosition();
        BlockPos destination = step.position();
        Direction facing = cardinalToward(previous, destination);
        bot.setYRot(facing.toYRot());
        bot.setYBodyRot(facing.toYRot());

        bot.holdBuildBlock();
        BlockPos floorCell = destination.below();
        boolean supported = bot.isSolid(floorCell) || this.placeBlockSurvival(floorCell, target);
        if (supported) {
            this.placeFailTicks = 0;
            boolean standing = bot.isBlockBelow() && bot.isSolid(floorCell);
            bot.wantedForward = standing ? BRIDGE_FORWARD : 0.0f;
        } else {
            this.placeFailTicks++;
            bot.wantedForward = 0.0f;
            if (this.placeFailTicks > PLACE_FAIL_LIMIT) {
                bot.navigator.clearPath();
                this.placeFailTicks = 0;
            }
        }
        bot.lookAlongBody(LOOK_PITCH_DOWN);
        bot.dampCrossAxis(facing);
    }

    boolean tryPatchLaneAhead(float bodyYaw, ServerPlayer target) {
        if (bot.isInWater() || bot.isInLava() || !bot.isBlockBelow()) {
            return false;
        }
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos frontFeet = bot.blockPosition().relative(facing);
        BlockPos frontSupport = frontFeet.below();
        boolean shallowWaterAhead = bot.level().getFluidState(frontFeet).is(FluidTags.WATER)
                && bot.isSolid(frontSupport)
                && bot.isPassable(frontFeet.above());
        if (shallowWaterAhead) {
            return this.placeBlockSurvival(frontFeet, target);
        }
        boolean oneDeepHoleAhead = bot.isPassable(frontFeet)
                && bot.isPassable(frontSupport)
                && bot.isSolid(frontSupport.below());
        if (oneDeepHoleAhead) {
            return this.placeBlockSurvival(frontSupport, target);
        }
        return false;
    }
}
