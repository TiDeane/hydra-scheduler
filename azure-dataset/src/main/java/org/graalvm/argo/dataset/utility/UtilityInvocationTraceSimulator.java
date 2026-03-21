package org.graalvm.argo.dataset.utility;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import org.graalvm.argo.dataset.Invocation;
import org.graalvm.argo.dataset.InvocationTraceSimulator;
import org.graalvm.argo.dataset.OutputEntry;
import org.graalvm.argo.dataset.SimulationState;
import org.graalvm.argo.dataset.utility.calculator.ColdStartUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.RandomUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.ExtendedUtilityCalculator;
import org.graalvm.argo.dataset.utility.calculator.FunctionUtilityInfo;
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
    private final boolean useSnapshot;

    public UtilityInvocationTraceSimulator(String inputFilePath, String utilityCalculationMethod, boolean useAOT, boolean useSnapshot) {
        this.inputFilePath = inputFilePath;
        this.utilityCalculationMethod = utilityCalculationMethod;
        this.useAOT = useAOT;
        this.useSnapshot = useSnapshot;
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
        int optimizedSlaViolations;
        HashSet<String> optimizedSlaViolationFunctions = new HashSet<>();
        float optimizationCost;
        UtilityCalculator utilityCalculator;

        public UtilitySimulationState() {
            switch (utilityCalculationMethod) {
                case "cold-start":
                    this.utilityCalculator = new ColdStartUtilityCalculator(inputFilePath, useAOT, useSnapshot);
                    break;
                case "extended":
                    this.utilityCalculator = new ExtendedUtilityCalculator(inputFilePath, useAOT, useSnapshot);
                    break;
                case "longest-running":
                    this.utilityCalculator = new LongestRunningUtilityCalculator(inputFilePath, useAOT, useSnapshot);
                    break;
                case "random":
                	this.utilityCalculator = new RandomUtilityCalculator(inputFilePath, useAOT, useSnapshot);
                	break;
                case "no-opt":
                	this.utilityCalculator = new NoOptUtilityCalculator(inputFilePath, useAOT, useSnapshot);
                	break;
                default:
                    this.utilityCalculator = new NoOptUtilityCalculator(inputFilePath, useAOT, useSnapshot);
            }
        }
    }

    @Override
    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, SimulationState ss) {
        @SuppressWarnings("unchecked")
        List<UtilityInvocation> runningUtilityInvocations = (List<UtilityInvocation>)(List<?>) runningInvocations;
        UtilityOutputEntry utilityOutputEntry = new UtilityOutputEntry();
        utilityOutputEntry.optimizedColdStarts = ((UtilitySimulationState)ss).optimizedColdStarts;
        utilityOutputEntry.optimizedSlaViolations = ((UtilitySimulationState)ss).optimizedSlaViolations;
        utilityOutputEntry.optimizationCost = ((UtilitySimulationState)ss).optimizationCost;
        utilityOutputEntry.runningOptimizedFunctions  = (int) runningUtilityInvocations.parallelStream().filter(UtilityInvocation::isOptimized).map(UtilityInvocation::getFunction).distinct().count();
        return super.updateStatistics(activeInvocations, runningInvocations, utilityOutputEntry, ss);
    }

    @Override
    protected void resetSimulationStateAfterUpdateStatistics(SimulationState ss) {
        ((UtilitySimulationState)ss).optimizedColdStarts = 0;
        ((UtilitySimulationState)ss).optimizedSlaViolations = 0;
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
                utilityss.optimizationCost += utilityss.utilityCalculator.runOptimizationRound(currentTimestamp);
                System.err.println("Total optimized functions: " + (utilityss.utilityCalculator.optimizedFunctionsAOT.size() + utilityss.utilityCalculator.optimizedFunctionsSnapshot.size()));
            }
            utilityss.utilityCalculator.lastOptimizationRound = currentTimestamp;
        }

        UtilityInvocation currentUtilityInvocation = (UtilityInvocation) currentInvocation;
        String currentFunction = currentUtilityInvocation.getFunction();
        utilityss.utilityCalculator.registerInvocation(currentFunction, currentInvocation.getMemory(), currentInvocation.getP99Duration());
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

                if (currentInvocation.getDuration() >  currentInvocation.getP50Duration() * super.APDEX_FRUSTRATION_THRESHOLD
                    || (currentInvocation.getP50Duration() == 0 && currentInvocation.getDuration() > APDEX_FRUSTRATION_THRESHOLD)) {
                    // SLA violation occurred
                    utilityss.optimizedSlaViolations++;
                    utilityss.optimizedSlaViolationFunctions.add(currentInvocation.getFunction());
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
        UtilitySimulationState utilityss = new UtilitySimulationState();
        List<OutputEntry> output = simulateInvocations(inputFile, utilityss, keepalive, interval);
        writeCdfData(inputFile, utilityss);
        return output;
    }

    private void writeCdfData(String inputFile, UtilitySimulationState utilityss) {
        File input = new File(inputFile);
        String parentDir = input.getParent();
        String inputFileName = input.getName().replace(".csv", "");

        String folderName = String.format("m%d_oa%d_ob%d_uci%d_oi%d",
                Configuration.MAX_OPTIMIZED,
                Configuration.OPTIMIZATION_AMOUNT,
                Configuration.OPTIMIZATION_BUDGET,
                Configuration.UTILITY_CALCULATION_INTERVAL / 60000,
                Configuration.OPTIMIZATION_INTERVAL / 60000
        );

        Path targetDirPath = Paths.get(parentDir, folderName);

        String configStr = "baseline";
        if (useAOT && useSnapshot) configStr = "aot.snapshot";
        else if (useAOT) configStr = "aot";
        else if (useSnapshot) configStr = "snapshot";

        // Format: [trace]_[utility]_[config]_cdf_data.csv
        String cdfFileName = String.format("%s_%s_%s_cdf_data.csv", inputFileName, utilityCalculationMethod, configStr);
        
        File cdfFile = targetDirPath.resolve(cdfFileName).toFile();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(cdfFile))) {
            writer.write("function,total_invocations,sla_violations,violation_rate");
            writer.newLine();

            for (FunctionUtilityInfo function : utilityss.utilityCalculator.functions.values()) {
                double violationRate = 0.0;
                if (function.totalInvocations > 0) {
                    violationRate = (double) function.totalSlaViolations / function.totalInvocations;
                }

                writer.write(String.format("%s,%d,%d,%.6f", 
                    function.name, 
                    function.totalInvocations, 
                    function.totalSlaViolations, 
                    violationRate
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing CDF data to " + cdfFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}
