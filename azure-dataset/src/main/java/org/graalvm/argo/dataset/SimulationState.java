package org.graalvm.argo.dataset;

import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class SimulationState {
    public final TreeSet<Invocation> activeInvocations = new TreeSet<>(Invocation.comparator());
    public final HashMap<String, TreeSet<Invocation>> invocationsByFunction = new HashMap<>();
    public int invocationsProcessed;
    public int currentTimestamp;
    public int previousTimestamp;
    public int lastInvocationsProcessed;
    public int coldStarts;
    public int totalDuration;
    public int totalFootprint;
    public int slaViolations;
    public int slaViolationsCost; // when an SLA violation occurs, the providers fully pay for the invocation's footprint costs

    public void addInvocation(Invocation inv) {
        boolean added = activeInvocations.add(inv);
        if (added) {
            invocationsByFunction.computeIfAbsent(inv.getFunction(), k -> new TreeSet<>(Invocation.comparator())).add(inv);
        }
    }

    // Counterpart used by the three optimised methods below.
    public void removeInvocation(Invocation inv) {
        activeInvocations.remove(inv);
        TreeSet<Invocation> bucket = invocationsByFunction.get(inv.getFunction());
        if (bucket != null) {
            bucket.remove(inv);
            if (bucket.isEmpty()) {
                invocationsByFunction.remove(inv.getFunction());
            }
        }
    }

    public List<Invocation> runningInvocations() {
        return activeInvocations.parallelStream().filter(i -> i.getEndTimestamp() > currentTimestamp).collect(Collectors.toList());
    }
}
