package org.graalvm.argo.dataset;

import org.apache.commons.cli.*;
import org.graalvm.argo.dataset.aot.AOTInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.UtilityInvocationTraceSimulator;
import org.graalvm.argo.dataset.utility.UtilityOutputEntry;
import org.graalvm.argo.dataset.aot.AOTOutputEntry;

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
                simulator = new UtilityInvocationTraceSimulator(utility, useAOT, useSnapshot);
            } else {
                if (useAOT) {
                    simulator = new AOTInvocationTraceSimulator();
                } else {
                    simulator = new InvocationTraceSimulator();
                }
            }
            List<OutputEntry> output = simulator.simulate(inputfile, keepalive, SAMPLE_INTERVAL);

            int totalColdStarts = 0;
            int totalDuration = 0;
            int totalFootprint = 0;
            int totalOptimizedColdStarts = 0;
            for (OutputEntry entry : output) {
                totalColdStarts += entry.coldStarts;
                totalDuration += entry.totalDuration;
                totalFootprint += entry.totalFootprint;
                if (entry instanceof UtilityOutputEntry) {
                    totalOptimizedColdStarts += ((UtilityOutputEntry) entry).optimizedColdStarts;
                } else if (entry instanceof AOTOutputEntry) {
                    totalOptimizedColdStarts += ((AOTOutputEntry) entry).optimizedColdStarts;
                }
                System.out.println(entry);
            }
            System.out.println("Total cold starts: " + totalColdStarts + "\nTotal duration: " + totalDuration + "\nTotal footprint: " + totalFootprint + "\nTotal optimized cold starts: " + totalOptimizedColdStarts);
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
        utility.setRequired(false);
        options.addOption(utility);
        Option aot = new Option("aot", "aot", false, "Enable AOT optimization.");
        aot.setRequired(false);
        options.addOption(aot);
        return options;
    }
}
