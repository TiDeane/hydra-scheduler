package org.graalvm.argo.dataset;

public class OutputEntry {

    // Current timestamp (when the output entry is produced).
    protected int timestamp;
    // Invocations processed since the last output entry.
    protected int invocationsProcessed;
    // Cold starts since the last output entry.
    protected int coldStarts;
    // Total duration of invocations processed since the last output entry.
    protected int totalDuration;
    // Total memory footprint of invocations processed since the last output entry.
    protected int totalFootprint;
    // Current number of users, functions, invocations, and invocation footprint (MBs).
    protected int runningUsers;
    protected int runningFunctions;
    protected int runningInvocations;
    protected int runningInvocationsFootprint;
    // Number of cached users, functions, and invocation footprint (MBs).
    protected int cachedUsers;
    protected int cachedFunctions;
    protected int cachedInvocationsFootprint;

    @Override
    public String toString() {
        return String.format("time %s | invocations %s cold %s | running users %s functions %s invocations %s footprint %s | cached users %s functions %s footprint %s",
                timestamp,
                invocationsProcessed,
                coldStarts,
                runningUsers,
                runningFunctions,
                runningInvocations,
                runningInvocationsFootprint,
                cachedUsers,
                cachedFunctions,
                cachedInvocationsFootprint);
    }
}
