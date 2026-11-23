package org.graalvm.argo.dataset.generator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public class FunctionInfoStorage {

    private static final String DELIMITER = ",";

    public static final Set<String> FUNCTIONS_SEEN = new HashSet<>();
    /* Key - function */
    public static final Map<String, Integer> DURATIONS = new HashMap<>();
    /* Key - function */
    public static final Map<String, Integer> P50_DURATIONS = new HashMap<>();
    /* Key - function */
    public static final Map<String, Integer> P99_DURATIONS = new HashMap<>();
    /* Key - function */
    public static final Map<String, Integer> COMPRESSED_MAPPING = new HashMap<>();
    /* Key - app */
    public static final Map<String, Integer> MEMORIES = new HashMap<>();

    public static void fillFunctionData(String datasetId) {
        FUNCTIONS_SEEN.clear();
        DURATIONS.clear();
        P50_DURATIONS.clear();
        P99_DURATIONS.clear();
        MEMORIES.clear();
        COMPRESSED_MAPPING.clear();
        fillDurations(datasetId);
        fillMemories(datasetId);
    }

    /*
    HashOwner, HashApp, HashFunction, Average, Count, Minimum, Maximum, percentile_Average_X...
     */
    private static void fillDurations(String datasetId) {
        try {
            File file = new File("input/function_durations_percentiles.anon." + datasetId + ".csv");
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            while ((line = br.readLine()) != null) {
                splitRow = line.split(DELIMITER);
                String function = splitRow[2];
                int averageDuration = Integer.parseInt(splitRow[3]);
                DURATIONS.put(function, averageDuration);
                int p50Duration = Integer.parseInt(splitRow[10]);
                P50_DURATIONS.put(function, p50Duration);
                int p99Duration = Integer.parseInt(splitRow[12]);
                P99_DURATIONS.put(function, p99Duration);
            }
            br.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    /*
    HashOwner, HashApp, SampleCount, AverageAllocatedMb, AverageAllocatedMb_pctX...
     */
    private static void fillMemories(String datasetId) {
        try {
            File file = new File("input/app_memory_percentiles.anon." + datasetId + ".csv");
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            while ((line = br.readLine()) != null) {
                splitRow = line.split(DELIMITER);
                String app = splitRow[1];
                int averageMemory = Integer.parseInt(splitRow[3]);
                MEMORIES.put(app, averageMemory);
            }
            br.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}
