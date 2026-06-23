package org.graalvm.argo.dataset.generator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.graalvm.argo.dataset.Invocation;
import org.graalvm.argo.dataset.InvocationTraceFormat;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

abstract class AbstractInvocationTraceGenerator {

    protected static final String SOURCE_DELIMITER = ",";

    protected void run(String[] args) throws Exception {
        Options options = prepareOptions();
        try {
            CommandLine cmd = new DefaultParser().parse(options, args);
            List<Invocation> invocations = loadInvocations(cmd);
            applyDownscaling(cmd, invocations);
            writeInvocations(cmd.getOptionValue("trace"), invocations);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            new HelpFormatter().printHelp("utility-name", options);
        }
    }

    private Options prepareOptions() {
        Options options = new Options();
        addSourceOptions(options);
        addCommonOptions(options);
        return options;
    }

    protected abstract void addSourceOptions(Options options);

    protected abstract List<Invocation> loadInvocations(CommandLine cmd) throws Exception;

    private void addCommonOptions(Options options) {
        Option output = new Option("t", "trace", true, "Output invocation trace file path.");
        output.setRequired(true);
        options.addOption(output);

        Option maxMemory = new Option("m", "mem", true, "Maximum memory (in MBs) used by generated trace.");
        maxMemory.setRequired(false);
        options.addOption(maxMemory);

        Option maxUsers = new Option("u", "users", true, "Maximum number of users present in the generated trace.");
        maxUsers.setRequired(false);
        options.addOption(maxUsers);

        Option maxConcInv = new Option("i", "cinv", true, "Maximum number of concurrent invocations in the generated trace.");
        maxConcInv.setRequired(false);
        options.addOption(maxConcInv);

        Option maxFunctions = new Option("f", "functions", true, "Maximum number of functions in the generated trace.");
        maxFunctions.setRequired(false);
        options.addOption(maxFunctions);
    }

    private void applyDownscaling(CommandLine cmd, List<Invocation> invocations) {
        int maxFunctions = Integer.parseInt(cmd.getOptionValue("functions", "0"));
        int maxConcInv = Integer.parseInt(cmd.getOptionValue("cinv", "0"));
        int maxUsers = Integer.parseInt(cmd.getOptionValue("users", "0"));
        int maxMemory = Integer.parseInt(cmd.getOptionValue("mem", "0"));

        if (maxFunctions != 0) {
            System.out.println("Number of invocations *before* filter by number of functions: " + invocations.size());
            downscaleByFunctions(invocations, maxFunctions);
            System.out.println("Number of invocations *after* filter by number of functions: " + invocations.size());
        }

        if (maxConcInv != 0) {
            System.out.println("Number of invocations *before* filter by concurrent invocations: " + invocations.size());
            downscaleByConcurrentInvocations(invocations, maxConcInv);
            System.out.println("Number of invocations *after* filter by concurrent invocations: " + invocations.size());
        }

        if (maxUsers != 0) {
            System.out.println("Number of invocations *before* filter by number of users: " + invocations.size());
            downscaleByUser(invocations, maxUsers);
            System.out.println("Number of invocations *after* filter by number of users: " + invocations.size());
        }

        if (maxMemory != 0) {
            System.out.println("Number of invocations *before* filter by memory: " + invocations.size());
            downscaleByMemory(invocations, maxMemory);
            System.out.println("Number of invocations *after* filter by memory: " + invocations.size());
        }

        System.out.println("Final number of invocations: " + invocations.size());
    }

    protected void writeInvocations(String outputFilePath, List<Invocation> invocations) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath, false))) {
            writer.write(InvocationTraceFormat.HEADER);
            writer.newLine();
            if (invocations.isEmpty()) {
                return;
            }
            int firstTimestamp = invocations.get(0).getTimestamp();
            for (Invocation invocation : invocations) {
                writer.write(invocation.toString(firstTimestamp));
                writer.newLine();
            }
        }
    }

    protected void downscaleByConcurrentInvocations(List<Invocation> invocations, int maxConcInv) {
        List<Invocation> activeInvocations = new ArrayList<>();
        ListIterator<Invocation> iter = invocations.listIterator();
        while (iter.hasNext()) {
            Invocation currentInvocation = iter.next();
            int currentInvocationTimestamp = currentInvocation.getTimestamp();

            activeInvocations.removeIf(f -> currentInvocationTimestamp >= f.getEndTimestamp());
            long currentInvokes = activeInvocations.size();

            if (currentInvokes + 1 <= maxConcInv) {
                activeInvocations.add(currentInvocation);
            } else {
                iter.remove();
            }
        }
    }

    protected void downscaleByUser(List<Invocation> invocations, int maxUsers) {
        Map<String, Set<String>> ownerFunctions = invocations.stream()
                .collect(Collectors.groupingBy(Invocation::getOwner,
                        Collectors.mapping(Invocation::getFunction, Collectors.toSet())));
        Set<String> selectedOwners = ownerFunctions.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(maxUsers)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        invocations.removeIf(i -> !selectedOwners.contains(i.getOwner()));
    }

    protected void downscaleByFunctions(List<Invocation> invocations, int maxFunctions) {
        Set<String> selectedFunctions = new HashSet<>();
        Map<String, Long> invocationsFunction = invocations.stream()
                .collect(Collectors.groupingBy(Invocation::getFunction, Collectors.counting()));
        int skipFunctions = (invocationsFunction.size() - maxFunctions) / 2;
        invocationsFunction.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .skip(skipFunctions)
                .limit(maxFunctions)
                .forEach(entry -> selectedFunctions.add(entry.getKey()));
        invocations.removeIf(i -> !selectedFunctions.contains(i.getFunction()));
    }

    protected void downscaleByMemory(List<Invocation> invocations, int maxMemory) {
        List<Invocation> activeInvocations = new ArrayList<>();
        ListIterator<Invocation> iter = invocations.listIterator();
        while (iter.hasNext()) {
            Invocation currentInvocation = iter.next();
            int currentInvocationTimestamp = currentInvocation.getTimestamp();

            activeInvocations.removeIf(f -> currentInvocationTimestamp >= f.getEndTimestamp());
            int currentConsumption = activeInvocations.stream().mapToInt(Invocation::getMemory).sum();

            if (currentConsumption + currentInvocation.getMemory() <= maxMemory) {
                activeInvocations.add(currentInvocation);
            } else {
                iter.remove();
            }
        }
    }
}
