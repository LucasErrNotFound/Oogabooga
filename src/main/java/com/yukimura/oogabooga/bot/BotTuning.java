package com.yukimura.oogabooga.bot;

import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

final class BotTuning {

    private BotTuning() {
    }

    static final int REPATH_INTERVAL_TICKS = 20;
    static final int STUCK_REPATH_TICKS = 60;
    static final int NO_PROGRESS_STACK_TICKS = 50;
    static final int HIT_RECOVERY_TICKS = 12;
    static final double APPROACH_PROGRESS_EPSILON_SQ = 0.5;
    static final double TARGET_MOVED_THRESHOLD_SQUARED = 64.0;
    static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.36;
    static final double STUCK_MOVEMENT_THRESHOLD_SQUARED = 0.0004;
    static final int BREAK_GRACE_TICKS = 8;
    static final double MOMENTUM_JUMP_MIN_DISTANCE_SQUARED = 25.0;
    static final double FLUID_ESCAPE_PUSH = 0.32;

    static final double JUMP_TAKEOFF_EDGE = 0.7;
    static final double JUMP_BRINK_EDGE = 0.92;
    static final double JUMP_CROSS_AXIS_TOLERANCE = 0.35;
    static final double JUMP_LAUNCH_BASE = 0.24;
    static final double JUMP_LAUNCH_PER_BLOCK = 0.14;
    static final int WALK_JUMP_MAX_DISTANCE = 3;

    static final double CLIMB_SPEED = 0.2;
    static final double CLIMB_EXIT_SPEED = 0.12;

    static final double ARRIVAL_ENTER_SQ = 4.0;
    static final double ARRIVAL_EXIT_SQ = 9.0;
    static final double ARRIVAL_MAX_DY = 1.5;

    static final float MAX_YAW_STEP_DEGREES = 12.0f;
    static final double AIM_LOOKAHEAD_BLOCKS = 2.5;
    static final double STUCK_FORWARD_PROGRESS = 0.02;

    static final double STACK_TRIGGER_MIN_HEIGHT = 2.0;
    static final double STACK_TRIGGER_HORIZONTAL_SQ = 9.0;
    static final double STACK_OVERRIDE_HORIZONTAL_SQ = 16.0;
    static final double STACK_ABORT_HORIZONTAL_SQ = 64.0;
    static final double STACK_STEEPNESS_RATIO = 1.0;
    static final double BUILD_LEVEL_EPSILON = 0.5;
    static final double BUILD_REACH_SQ = 2.25;
    static final int BUILD_STALL_LIMIT = 60;
    static final int STACK_MAX_BLOCKS = 64;
    static final double PILLAR_CENTER_TOLERANCE = 0.16;
    static final int PILLAR_JUMP_TIMEOUT_TICKS = 8;

    static final double BREAK_BUDGET_PER_BLOCK = 1.0;
    static final int BREAK_BUDGET_MIN = 8;
    static final int BREAK_BUDGET_MAX = 64;

    static final Item BUILD_ITEM = Items.DIRT;
    static final Block BUILD_BLOCK = Blocks.DIRT;
    static final double PLACE_REACH_SQ = 20.25;
    static final Direction[] SUPPORT_SEARCH_ORDER = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN, Direction.UP
    };
    static final int PLACE_FAIL_LIMIT = 10;
    static final float BRIDGE_FORWARD = 0.5f;

    static final float LOOK_PITCH_DOWN = 55.0f;
    static final float LOOK_PITCH_UP = -45.0f;
    static final float LOOK_PITCH_JUMP = 12.0f;
    static final float LOOK_PITCH_EXIT = -12.0f;

    static final int PORTAL_SCAN_RADIUS = 20;
    static final int PORTAL_SCAN_HEIGHT = 12;
    static final int PORTAL_SCAN_INTERVAL = 30;
    static final double PORTAL_ENTRY_RANGE_SQ = 6.25;
    static final int CROSS_DIM_TIMEOUT_TICKS = 1200;
    static final int END_ESCAPE_DELAY_TICKS = 100;
    static final double ANCHOR_REACHED_SQ = 16.0;

    static final double FALL_SAVE_MIN_DESCENT = 0.15;
    static final int FALL_SAVE_BAD_DROP = 3;
    static final int FALL_SAVE_SCAN = 24;

    static final Item WEAPON_SWORD = Items.DIAMOND_SWORD;
    static final Item WEAPON_AXE = Items.DIAMOND_AXE;
    static final double ATTACK_REACH = 3.0;
    static final double ATTACK_REACH_SQ = ATTACK_REACH * ATTACK_REACH;
    static final float FULL_CHARGE_THRESHOLD = 0.9f;
    static final float SHIELD_BREAK_CHARGE_THRESHOLD = 0.45f;
    static final int TOOL_COMMIT_TICKS = 10;
    static final int CRIT_COMBO_INTERVAL_TICKS = 140;
    static final int CRIT_COMBO_AIRBORNE_TIMEOUT = 25;
    static final int LEDGE_KNOCKOFF_DROP = 3;

    static final int WIN_DISCONNECT_TICKS = 250;
}
