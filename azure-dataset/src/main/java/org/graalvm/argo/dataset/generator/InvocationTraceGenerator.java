package org.graalvm.argo.dataset.generator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.graalvm.argo.dataset.Invocation;

/**
 * This class generates an invocation trace from the azure dataset. Given a set
 * of options, it will generate a file with one invocation per line.
 */
public class InvocationTraceGenerator {

    public static final String DELIMITER = ",";
    private static final int MINUTES_COLUMN_OFFSET = 3;
    private static final List<Invocation> invocations = new LinkedList<>();
    private static final Map<String, Owner> owners = new HashMap<>(2048);
    private static final Map<String, Integer> compressedOwnerMapping = new HashMap<>(2048);
    private static boolean compress = false;
    private static int skipped = 0;

    private static Options prepareOptions() {
        Options options = new Options();
        Option input = new Option("d", "day", true, "Input dataset day (eg., d03).");
        input.setRequired(true);
        options.addOption(input);
        Option output = new Option("t", "trace", true, "Output invocation trace file path.");
        output.setRequired(true);
        options.addOption(output);
        Option firstMinute = new Option("b", "bmin", true, "First minute to include from the trace ([1,1440]).");
        firstMinute.setRequired(false);
        options.addOption(firstMinute);
        Option lastMinute = new Option("e", "emin", true, "Last minute to include from the trace ([1,1440]).");
        lastMinute.setRequired(false);
        options.addOption(lastMinute);
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
        Option compress = new Option("c", "compress", false, "Compresses the output trace file.");
        compress.setRequired(false);
        options.addOption(compress);
        return options;
    }

    public static void main(String[] args) throws Exception {
        Options options = prepareOptions();
        try {
            CommandLine cmd = new DefaultParser().parse(options, args);
            String day = cmd.getOptionValue("day");
            String outputFilePath = cmd.getOptionValue("trace");
            int firstMinute = Integer.parseInt(cmd.getOptionValue("bmin", "701"));
            int lastMinute = Integer.parseInt(cmd.getOptionValue("emin", "710"));
            int maxMemory = Integer.parseInt(cmd.getOptionValue("mem", "0"));
            int maxUsers = Integer.parseInt(cmd.getOptionValue("users", "0"));
            int maxConcInv = Integer.parseInt(cmd.getOptionValue("cinv", "0"));
            int maxFunctions = Integer.parseInt(cmd.getOptionValue("functions", "0"));
            compress = cmd.hasOption("compress");

            FunctionInfoStorage.fillFunctionData(day);
            processDay(day, firstMinute, lastMinute);

            if (maxFunctions != 0) {
                System.out.println("Number of invocations *before* filter by number of functions: " + invocations.size());
                downscaleByFunctions(maxFunctions);
                System.out.println("Number of invocations *after* filter by number of functions: " + invocations.size());
            }

            if (maxConcInv != 0) {
                System.out.println("Number of invocations *before* filter by concurrent invocations: " + invocations.size());
                downscaleByConcurrentInvocations(maxConcInv);
                System.out.println("Number of invocations *after* filter by concurrent invocations: " + invocations.size());
            }

            if (maxUsers != 0) {
                System.out.println("Number of invocations *before* filter by number of users: " + invocations.size());
                downscaleByUser(maxUsers);
                System.out.println("Number of invocations *after* filter by number of users: " + invocations.size());
            }

            if (maxMemory != 0) {
                System.out.println("Number of invocations *before* filter by memory: " + invocations.size());
                downscaleByMemory(maxMemory);
                System.out.println("Number of invocations *after* filter by memory: " + invocations.size());
            }

            System.out.println("Final number of invocations: " + invocations.size());
            writeInvocationsToFile(outputFilePath);

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            new HelpFormatter().printHelp("utility-name", options);
            return;
        }
    }

    private static void writeInvocationsToFile(String outputFilePath) throws Exception {
        int firstTimestamp = invocations.get(0).getTimestamp();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath, false))) {
            writer.write("HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp");
            writer.newLine();
            for (Invocation invocation : invocations) {
                writer.write(invocation.toString(firstTimestamp));
                writer.newLine();
            }
        }
        if (compress) {
            /* Write mapping of compressed function ID to real hashes */
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath + ".function_mapping", false))) {
                writer.write("HashFunction,CompressedHash");
                writer.newLine();
                for (String function : FunctionInfoStorage.COMPRESSED_MAPPING.keySet()) {
                    writer.write(function + "," + FunctionInfoStorage.COMPRESSED_MAPPING.get(function));
                    writer.newLine();
                }
            }
            /* Write mapping of compressed owner IDs to real hashes */
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath + ".owner_mapping", false))) {
                writer.write("HashOwner,CompressedHash");
                writer.newLine();
                for (String owner : compressedOwnerMapping.keySet()) {
                    writer.write(owner + "," + compressedOwnerMapping.get(owner));
                    writer.newLine();
                }
            }
        }
    }

    private static void processFunction(String line, int firstMinute, int lastMinute) {
        String[] splitRow = line.split(DELIMITER);
        String owner = splitRow[0];
        String app = splitRow[1];
        String function = splitRow[2];
        String compressedFunctionHash = function;
        String compressedOwnerHash = owner;

        /* If there is no record about this function about avg duration or memory, then skip */
        if (!FunctionInfoStorage.DURATIONS.containsKey(function) || !FunctionInfoStorage.MEMORIES.containsKey(app)) {
            ++skipped;
            return;
        }

        if (compress) {
            compressedOwnerMapping.put(owner, compressedOwnerMapping.size());
            compressedOwnerHash = compressedOwnerMapping.get(owner).toString();
            FunctionInfoStorage.COMPRESSED_MAPPING.put(function, FunctionInfoStorage.COMPRESSED_MAPPING.size());
            compressedFunctionHash = FunctionInfoStorage.COMPRESSED_MAPPING.get(function).toString();
        }

        int memory = FunctionInfoStorage.MEMORIES.get(app);
        int duration = FunctionInfoStorage.DURATIONS.get(function);
        int currentMinute = firstMinute;
        int invocationCount = 0;
        while (currentMinute <= lastMinute) {
            int invocationsForMinute = Integer.parseInt(splitRow[currentMinute + MINUTES_COLUMN_OFFSET]);
            invocationCount += invocationsForMinute;
            int minBeginningMs = (currentMinute - 1) * 60000;
            int minEndMs = minBeginningMs + 60000;
            for (int i = 0; i < invocationsForMinute; ++i) {
                int timestamp = ThreadLocalRandom.current().nextInt(minBeginningMs, minEndMs);
                invocations.add(new Invocation(compressedOwnerHash, compressedFunctionHash, memory, duration, timestamp));
            }
            ++currentMinute;
        }
        if (invocationCount > 0) {
            if (!owners.containsKey(owner)) {
                owners.put(owner, new Owner(owner));
            }
            Owner currentOwner = owners.get(owner);
            currentOwner.addFunction(function);
            currentOwner.addInvocations(invocationCount);
        }
    }

    /*
     * Read data from the CSV file, generate timestamps for the desired time frame.
     * File expected syntax: HashOwner, HashApp, HashFunction, Trigger, 1, 2, 3...
     */
    private static void processDay(String datasetId, int firstMinute, int lastMinute) {
        try {
            File file = new File("input/invocations_per_function_md.anon." + datasetId + ".csv");
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            br.readLine(); // To skip the header
            int fcounter = 1;

            while ((line = br.readLine()) != null) {
                processFunction(line, firstMinute, lastMinute);
                System.out.println("Processed function " + fcounter++);
            }
            System.out.println("Skipped " + skipped + " functions due to lack of information.");
            br.close();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }

        /* At this point, we have the unordered list of all invocations */
        Collections.sort(invocations, Comparator.comparingInt(Invocation::getTimestamp));
        System.out.println("Finished sorting.");
    }

    /* Remove invocations that go over the maximum number of concurrent invocations. */
    private static void downscaleByConcurrentInvocations(int maxConcInv) {
        List<Invocation> activeInvocations = new LinkedList<>();
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

    /* Remove invocations that are not from the N more popular users. */
    private static void downscaleByUser(int maxUsers) {
        Set<String> selectedOwners = owners.values().stream()
                .sorted(Comparator.comparingInt(Owner::getFunctions).reversed())
                .limit(maxUsers).map(Owner::getOwnerHash).collect(Collectors.toSet());
        invocations.removeIf(i -> !selectedOwners.contains(i.getOwner()));
    }

    /* Remove invocations that are not from the N first functions that appear in the trace. */
    private static void downscaleByFunctions(int maxFunctions) {
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

    /* Remove invocations that go over the maximum memory. */
    private static void downscaleByMemory(int maxMemory) {
        List<Invocation> activeInvocations = new LinkedList<>();
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
