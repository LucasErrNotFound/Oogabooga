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

import java.util.List;

import static com.yukimura.oogabooga.ai.PathfinderTuning.MAX_FALL_SCAN;

final class PathfinderWorldQuery {

    private PathfinderWorldQuery() {
    }

    static @Nullable BlockPos findLanding(Level level, BlockPos startFeet) {
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

    static @Nullable BlockPos findLandingAssumingCleared(Level level, BlockPos startFeet) {
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

    static boolean isPassable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        if (isLava(level, position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        return state.getCollisionShape(level, position).isEmpty();
    }

    static boolean isSolidGround(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        return !level.getBlockState(position).getCollisionShape(level, position).isEmpty();
    }

    static boolean isWater(Level level, BlockPos position) {
        return level.getFluidState(position).is(FluidTags.WATER);
    }

    static boolean isLava(Level level, BlockPos position) {
        return level.getFluidState(position).is(FluidTags.LAVA);
    }

    static boolean isClimbable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        return level.getBlockState(position).is(BlockTags.CLIMBABLE);
    }

    static boolean isHazardousFooting(Level level, BlockPos feetPosition) {
        return isDangerousBlock(level, feetPosition) || isDangerousBlock(level, feetPosition.below());
    }

    static boolean isDangerousBlock(Level level, BlockPos position) {
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

    static boolean hasBodyClearance(Level level, BlockPos footPosition) {
        return isPassable(level, footPosition) && isPassable(level, footPosition.above());
    }

    static boolean canStandAt(Level level, BlockPos footPosition) {
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

    static boolean isBreakable(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return false;
        }
        BlockState state = level.getBlockState(position);
        if (state.isAir() || state.getCollisionShape(level, position).isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(level, position) >= 0.0f;
    }

    static boolean wouldFlood(Level level, BlockPos cell) {
        for (Direction face : Direction.values()) {
            BlockPos neighbor = cell.relative(face);
            if (isWater(level, neighbor) || isLava(level, neighbor)) {
                return true;
            }
        }
        return false;
    }

    static boolean isFallingBlockAt(Level level, BlockPos position) {
        if (!level.isLoaded(position)) {
            return true;
        }
        return level.getBlockState(position).getBlock() instanceof FallingBlock;
    }

    static boolean dropsFallingBlock(Level level, List<BlockPos> brokenCells) {
        for (BlockPos cell : brokenCells) {
            BlockPos above = cell.above();
            if (isFallingBlockAt(level, above) && !brokenCells.contains(above)) {
                return true;
            }
        }
        return false;
    }

    static boolean allBreakableAndSafe(Level level, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (!isBreakable(level, cell) || wouldFlood(level, cell)) {
                return false;
            }
        }
        return !dropsFallingBlock(level, cells);
    }

    static boolean canPlaceFloorAt(Level level, BlockPos cell) {
        if (!level.isLoaded(cell)) {
            return false;
        }
        if (isSolidGround(level, cell)) {
            return false;
        }
        BlockState state = level.getBlockState(cell);
        return state.isAir() || state.canBeReplaced();
    }
}
