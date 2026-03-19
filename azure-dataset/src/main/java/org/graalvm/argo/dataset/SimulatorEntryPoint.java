package org.graalvm.argo.dataset;

import org.graalvm.argo.dataset.aot.AOTInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.Configuration;
import org.graalvm.argo.dataset.utility.UtilityInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.UtilityOutputEntry;
import org.graalvm.argo.dataset.aot.AOTOutputEntry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class SimulatorEntryPoint {

    /**
     * Minimum number of milliseconds to wait before recalculating aggregated data.
     */
    protected static final int SAMPLE_INTERVAL = 1000;

    public static void main(String[] args) throws Exception {
        Options options = prepareOptions();
        try {
            CommandLine cmd = new DefaultParser().parse(options, args);
            String inputfile = cmd.getOptionValue("input");
            int keepalive = Integer.parseInt(cmd.getOptionValue("keepalive", "600000"));
            String utility = cmd.getOptionValue("utility", "none");
            boolean useAOT = cmd.hasOption("aot");
            boolean useSnapshot = cmd.hasOption("snapshot");

            InvocationTraceSimulator simulator;
            if (!utility.equals("none")) {
                simulator = new UtilityInvocationTraceSimulator(inputfile, utility, useAOT, useSnapshot);
            } else {
                if (useAOT) {
                    simulator = new AOTInvocationTraceSimulator();
                } else {
                    simulator = new InvocationTraceSimulator();
                }
            }
            List<OutputEntry> output = simulator.simulate(inputfile, keepalive, SAMPLE_INTERVAL);
            processOutput(output, keepalive, utility, useAOT, useSnapshot, inputfile);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            new HelpFormatter().printHelp("utility-name", options);
        }
    }

    private static Options prepareOptions() {
        Options options = new Options();
        Option input = new Option("i", "input", true, "Input invocation trace file path.");
        input.setRequired(true);
        options.addOption(input);
        Option keepalive = new Option("k", "keepalive", true, "Function keep alive time in milliseconds.");
        keepalive.setRequired(false);
        options.addOption(keepalive);
        Option utility = new Option("u", "utility", true, "Enable utility calculation (e.g., cold-start, longest-running, etc.).");
        utility.setRequired(false);
        options.addOption(utility);
        Option aot = new Option("aot", "aot", false, "Enable AOT optimization.");
        aot.setRequired(false);
        options.addOption(aot);
        Option snapshot = new Option("snapshot", "snapshot", false, "Enable snapshotting optimization.");
        snapshot.setRequired(false);
        options.addOption(snapshot);
        return options;
    }

    private static void processOutput(List<OutputEntry> output, int keepalive, String utility, boolean useAOT, boolean useSnapshot, String filePath) {
        String outputFilePath = getOutputPath(filePath, utility, useAOT, useSnapshot);
        System.out.println("Saving output to " + outputFilePath);

        int totalColdStarts = 0;
        long totalDuration = 0;
        long totalFootprint = 0;
        int totalOptimizedColdStarts = 0;
        int totalOptimizedSlaViolations = 0;
        float totalOptimizationCost = 0;
        int totalSlaViolations = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
            for (OutputEntry entry : output) {
                totalColdStarts += entry.coldStarts;
                totalDuration += entry.totalDuration;
                totalFootprint += entry.totalFootprint;
                totalSlaViolations += entry.slaViolations;
                
                if (entry instanceof UtilityOutputEntry) {
                    totalOptimizedColdStarts += ((UtilityOutputEntry) entry).optimizedColdStarts;
                    totalOptimizedSlaViolations += ((UtilityOutputEntry) entry).optimizedSlaViolations;
                    totalOptimizationCost += ((UtilityOutputEntry) entry).optimizationCost;
                } else if (entry instanceof AOTOutputEntry) {
                    totalOptimizedColdStarts += ((AOTOutputEntry) entry).optimizedColdStarts;
                }
                
                writer.write(entry.toString());
                writer.newLine();
            }
            
            writer.write("Utility: " + utility + ", keepalive: " + keepalive + ", useAOT: " + useAOT + ", useSnapshot: " + useSnapshot);
            writer.newLine();

            writer.write("Total cold starts: " + totalColdStarts);
            writer.newLine();
            writer.write("Total duration: " + totalDuration);
            writer.newLine();
            writer.write("Total footprint: " + totalFootprint);
            writer.newLine();
            writer.write("Total SLA violations: " + totalSlaViolations);
            // TODO: include number of functions that suffered SLA violations
            writer.newLine();
            writer.write("Total optimized cold starts: " + totalOptimizedColdStarts);
            writer.newLine();
            writer.write("Total optimization cost: " + totalOptimizationCost);
            writer.newLine();
            writer.write("Total optimized SLA violations: " + totalOptimizedSlaViolations);
            // TODO: include number of optimized functions that suffered SLA violations
        } catch (IOException e) {
            System.err.println("Error writing to output file: " + e.getMessage());
        }
    }

    private static String getOutputPath(String inputFilePath, String utility, boolean useAOT, boolean useSnapshot) {
        File inputFile = new File(inputFilePath);
        
        String parentDir = inputFile.getParent();
        String inputFileName = inputFile.getName();

        String folderName = String.format("m%d_oa%d_ob%d_uci%d_oi%d",
                Configuration.MAX_OPTIMIZED,
                Configuration.OPTIMIZATION_AMOUNT,
                Configuration.OPTIMIZATION_BUDGET,
                Configuration.UTILITY_CALCULATION_INTERVAL / 60000,
                Configuration.OPTIMIZATION_INTERVAL / 60000
        );

        String outputFileName = inputFileName;
        outputFileName += "." + utility;
        
        if (useAOT) {
            outputFileName += ".aot";
        }
        if (useSnapshot) {
            outputFileName += ".snapshot";
        }
        outputFileName += ".output";

        Path targetDirPath = Paths.get(parentDir, folderName);

        try {            
            if (Files.notExists(targetDirPath)) {
                Files.createDirectories(targetDirPath);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.exit(1);
        }

        return targetDirPath.resolve(outputFileName.toString()).toString();
    }
}
