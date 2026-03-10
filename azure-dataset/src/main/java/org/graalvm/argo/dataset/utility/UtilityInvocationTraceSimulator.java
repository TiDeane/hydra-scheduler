package org.graalvm.argo.dataset.utility;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.Invocation;
import org.graalvm.argo.dataset.InvocationTraceSimulator;
import org.graalvm.argo.dataset.OutputEntry;
import org.graalvm.argo.dataset.SimulationState;
import org.graalvm.argo.dataset.utility.calculator.NaiveUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.RandomUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.ExtendedUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.LongestRunningUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.NoOptUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.UtilityCalculator;

/**
 * This class is an extension of InvocationTraceSimulator that also
 * allows for simulating the application of optimizations using utility scores.
 */
public class UtilityInvocationTraceSimulator extends InvocationTraceSimulator {

    private final String inputFilePath;
    private final String utilityCalculationMethod;
    private final boolean useAOT;
    private final boolean useSnapshotting;

    public UtilityInvocationTraceSimulator(String inputFilePath, String utilityCalculationMethod, boolean useAOT, boolean useSnapshotting) {
        this.inputFilePath = inputFilePath;
        this.utilityCalculationMethod = utilityCalculationMethod;
        this.useAOT = useAOT;
        this.useSnapshotting = useSnapshotting;
    }

    @Override
    protected Invocation createInvocation(String owner, String function, int memory, int duration, int timestamp) {
        return new UtilityInvocation(owner, function, memory, duration, timestamp);
    }

    @Override
    protected Invocation createInvocation(String owner, String function, int memory, int p50duration, int p99duration, int timestamp) {
        return new UtilityInvocation(owner, function, memory, p50duration, p99duration, timestamp);
    }

    class UtilitySimulationState extends SimulationState {
        int optimizedColdStarts;
        float optimizationCost;
        UtilityCalculator utilityCalculator;

        public UtilitySimulationState() {
            this.optimizedColdStarts = 0;
            switch (utilityCalculationMethod) {
                case "naive":
                    this.utilityCalculator = new NaiveUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
                    break;
                case "extended":
                    this.utilityCalculator = new ExtendedUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
                    break;
                case "longest-running":
                    this.utilityCalculator = new LongestRunningUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
                    break;
                case "random":
                	this.utilityCalculator = new RandomUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
                	break;
                case "no-opt":
                	this.utilityCalculator = new NoOptUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
                	break;
                default:
                    this.utilityCalculator = new NoOptUtilityCalculator(inputFilePath, useAOT, useSnapshotting);
            }
        }
    }

    @Override
    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, SimulationState ss) {
        @SuppressWarnings("unchecked")
        List<UtilityInvocation> runningUtilityInvocations = (List<UtilityInvocation>)(List<?>) runningInvocations;
        UtilityOutputEntry utilityOutputEntry = new UtilityOutputEntry();
        utilityOutputEntry.optimizedColdStarts = ((UtilitySimulationState)ss).optimizedColdStarts;
        utilityOutputEntry.optimizationCost = ((UtilitySimulationState)ss).optimizationCost;
        utilityOutputEntry.runningOptimizedFunctions  = (int) runningUtilityInvocations.parallelStream().filter(UtilityInvocation::isOptimized).map(UtilityInvocation::getFunction).distinct().count();
        return super.updateStatistics(activeInvocations, runningInvocations, utilityOutputEntry, ss);
    }

    @Override
    protected void resetSimulationStateAfterUpdateStatistics(SimulationState ss) {
        ((UtilitySimulationState)ss).optimizedColdStarts = 0;
        ((UtilitySimulationState)ss).optimizationCost = 0;
        super.resetSimulationStateAfterUpdateStatistics(ss);
    }

    @Override
    protected void updateAfterWarmCheck(SimulationState ss, Invocation currentInvocation, Invocation warm) {
        UtilitySimulationState utilityss = ((UtilitySimulationState)ss);
        int currentTimestamp = utilityss.currentTimestamp;

        /* We make use of this function to perform utility calculation and optimization */
        if (currentTimestamp - utilityss.utilityCalculator.lastUtilityCalculation > Configuration.UTILITY_CALCULATION_INTERVAL) {
            utilityss.utilityCalculator.calculateUtilityScores(currentTimestamp);
        }

        /* Run optimization rounds until the functions marked for utility calculation have been optimized */
        if (currentTimestamp - utilityss.utilityCalculator.lastOptimizationRound >= Configuration.OPTIMIZATION_INTERVAL) {
            if (!utilityss.utilityCalculator.optimizationQueue.isEmpty()) {
                utilityss.utilityCalculator.runOptimizationRound(currentTimestamp);
                // TODO: update simulation state optimization cost
                System.err.println("Total optimized functions: " + (utilityss.utilityCalculator.optimizedFunctionsAOT.size() + utilityss.utilityCalculator.optimizedFunctionsSnapshot.size()));
            }
            utilityss.utilityCalculator.lastOptimizationRound = currentTimestamp;
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
            // duration for warm starts is set to P50 in the superclass
        }
        super.updateAfterWarmCheck(ss, currentInvocation, warm);
    }

    protected List<OutputEntry> simulateInvocations(String inputFile, int keepalive, int interval) {
        return simulateInvocations(inputFile, new UtilitySimulationState(), keepalive, interval);
    }
}
