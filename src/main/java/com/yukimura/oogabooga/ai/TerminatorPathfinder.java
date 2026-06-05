package com.yukimura.oogabooga.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class TerminatorPathfinder {

    private static final int MAX_ITERATIONS = 4000;
    private static final int MAX_SEARCH_RADIUS = 128;
    private static final int MAX_FALL_SCAN = 256;

    private static final double COST_STRAIGHT = 1.0;
    private static final double COST_DIAGONAL = 1.4142135;
    private static final double COST_STEP_UP_EXTRA = 0.5;
    private static final double COST_FALL_BASE = 0.5;
    private static final double COST_FALL_PER_BLOCK = 0.1;

    private static final int MAX_JUMP_DISTANCE = 5;
    private static final double COST_JUMP_BASE = 1.0;
    private static final double COST_JUMP_PER_BLOCK = 0.6;
    private static final double COST_WATER_PIT_PENALTY = 4.0;

    private static final double COST_CLIMB_UP = 1.8;
    private static final double COST_CLIMB_DOWN = 1.4;

    private static final double COST_BREAK_BASE = 6.0;
    private static final double COST_BREAK_PER_BLOCK = 4.0;
    private static final int MAX_BREAK_DROP = 4;
    private static final int BREAK_MAX_ITERATIONS = 8000;

    private static final double COST_PLACE_BASE = 5.0;
    private static final double COST_PLACE_PER_BLOCK = 3.0;

    private static final int[][] HORIZONTAL_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private TerminatorPathfinder() {
    }

    public record SearchResult(List<PathStep> steps, boolean reachedGoal) {
    }

    private record SearchContext(boolean allowBreaking, int breakBudget,
                                 boolean allowPlacing, int placeBudget, int maxIterations) {
        static final SearchContext TERRAIN_ONLY = new SearchContext(false, 0, false, 0, MAX_ITERATIONS);
    }

    public static @Nullable SearchResult findPath(Level level, BlockPos start, BlockPos goal) {
        return search(level, start, goal, SearchContext.TERRAIN_ONLY);
    }

    public static @Nullable SearchResult findPathModifying(Level level, BlockPos start, BlockPos goal,
                                                           int breakBudget, int placeBudget) {
        return search(level, start, goal,
                new SearchContext(true, breakBudget, true, placeBudget, BREAK_MAX_ITERATIONS));
    }

    public static boolean withinSearchRange(BlockPos start, BlockPos goal) {
        return chebyshevDistance(start, goal) <= MAX_SEARCH_RADIUS;
    }

    private static @Nullable SearchResult search(Level level, BlockPos start, BlockPos goal,
                                                 SearchContext context) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, Double> bestCostToReach = new HashMap<>();

        PathNode startNode = new PathNode(start, null, 0.0, heuristic(start, goal), MovementKind.WALK, 0, 0);
        openSet.add(startNode);
        bestCostToReach.put(start, 0.0);

        PathNode closestNode = startNode;

        int iterations = 0;
        while (!openSet.isEmpty() && iterations < context.maxIterations()) {
            iterations++;
            PathNode current = openSet.poll();

            if (isAtGoal(current.position, goal)) {
                return new SearchResult(reconstructPath(current), true);
            }
            if (!closedSet.add(current.position)) {
                continue;
            }
            if (current.estimatedCostToGoal < closestNode.estimatedCostToGoal) {
                closestNode = current;
            }

            for (PathNode neighbor : expandNeighbors(level, current, goal, context)) {
                if (neighbor.blocksBroken > context.breakBudget()) {
                    continue;
                }
                if (neighbor.blocksPlaced > context.placeBudget()) {
                    continue;
                }
                if (closedSet.contains(neighbor.position)) {
                    continue;
                }
                if (chebyshevDistance(start, neighbor.position) > MAX_SEARCH_RADIUS) {
                    continue;
                }
                Double recordedCost = bestCostToReach.get(neighbor.position);
                if (recordedCost != null && neighbor.costFromStart >= recordedCost) {
                    continue;
                }
                bestCostToReach.put(neighbor.position, neighbor.costFromStart);
                openSet.add(neighbor);
            }
        }
        if (closestNode == startNode) {
            return null;
        }
        return new SearchResult(reconstructPath(closestNode), false);
    }

    private static List<PathNode> expandNeighbors(Level level, PathNode current, BlockPos goal,
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

    private static @Nullable BlockPos findLanding(Level level, BlockPos startFeet) {
        int minimumY = level.getMinY();
        BlockPos.MutableBlockPos cursor = startFeet.mutable();
        for (int steps = 0; steps < MAX_FALL_SCAN && cursor.getY() > minimumY; steps++) {
            if (!level.isLoaded(cursor)) {
                return null;
            }
            if (isWater(level, cursor)) {
                return cursor.immutable();
            }
            if (!hasBodyClearance(level, cursor)) {
                return null;
            }
            if (isSolidGround(level, cursor.below())) {
                if (isHazardousFooting(level, cursor)) {
                    return null;
                }
                return cursor.immutable();
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }

    private static @Nullable BlockPos findLandingAssumingCleared(Level level, BlockPos startFeet) {
        int minimumY = level.getMinY();
        BlockPos.MutableBlockPos cursor = startFeet.mutable();
        for (int steps = 0; steps < MAX_FALL_SCAN && cursor.getY() > minimumY; steps++) {
            if (!level.isLoaded(cursor)) {
                return null;
            }
            if (isWater(level, cursor)) {
                return cursor.immutable();
            }
            boolean firstCell = steps == 0;
            if (firstCell ? !isPassable(level, cursor.above()) : !hasBodyClearance(level, cursor)) {
                return null;
            }
            if (isSolidGround(level, cursor.below())) {
                if (isHazardousFooting(level, cursor)) {
                    return null;
                }
                return cursor.immutable();
            }
            cursor.move(Direction.DOWN);
        }
        return null;
    }


    private static boolean isPassable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        if (isLava(level, position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty();
    }

    private static boolean isSolidGround(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        return !level.getBlockState(position).getCollisionShape(level, position).isEmpty();
    }

    private static boolean isWater(Level level, BlockPos position) {
        return level.getFluidState(position).is(FluidTags.WATER);
    }

    private static boolean isLava(Level level, BlockPos position) {
        return level.getFluidState(position).is(FluidTags.LAVA);
    }

    private static boolean isClimbable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        return level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    public static boolean isHazardousFooting(Level level, BlockPos feetPosition) {
        return isDangerousBlock(level, feetPosition) || isDangerousBlock(level, feetPosition.below());
    }

    private static boolean isDangerousBlock(Level level, BlockPos position) {
        if (isLava(level, position)) {
            return true;
        }
        BlockState state = level.getBlockState(position);
        return state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.CACTUS)
            || state.is(Blocks.FIRE)
            || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.CAMPFIRE)
            || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.POWDER_SNOW)
            || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static boolean hasBodyClearance(Level level, BlockPos footPosition) {
        return isPassable(level, footPosition) && isPassable(level, footPosition.above());
    }

    private static boolean canStandAt(Level level, BlockPos footPosition) {
        if (!hasBodyClearance(level, footPosition)) {
            return false;
        }
        if (isWater(level, footPosition)) {
            return true;
        }
        if (isClimbable(level, footPosition)) {
            return true;
        }
        return isSolidGround(level, footPosition.below());
    }


    private static boolean isBreakable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        if (state.isAir() || state.getCollisionShape(level, position).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(level, position) >= 0.0f;
    }

    private static boolean wouldFlood(Level level, BlockPos cell) {
        for (Direction face : Direction.values()) {
            BlockPos neighbor = cell.relative(face);
            if (isWater(level, neighbor) || isLava(level, neighbor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFallingBlockAt(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return true;
        }
        return level.getBlockState(position).getBlock() instanceof FallingBlock;
    }

    private static boolean dropsFallingBlock(Level level, List<BlockPos> brokenCells) {
        for (BlockPos cell : brokenCells) {
            BlockPos above = cell.above();
            if (isFallingBlockAt(level, above) && !brokenCells.contains(above)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allBreakableAndSafe(Level level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (!isBreakable(level, cell) || wouldFlood(level, cell)) {
                return false;
            }
        }
        return !dropsFallingBlock(level, cells);
    }

    private static boolean canPlaceFloorAt(Level level, BlockPos cell) {
        if (!level.isLoaded(cell)) {
            return false;
        }
        if (isSolidGround(level, cell)) {
            return false;
        }
        BlockState state = level.getBlockState(cell);
        return state.isAir() || state.canBeReplaced();
    }


    private static double heuristic(BlockPos from, BlockPos to) {
        double deltaX = Math.abs(from.getX() - to.getX());
        double deltaZ = Math.abs(from.getZ() - to.getZ());
        return Math.min(deltaX, deltaZ) * COST_DIAGONAL + Math.abs(deltaX - deltaZ) * COST_STRAIGHT;
    }

    private static int chebyshevDistance(BlockPos from, BlockPos to) {
        int deltaX = Math.abs(from.getX() - to.getX());
        int deltaY = Math.abs(from.getY() - to.getY());
        int deltaZ = Math.abs(from.getZ() - to.getZ());
        return Math.max(deltaX, Math.max(deltaY, deltaZ));
    }

    private static boolean isAtGoal(BlockPos position, BlockPos goal) {
        return Math.abs(position.getX() - goal.getX()) <= 1
            && Math.abs(position.getY() - goal.getY()) <= 1
            && Math.abs(position.getZ() - goal.getZ()) <= 1;
    }

    private static List<PathStep> reconstructPath(PathNode goalNode) {
        List<PathStep> path = new ArrayList<>();
        PathNode cursor = goalNode;
        while (cursor != null) {
            path.add(new PathStep(cursor.position, cursor.kind));
            cursor = cursor.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
