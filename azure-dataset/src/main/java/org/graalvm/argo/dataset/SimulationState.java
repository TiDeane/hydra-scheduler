package org.graalvm.argo.dataset;

import java.util.List;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class SimulationState {
    public final TreeSet<Invocation> activeInvocations = new TreeSet<>(Invocation.comparator());
    public int invocationsProcessed;
    public int currentTimestamp;
    public int previousTimestamp;
    public int lastInvocationsProcessed;
    public int coldStarts;
    public int totalDuration;
    public int totalFootprint;

    public final HashSet<String> slaViolationFunctions = new HashSet<>();
    public int slaViolations;

    public List<Invocation> runningInvocations() {
        return activeInvocations.parallelStream().filter(i -> i.getEndTimestamp() > currentTimestamp).collect(Collectors.toList());
    }
}
