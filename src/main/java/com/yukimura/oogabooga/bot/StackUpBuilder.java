package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import static com.yukimura.oogabooga.bot.BotMath.cardinalToward;
import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.BUILD_LEVEL_EPSILON;
import static com.yukimura.oogabooga.bot.BotTuning.BUILD_REACH_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.BUILD_STALL_LIMIT;
import static com.yukimura.oogabooga.bot.BotTuning.NO_PROGRESS_STACK_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.PILLAR_CENTER_TOLERANCE;
import static com.yukimura.oogabooga.bot.BotTuning.PILLAR_JUMP_TIMEOUT_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_ABORT_HORIZONTAL_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_MAX_BLOCKS;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_OVERRIDE_HORIZONTAL_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_STEEPNESS_RATIO;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_TRIGGER_HORIZONTAL_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_TRIGGER_MIN_HEIGHT;

final class StackUpBuilder {

    private final TerminatorBot bot;
    private boolean stackingUp = false;
    private boolean placePending = false;
    private int placeFloorY = Integer.MIN_VALUE;
    private int placePendingTicks = 0;
    private int pillarX = Integer.MIN_VALUE;
    private int pillarZ = Integer.MIN_VALUE;
    private int pillarBlocksPlaced = 0;
    private double buildBestY = Double.NEGATIVE_INFINITY;
    private double buildBestHorizontalSq = Double.MAX_VALUE;
    private int buildStallTicks = 0;

    StackUpBuilder(TerminatorBot bot) {
        this.bot = bot;
    }

    boolean isActive() {
        return this.stackingUp;
    }

    boolean isPlacePending() {
        return this.placePending;
    }

    void clearPillarCount() {
        this.pillarBlocksPlaced = 0;
    }

    boolean shouldStackUp(ServerPlayer target) {
        if (!bot.isBlockBelow() && (bot.isInWater() || bot.isInLava())) {
            return false;
        }
        if (this.pillarBlocksPlaced >= STACK_MAX_BLOCKS) {
            return false;
        }
        double deltaX = target.getX() - bot.getX();
        double deltaZ = target.getZ() - bot.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        double heightToPlayer = target.getY() - bot.getY();

        if (this.stackingUp) {
            if (horizontalSquared <= BUILD_REACH_SQ && heightToPlayer <= 1.5) {
                return false;
            }
            if (heightToPlayer < -2.0) {
                return false;
            }
            if (horizontalSquared > STACK_ABORT_HORIZONTAL_SQ) {
                return false;
            }
            if (this.buildStallTicks > BUILD_STALL_LIMIT) {
                return false;
            }
            return true;
        }

        boolean hasUpwardPath = bot.navigator.reachesTarget() && bot.navigator.maxPathY() >= target.getY() - 1.0;

        boolean upwardStalled = bot.noProgressTicks > NO_PROGRESS_STACK_TICKS;
        if (upwardStalled
                && heightToPlayer > STACK_TRIGGER_MIN_HEIGHT
                && horizontalSquared <= STACK_OVERRIDE_HORIZONTAL_SQ
                && bot.isBlockBelow()) {
            return true;
        }

        if (upwardStalled
                && !hasUpwardPath
                && heightToPlayer > STACK_TRIGGER_MIN_HEIGHT
                && horizontalSquared <= STACK_ABORT_HORIZONTAL_SQ
                && bot.isBlockBelow()) {
            return true;
        }

        if (bot.inHitRecovery()
                && heightToPlayer > STACK_TRIGGER_MIN_HEIGHT
                && horizontalSquared <= STACK_OVERRIDE_HORIZONTAL_SQ
                && bot.isBlockBelow()
                && !hasUpwardPath) {
            return true;
        }

        boolean steepClimb = heightToPlayer > STACK_TRIGGER_MIN_HEIGHT
                && heightToPlayer * heightToPlayer >= horizontalSquared * STACK_STEEPNESS_RATIO * STACK_STEEPNESS_RATIO;
        if (steepClimb
                && !hasUpwardPath
                && horizontalSquared <= STACK_OVERRIDE_HORIZONTAL_SQ
                && bot.isBlockBelow()) {
            return true;
        }

        if (heightToPlayer <= STACK_TRIGGER_MIN_HEIGHT || horizontalSquared > STACK_TRIGGER_HORIZONTAL_SQ) {
            return false;
        }
        if (hasUpwardPath) {
            return false;
        }
        return bot.isBlockBelow();
    }

    void enterStackUp() {
        this.stackingUp = true;
        this.placePending = false;
        this.placePendingTicks = 0;
        bot.terrain.clearMiningProgress();
        bot.resetProgressWatchdog();
        this.pillarBlocksPlaced = 0;
        this.pillarX = Mth.floor(bot.getX());
        this.pillarZ = Mth.floor(bot.getZ());
        this.buildBestY = bot.getY();
        this.buildBestHorizontalSq = Double.MAX_VALUE;
        this.buildStallTicks = 0;
        if (!bot.cobbleEquipped) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE));
            bot.cobbleEquipped = true;
        }
    }

    void exitStackUp() {
        this.stackingUp = false;
        this.placePending = false;
        bot.terrain.clearMiningProgress();
        if (bot.cobbleEquipped) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            bot.cobbleEquipped = false;
        }
    }

    void runStackUp(ServerPlayer target) {
        bot.setSprinting(false);
        bot.wantedUpward = 0f;
        bot.wantedForward = 0f;

        if (this.placePending) {
            this.finishPillarPlacement(target);
        } else if (!bot.isBlockBelow()) {
            bot.wantedJumping = false;
        } else if (target.getY() - bot.getY() > BUILD_LEVEL_EPSILON) {
            this.pillarX = Mth.floor(bot.getX());
            this.pillarZ = Mth.floor(bot.getZ());
            BlockPos obstruction = this.findHeadObstruction();
            if (obstruction != null) {
                bot.setJumping(false);
                bot.wantedJumping = false;
                this.recenterOnPillar();
                if (bot.terrain.isBreakableObstruction(obstruction)) {
                    this.buildStallTicks = 0;
                    if (bot.terrain.progressMine(obstruction)) {
                        bot.ensureHolding(Items.COBBLESTONE);
                    }
                }
            } else if (!this.isCenteredOnPillar()) {
                bot.setJumping(false);
                bot.wantedJumping = false;
                this.recenterOnPillar();
            } else {
                bot.terrain.clearMiningProgress();
                this.startPillarCycle();
            }
        } else {
            this.bridgeTowardPlayer(target);
        }

        this.updateBuildProgress(target);
    }

    private void recenterOnPillar() {
        float yaw = yawToward(this.pillarX + 0.5 - bot.getX(), this.pillarZ + 0.5 - bot.getZ());
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        Vec3 velocity = bot.getDeltaMovement();
        bot.setDeltaMovement(0.0, velocity.y, 0.0);
        bot.move(MoverType.SELF, new Vec3(
                (this.pillarX + 0.5 - bot.getX()) * 0.2, 0.0, (this.pillarZ + 0.5 - bot.getZ()) * 0.2));
    }

    private @Nullable BlockPos findHeadObstruction() {
        int riseY = bot.blockPosition().getY() + 2;
        double halfWidth = bot.getBbWidth() / 2.0;
        int minX = Mth.floor(bot.getX() - halfWidth);
        int maxX = Mth.floor(bot.getX() + halfWidth);
        int minZ = Mth.floor(bot.getZ() - halfWidth);
        int maxZ = Mth.floor(bot.getZ() + halfWidth);
        BlockPos solidFallback = null;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos cell = new BlockPos(x, riseY, z);
                if (bot.terrain.isBreakableObstruction(cell)) {
                    return cell;
                }
                if (bot.isSolid(cell)) {
                    solidFallback = cell;
                }
            }
        }
        return solidFallback;
    }

    private boolean isCenteredOnPillar() {
        double offsetX = Math.abs(bot.getX() - (this.pillarX + 0.5));
        double offsetZ = Math.abs(bot.getZ() - (this.pillarZ + 0.5));
        return offsetX <= PILLAR_CENTER_TOLERANCE && offsetZ <= PILLAR_CENTER_TOLERANCE;
    }

    private void startPillarCycle() {
        bot.setJumping(false);
        this.recenterOnPillar();
        this.placeFloorY = Mth.floor(bot.getY()) - 1;
        this.placePendingTicks = 0;
        bot.wantedJumping = true;
        this.placePending = true;
    }

    private void finishPillarPlacement(ServerPlayer target) {
        this.placePendingTicks++;
        bot.wantedJumping = false;
        if (bot.getY() >= this.placeFloorY + 2.0) {
            BlockPos cell = new BlockPos(this.pillarX, this.placeFloorY + 1, this.pillarZ);
            if (this.canPlacePillarBlock(cell, target)) {
                bot.level().setBlockAndUpdate(cell, Blocks.COBBLESTONE.defaultBlockState());
                bot.swing(InteractionHand.MAIN_HAND);
                this.pillarBlocksPlaced++;
            }
            this.placePending = false;
        } else if (this.placePendingTicks > PILLAR_JUMP_TIMEOUT_TICKS) {
            this.placePending = false;
            bot.terrain.clearMiningProgress();
        }
    }

    private void bridgeTowardPlayer(ServerPlayer target) {
        bot.wantedJumping = false;
        bot.setJumping(false);
        Direction facing = cardinalToward(bot.blockPosition(), target.blockPosition());
        bot.setYRot(facing.toYRot());
        bot.setYBodyRot(facing.toYRot());

        BlockPos frontFeet = bot.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        BlockPos frontSupport = frontFeet.below();
        boolean laneClear = bot.isPassable(frontFeet) && bot.isPassable(frontHead);

        if (!laneClear) {
            BlockPos obstruction = bot.terrain.isBreakableObstruction(frontFeet) ? frontFeet
                    : (bot.terrain.isBreakableObstruction(frontHead) ? frontHead : null);
            if (obstruction != null) {
                this.buildStallTicks = 0;
                if (bot.terrain.progressMine(obstruction)) {
                    bot.ensureHolding(Items.COBBLESTONE);
                }
            }
            bot.wantedForward = 0.0f;
            bot.dampCrossAxis(facing);
            return;
        }

        bot.terrain.clearMiningProgress();
        boolean supported = bot.isSolid(frontSupport);
        if (!supported && this.canPlacePillarBlock(frontSupport, target)) {
            bot.ensureHolding(Items.COBBLESTONE);
            bot.level().setBlockAndUpdate(frontSupport, Blocks.COBBLESTONE.defaultBlockState());
            bot.swing(InteractionHand.MAIN_HAND);
            this.pillarBlocksPlaced++;
            supported = true;
        }
        bot.wantedForward = supported ? 1.0f : 0.0f;
        bot.dampCrossAxis(facing);
    }

    private void updateBuildProgress(ServerPlayer target) {
        double deltaX = target.getX() - bot.getX();
        double deltaZ = target.getZ() - bot.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (bot.getY() > this.buildBestY + 0.1 || horizontalSquared < this.buildBestHorizontalSq - 0.1) {
            this.buildBestY = Math.max(this.buildBestY, bot.getY());
            this.buildBestHorizontalSq = Math.min(this.buildBestHorizontalSq, horizontalSquared);
            this.buildStallTicks = 0;
        } else {
            this.buildStallTicks++;
        }
    }

    private boolean canPlacePillarBlock(BlockPos cell, ServerPlayer target) {
        BlockState existing = bot.level().getBlockState(cell);
        if (!existing.canBeReplaced() && !existing.isAir()) {
            return false;
        }
        if (!bot.level().getFluidState(cell).isEmpty()) {
            return false;
        }
        if (cell.equals(target.blockPosition()) || cell.equals(target.blockPosition().above())) {
            return false;
        }
        if (bot.getY() < cell.getY() + 1.0) {
            return false;
        }
        BlockState cobblestone = Blocks.COBBLESTONE.defaultBlockState();
        return bot.level().isUnobstructed(cobblestone, cell, CollisionContext.empty());
    }
}
