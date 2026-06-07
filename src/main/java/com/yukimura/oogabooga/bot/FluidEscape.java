package com.yukimura.oogabooga.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import static com.yukimura.oogabooga.bot.BotTuning.LOOK_PITCH_EXIT;

final class FluidEscape {

    private final TerminatorBot bot;

    FluidEscape(TerminatorBot bot) {
        this.bot = bot;
    }

    boolean tryFluidEscape(ServerPlayer target) {
        boolean feetInFluid = !bot.level().getFluidState(bot.blockPosition()).isEmpty();
        if (!feetInFluid && !bot.isInWater() && !bot.isInLava()) {
            return false;
        }
        if (!bot.isBlockBelow() && bot.isUnderWater()) {
            return false;
        }
        Direction exit = this.chooseFluidExit(target);
        if (exit == null) {
            return false;
        }
        float yaw = exit.toYRot();
        bot.setYRot(yaw);
        bot.setYBodyRot(yaw);
        bot.lookAlongBody(LOOK_PITCH_EXIT);
        bot.wantedForward = 1.0f;
        bot.wantedUpward = 1.0f;
        bot.wantedJumping = true;
        bot.escapeFluidDirection = exit;
        return true;
    }

    private @Nullable Direction chooseFluidExit(ServerPlayer target) {
        BlockPos feet = bot.blockPosition();
        double toPlayerX = target.getX() - bot.getX();
        double toPlayerZ = target.getZ() - bot.getZ();
        double playerLength = Math.sqrt(toPlayerX * toPlayerX + toPlayerZ * toPlayerZ);
        if (playerLength > 1.0e-4) {
            toPlayerX /= playerLength;
            toPlayerZ /= playerLength;
        }
        Direction best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int stepUp = this.fluidExitRise(feet.relative(direction));
            if (stepUp < 0) {
                continue;
            }
            double bearing = direction.getStepX() * toPlayerX + direction.getStepZ() * toPlayerZ;
            double score = bearing + (stepUp == 1 ? 0.5 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return best;
    }

    private int fluidExitRise(BlockPos sideFeet) {
        if (bot.isStandableExit(sideFeet)) {
            return 0;
        }
        if (bot.isStandableExit(sideFeet.above()) && bot.isPassable(bot.blockPosition().above().above())) {
            return 1;
        }
        return -1;
    }
}
