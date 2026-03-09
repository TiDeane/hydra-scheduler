package org.graalvm.argo.dataset;

import org.graalvm.argo.dataset.aot.AOTInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.UtilityInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.UtilityOutputEntry;
import org.graalvm.argo.dataset.aot.AOTOutputEntry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
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
            // TODO: write total compilation cost
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
        Option utility = new Option("u", "utility", true, "Enable utility calculation (e.g., naive, longest-running, etc.).");
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
        String outputFilePath = filePath + "." + utility + ".output";
        System.out.println("Saving output to " + outputFilePath);

        int totalColdStarts = 0;
        long totalDuration = 0;
        long totalFootprint = 0;
        int totalOptimizedColdStarts = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath))) {
            for (OutputEntry entry : output) {
                totalColdStarts += entry.coldStarts;
                totalDuration += entry.totalDuration;
                totalFootprint += entry.totalFootprint;
                
                if (entry instanceof UtilityOutputEntry) {
                    totalOptimizedColdStarts += ((UtilityOutputEntry) entry).optimizedColdStarts;
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
            writer.write("Total optimized cold starts: " + totalOptimizedColdStarts);
            // TODO: write total optimization cost
        } catch (IOException e) {
            System.err.println("Error writing to output file: " + e.getMessage());
        }
    }
}
