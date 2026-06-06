package com.yukimura.oogabooga.ai;

import static com.yukimura.oogabooga.ai.PathfinderTuning.MAX_ITERATIONS;

record SearchContext(boolean allowBreaking, int breakBudget,
                     boolean allowPlacing, int placeBudget, int maxIterations) {

    static final SearchContext TERRAIN_ONLY = new SearchContext(false, 0, false, 0, MAX_ITERATIONS);
}
