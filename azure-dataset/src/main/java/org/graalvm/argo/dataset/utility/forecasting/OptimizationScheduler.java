package org.graalvm.argo.dataset.utility.forecasting;

import org.graalvm.argo.dataset.utility.Configuration;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/*
 * This program processes an invocation trace and ranks functions by 
 * expected imminent invocations to determine optimization scheduling
*/
public class OptimizationScheduler {

  private static final int HORIZON = 10;
  private static final int WINDOW_MINUTES = 60;
  private static final int WINDOW_MS = WINDOW_MINUTES * 60000;

  private static final int READ_BUFFER_SIZE = 256 * 1024; // 256KB
  private static final int WRITE_BUFFER_SIZE = 64 * 1024;  // 64KB

  private static final String PREDICTIONS_SUFFIX = "_predictions.csv";

  record TimeSeriesInvocation(String functionId, int timestamp) {}

  public static void main(String[] args) throws Exception {
    Options options = prepareOptions();
    try {
      CommandLine cmd = new DefaultParser().parse(options, args);
      String inputfile = cmd.getOptionValue("input");
      String outputFile = cmd.getOptionValue("outputFile", inputfile.replace(".csv", PREDICTIONS_SUFFIX));

      long start = System.currentTimeMillis();
      processTrace(inputfile, outputFile);
      long end = System.currentTimeMillis();

      System.out.println("Creating optimization schedule took " + (end - start) + " milliseconds");
      System.out.println("Saving output to " + outputFile);
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
    Option output = new Option("o", "output", true, "Output expected invocations file path.");
    output.setRequired(false);
    options.addOption(output);
    return options;
  }

  private static void processTrace(String inputFilePath, String outputFilePath) {
    File tempFile = null;
    int lastUtilityCalculation = 0;
    int nextForecastTime = 0;
    int roundsToGenerate = 0;
    long totalForecastingTime = 0;

    try {
      tempFile = File.createTempFile("trace_window_", ".tmp");
      tempFile.deleteOnExit();

      try (BufferedReader br = new BufferedReader(new FileReader(inputFilePath));
          BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath));
          FileChannel tempChannel = FileChannel.open(tempFile.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {

        long windowStartOffset = 0;
        long writeOffset = 0;   // logical end of all data (flushed + buffered)
        long flushedOffset = 0; // how many bytes have been flushed to tempChannel
        byte[] writeBuf = new byte[WRITE_BUFFER_SIZE];
        int writeBufLen = 0;

        String line;
        br.readLine(); // Skip header
        while ((line = br.readLine()) != null) {
          TimeSeriesInvocation currentInv = parseLine(line);
          int currentTimestamp = currentInv.timestamp();

          String entryStr = currentInv.functionId() + "," + currentTimestamp + "\n";
          byte[] entry = entryStr.getBytes(StandardCharsets.UTF_8);

          // Buffer write, flush to channel only when full
          if (writeBufLen + entry.length > writeBuf.length) {
            tempChannel.write(ByteBuffer.wrap(writeBuf, 0, writeBufLen), flushedOffset);
            flushedOffset += writeBufLen;
            writeBufLen = 0;
          }
          System.arraycopy(entry, 0, writeBuf, writeBufLen, entry.length);
          writeBufLen += entry.length;
          writeOffset += entry.length;

          if (currentTimestamp - lastUtilityCalculation >= Configuration.UTILITY_CALCULATION_INTERVAL) {
            lastUtilityCalculation = (currentTimestamp / Configuration.UTILITY_CALCULATION_INTERVAL) * Configuration.UTILITY_CALCULATION_INTERVAL;
            nextForecastTime = lastUtilityCalculation;
            roundsToGenerate = (int) Math.ceil((double) Configuration.OPTIMIZATION_AMOUNT / Configuration.OPTIMIZATION_BUDGET);
          }

          if (roundsToGenerate > 0 && currentTimestamp >= nextForecastTime) {
            // Flush pending writes before reading so reads see all data up to writeOffset
            if (writeBufLen > 0) {
              tempChannel.write(ByteBuffer.wrap(writeBuf, 0, writeBufLen), flushedOffset);
              flushedOffset += writeBufLen;
              writeBufLen = 0;
            }

            int windowStartThreshold = Math.max(0, nextForecastTime - WINDOW_MS);
            windowStartOffset = advanceWindowStart(tempChannel, windowStartOffset, writeOffset, windowStartThreshold);

            Map<String, int[]> functionTimeSeries = buildTimeSeries(tempChannel, windowStartOffset, writeOffset, nextForecastTime);

            // Every "timestamp,nextForecastTime" line marks the start of a forecasting round
            bw.write("timestamp," + nextForecastTime);
            bw.newLine();

            long start = System.currentTimeMillis();
            // Perform forecasting in parallel for all functions
            Map<String, Integer> results = functionTimeSeries.entrySet().parallelStream()
                .map(e -> {
                  double[] predictions = Predictor.fourierExtrapolationPredict(e.getValue(), HORIZON);
                  int totalExpected = 0;
                  for (double p : predictions) totalExpected += Math.max(0, Math.round(p));
                  return Map.entry(e.getKey(), totalExpected);
                })
                .filter(e -> e.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            totalForecastingTime += System.currentTimeMillis() - start;

            for (Map.Entry<String, Integer> e : results.entrySet()) {
              bw.write(e.getKey() + "," + e.getValue());
              bw.newLine();
            }

            nextForecastTime += Configuration.OPTIMIZATION_INTERVAL;
            roundsToGenerate--;
          }
        }
      }
      System.out.println("Total time making forecasts: " + totalForecastingTime + " milliseconds");
    } catch (Exception e) {
      e.printStackTrace();
      new File(outputFilePath).delete();
      System.exit(1);
    } finally {
      if (tempFile != null) tempFile.delete();
    }
  }

  /*
   * Reads lines in bulk via ByteBuffer, advances windowStartOffset past entries older than threshold
  */
  private static long advanceWindowStart(FileChannel ch, long startOffset, long endOffset, int threshold) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER_SIZE);
    long pos = startOffset;
    long newStart = startOffset;

    outer:
    while (pos < endOffset) {
      buf.clear();
      if (endOffset - pos < buf.capacity()) buf.limit((int) (endOffset - pos));
      int n = ch.read(buf, pos);
      if (n <= 0) break;

      byte[] data = buf.array();
      int lineStart = 0;

      for (int i = 0; i < n; i++) {
        if (data[i] == '\n') {
          int commaIdx = -1;
          for (int j = lineStart; j < i; j++) {
            if (data[j] == ',') { commaIdx = j; break; }
          }
          int ts = parseIntFromBytes(data, commaIdx + 1, i);
          if (ts >= threshold) break outer; // this entry is still in window; stop
          newStart = pos + i + 1; // entry is expired; advance past it
          lineStart = i + 1;
        }
      }
      pos += lineStart;
    }
    return newStart;
  }

  /*
   * Reads lines in bulk via ByteBuffer
  */
  private static Map<String, int[]> buildTimeSeries(FileChannel ch, long startOffset, long endOffset, int forecastTime) throws IOException {
    Map<String, int[]> timeSeriesMap = new HashMap<>();
    int windowSize = (int) Math.min(WINDOW_MINUTES, forecastTime / 60000);
    ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER_SIZE);
    long pos = startOffset;

    while (pos < endOffset) {
      buf.clear();
      if (endOffset - pos < buf.capacity()) buf.limit((int) (endOffset - pos));
      int n = ch.read(buf, pos);
      if (n <= 0) break;

      byte[] data = buf.array();
      int lineStart = 0;

      for (int i = 0; i < n; i++) {
        if (data[i] == '\n') {
          int commaIdx = -1;
          for (int j = lineStart; j < i; j++) {
            if (data[j] == ',') { commaIdx = j; break; }
          }
          String functionId = new String(data, lineStart, commaIdx - lineStart, StandardCharsets.UTF_8);
          int timestamp = parseIntFromBytes(data, commaIdx + 1, i);

          int minutesAgo = (forecastTime - timestamp) / 60000;
          int minuteIndex = (windowSize - 1) - minutesAgo;
          if (minuteIndex >= 0 && minuteIndex < windowSize) {
            timeSeriesMap.computeIfAbsent(functionId, k -> new int[windowSize])[minuteIndex]++;
          }

          lineStart = i + 1;
        }
      }
      pos += lineStart;
    }
    return timeSeriesMap;
  }

  private static int parseIntFromBytes(byte[] data, int start, int end) {
    int result = 0;
    for (int i = start; i < end; i++) {
      result = result * 10 + (data[i] - '0');
    }
    return result;
  }

  /* HashOwner, HashFunction, AverageAllocatedMb, AverageDuration, Timestamp */
  private static TimeSeriesInvocation parseLine(String line) {
    String[] splitRow = line.split(",");
    return new TimeSeriesInvocation(splitRow[1], Integer.parseInt(splitRow[4]));
  }
}
