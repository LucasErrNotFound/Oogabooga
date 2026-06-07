package com.yukimura.oogabooga.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static com.yukimura.oogabooga.ai.PathfinderTuning.BREAK_MAX_ITERATIONS;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_DIAGONAL;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_STRAIGHT;
import static com.yukimura.oogabooga.ai.PathfinderTuning.COST_VERTICAL_HEURISTIC;
import static com.yukimura.oogabooga.ai.PathfinderTuning.MAX_SEARCH_RADIUS;

public final class TerminatorPathfinder {

    private TerminatorPathfinder() {
    }

    public record SearchResult(List<PathStep> steps, boolean reachedGoal) {
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

    public static boolean isHazardousFooting(Level level, BlockPos feetPosition) {
        return PathfinderWorldQuery.isHazardousFooting(level, feetPosition);
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

            for (PathNode neighbor : PathfinderNeighbors.expandNeighbors(level, current, goal, context)) {
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

    static double heuristic(BlockPos from, BlockPos to) {
        double deltaX = Math.abs(from.getX() - to.getX());
        double deltaY = Math.abs(from.getY() - to.getY());
        double deltaZ = Math.abs(from.getZ() - to.getZ());
        double horizontal = Math.min(deltaX, deltaZ) * COST_DIAGONAL + Math.abs(deltaX - deltaZ) * COST_STRAIGHT;
        return horizontal + deltaY * COST_VERTICAL_HEURISTIC;
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
