package com.yukimura.oogabooga.bot;

import com.yukimura.oogabooga.ai.MovementKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import static com.yukimura.oogabooga.bot.BotTuning.FALL_SAVE_BAD_DROP;
import static com.yukimura.oogabooga.bot.BotTuning.FALL_SAVE_MIN_DESCENT;
import static com.yukimura.oogabooga.bot.BotTuning.FALL_SAVE_SCAN;
import static com.yukimura.oogabooga.bot.BotTuning.LOOK_PITCH_DOWN;

final class FallSave {

    private final TerminatorBot bot;

    FallSave(TerminatorBot bot) {
        this.bot = bot;
    }

    boolean shouldFallSave() {
        if (bot.stackUp.isActive() || bot.stackUp.isPlacePending()) {
            return false;
        }
        if (bot.isBlockBelow() || bot.onClimbable() || bot.isInWater() || bot.isInLava()) {
            return false;
        }
        if (bot.getDeltaMovement().y >= -FALL_SAVE_MIN_DESCENT) {
            return false;
        }
        if (this.isExecutingPlannedDescent()) {
            return false;
        }
        if (!this.hasAdjacentWall(bot.blockPosition().below())) {
            return false;
        }
        return this.isFallLandingBad();
    }

    private boolean isExecutingPlannedDescent() {
        MovementKind kind = bot.navigator.currentStepKind();
        return kind == MovementKind.FALL || kind == MovementKind.BREAK_DOWN;
    }

    private boolean hasAdjacentWall(BlockPos cell) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (bot.isSolid(cell.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    private boolean isFallLandingBad() {
        BlockPos feet = bot.blockPosition();
        for (int depth = 1; depth <= FALL_SAVE_SCAN; depth++) {
            BlockPos cell = feet.below(depth);
            if (!bot.level().isLoaded(cell)) {
                return true;
            }
            if (bot.level().getFluidState(cell).is(FluidTags.WATER)
                    || bot.level().getFluidState(cell).is(FluidTags.LAVA)) {
                return true;
            }
            if (bot.isSolid(cell)) {
                return (depth - 1) > FALL_SAVE_BAD_DROP;
            }
        }
        return true;
    }

    void runFallSave(ServerPlayer target) {
        bot.setSprinting(false);
        bot.wantedForward = 0f;
        bot.wantedUpward = 0f;
        bot.wantedJumping = false;
        bot.setJumping(false);
        Vec3 velocity = bot.getDeltaMovement();
        bot.setDeltaMovement(velocity.x * 0.6, velocity.y, velocity.z * 0.6);
        bot.lookAlongBody(LOOK_PITCH_DOWN);
        bot.terrain.placeBlockSurvival(bot.blockPosition().below(), target);
    }
}
