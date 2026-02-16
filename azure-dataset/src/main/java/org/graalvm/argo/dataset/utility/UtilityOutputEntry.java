package org.graalvm.argo.dataset.utility;

import org.graalvm.argo.dataset.OutputEntry;

public class UtilityOutputEntry extends OutputEntry {

    /* Note: for now, only AOT */
    public int optimizedColdStarts;
    protected int runningOptimizedFunctions;

    @Override
    public String toString() {
        return String.format("%s | optimized_cold %s running_optimized_functions %s",
                super.toString(),
                optimizedColdStarts,
                runningOptimizedFunctions);
    }
}
