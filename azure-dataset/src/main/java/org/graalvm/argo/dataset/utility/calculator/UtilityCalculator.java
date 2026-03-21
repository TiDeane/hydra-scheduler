
package org.graalvm.argo.dataset.utility.calculator;

import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Iterator;
import java.util.HashMap;
import java.io.IOException;

import org.graalvm.argo.dataset.utility.Configuration;
import org.graalvm.argo.dataset.utility.forecasting.ForecastProvider;
import org.graalvm.argo.dataset.utility.forecasting.ForecastProvider.ForecastEntry;

public abstract class UtilityCalculator {

  protected final float SNAPSHOT_CREATION_OVERHEAD = (float) 496.45; // milliseconds
  protected final float AOT_COMPILATION_LATENCY = 66075; // milliseconds
  protected final float AOT_COMPILATION_FOOTPRINT = (float) 2525.63; // peak RSS (MB), currently unused

  protected final boolean USE_AOT;
  protected final boolean USE_SNAPSHOT;

  /* Key - function */
  public final Map<String, FunctionUtilityInfo> functions;
  
  public final HashSet<String> unoptimizedFunctions;
  public final HashSet<String> optimizedFunctionsAOT;
  public final HashSet<String> optimizedFunctionsSnapshot;

  public final HashSet<String> optimizationQueue; // TODO: this name is misleading

  private ForecastProvider forecastProvider;

  /* Used to calculate averages or rates, when combined with the current timestamp */
  public int startTimestamp;

  /* When both snapshotting and AOT are enabled, this is used to alternate between them when optimizing */
  private boolean nextIsAOT;

  /* Used to decide when to mark functions for optimization */
  public int lastUtilityCalculation;
  /* Used to decide when to start a new optimization round for marked functions */
  public int lastOptimizationRound;

  public UtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
    try {
      int dotIndex = inputFilePath.lastIndexOf('.');
      String forecastsFilePath = inputFilePath.substring(0, dotIndex) + "_predictions" + inputFilePath.substring(dotIndex);
      this.forecastProvider = new ForecastProvider(forecastsFilePath);
    } catch (IOException ioe) {
      // ForecastProvider failed, no prioritization will be made in terms of optimization ordering based on short-term forecasts
      this.forecastProvider = null;
      System.err.println("Forecasts file not found, functions marked for optimization by utility calculation will be optimized in random order");
    }
    this.USE_AOT = useAOT;
    this.USE_SNAPSHOT = useSnapshotting;
    this.functions = new HashMap<>();
    this.unoptimizedFunctions = new HashSet<>();
    this.optimizedFunctionsAOT = new HashSet<>();
    this.optimizedFunctionsSnapshot = new HashSet<>();
    this.optimizationQueue = new HashSet<>();

    this.nextIsAOT = true;
    this.lastUtilityCalculation = 0;
    this.lastOptimizationRound = 0;
  }

  public boolean optimizedAOT(String function) {
    return optimizedFunctionsAOT.contains(function);
  }

  public boolean optimizedSnapshot(String function) {
    return optimizedFunctionsSnapshot.contains(function);
  }

  public boolean optimized(String function) {
    return optimizedAOT(function) || optimizedSnapshot(function);
  }

  public void registerInvocation(String function, int memory, int duration) {
    if (!functions.containsKey(function)) {
      // First invocation
      FunctionUtilityInfo functionInfo = new FunctionUtilityInfo(function, memory, duration);
      unoptimizedFunctions.add(function);
      functions.put(function, functionInfo);
    }
    functions.get(function).totalInvocations++;
  }

  public void registerColdStart(String function) {
    functions.get(function).totalColdStarts++;
  }

  public void registerSlaViolations(String function) {
    functions.get(function).totalSlaViolations++;
  }

  protected float applyAOT(String function) {
    optimizedFunctionsAOT.add(function);
    unoptimizedFunctions.remove(function);
    functions.get(function).utility = -1;
    return AOT_COMPILATION_LATENCY;
  }

  protected float applySnapshot(String function) {
    optimizedFunctionsSnapshot.add(function);
    unoptimizedFunctions.remove(function);
    functions.get(function).utility = -1;
    return SNAPSHOT_CREATION_OVERHEAD;
  }

  /* Optimizes a function, returns the optimization cost */
  protected float optimize(String function) {
    if (USE_AOT && USE_SNAPSHOT) {
      // apply AOT and snapshotting alternating
      nextIsAOT = !nextIsAOT;
      if (nextIsAOT) return applyAOT(function);
      else return applySnapshot(function);
    } else if (USE_AOT) {
      return applyAOT(function);
    } else if (USE_SNAPSHOT) {
      return applySnapshot(function);
    } else {
      return 0;
    }
  }

  /* This method is overriden by every subclass based on their utility calculation strategy */
  public void calculateUtilityScores(int currentTimestamp) {
    this.lastUtilityCalculation = currentTimestamp;
    // Reset so the first 10-min round can trigger immediately
    this.lastOptimizationRound = currentTimestamp - Configuration.OPTIMIZATION_INTERVAL;
  }

  public float runOptimizationRound(int currentTimestamp) {
    try {
      PriorityQueue<ForecastEntry> hotFunctions = null;
      if (forecastProvider != null) {
        hotFunctions = forecastProvider.getRelevantForecast(currentTimestamp, optimizationQueue);
      }

      float optimizationCost = 0;
      int budgetRemaining = Configuration.OPTIMIZATION_BUDGET;

      // Optimize functions with highest expected imminent invocations
      if (hotFunctions != null) {
        while (!hotFunctions.isEmpty() && budgetRemaining > 0 && !optimizationQueue.isEmpty()) {
          ForecastEntry entry = hotFunctions.poll();
          optimizationCost += optimize(entry.function());
          optimizationQueue.remove(entry.function());
          budgetRemaining--;
        }
      }

      // Optimize remaining candidates randomly
      if (budgetRemaining > 0 && !optimizationQueue.isEmpty()) {
        Iterator<String> iter = optimizationQueue.iterator();
        while (iter.hasNext() && budgetRemaining > 0) {
          String function = iter.next();
          optimizationCost += optimize(function);
          iter.remove();
          budgetRemaining--;
        }
      }

      this.lastOptimizationRound = currentTimestamp;
      return optimizationCost / 1000; // convert to seconds
    } catch (IOException ioe) {
      ioe.printStackTrace();
      return 0;
    }
  }
}
