package org.graalvm.argo.dataset.generator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.graalvm.argo.dataset.Invocation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates per-invocation traces from Huawei private dataset (per-minute).
 *
 * Expected input directories (relative to repo root by default):
 *  - input/huawei_private_minute/requests_minute/day_XXX.csv
 *  - input/huawei_private_minute/function_delay_minute/day_XXX.csv
 *  - input/huawei_private_minute/memory_limit_minute/day_XXX.csv
 *
 * Output schema:
 *  HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp
 */
public class HuaweiInvocationTraceGenerator extends AbstractInvocationTraceGenerator {

    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MINUTES_PER_DAY = 1440;
    private static final int SECONDS_PER_DAY = 86_400;

    /* Starts the Huawei trace generator from command-line arguments. */
    public static void main(String[] args) throws Exception {
        new HuaweiInvocationTraceGenerator().run(args);
    }

    /* Adds Huawei-specific input and minute range options. */
    @Override
    protected void addSourceOptions(Options options) {
        Option day = new Option("d", "day", true, "Input dataset day (000-234).");
        day.setRequired(true);
        options.addOption(day);

        Option startDay = new Option(null, "startDay", true, "Start dataset day (000-234).");
        startDay.setRequired(false);
        options.addOption(startDay);

        Option endDay = new Option(null, "endDay", true, "End dataset day (000-234).");
        endDay.setRequired(false);
        options.addOption(endDay);

        Option inputDir = new Option(null, "inputDir", true, "Input Huawei dataset base directory.");
        inputDir.setRequired(false);
        options.addOption(inputDir);

        Option firstMinute = new Option("b", "bmin", true, "First minute to include from the trace ([0,1439]).");
        firstMinute.setRequired(false);
        options.addOption(firstMinute);

        Option lastMinute = new Option("e", "emin", true, "Last minute to include from the trace ([0,1439]).");
        lastMinute.setRequired(false);
        options.addOption(lastMinute);
    }

    /* Reads command-line options and loads the requested Huawei trace range. */
    @Override
    protected List<Invocation> loadInvocations(CommandLine cmd) throws IOException, ParseException {
        String inputBaseDir = cmd.getOptionValue("inputDir", "input/huawei_private_minute");
        int firstMinute = Integer.parseInt(cmd.getOptionValue("bmin", "0"));
        int lastMinute = Integer.parseInt(cmd.getOptionValue("emin", "1439"));

        Integer startDay = cmd.hasOption("startDay") ? parseDay(cmd.getOptionValue("startDay")) : null;
        Integer endDay = cmd.hasOption("endDay") ? parseDay(cmd.getOptionValue("endDay")) : null;
        int singleDay = parseDay(cmd.getOptionValue("day"));
        if (startDay == null || endDay == null) {
            startDay = singleDay;
            endDay = singleDay;
        }

        validateMinuteRange(firstMinute, lastMinute);
        return loadInvocations(inputBaseDir, startDay, endDay, firstMinute, lastMinute);
    }

    /* Parses a dataset day value from the command line. */
    private int parseDay(String dayStr) throws ParseException {
        try {
            return Integer.parseInt(dayStr);
        } catch (NumberFormatException nfe) {
            throw new ParseException("Invalid day value: " + dayStr);
        }
    }

    /* Validates that the requested minute range is within one day. */
    private void validateMinuteRange(int firstMinute, int lastMinute) throws ParseException {
        if (firstMinute < 0 || lastMinute >= MINUTES_PER_DAY || firstMinute > lastMinute) {
            throw new ParseException("Invalid minute range: [" + firstMinute + "," + lastMinute + "]");
        }
    }

    /* Loads and sorts invocations across the requested day range. */
    private List<Invocation> loadInvocations(String inputBaseDir,
                                             int startDay,
                                             int endDay,
                                             int firstMinute,
                                             int lastMinute) throws IOException {
        List<Invocation> invocations = new ArrayList<>();
        for (int day = startDay; day <= endDay; day++) {
            processDay(invocations, inputBaseDir, day, firstMinute, lastMinute);
        }
        invocations.sort(Comparator.comparingInt(Invocation::getTimestamp));
        return invocations;
    }

    /* Reads one Huawei dataset day and expands minute aggregates into invocations. */
    private void processDay(List<Invocation> invocations,
                            String inputBaseDir,
                            int day,
                            int firstMinute,
                            int lastMinute) throws IOException {
        String dayFile = String.format("day_%03d.csv", day);
        File reqFile = new File(inputBaseDir, "requests_minute/" + dayFile);
        File delayFile = new File(inputBaseDir, "function_delay_minute/" + dayFile);
        File memFile = new File(inputBaseDir, "memory_limit_minute/" + dayFile);

        if (!reqFile.exists() || !delayFile.exists() || !memFile.exists()) {
            throw new IOException("Missing input files for day " + day + " under " + inputBaseDir);
        }

        int dayStartSeconds = day * SECONDS_PER_DAY;
        int dayEndSeconds = dayStartSeconds + SECONDS_PER_DAY;
        try (BufferedReader reqReader = new BufferedReader(new FileReader(reqFile));
             BufferedReader delayReader = new BufferedReader(new FileReader(delayFile));
             BufferedReader memReader = new BufferedReader(new FileReader(memFile))) {

            String reqHeader = reqReader.readLine();
            String delayHeader = delayReader.readLine();
            String memHeader = memReader.readLine();

            if (reqHeader == null || delayHeader == null || memHeader == null) {
                throw new IOException("Empty header in one of the files for day " + day);
            }

            /* Huawei minute CSVs use two leading metadata columns before the per-function columns. */
            int functionCount = reqHeader.split(SOURCE_DELIMITER, -1).length - 2;
            /* Delay and memory files must expose the same function columns in the same order. */
            int delayFunctionCount = delayHeader.split(SOURCE_DELIMITER, -1).length - 2;
            int memFunctionCount = memHeader.split(SOURCE_DELIMITER, -1).length - 2;
            if (functionCount != delayFunctionCount || functionCount != memFunctionCount) {
                throw new IOException("Header mismatch for day " + day + ": different function counts.");
            }

            String reqLine;
            String delayLine;
            String memLine;
            while ((reqLine = reqReader.readLine()) != null &&
                   (delayLine = delayReader.readLine()) != null &&
                   (memLine = memReader.readLine()) != null) {

                String[] reqRow = reqLine.split(SOURCE_DELIMITER, -1);
                String[] delayRow = delayLine.split(SOURCE_DELIMITER, -1);
                String[] memRow = memLine.split(SOURCE_DELIMITER, -1);

                /* The dataset timestamps are absolute seconds; convert them to a minute within this day. */
                int timeSeconds = Integer.parseInt(reqRow[1]);
                if (timeSeconds < dayStartSeconds || timeSeconds >= dayEndSeconds) {
                    continue;
                }
                int minuteIndex = (timeSeconds - dayStartSeconds) / SECONDS_PER_MINUTE;
                if (minuteIndex < firstMinute || minuteIndex > lastMinute) {
                    continue;
                }

                for (int functionId = 0; functionId < functionCount; functionId++) {
                    /* Column index 2 maps functionId 0 because columns 0 and 1 are metadata. */
                    String countStr = reqRow[2 + functionId];
                    if (countStr == null || countStr.isEmpty()) {
                        continue;
                    }
                    int count = (int) Double.parseDouble(countStr);
                    if (count <= 0) {
                        continue;
                    }

                    String delayStr = delayRow[2 + functionId];
                    String memStr = memRow[2 + functionId];
                    if (delayStr == null || delayStr.isEmpty() || memStr == null || memStr.isEmpty()) {
                        continue;
                    }

                    int durationMs = (int) Math.round(Double.parseDouble(delayStr));
                    int memoryMb = (int) Math.round(Double.parseDouble(memStr));

                    String owner = "owner_" + functionId;
                    String function = "f_" + functionId;

                    /* Expand each per-minute aggregate count into individual invocations spread across the minute. */
                    for (int i = 0; i < count; i++) {
                        int randomOffsetMs = ThreadLocalRandom.current().nextInt(SECONDS_PER_MINUTE * 1000);
                        long timestampMs = (long) timeSeconds * 1000L + randomOffsetMs;
                        invocations.add(new Invocation(owner, function, memoryMb, durationMs, (int) timestampMs));
                    }
                }
            }
        }
    }
}
