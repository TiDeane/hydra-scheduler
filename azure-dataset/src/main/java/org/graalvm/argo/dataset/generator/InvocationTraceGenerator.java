package org.graalvm.argo.dataset.generator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
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

import org.graalvm.argo.dataset.utils.ExternalTraceSorter;

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

            String currentInput = "/tmp/generator/sorted_trace.csv";
            String currentOutput = "/tmp/generator/temp_buffer.csv";

            if (maxFunctions != 0) {
                downscaleByFunctions(currentInput, currentOutput, maxFunctions);
                currentInput = currentOutput; 
                currentOutput = (currentInput.contains("buffer")) ? "/tmp/generator/temp_work.csv" : "/tmp/generator/temp_buffer.csv";
                System.err.println("Finished downscaling to " + maxFunctions + " functions.");
            }

            if (maxConcInv != 0) {
                downscaleByConcurrentInvocations(currentInput, currentOutput, maxConcInv);
                currentInput = currentOutput;
                currentOutput = (currentInput.contains("buffer")) ? "/tmp/generator/temp_work.csv" : "/tmp/generator/temp_buffer.csv";
                System.err.println("Finished downscaling to " + maxConcInv + " concurrent invocations.");
            }

            if (maxUsers != 0) {
                downscaleByUser(currentInput, currentOutput, maxUsers);
                currentInput = currentOutput;
                currentOutput = (currentInput.contains("buffer")) ? "/tmp/generator/temp_work.csv" : "/tmp/generator/temp_buffer.csv";
                System.err.println("Finished downscaling to " + maxUsers + " users.");
            }

            if (maxMemory != 0) {
                downscaleByMemory(currentInput, currentOutput, maxMemory);
                currentInput = currentOutput;
                currentOutput = (currentInput.contains("buffer")) ? "/tmp/generator/temp_work.csv" : "/tmp/generator/temp_buffer.csv";
                System.err.println("Finished downscaling to " + maxMemory + " MB of memory.");
            }

            writeInvocationsToFile(currentInput, outputFilePath);

            /* Clear temporary files */
            new File("/tmp/generator/sorted_trace.csv").delete();
            new File("/tmp/generator/temp_work.csv").delete();
            new File("/tmp/generator/temp_buffer.csv").delete();
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            new HelpFormatter().printHelp("utility-name", options);
            return;
        }
    }

    private static void writeInvocationsToFile(String inputFilePath, String outputFilePath) throws Exception {
        int firstTimestamp = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
            String firstLine = reader.readLine();
            if (firstLine != null) {
                String[] splitRow = firstLine.split(DELIMITER);
                firstTimestamp = Integer.parseInt(splitRow[4]);
            }
        }

        /* Stream from the final temp file to the final output file */
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath, false))) {
            
            writer.write("HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp");
            writer.newLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(DELIMITER);
                String owner = parts[0];
                String function = parts[1];
                String memory = parts[2];
                String duration = parts[3];
                int timestamp = Integer.parseInt(parts[4]);
                
                int normalizedTimestamp = timestamp - firstTimestamp;

                writer.write(String.format("%s,%s,%s,%s,%d", owner, function, memory, duration, normalizedTimestamp));
                writer.newLine();
            }
        }

        if (compress) {
            writeMapping(outputFilePath + ".function_mapping", "HashFunction,CompressedHash", FunctionInfoStorage.COMPRESSED_MAPPING);
            writeMapping(outputFilePath + ".owner_mapping", "HashOwner,CompressedHash", compressedOwnerMapping);
        }
    }

    private static void writeMapping(String path, String header, Map<String, ?> mapping) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path, false))) {
            writer.write(header);
            writer.newLine();
            for (Map.Entry<String, ?> entry : mapping.entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue());
                writer.newLine();
            }
        }
    }

    private static void processFunction(String line, int firstMinute, int lastMinute, BufferedWriter bw) {
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
            compressedOwnerMapping.computeIfAbsent(owner, k -> compressedOwnerMapping.size());
            compressedOwnerHash = compressedOwnerMapping.get(owner).toString();
            FunctionInfoStorage.COMPRESSED_MAPPING.computeIfAbsent(function, k -> FunctionInfoStorage.COMPRESSED_MAPPING.size());
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
                String csvLine = String.join(",", 
                    compressedOwnerHash, 
                    compressedFunctionHash, 
                    String.valueOf(memory), 
                    String.valueOf(duration), 
                    String.valueOf(timestamp)
                );
                try {
                    bw.write(csvLine);
                    bw.newLine();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }
            ++currentMinute;
        }
        if (invocationCount > 0) {
            if (!owners.containsKey(compressedOwnerHash)) {
                owners.put(compressedOwnerHash, new Owner(compressedOwnerHash));
            }
            Owner currentOwner = owners.get(compressedOwnerHash);
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
            new File("/tmp/generator").mkdirs();
            File file = new File("input/invocations_per_function_md.anon." + datasetId + ".csv");
            BufferedReader br = new BufferedReader(new FileReader(file));
            BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/generator/raw_unsorted_trace.csv", false));
            String line;
            br.readLine(); // To skip the header
            int fcounter = 1;

            while ((line = br.readLine()) != null) {
                processFunction(line, firstMinute, lastMinute, bw);
                System.out.println("Processed function " + fcounter++);
            }
            System.out.println("Skipped " + skipped + " functions due to lack of information.");
            bw.close();
            br.close();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }

        /* At this point, we have the unordered list of all invocations */
        try {
            ExternalTraceSorter.sortTraceByTimestamp("/tmp/generator/raw_unsorted_trace.csv", "/tmp/generator/sorted_trace.csv", false);
            new File("/tmp/generator/raw_unsorted_trace.csv").delete();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        System.out.println("Finished sorting.");
    }

    /* Remove invocations that go over the maximum number of concurrent invocations. */
    private static void downscaleByConcurrentInvocations(String inputPath, String outputPath, int maxConcInv) throws IOException {
        List<Integer> activeInvocationsEndTimes = new LinkedList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                int duration = Integer.parseInt(splitRow[3]);
                int timestamp = Integer.parseInt(splitRow[4]);
                int endTimestamp = timestamp + duration;

                /* Note: this is REALLY slow */
                activeInvocationsEndTimes.removeIf(endTime -> timestamp >= endTime);

                if (activeInvocationsEndTimes.size() < maxConcInv) {
                    activeInvocationsEndTimes.add(endTimestamp);
                    
                    writer.write(line);
                    writer.newLine();
                } /* else: skip writing */
            }
        }
    }

    /* Remove invocations that are not from the N more popular users. */
    private static void downscaleByUser(String inputPath, String outputPath, int maxUsers) throws IOException {
        Set<String> selectedOwners = owners.values().stream()
                .sorted(Comparator.comparingInt(Owner::getFunctions).reversed())
                .limit(maxUsers).map(Owner::getOwnerHash).collect(Collectors.toSet());
        
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                String owner = splitRow[0];
                if (selectedOwners.contains(owner)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    /* Remove invocations that are not from the N first functions that appear in the trace. */
    private static void downscaleByFunctions(String inputPath, String outputPath, int maxFunctions) throws IOException {
        /* Count function frequencies */
        Map<String, Long> invocationsFunction = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                String functionId = splitRow[1];
                invocationsFunction.merge(functionId, 1L, Long::sum);
            }
        }

        int totalFunctions = invocationsFunction.size();
        int skipFunctions = Math.max(0, (totalFunctions - maxFunctions) / 2);

        Set<String> selectedFunctions = invocationsFunction.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .skip(skipFunctions)
                .limit(maxFunctions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        invocationsFunction.clear();

        /* Filter and write */
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                String functionId = splitRow[1];
                if (selectedFunctions.contains(functionId)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
    }

    /* Remove invocations that go over the maximum memory. */
    private static void downscaleByMemory(String inputPath, String outputPath, int maxMemory) throws IOException {
        List<Invocation> activeInvocations = new LinkedList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] splitRow = line.split(DELIMITER);
                int memory = Integer.parseInt(splitRow[2]);
                int duration = Integer.parseInt(splitRow[3]);
                int timestamp = Integer.parseInt(splitRow[4]);

                int currentInvocationTimestamp = timestamp;

                activeInvocations.removeIf(f -> currentInvocationTimestamp >= f.getEndTimestamp());
                int currentConsumption = activeInvocations.stream().mapToInt(Invocation::getMemory).sum();

                if (currentConsumption + memory <= maxMemory) {
                    activeInvocations.add(new Invocation(splitRow[0], splitRow[1], memory, duration, timestamp));
                    writer.write(line);
                    writer.newLine();
                } /* else: skip writing */
            }
        }
    }
}
