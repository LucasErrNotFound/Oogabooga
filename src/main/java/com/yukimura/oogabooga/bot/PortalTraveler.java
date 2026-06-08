package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.ANCHOR_REACHED_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.CROSS_DIM_TIMEOUT_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.END_ESCAPE_DELAY_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_ENTRY_RANGE_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_HEIGHT;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_INTERVAL;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_RADIUS;

final class PortalTraveler {

    private static final BlockPos END_SPAWN_PLATFORM = new BlockPos(100, 50, 0);

    private final TerminatorBot bot;
    private @Nullable BlockPos cachedPortal = null;
    private int scanCooldown = 0;
    private int crossDimTicks = 0;
    private @Nullable BlockPos portalAnchor = null;
    private @Nullable BlockPos crossingEntryAnchor = null;
    private @Nullable Vec3 crossingExitPosition = null;

    PortalTraveler(TerminatorBot bot) {
        this.bot = bot;
    }

    void reset() {
        this.cachedPortal = null;
        this.scanCooldown = 0;
        this.crossDimTicks = 0;
        this.portalAnchor = null;
        this.crossingEntryAnchor = null;
        this.crossingExitPosition = null;
    }

    void onSameDimension(ServerPlayer target) {
        this.crossingEntryAnchor = target.blockPosition();
        this.cachedPortal = null;
        this.scanCooldown = 0;
        this.crossDimTicks = 0;
        this.portalAnchor = null;
        this.crossingExitPosition = null;
    }

    void run(ServerPlayer target) {
        this.crossDimTicks++;
        if (this.crossingExitPosition == null) {
            this.crossingExitPosition = target.position();
        }
        if (bot.level().dimension().equals(Level.END)
                && !target.level().dimension().equals(Level.END)
                && this.crossDimTicks > END_ESCAPE_DELAY_TICKS) {
            this.teleportToTargetPosition(target);
            return;
        }
        if (this.crossDimTicks > CROSS_DIM_TIMEOUT_TICKS) {
            this.teleportIntoTargetDimension(target);
            return;
        }
        Block portalBlock = this.requiredPortalBlock(target);
        BlockPos goal = portalBlock != null ? this.locatePortal(portalBlock) : null;
        if (goal != null && this.driveIntoPortal(goal)) {
            return;
        }
        if (goal == null) {
            goal = this.crossingEntryAnchor != null ? this.crossingEntryAnchor : this.stableAnchor(target);
        }
        bot.navigator.setGoalOverride(goal);
        bot.navigator.recomputePathIfNeeded(target);
        bot.navigator.followPath(target);
    }

    private void teleportIntoTargetDimension(ServerPlayer target) {
        if (target.level() instanceof ServerLevel targetLevel) {
            Vec3 landing = this.crossDimensionLanding(target, targetLevel);
            bot.teleportTo(targetLevel, landing.x, landing.y, landing.z,
                    Set.<Relative>of(), bot.getYRot(), bot.getXRot(), false);
        }
        this.reset();
    }

    private Vec3 crossDimensionLanding(ServerPlayer target, ServerLevel targetLevel) {
        if (targetLevel.dimension().equals(Level.END)) {
            return new Vec3(END_SPAWN_PLATFORM.getX() + 0.5,
                    END_SPAWN_PLATFORM.getY(),
                    END_SPAWN_PLATFORM.getZ() + 0.5);
        }
        return this.crossingExitPosition != null ? this.crossingExitPosition : target.position();
    }

    private void teleportToTargetPosition(ServerPlayer target) {
        if (target.level() instanceof ServerLevel targetLevel) {
            bot.teleportTo(targetLevel, target.getX(), target.getY(), target.getZ(),
                    Set.<Relative>of(), bot.getYRot(), bot.getXRot(), false);
        }
        this.reset();
    }

    private BlockPos stableAnchor(ServerPlayer target) {
        if (this.portalAnchor == null
                || bot.blockPosition().distSqr(this.portalAnchor) < ANCHOR_REACHED_SQ) {
            this.portalAnchor = this.projectedApproach(target);
        }
        return this.portalAnchor;
    }

    private boolean driveIntoPortal(BlockPos portal) {
        double deltaX = (portal.getX() + 0.5) - bot.getX();
        double deltaZ = (portal.getZ() + 0.5) - bot.getZ();
        double horizontalSquared = deltaX * deltaX + deltaZ * deltaZ;
        if (horizontalSquared > PORTAL_ENTRY_RANGE_SQ || Math.abs(portal.getY() - bot.getY()) > 2.0) {
            return false;
        }
        bot.setPortalCooldown(0);
        float yaw = yawToward(deltaX, deltaZ);
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.lookAlongBody(0.0f);
        bot.setShiftKeyDown(false);
        bot.setSprinting(false);
        bot.wantedForward = horizontalSquared > 0.04 ? 1.0f : 0.0f;
        bot.wantedUpward = 0f;
        boolean portalAbove = portal.getY() > bot.getY() + 0.5;
        bot.wantedJumping = portalAbove;
        bot.setJumping(portalAbove);
        return true;
    }

    private @Nullable Block requiredPortalBlock(ServerPlayer target) {
        ResourceKey<Level> botDimension = bot.level().dimension();
        ResourceKey<Level> targetDimension = target.level().dimension();
        if (botDimension.equals(Level.NETHER) || targetDimension.equals(Level.NETHER)) {
            return Blocks.NETHER_PORTAL;
        }
        if (botDimension.equals(Level.END) || targetDimension.equals(Level.END)) {
            return Blocks.END_PORTAL;
        }
        return null;
    }

    private @Nullable BlockPos locatePortal(Block portalBlock) {
        if (this.cachedPortal != null && bot.level().getBlockState(this.cachedPortal).is(portalBlock)) {
            return this.cachedPortal;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return this.cachedPortal;
        }
        this.scanCooldown = PORTAL_SCAN_INTERVAL;
        BlockPos origin = bot.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int deltaX = -PORTAL_SCAN_RADIUS; deltaX <= PORTAL_SCAN_RADIUS; deltaX++) {
            for (int deltaZ = -PORTAL_SCAN_RADIUS; deltaZ <= PORTAL_SCAN_RADIUS; deltaZ++) {
                for (int deltaY = -PORTAL_SCAN_HEIGHT; deltaY <= PORTAL_SCAN_HEIGHT; deltaY++) {
                    cursor.set(origin.getX() + deltaX, origin.getY() + deltaY, origin.getZ() + deltaZ);
                    if (!bot.level().isLoaded(cursor)) {
                        continue;
                    }
                    if (!bot.level().getBlockState(cursor).is(portalBlock)) {
                        continue;
                    }
                    double distanceSquared = cursor.distSqr(origin);
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        best = cursor.immutable();
                    }
                }
            }
        }
        this.cachedPortal = best;
        return best;
    }

    private BlockPos projectedApproach(ServerPlayer target) {
        ResourceKey<Level> botDimension = bot.level().dimension();
        ResourceKey<Level> targetDimension = target.level().dimension();
        double projectedX = target.getX();
        double projectedZ = target.getZ();
        if (botDimension.equals(Level.OVERWORLD) && targetDimension.equals(Level.NETHER)) {
            projectedX = target.getX() * 8.0;
            projectedZ = target.getZ() * 8.0;
        } else if (botDimension.equals(Level.NETHER) && targetDimension.equals(Level.OVERWORLD)) {
            projectedX = target.getX() / 8.0;
            projectedZ = target.getZ() / 8.0;
        } else if (botDimension.equals(Level.END)) {
            projectedX = 0.0;
            projectedZ = 0.0;
        }
        return BlockPos.containing(projectedX, bot.getY(), projectedZ);
    }
}
