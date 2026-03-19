package org.graalvm.argo.dataset.utility;

import org.graalvm.argo.dataset.OutputEntry;

public class UtilityOutputEntry extends OutputEntry {

    public int optimizedColdStarts;
    public int optimizedSlaViolations;
    protected int runningOptimizedFunctions;
    public float optimizationCost;

    @Override
    public String toString() {
        return String.format("%s | optimized_cold %s running_optimized_functions %s | optimized_SLA_violations %s",
                super.toString(),
                optimizedColdStarts,
                runningOptimizedFunctions,
                optimizedSlaViolations);
    }
}
