package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_ENTRY_RANGE_SQ;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_HEIGHT;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_INTERVAL;
import static com.yukimura.oogabooga.bot.BotTuning.PORTAL_SCAN_RADIUS;

final class PortalTraveler {

    private final TerminatorBot bot;
    private @Nullable BlockPos cachedPortal = null;
    private int scanCooldown = 0;

    PortalTraveler(TerminatorBot bot) {
        this.bot = bot;
    }

    void reset() {
        this.cachedPortal = null;
        this.scanCooldown = 0;
    }

    void run(ServerPlayer target) {
        Block portalBlock = this.requiredPortalBlock(target);
        BlockPos goal = portalBlock != null ? this.locatePortal(portalBlock) : null;
        if (goal != null && this.driveIntoPortal(goal)) {
            return;
        }
        if (goal == null) {
            goal = this.projectedApproach(target);
        }
        bot.navigator.setGoalOverride(goal);
        bot.navigator.recomputePathIfNeeded(target);
        bot.navigator.followPath(target);
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
        bot.wantedJumping = false;
        bot.setJumping(false);
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
