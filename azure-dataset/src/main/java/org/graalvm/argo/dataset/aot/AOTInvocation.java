package org.graalvm.argo.dataset.aot;

import org.graalvm.argo.dataset.Invocation;

public class AOTInvocation extends Invocation {

    private boolean optimized;
    private static final double OPTIMIZED_AOT_DURATION_RATIO = 0.015; // Native Image paper: based on Figure 9 values
    private static final double OPTIMIZED_FOOTPRINT_RATIO = 0.375;

    public AOTInvocation(String owner, String function, int memory, int duration, int timestamp) {
        super(owner, function, memory, duration, timestamp);
        this.optimized = false;
    }

    public void setOptimizedMemory(AOTInvocation other) {
        this.optimized = other.optimized;
        this.memory = other.memory;
    }

    public void optimize() {
        assert optimized == false;
        memory = (int) ((double) memory * OPTIMIZED_FOOTPRINT_RATIO);
        duration = (int) ((double) duration * OPTIMIZED_AOT_DURATION_RATIO);
        optimized = true;
    }

    public boolean isOptimized() {
        return optimized;
    }
}
