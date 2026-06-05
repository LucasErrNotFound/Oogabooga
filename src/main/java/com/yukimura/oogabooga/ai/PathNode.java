package com.yukimura.oogabooga.ai;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public class PathNode implements Comparable<PathNode> {

    public final BlockPos position;
    public final @Nullable PathNode parent;

    public final double costFromStart;

    public final double estimatedCostToGoal;

    public final double totalCost;

    public final MovementKind kind;

    public final int blocksBroken;

    public final int blocksPlaced;

    public PathNode(BlockPos position, @Nullable PathNode parent,
                    double costFromStart, double estimatedCostToGoal, MovementKind kind,
                    int blocksBroken, int blocksPlaced) {
        this.position = position;
        this.parent = parent;
        this.costFromStart = costFromStart;
        this.estimatedCostToGoal = estimatedCostToGoal;
        this.totalCost = costFromStart + estimatedCostToGoal;
        this.kind = kind;
        this.blocksBroken = blocksBroken;
        this.blocksPlaced = blocksPlaced;
    }

    @Override
    public int compareTo(PathNode other) {
        return Double.compare(this.totalCost, other.totalCost);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PathNode otherNode)) {
            return false;
        }
        return this.position.equals(otherNode.position);
    }

    @Override
    public int hashCode() {
        return this.position.hashCode();
    }
}
