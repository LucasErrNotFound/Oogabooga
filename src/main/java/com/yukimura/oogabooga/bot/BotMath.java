package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

final class BotMath {

    private BotMath() {
    }

    static float yawToward(double deltaX, double deltaZ) {
        return (float) (Mth.atan2(deltaZ, deltaX) * (180.0 / Math.PI)) - 90.0f;
    }

    static Direction cardinalToward(BlockPos from, BlockPos to) {
        int deltaX = to.getX() - from.getX();
        int deltaZ = to.getZ() - from.getZ();
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0 ? Direction.EAST : Direction.WEST;
        }
        return deltaZ >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    static float approachYaw(float current, float target, float maxStep) {
        float difference = Mth.degreesDifference(current, target);
        return Mth.wrapDegrees(current + Mth.clamp(difference, -maxStep, maxStep));
    }
}
