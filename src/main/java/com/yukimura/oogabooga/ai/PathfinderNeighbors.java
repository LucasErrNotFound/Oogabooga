package com.yukimura.oogabooga.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_BREAK_BASE;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_BREAK_PER_BLOCK;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_CLIMB_DOWN;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_CLIMB_UP;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_DIAGONAL;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_FALL_BASE;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_FALL_PER_BLOCK;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_JUMP_BASE;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_JUMP_PER_BLOCK;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_PLACE_BASE;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_PLACE_PER_BLOCK;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_STEP_UP_EXTRA;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_STRAIGHT;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_WATER_PIT_PENALTY;
import static com.yukimura.oogabooga.ai.PathfinderTuning.HORIZONTAL_DIRECTIONS;
import static com.yukimura.oogabooga.ai.PathfinderTuning.MAX_BREAK_DROP;
import static com.yukimura.oogabooga.ai.PathfinderTuning.MAX_JUMP_DISTANCE;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.canPlaceFloorAt;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.canStandAt;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.findLanding;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.findLandingAssumingCleared;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.hasBodyClearance;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isBreakable;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isClimbable;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isDangerousBlock;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isHazardousFooting;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isPassable;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isSolidGround;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.isWater;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.allBreakableAndSafe;
import static com.yukimura.oogabooga.ai.PathfinderWorldQuery.wouldFlood;
import static com.yukimura.oogabooga.ai.TerminatorPathfinder.heuristic;

final class PathfinderNeighbors {

    private PathfinderNeighbors() {
    }

    static List<PathNode> expandNeighbors(Level level, PathNode current, BlockPos goal,
                                          SearchContext context) {
        List<PathNode> neighbors = new ArrayList<>();
        BlockPos position = current.position;

        for (int[] direction : HORIZONTAL_DIRECTIONS) {
            int deltaX = direction[0];
            int deltaZ = direction[1];
            boolean diagonal = deltaX != 0 && deltaZ != 0;
            double stepCost = diagonal ? COST_DIAGONAL : COST_STRAIGHT;

            if (diagonal) {
                boolean firstCornerClear = hasBodyClearance(level, position.offset(deltaX, 0, 0));
                boolean secondCornerClear = hasBodyClearance(level, position.offset(0, 0, deltaZ));
                if (!firstCornerClear || !secondCornerClear) {
                    continue;
                }
            }

            BlockPos sameLevelTarget = position.offset(deltaX, 0, deltaZ);

            if (canStandAt(level, sameLevelTarget)) {
                addNeighbor(neighbors, current, sameLevelTarget, stepCost, goal, MovementKind.WALK);
                continue;
            }

            if (!diagonal) {
                BlockPos stepUpTarget = sameLevelTarget.above();
                boolean headroomToJump = isPassable(level, position.above().above());
                if (headroomToJump && canStandAt(level, stepUpTarget)) {
                    addNeighbor(neighbors, current, stepUpTarget,
                            stepCost + COST_STEP_UP_EXTRA, goal, MovementKind.STEP_UP);
                    continue;
                }

                addGapJumpNeighbor(level, current, goal, deltaX, deltaZ, neighbors);

                if (context.allowBreaking()) {
                    addBreakWalkNeighbor(level, current, goal, sameLevelTarget, stepCost, neighbors);
                    addBreakUpNeighbor(level, current, goal, deltaX, deltaZ, stepCost, neighbors);
                }

                if (context.allowPlacing()) {
                    addPlaceBridgeNeighbor(level, current, goal, sameLevelTarget, stepCost, neighbors);
                }
            }

            if (hasBodyClearance(level, sameLevelTarget)) {
                BlockPos landing = findLanding(level, sameLevelTarget);
                if (landing != null) {
                    double dropHeight = position.getY() - landing.getY();
                    double fallCost = stepCost + COST_FALL_BASE + dropHeight * COST_FALL_PER_BLOCK;
                    if (isWater(level, landing)) {
                        fallCost += COST_WATER_PIT_PENALTY;
                    }
                    addNeighbor(neighbors, current, landing, fallCost, goal, MovementKind.FALL);
                }
            }
        }

        if (!isSolidGround(level, position.below()) && hasBodyClearance(level, position)) {
            BlockPos landing = findLanding(level, position.below());
            if (landing != null && !landing.equals(position)) {
                double dropHeight = position.getY() - landing.getY();
                double fallCost = COST_FALL_BASE + dropHeight * COST_FALL_PER_BLOCK;
                if (isWater(level, landing)) {
                    fallCost += COST_WATER_PIT_PENALTY;
                }
                addNeighbor(neighbors, current, landing, fallCost, goal, MovementKind.FALL);
            }
        }

        if (context.allowBreaking()) {
            addBreakDownNeighbor(level, current, goal, neighbors);
        }

        if (isWater(level, position)) {
            BlockPos above = position.above();
            if (isPassable(level, above)) {
                addNeighbor(neighbors, current, above, COST_STRAIGHT, goal, MovementKind.SWIM);
            }
            BlockPos below = position.below();
            if (isPassable(level, below)) {
                addNeighbor(neighbors, current, below, COST_STRAIGHT, goal, MovementKind.SWIM);
            }
        }

        if (isClimbable(level, position)) {
            BlockPos above = position.above();
            if (isClimbable(level, above) || canStandAt(level, above)) {
                addNeighbor(neighbors, current, above, COST_CLIMB_UP, goal, MovementKind.CLIMB_UP);
            }
            BlockPos below = position.below();
            if (isClimbable(level, below) || canStandAt(level, below)) {
                addNeighbor(neighbors, current, below, COST_CLIMB_DOWN, goal, MovementKind.CLIMB_DOWN);
            }
        }

        return neighbors;
    }

    private static void addNeighbor(List<PathNode> neighbors, PathNode parent, BlockPos neighborPosition,
                                    double moveCost, BlockPos goal, MovementKind kind) {
        double costFromStart = parent.costFromStart + moveCost;
        neighbors.add(new PathNode(
            neighborPosition, parent, costFromStart, heuristic(neighborPosition, goal), kind,
            parent.blocksBroken, parent.blocksPlaced));
    }

    private static void addBreakNeighbor(List<PathNode> neighbors, PathNode parent, BlockPos neighborPosition,
                                         double moveCost, BlockPos goal, MovementKind kind, int cellsBroken) {
        double costFromStart = parent.costFromStart + moveCost;
        neighbors.add(new PathNode(
            neighborPosition, parent, costFromStart, heuristic(neighborPosition, goal), kind,
            parent.blocksBroken + cellsBroken, parent.blocksPlaced));
    }

    private static void addPlaceNeighbor(List<PathNode> neighbors, PathNode parent, BlockPos neighborPosition,
                                         double moveCost, BlockPos goal, MovementKind kind, int cellsPlaced) {
        double costFromStart = parent.costFromStart + moveCost;
        neighbors.add(new PathNode(
            neighborPosition, parent, costFromStart, heuristic(neighborPosition, goal), kind,
            parent.blocksBroken, parent.blocksPlaced + cellsPlaced));
    }

    private static void addGapJumpNeighbor(Level level, PathNode current, BlockPos goal,
                                           int deltaX, int deltaZ, List<PathNode> neighbors) {
        BlockPos position = current.position;
        if (!isPassable(level, position.above().above())) {
            return;
        }
        for (int distance = 2; distance <= MAX_JUMP_DISTANCE; distance++) {
            BlockPos gapCell = position.offset(deltaX * (distance - 1), 0, deltaZ * (distance - 1));
            if (canStandAt(level, gapCell) || !hasBodyClearance(level, gapCell)) {
                return;
            }
            BlockPos sameLevelLanding = position.offset(deltaX * distance, 0, deltaZ * distance);
            BlockPos landing = null;
            if (canStandAt(level, sameLevelLanding)) {
                landing = sameLevelLanding;
            } else if (canStandAt(level, sameLevelLanding.below())) {
                landing = sameLevelLanding.below();
            }
            if (landing != null) {
                double cost = COST_JUMP_BASE + distance * COST_JUMP_PER_BLOCK;
                addNeighbor(neighbors, current, landing, cost, goal, MovementKind.JUMP);
                return;
            }
        }
    }

    private static void addBreakWalkNeighbor(Level level, PathNode current, BlockPos goal,
                                             BlockPos target, double stepCost, List<PathNode> neighbors) {
        boolean feetSolid = isSolidGround(level, target);
        boolean headSolid = isSolidGround(level, target.above());
        if (!feetSolid && !headSolid) {
            return;
        }
        if (!isSolidGround(level, target.below()) || isHazardousFooting(level, target)) {
            return;
        }
        List<BlockPos> broken = new ArrayList<>(2);
        if (feetSolid) {
            broken.add(target);
        }
        if (headSolid) {
            broken.add(target.above());
        }
        if (!allBreakableAndSafe(level, broken)) {
            return;
        }
        double cost = stepCost + COST_BREAK_BASE + COST_BREAK_PER_BLOCK * broken.size();
        addBreakNeighbor(neighbors, current, target, cost, goal, MovementKind.BREAK_WALK, broken.size());
    }

    private static void addBreakUpNeighbor(Level level, PathNode current, BlockPos goal,
                                           int deltaX, int deltaZ, double stepCost, List<PathNode> neighbors) {
        BlockPos position = current.position;
        BlockPos sameLevelTarget = position.offset(deltaX, 0, deltaZ);
        if (!isSolidGround(level, sameLevelTarget)) {
            return;
        }
        BlockPos stepUpTarget = sameLevelTarget.above();
        BlockPos newHead = stepUpTarget.above();
        BlockPos jumpHeadroom = position.above().above();
        List<BlockPos> broken = new ArrayList<>(3);
        if (isSolidGround(level, jumpHeadroom)) {
            broken.add(jumpHeadroom);
        }
        if (isSolidGround(level, stepUpTarget)) {
            broken.add(stepUpTarget);
        }
        if (isSolidGround(level, newHead)) {
            broken.add(newHead);
        }
        if (broken.isEmpty()) {
            return;
        }
        if (isHazardousFooting(level, stepUpTarget)) {
            return;
        }
        if (!allBreakableAndSafe(level, broken)) {
            return;
        }
        double cost = stepCost + COST_STEP_UP_EXTRA + COST_BREAK_BASE + COST_BREAK_PER_BLOCK * broken.size();
        addBreakNeighbor(neighbors, current, stepUpTarget, cost, goal, MovementKind.BREAK_UP, broken.size());
    }

    private static void addBreakDownNeighbor(Level level, PathNode current, BlockPos goal,
                                             List<PathNode> neighbors) {
        BlockPos position = current.position;
        BlockPos floorCell = position.below();
        if (!isBreakable(level, floorCell) || wouldFlood(level, floorCell)) {
            return;
        }
        BlockPos landing = findLandingAssumingCleared(level, floorCell);
        if (landing == null) {
            return;
        }
        int drop = position.getY() - landing.getY();
        if (drop > MAX_BREAK_DROP) {
            return;
        }
        double cost = COST_BREAK_BASE + COST_BREAK_PER_BLOCK + COST_FALL_BASE + drop * COST_FALL_PER_BLOCK;
        addBreakNeighbor(neighbors, current, landing, cost, goal, MovementKind.BREAK_DOWN, 1);
    }

    private static void addPlaceBridgeNeighbor(Level level, PathNode current, BlockPos goal,
                                               BlockPos target, double stepCost, List<PathNode> neighbors) {
        if (!hasBodyClearance(level, target)) {
            return;
        }
        if (isSolidGround(level, target.below())) {
            return;
        }
        BlockPos floorCell = target.below();
        if (!canPlaceFloorAt(level, floorCell)) {
            return;
        }
        if (isDangerousBlock(level, target)) {
            return;
        }
        double cost = stepCost + COST_PLACE_BASE + COST_PLACE_PER_BLOCK;
        addPlaceNeighbor(neighbors, current, target, cost, goal, MovementKind.PLACE_BRIDGE, 1);
    }
}
