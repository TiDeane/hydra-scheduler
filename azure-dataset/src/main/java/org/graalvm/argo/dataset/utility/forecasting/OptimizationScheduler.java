package org.graalvm.argo.dataset.utility.forecasting;

import org.graalvm.argo.dataset.utility.forecasting.Predictor;
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
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * This program processes an invocation trace and ranks functions by 
 * expected imminent invocations to determine optimization scheduling
*/
public class OptimizationScheduler {

  private static final int HORIZON = 10;
  private static final int WINDOW_MINUTES = 60;
  private static final int WINDOW_MS = WINDOW_MINUTES * 60000;

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

      System.out.println("Creating time series and making predictions took " + (end - start) + " milliseconds");
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

  // TODO: store windowBuffer on temporary file to avoid out of memory errors
  private static void processTrace(String inputFilePath, String outputFilePath) {
    Deque<TimeSeriesInvocation> windowBuffer = new ArrayDeque<>();
    int lastUtilityCalculation = 0;
    int nextForecastTime = 0;
    int roundsToGenerate = 0;

    try (BufferedReader br = new BufferedReader(new FileReader(inputFilePath));
         BufferedWriter bw = new BufferedWriter(new FileWriter(outputFilePath))) {
      String line;
      br.readLine(); // Skip header

      while ((line = br.readLine()) != null) {
        TimeSeriesInvocation currentInv = parseLine(line);
        int currentTimestamp = currentInv.timestamp();
        windowBuffer.add(currentInv);

        if (currentTimestamp - lastUtilityCalculation >= Configuration.UTILITY_CALCULATION_INTERVAL) {
          lastUtilityCalculation = (currentTimestamp / Configuration.UTILITY_CALCULATION_INTERVAL) * Configuration.UTILITY_CALCULATION_INTERVAL;
          nextForecastTime = lastUtilityCalculation;
          // Calculate how many rounds we need based on our budget, and how many functions we want to optimize
          roundsToGenerate = (int) Math.ceil((double) Configuration.OPTIMIZATION_AMOUNT / Configuration.OPTIMIZATION_BUDGET);
        }

        if (roundsToGenerate > 0 && currentTimestamp >= nextForecastTime) {
          // window starts 60 minutes before optimization time
          int windowStartThreshold = Math.max(0, nextForecastTime - WINDOW_MS);
          while (!windowBuffer.isEmpty() && windowBuffer.peek().timestamp() < windowStartThreshold) {
            windowBuffer.poll();
          }

          // functionId -> timeSeriesArray
          Map<String, int[]> functionTimeSeries = buildTimeSeries(windowBuffer, nextForecastTime);

          // every "timestamp,currentTime" line marks the start of a forecasting/utility calculation round
          bw.write("timestamp," + nextForecastTime);
          bw.newLine();

          for (Map.Entry<String, int[]> entry : functionTimeSeries.entrySet()) {
            String function = entry.getKey();
            int[] history = entry.getValue();

            double[] predictions = Predictor.fourierExtrapolationPredict(history, HORIZON);
            
            int totalExpected = 0;
            for (double p : predictions) {
              totalExpected += Math.max(0, Math.round(p));
            }


            if (totalExpected > 0) {
              bw.write(function + "," + totalExpected);
              bw.newLine();
            }
          }
        
          nextForecastTime += Configuration.OPTIMIZATION_INTERVAL;
          roundsToGenerate--;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("deleting file " +outputFilePath);
      new File(outputFilePath).delete();
      System.exit(1);
    }
  }

  /* Given the invocations in the last 60 minutes, constructs each function's time series */
  private static Map<String, int[]> buildTimeSeries(Deque<TimeSeriesInvocation> invocations, int forecastTime) {
    Map<String, int[]> timeSeriesMap = new HashMap<>();
    int windowSize = (int) Math.min(WINDOW_MINUTES, forecastTime / 60000);

    for (TimeSeriesInvocation invocation : invocations) {
      timeSeriesMap.putIfAbsent(invocation.functionId(), new int[windowSize]);
      
      // Calculate which 1-minute bin this invocation falls into (0 to windowSize - 1)
      int minutesAgo = (forecastTime - invocation.timestamp) / 60000;
      int minuteIndex = (windowSize - 1) - minutesAgo;
      
      if (minuteIndex >= 0 && minuteIndex < windowSize) {
        timeSeriesMap.get(invocation.functionId())[minuteIndex]++;
      }
    }
    return timeSeriesMap;
  }

  /* HashOwner, HashFunction, AverageAllocatedMb, AverageDuration, Timestamp */
  private static TimeSeriesInvocation parseLine(String line) {
    String[] splitRow = line.split(",");
    return new TimeSeriesInvocation(splitRow[1], Integer.parseInt(splitRow[4]));
  }
}
