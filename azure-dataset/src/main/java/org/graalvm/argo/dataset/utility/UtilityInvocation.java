package org.graalvm.argo.dataset.utility;

import org.graalvm.argo.dataset.Invocation;

public class UtilityInvocation extends Invocation {

    enum OptimizationType {
        SNAPSHOT,
        AOT,
        NONE,
    }

    private boolean optimized;
    private OptimizationType optimizationType;
    private static final double OPTIMIZED_AOT_FOOTPRINT_RATIO = 0.375;
    // TODO: calculate optimized duration ratio
    private static final double OPTIMIZED_AOT_DURATION_RATIO = 1;
    //TODO: calculate optimized footprint ratio
    private static final double OPTIMIZED_SNAPSHOT_FOOTPRINT_RATIO = 1;
    // TODO: calculate optimized duration ratio
    private static final double OPTIMIZED_SNAPSHOT_DURATION_RATIO = 1;

    public UtilityInvocation(String owner, String function, int memory, int duration, int timestamp) {
        super(owner, function, memory, duration, timestamp);
        this.optimized = false;
        this.optimizationType = OptimizationType.NONE;
    }

    public void setOptimizedMemory(UtilityInvocation other) {
        this.optimized = other.optimized;
        this.memory = other.memory;
    }

    public void setOptimizedDuration(UtilityInvocation other) {
        this.optimized = other.optimized;
        this.duration = other.duration;
    }

    /* Note: for now, only AOT */
    public void optimize() {
        assert optimized == false;
        memory = (int) ((double) memory * OPTIMIZED_AOT_FOOTPRINT_RATIO);
        duration = (int) ((double) duration * OPTIMIZED_AOT_DURATION_RATIO);
        optimized = true;
        optimizationType = OptimizationType.AOT;
    }

    public boolean isOptimized() {
        return optimized;
    }
}
