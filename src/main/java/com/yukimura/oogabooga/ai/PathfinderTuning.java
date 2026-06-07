package com.yukimura.oogabooga.ai;

final class PathfinderTuning {

    private PathfinderTuning() {
    }

    static final int MAX_ITERATIONS = 4000;
    static final int MAX_SEARCH_RADIUS = 128;
    static final int MAX_FALL_SCAN = 256;

    static final double COST_STRAIGHT = 1.0;
    static final double COST_DIAGONAL = 1.4142135;
    static final double COST_VERTICAL_HEURISTIC = 1.0;
    static final double COST_STEP_UP_EXTRA = 0.5;
    static final double COST_FALL_BASE = 0.5;
    static final double COST_FALL_PER_BLOCK = 0.1;

    static final int MAX_JUMP_DISTANCE = 5;
    static final double COST_JUMP_BASE = 1.0;
    static final double COST_JUMP_PER_BLOCK = 0.6;
    static final double COST_WATER_PIT_PENALTY = 4.0;

    static final double COST_CLIMB_UP = 1.8;
    static final double COST_CLIMB_DOWN = 1.4;

    static final double COST_BREAK_BASE = 6.0;
    static final double COST_BREAK_PER_BLOCK = 4.0;
    static final int MAX_BREAK_DROP = 4;
    static final int BREAK_MAX_ITERATIONS = 8000;

    static final double COST_PLACE_BASE = 5.0;
    static final double COST_PLACE_PER_BLOCK = 3.0;

    static final int[][] HORIZONTAL_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };
}
