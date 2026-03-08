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
    private static final double OPTIMIZED_AOT_FOOTPRINT_RATIO = 0.235;
    private static final double OPTIMIZED_AOT_DURATION_RATIO = 0.773;
    private static final double OPTIMIZED_SNAPSHOT_FOOTPRINT_RATIO = 0.284;
    private static final double SNAPSHOT_RESTORE_PENALTY = 33.2;

    public UtilityInvocation(String owner, String function, int memory, int duration, int timestamp) {
        super(owner, function, memory, duration, timestamp);
        this.optimized = false;
        this.optimizationType = OptimizationType.NONE;
    }

    public UtilityInvocation(String owner, String function, int memory, int p50duration, int p99duration, int timestamp) {
        super(owner, function, memory, p50duration, p99duration, timestamp);
        this.optimized = false;
        this.optimizationType = OptimizationType.NONE;
    }

    public void setOptimizedMemory(UtilityInvocation other) {
        this.optimized = other.optimized;
        this.optimizationType = other.optimizationType;
        /* this is used for warm invocations - snapshotting memory reduction only applies to cold starts */
        if (this.optimizationType == OptimizationType.AOT) this.memory = other.memory;
    }

    public void optimize(String optimizationType) {
        assert optimized == false;
        if (optimizationType.equals("SNAPSHOT")) {
            this.optimizationType = OptimizationType.SNAPSHOT;
            memory = (int) ((double) memory * OPTIMIZED_SNAPSHOT_FOOTPRINT_RATIO);
            // note: should we use p50 or p75 for snapshotting? Since snapshot-restored executions are slower than normal warm starts
            duration = (int) ((double) p50duration + SNAPSHOT_RESTORE_PENALTY);
        } else {
            this.optimizationType = OptimizationType.AOT;
            memory = (int) ((double) memory * OPTIMIZED_AOT_FOOTPRINT_RATIO);
            duration = (int) ((double) duration * OPTIMIZED_AOT_DURATION_RATIO);
        }
        optimized = true;
    }

    public boolean isOptimized() {
        return optimized;
    }
}
