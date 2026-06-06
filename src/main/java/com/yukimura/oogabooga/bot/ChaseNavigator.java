package com.yukimura.oogabooga.bot;

import com.yukimura.oogabooga.ai.MovementKind;
import com.yukimura.oogabooga.ai.PathStep;
import com.yukimura.oogabooga.ai.TerminatorPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.yukimura.oogabooga.bot.BotMath.approachYaw;
import static com.yukimura.oogabooga.bot.BotMath.cardinalToward;
import static com.yukimura.oogabooga.bot.BotMath.yawToward;
import static com.yukimura.oogabooga.bot.BotTuning.AIM_LOOKAHEAD_BLOCKS;
import static com.yukimura.oogabooga.bot.BotTuning.BREAK_BUDGET_MAX;
import static com.yukimura.oogabooga.bot.BotTuning.BREAK_BUDGET_MIN;
import static com.yukimura.oogabooga.bot.BotTuning.BREAK_BUDGET_PER_BLOCK;
import static com.yukimura.oogabooga.bot.BotTuning.BREAK_GRACE_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.JUMP_BRINK_EDGE;
import static com.yukimura.oogabooga.bot.BotTuning.JUMP_CROSS_AXIS_TOLERANCE;
import static com.yukimura.oogabooga.bot.BotTuning.JUMP_TAKEOFF_EDGE;
import static com.yukimura.oogabooga.bot.BotTuning.MAX_YAW_STEP_DEGREES;
import static com.yukimura.oogabooga.bot.BotTuning.MOMENTUM_JUMP_MIN_DISTANCE_SQUARED;
import static com.yukimura.oogabooga.bot.BotTuning.REPATH_INTERVAL_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.STACK_TRIGGER_MIN_HEIGHT;
import static com.yukimura.oogabooga.bot.BotTuning.STUCK_REPATH_TICKS;
import static com.yukimura.oogabooga.bot.BotTuning.TARGET_MOVED_THRESHOLD_SQUARED;
import static com.yukimura.oogabooga.bot.BotTuning.WALK_JUMP_MAX_DISTANCE;
import static com.yukimura.oogabooga.bot.BotTuning.WAYPOINT_REACHED_DISTANCE_SQUARED;

final class ChaseNavigator {

    private final TerminatorBot bot;
    private @Nullable List<PathStep> currentPath = null;
    private boolean pathReachesTarget = false;
    private int pathIndex = 0;
    private int ticksSinceRepath = 0;
    private @Nullable BlockPos lastTargetBlock = null;

    ChaseNavigator(TerminatorBot bot) {
        this.bot = bot;
    }

    boolean hasPath() {
        return this.currentPath != null;
    }

    void primeRepath() {
        this.ticksSinceRepath = REPATH_INTERVAL_TICKS;
    }

    void resetPath() {
        this.currentPath = null;
        this.pathIndex = 0;
        this.ticksSinceRepath = 0;
        this.lastTargetBlock = null;
    }

    void clearPath() {
        this.currentPath = null;
    }

    BlockPos previousStepPosition() {
        return this.currentPath.get(Math.max(0, this.pathIndex - 1)).position();
    }

    @Nullable MovementKind currentStepKind() {
        if (this.currentPath == null || this.pathIndex >= this.currentPath.size()) {
            return null;
        }
        return this.currentPath.get(this.pathIndex).kind();
    }

    boolean isPlacingStep() {
        return this.currentPath != null
                && this.pathIndex < this.currentPath.size()
                && this.currentPath.get(this.pathIndex).kind() == MovementKind.PLACE_BRIDGE;
    }

    double maxPathY() {
        if (this.currentPath == null || this.currentPath.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        double highest = Double.NEGATIVE_INFINITY;
        for (PathStep step : this.currentPath) {
            highest = Math.max(highest, step.position().getY());
        }
        return highest;
    }

    void recomputePathIfNeeded(ServerPlayer target) {
        this.ticksSinceRepath++;
        BlockPos targetBlock = target.blockPosition();
        boolean targetMovedFar = this.lastTargetBlock == null
                || this.lastTargetBlock.distSqr(targetBlock) > TARGET_MOVED_THRESHOLD_SQUARED;

        boolean midModify = bot.terrain.isMidBreak() || this.isPlacingStep();
        boolean needsPath;
        if (bot.stackUp.isActive()) {
            needsPath = targetMovedFar;
        } else {
            needsPath = this.currentPath == null
                    || (!midModify && this.ticksSinceRepath >= REPATH_INTERVAL_TICKS)
                    || targetMovedFar
                    || (!midModify && bot.stuckTicks > STUCK_REPATH_TICKS);
        }
        if (needsPath) {
            if (targetMovedFar) {
                bot.terrain.clearMiningProgress();
                bot.resetProgressWatchdog();
            }
            BlockPos start = bot.blockPosition();
            BlockPos searchGoal = this.approachGoal(target);
            TerminatorPathfinder.SearchResult terrainResult =
                    TerminatorPathfinder.findPath(bot.level(), start, searchGoal);
            List<PathStep> chosen = terrainResult != null ? terrainResult.steps() : null;
            boolean reached = terrainResult != null && terrainResult.reachedGoal();

            if (!reached
                    && TerminatorPathfinder.withinSearchRange(start, searchGoal)
                    && (target.getY() - bot.getY()) <= STACK_TRIGGER_MIN_HEIGHT) {
                int modifyBudget = Mth.clamp(
                        (int) (Math.sqrt(start.distSqr(searchGoal)) * BREAK_BUDGET_PER_BLOCK),
                        BREAK_BUDGET_MIN, BREAK_BUDGET_MAX);
                TerminatorPathfinder.SearchResult modifying = TerminatorPathfinder.findPathModifying(
                        bot.level(), start, searchGoal, modifyBudget, modifyBudget);
                if (modifying != null && (modifying.reachedGoal() || chosen == null)) {
                    chosen = modifying.steps();
                    reached = modifying.reachedGoal();
                }
            }

            this.currentPath = chosen;
            this.pathReachesTarget = reached;
            this.pathIndex = this.nearestPathIndex();
            this.ticksSinceRepath = 0;
            bot.stuckTicks = 0;
            this.lastTargetBlock = targetBlock;
        }
    }

    private BlockPos approachGoal(ServerPlayer target) {
        BlockPos playerFeet = target.blockPosition();
        if (!bot.isSolid(playerFeet.below())) {
            return playerFeet;
        }
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos cell = playerFeet.relative(direction);
            if (!bot.isStandableExit(cell)) {
                continue;
            }
            double distanceSq = cell.distSqr(bot.blockPosition());
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = cell;
            }
        }
        return best != null ? best : playerFeet;
    }

    private void advancePathIndex() {
        while (this.pathIndex < this.currentPath.size()) {
            BlockPos waypoint = this.currentPath.get(this.pathIndex).position();
            double centerX = waypoint.getX() + 0.5;
            double centerZ = waypoint.getZ() + 0.5;
            double horizontalDistanceSquared =
                    (centerX - bot.getX()) * (centerX - bot.getX())
                  + (centerZ - bot.getZ()) * (centerZ - bot.getZ());
            boolean verticallyClose = Math.abs(waypoint.getY() - bot.getY()) <= 1.5;
            if (horizontalDistanceSquared <= WAYPOINT_REACHED_DISTANCE_SQUARED && verticallyClose) {
                this.pathIndex++;
            } else {
                break;
            }
        }
    }

    private int nearestPathIndex() {
        if (this.currentPath == null || this.currentPath.isEmpty()) {
            return 0;
        }
        int bestIndex = 0;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int index = 0; index < this.currentPath.size(); index++) {
            BlockPos position = this.currentPath.get(index).position();
            double centerX = position.getX() + 0.5;
            double centerZ = position.getZ() + 0.5;
            double deltaY = position.getY() - bot.getY();
            double distanceSquared = (centerX - bot.getX()) * (centerX - bot.getX())
                    + (centerZ - bot.getZ()) * (centerZ - bot.getZ())
                    + deltaY * deltaY;
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestIndex = index;
            }
        }
        if (bestIndex > 0 && this.currentPath.get(bestIndex).kind() == MovementKind.JUMP) {
            bestIndex--;
        }
        return bestIndex;
    }

    private Vec3 computeAimPoint() {
        BlockPos first = this.currentPath.get(this.pathIndex).position();
        double previousX = first.getX() + 0.5;
        double previousZ = first.getZ() + 0.5;
        double aimX = previousX;
        double aimZ = previousZ;
        double remaining = AIM_LOOKAHEAD_BLOCKS;
        for (int index = this.pathIndex + 1; index < this.currentPath.size(); index++) {
            PathStep step = this.currentPath.get(index);
            if (step.kind() != MovementKind.WALK && step.kind() != MovementKind.SWIM) {
                break;
            }
            double nextX = step.position().getX() + 0.5;
            double nextZ = step.position().getZ() + 0.5;
            if (!this.hasHorizontalLineOfSight(bot.getX(), bot.getZ(), nextX, nextZ)) {
                break;
            }
            double segment = Math.sqrt((nextX - previousX) * (nextX - previousX)
                    + (nextZ - previousZ) * (nextZ - previousZ));
            if (segment >= remaining) {
                double fraction = segment > 1.0e-6 ? remaining / segment : 0.0;
                return new Vec3(previousX + (nextX - previousX) * fraction, 0.0,
                        previousZ + (nextZ - previousZ) * fraction);
            }
            remaining -= segment;
            previousX = nextX;
            previousZ = nextZ;
            aimX = nextX;
            aimZ = nextZ;
        }
        return new Vec3(aimX, 0.0, aimZ);
    }

    private boolean hasHorizontalLineOfSight(double fromX, double fromZ, double toX, double toZ) {
        double deltaX = toX - fromX;
        double deltaZ = toZ - fromZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        int samples = Math.max(1, (int) Math.ceil(distance * 2.0));
        int feetY = Mth.floor(bot.getY());
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            BlockPos feet = BlockPos.containing(fromX + deltaX * fraction, feetY, fromZ + deltaZ * fraction);
            if (!bot.isPassable(feet) || !bot.isPassable(feet.above())) {
                return false;
            }
        }
        return true;
    }

    void runClimb(PathStep climbStep) {
        bot.setSprinting(false);
        bot.wantedJumping = false;
        bot.setJumping(false);
        if (bot.onClimbable()) {
            bot.wantedForward = 0f;
            bot.wantedUpward = climbStep.kind() == MovementKind.CLIMB_UP ? 1.0f : -1.0f;
        } else {
            BlockPos column = climbStep.position();
            float yaw = yawToward(column.getX() + 0.5 - bot.getX(), column.getZ() + 0.5 - bot.getZ());
            bot.setYRot(yaw);
            bot.setYBodyRot(yaw);
            bot.wantedForward = 1.0f;
            bot.wantedUpward = 0f;
        }
    }

    void followPath(ServerPlayer target) {
        boolean hasPath = this.currentPath != null && !this.currentPath.isEmpty();
        if (hasPath) {
            this.advancePathIndex();
        }

        PathStep currentStep = (hasPath && this.pathIndex < this.currentPath.size())
                ? this.currentPath.get(this.pathIndex)
                : null;
        boolean jumpMode = currentStep != null && currentStep.kind() == MovementKind.JUMP;
        boolean climbMode = currentStep != null
                && (currentStep.kind() == MovementKind.CLIMB_UP || currentStep.kind() == MovementKind.CLIMB_DOWN);
        boolean breakMode = currentStep != null
                && (currentStep.kind() == MovementKind.BREAK_WALK
                    || currentStep.kind() == MovementKind.BREAK_UP
                    || currentStep.kind() == MovementKind.BREAK_DOWN);
        boolean placeMode = currentStep != null && currentStep.kind() == MovementKind.PLACE_BRIDGE;

        float desiredYaw;
        double destinationY;
        boolean waypointIsHigher = false;
        if (jumpMode) {
            BlockPos takeoff = this.currentPath.get(this.pathIndex - 1).position();
            BlockPos landing = currentStep.position();
            desiredYaw = cardinalToward(takeoff, landing).toYRot();
            destinationY = landing.getY();
            bot.currentJumpDistance = Math.max(
                    Math.abs(landing.getX() - takeoff.getX()),
                    Math.abs(landing.getZ() - takeoff.getZ()));
        } else if (climbMode) {
            destinationY = currentStep.position().getY();
            desiredYaw = yawToward(target.getX() - bot.getX(), target.getZ() - bot.getZ());
            bot.currentJumpDistance = 0;
        } else if (currentStep != null) {
            BlockPos waypoint = currentStep.position();
            destinationY = waypoint.getY();
            waypointIsHigher = destinationY > bot.getY() + 0.5;
            Vec3 aim = this.computeAimPoint();
            desiredYaw = yawToward(aim.x - bot.getX(), aim.z - bot.getZ());
            bot.currentJumpDistance = 0;
        } else {
            destinationY = target.getY();
            desiredYaw = yawToward(target.getX() - bot.getX(), target.getZ() - bot.getZ());
            bot.currentJumpDistance = 0;
        }

        float bodyYaw = jumpMode
                ? desiredYaw
                : approachYaw(bot.getYRot(), desiredYaw, MAX_YAW_STEP_DEGREES);
        bot.setYRot(bodyYaw);
        bot.setYBodyRot(bodyYaw);

        if (climbMode) {
            this.runClimb(currentStep);
            bot.updateStuckTracking();
            bot.terrain.clearMiningProgress();
            return;
        }

        if (breakMode) {
            bot.terrain.runBreakStep(currentStep);
            bot.updateStuckTracking();
            return;
        }

        if (placeMode) {
            bot.terrain.runPlaceStep(currentStep, target);
            bot.updateStuckTracking();
            return;
        }

        bot.wantedForward = 1.0f;
        bot.setSprinting(!jumpMode || bot.currentJumpDistance > WALK_JUMP_MAX_DISTANCE);

        boolean followingRealStep = currentStep != null && this.pathReachesTarget;
        if (bot.isInWater()) {
            if (followingRealStep) {
                double verticalDelta = destinationY - bot.getY();
                bot.wantedUpward = verticalDelta > 0.5 ? 1.0f : (verticalDelta < -0.5 ? -1.0f : 0.0f);
            } else {
                bot.wantedUpward = 1.0f;
            }
        } else if (bot.isInLava()) {
            bot.wantedUpward = 1.0f;
        } else {
            bot.wantedUpward = 0.0f;
        }

        boolean steppable = false;
        if (jumpMode) {
            bot.wantedJumping = this.shouldLaunchParkourJump(bodyYaw);
        } else {
            steppable = this.isSteppableWallAhead(bodyYaw);
            boolean momentumJump = this.shouldMomentumJump(bodyYaw, target);
            bot.wantedJumping = waypointIsHigher || steppable || momentumJump;
        }

        bot.setJumping(bot.isInLava() && bot.wantedForward != 0f);

        boolean patched = !jumpMode && bot.terrain.tryPatchLaneAhead(bodyYaw, target);

        bot.updateStuckTracking();
        if (!jumpMode
                && bot.horizontalCollision
                && bot.stuckTicks > BREAK_GRACE_TICKS
                && !bot.isInWater()
                && !steppable) {
            bot.terrain.breakBlockInFront(bodyYaw);
        } else if (!patched) {
            bot.terrain.clearMiningProgress();
            bot.combat.holdChaseWeapon();
        }
    }

    private boolean shouldLaunchParkourJump(float bodyYaw) {
        if (!bot.isBlockBelow()) {
            return false;
        }
        double fractionalX = bot.getX() - Math.floor(bot.getX());
        double fractionalZ = bot.getZ() - Math.floor(bot.getZ());
        double edgeProgress = 0.0;
        double crossOffset = 1.0;
        switch (Direction.fromYRot(bodyYaw)) {
            case EAST -> { edgeProgress = fractionalX; crossOffset = Math.abs(fractionalZ - 0.5); }
            case WEST -> { edgeProgress = 1.0 - fractionalX; crossOffset = Math.abs(fractionalZ - 0.5); }
            case SOUTH -> { edgeProgress = fractionalZ; crossOffset = Math.abs(fractionalX - 0.5); }
            case NORTH -> { edgeProgress = 1.0 - fractionalZ; crossOffset = Math.abs(fractionalX - 0.5); }
            default -> { return false; }
        }
        boolean atEdgeCentred = edgeProgress >= JUMP_TAKEOFF_EDGE && crossOffset <= JUMP_CROSS_AXIS_TOLERANCE;
        return atEdgeCentred || edgeProgress >= JUMP_BRINK_EDGE;
    }

    private boolean isSteppableWallAhead(float bodyYaw) {
        if (this.isStepUpAt(Direction.fromYRot(bodyYaw))) {
            return true;
        }
        Vec3 velocity = bot.getDeltaMovement();
        if (velocity.x * velocity.x + velocity.z * velocity.z > 1.0e-4) {
            float velocityYaw = (float) (Mth.atan2(velocity.z, velocity.x) * (180.0 / Math.PI)) - 90.0f;
            return this.isStepUpAt(Direction.fromYRot(velocityYaw));
        }
        return false;
    }

    private boolean isStepUpAt(Direction facing) {
        BlockPos frontFeet = bot.blockPosition().relative(facing);
        BlockPos frontHead = frontFeet.above();
        boolean feetBlocked =
                !bot.level().getBlockState(frontFeet).getCollisionShape(bot.level(), frontFeet).isEmpty();
        boolean headClear =
                bot.level().getBlockState(frontHead).getCollisionShape(bot.level(), frontHead).isEmpty();
        return feetBlocked && headClear;
    }

    private boolean shouldMomentumJump(float bodyYaw, ServerPlayer target) {
        if (bot.isInWater() || !bot.isSprinting() || !bot.isBlockBelow()) {
            return false;
        }
        double deltaX = target.getX() - bot.getX();
        double deltaZ = target.getZ() - bot.getZ();
        if (deltaX * deltaX + deltaZ * deltaZ < MOMENTUM_JUMP_MIN_DISTANCE_SQUARED) {
            return false;
        }
        Direction facing = Direction.fromYRot(bodyYaw);
        BlockPos self = bot.blockPosition();
        for (int step = 1; step <= 2; step++) {
            BlockPos feet = self.relative(facing, step);
            if (!bot.isPassable(feet) || !bot.isPassable(feet.above())) {
                return false;
            }
            if (bot.isPassable(feet.below())) {
                return false;
            }
        }
        return bot.isPassable(self.above().above());
    }
}
