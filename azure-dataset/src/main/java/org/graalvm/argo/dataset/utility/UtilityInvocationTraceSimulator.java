package org.graalvm.argo.dataset.utility;

import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.Invocation;
import org.graalvm.argo.dataset.InvocationTraceSimulator;
import org.graalvm.argo.dataset.OutputEntry;
import org.graalvm.argo.dataset.SimulationState;
import org.graalvm.argo.dataset.utility.utils.NaiveUtilityCalculator;
import org.graalvm.argo.dataset.utility.utils.RandomUtilityCalculator;
import org.graalvm.argo.dataset.utility.utils.ExtendedUtilityCalculator;
import org.graalvm.argo.dataset.utility.utils.LongestRunningUtilityCalculator;
import org.graalvm.argo.dataset.utility.utils.UtilityCalculator;

/**
 * This class is an extension of InvocationTraceSimulator that also
 * allows for simulating the application of optimizations using utility scores.
 */
public class UtilityInvocationTraceSimulator extends InvocationTraceSimulator {

    /**
     * Number of milliseconds between each round of utility calculation and optimization.
     */
    private static final int UTILITY_CALCULATION_INTERVAL = 60000; // 1 minutes

    private final String utilityCalculationMethod;
    private final boolean useAOT;
    private final boolean useSnapshotting;

    public UtilityInvocationTraceSimulator(String utilityCalculationMethod, boolean useAOT, boolean useSnapshotting) {
        this.utilityCalculationMethod = utilityCalculationMethod;
        this.useAOT = useAOT;
        this.useSnapshotting = useSnapshotting;
    }

    @Override
    protected Invocation createInvocation(String owner, String function, int memory, int duration, int timestamp) {
        return new UtilityInvocation(owner, function, memory, duration, timestamp);
    }

    class UtilitySimulationState extends SimulationState {
        int optimizedColdStarts;
        UtilityCalculator utilityCalculator;

        public UtilitySimulationState() {
            this.optimizedColdStarts = 0;
            switch (utilityCalculationMethod) {
                case "naive":
                    this.utilityCalculator = new NaiveUtilityCalculator();
                    break;
                case "extended":
                    this.utilityCalculator = new ExtendedUtilityCalculator();
                    break;
                case "longest-running":
                    this.utilityCalculator = new LongestRunningUtilityCalculator();
                    break;
                case "random":
                	this.utilityCalculator = new RandomUtilityCalculator();
                	break;
                default:
                    this.utilityCalculator = new NaiveUtilityCalculator();
            }
        }
    }


    @Override
    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, SimulationState ss) {
        @SuppressWarnings("unchecked")
        List<UtilityInvocation> runningUtilityInvocations = (List<UtilityInvocation>)(List<?>) runningInvocations;
        UtilityOutputEntry utilityOutputEntry = new UtilityOutputEntry();
        utilityOutputEntry.optimizedColdStarts = ((UtilitySimulationState)ss).optimizedColdStarts;
        utilityOutputEntry.runningOptimizedFunctions  = (int) runningUtilityInvocations.parallelStream().filter(UtilityInvocation::isOptimized).map(UtilityInvocation::getFunction).distinct().count();
        return super.updateStatistics(activeInvocations, runningInvocations, utilityOutputEntry, ss);
    }

    @Override
    protected void resetSimulationStateAfterUpdateStatistics(SimulationState ss) {
        ((UtilitySimulationState)ss).optimizedColdStarts = 0;
        super.resetSimulationStateAfterUpdateStatistics(ss);
    }

    @Override
    protected void updateAfterWarmCheck(SimulationState ss, Invocation currentInvocation, Invocation warm) {
        UtilityInvocation currentUtilityInvocation = (UtilityInvocation) currentInvocation;
        UtilitySimulationState utilityss = ((UtilitySimulationState)ss);
        String currentFunction = currentUtilityInvocation.getFunction();
        utilityss.utilityCalculator.registerInvocation(currentFunction, currentInvocation.getMemory(), currentInvocation.getDuration());
        if (warm == null) {
            if (utilityss.utilityCalculator.optimized(currentFunction)) {
                utilityss.optimizedColdStarts++;
                // Cold start happened, but the function is optimized.
                currentUtilityInvocation.optimize();
            }
            utilityss.utilityCalculator.registerColdStart(currentFunction);
        } else {
            // Reuse memory and optimization status of the warm invocation instead of always using unoptimized.
            currentUtilityInvocation.setOptimizedMemory((UtilityInvocation) warm);
        }
        super.updateAfterWarmCheck(ss, currentInvocation, warm);
    }

    protected List<OutputEntry> simulateInvocations(List<Invocation> invocations, int keepalive, int interval) {
        return simulateInvocations(invocations, new UtilitySimulationState(), keepalive, interval);
    }

    // Note: can we use "SimulationState" and overrride?
    protected List<OutputEntry> simulateInvocations(List<Invocation> invocations, UtilitySimulationState ss, int keepalive, int interval) {
        List<OutputEntry> statistics = new LinkedList<>();
        ss.utilityCalculator.startTimestamp = invocations.get(0).getTimestamp();

        System.err.println("Simulating trace with " + invocations.size() + " invocations and keepalive of " + keepalive);
        for (Invocation currentInvocation : invocations) {
            ss.currentTimestamp = currentInvocation.getTimestamp();

            if (ss.currentTimestamp - ss.utilityCalculator.lastOptimization > UTILITY_CALCULATION_INTERVAL) {
                ss.utilityCalculator.calculateUtilityAndOptimize(ss.currentTimestamp);
                System.err.println("Currently optimized functions: " + ss.utilityCalculator.optimizedFunctions.size());
            }

            // Remove invocations that have past their keep alive time.
            evictTimedOutInvocations(ss.activeInvocations, ss.currentTimestamp, keepalive);

            // We try to find an inactive invocation that can be replaced with the new one.
            Invocation warm = findWarmInvocation(ss.activeInvocations, ss.currentTimestamp, currentInvocation.getFunction());
            updateAfterWarmCheck(ss, currentInvocation, warm);

            // Add invocation to array of active invocations.
            ss.activeInvocations.add(currentInvocation);
            ss.invocationsProcessed++;
            ss.totalDuration += currentInvocation.getDuration();
            ss.totalFootprint += currentInvocation.getMemory();

            if (ss.currentTimestamp - ss.previousTimestamp > interval) {
                // Calculate and update statistics.
                List<Invocation> runningInvocations = ss.activeInvocations.parallelStream().filter(i -> i.getEndTimestamp() > ss.currentTimestamp).collect(Collectors.toList());
                statistics.add(updateStatistics(ss.activeInvocations, runningInvocations, ss));

                // Reset values until the next round.
                resetSimulationStateAfterUpdateStatistics(ss);
            }

            // Progress update...
            if (ss.invocationsProcessed % Math.max(invocations.size() / 100, 1) == 0) {
                System.err.println(String.format("Processed %s (%.2f %%)", ss.invocationsProcessed, ((float) ss.invocationsProcessed / (float)invocations.size() * 100)));
            }
        }

        // Final update to statistics.
        statistics.add(updateStatistics(ss.activeInvocations, ss.runningInvocations(), ss));

        return statistics;
    }
}
