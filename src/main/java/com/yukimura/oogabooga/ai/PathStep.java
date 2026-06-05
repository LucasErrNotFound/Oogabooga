package com.yukimura.oogabooga.ai;

import net.minecraft.core.BlockPos;

public record PathStep(BlockPos position, MovementKind kind) {
}
