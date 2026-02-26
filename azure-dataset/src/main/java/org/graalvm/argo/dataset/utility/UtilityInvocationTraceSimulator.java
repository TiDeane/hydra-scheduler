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
import org.graalvm.argo.dataset.utility.utils.NoOptUtilityCalculator;
import org.graalvm.argo.dataset.utility.utils.UtilityCalculator;

/**
 * This class is an extension of InvocationTraceSimulator that also
 * allows for simulating the application of optimizations using utility scores.
 */
public class UtilityInvocationTraceSimulator extends InvocationTraceSimulator {

    /**
     * Number of milliseconds between each round of utility calculation and optimization.
     */
    private static final int UTILITY_CALCULATION_INTERVAL = 3600000; // 60 minutes

    private final String utilityCalculationMethod;
    private final boolean useAOT;
    private final boolean useSnapshotting;

    public UtilityInvocationTraceSimulator(String utilityCalculationMethod, boolean useAOT, boolean useSnapshotting) {
        this.utilityCalculationMethod = utilityCalculationMethod;
        this.useAOT = useAOT;
        this.useSnapshotting = useSnapshotting;
    }

    @Override
    protected Invocation createInvocation(String owner, String function, int memory, int p25duration, int p99duration, int timestamp) {
        return new UtilityInvocation(owner, function, memory, p25duration, p99duration, timestamp);
    }

    class UtilitySimulationState extends SimulationState {
        int optimizedColdStarts;
        float optimizationCost;
        UtilityCalculator utilityCalculator;

        public UtilitySimulationState() {
            this.optimizedColdStarts = 0;
            switch (utilityCalculationMethod) {
                case "naive":
                    this.utilityCalculator = new NaiveUtilityCalculator(useAOT, useSnapshotting);
                    break;
                case "extended":
                    this.utilityCalculator = new ExtendedUtilityCalculator(useAOT, useSnapshotting);
                    break;
                case "longest-running":
                    this.utilityCalculator = new LongestRunningUtilityCalculator(useAOT, useSnapshotting);
                    break;
                case "random":
                	this.utilityCalculator = new RandomUtilityCalculator(useAOT, useSnapshotting);
                	break;
                case "no-opt":
                	this.utilityCalculator = new NoOptUtilityCalculator(useAOT, useSnapshotting);
                	break;
                default:
                    this.utilityCalculator = new NaiveUtilityCalculator(useAOT, useSnapshotting);
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
        UtilitySimulationState utilityss = ((UtilitySimulationState)ss);

        /* We make use of this function to optimize if the utility calculation interval has passed  */
        if (utilityss.currentTimestamp - utilityss.utilityCalculator.lastOptimization > UTILITY_CALCULATION_INTERVAL) {
            utilityss.utilityCalculator.calculateUtilityAndOptimize(utilityss.currentTimestamp);
        }

        UtilityInvocation currentUtilityInvocation = (UtilityInvocation) currentInvocation;
        String currentFunction = currentUtilityInvocation.getFunction();
        utilityss.utilityCalculator.registerInvocation(currentFunction, currentInvocation.getMemory(), currentInvocation.getDuration());
        if (warm == null) {
            currentInvocation.setDuration(currentInvocation.getP99Duration());
            currentInvocation.setEndTimestamp(currentInvocation.getDuration());
            if (utilityss.utilityCalculator.optimized(currentFunction)) {
                utilityss.optimizedColdStarts++;
                // Cold start happened, but the function is optimized.
                if (utilityss.utilityCalculator.optimizedSnapshot(currentFunction)) {
                    currentUtilityInvocation.optimize("SNAPSHOT");
                } else {
                    currentUtilityInvocation.optimize("AOT");
                }
            }
            utilityss.utilityCalculator.registerColdStart(currentFunction);
        } else {
            // Reuse memory and optimization status of the warm invocation instead of always using unoptimized.
            currentUtilityInvocation.setOptimizedMemory((UtilityInvocation) warm);
            // duration for warm starts is set to P25 in the superclass
        }
        super.updateAfterWarmCheck(ss, currentInvocation, warm);
    }

    protected List<OutputEntry> simulateInvocations(String inputFile, int keepalive, int interval) {
        return simulateInvocations(inputFile, new UtilitySimulationState(), keepalive, interval);
    }
}
