
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

  protected final float SNAPSHOT_CREATION_OVERHEAD = (float) 496.45;
  protected final float AOT_COMPILATION_LATENCY = 66075;
  // note: this is peak RSS, not average RSS
  protected final float AOT_COMPILATION_FOOTPRINT = (float) 2525.625; // currently unused

  protected final boolean USE_AOT;
  protected final boolean USE_SNAPSHOT;

  /* Key - function */
  protected final Map<String, FunctionUtilityInfo> functions;
  
  public final HashSet<String> optimizedFunctionsAOT;
  public final HashSet<String> optimizedFunctionsSnapshot;

  public final HashSet<String> optimizationQueue;

  private ForecastProvider forecastProvider;

  /* Used to calculate averages or rates, when combined with the current timestamp */
  public int startTimestamp;

  /* Used to decide when to mark functions for optimization */
  public int lastUtilityCalculation;
  /* Used to decide when to start a new optimization round for marked functions */
  public int lastOptimizationRound;
  /* Total seconds used optimizing functions (creating snapshots or AOT-compiling) */
  public int totalOptimizationCost;

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
    this.optimizedFunctionsAOT = new HashSet<>();
    this.optimizedFunctionsSnapshot = new HashSet<>();
    this.optimizationQueue = new HashSet<>();

    this.lastUtilityCalculation = 0;
    this.lastOptimizationRound = 0;
    this.totalOptimizationCost = 0;
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
    if (optimizedFunctionsAOT.contains(function) || optimizedFunctionsSnapshot.contains(function)) {
      // We return because this information is stored only to decide which functions to optimize, and the function is already optimized
      return;
    }
    if (!functions.containsKey(function)) {
      functions.put(function, new FunctionUtilityInfo(function, memory, duration));
    }
    functions.get(function).totalInvocations++;
  }

  public void registerColdStart(String function) {
    if (optimizedFunctionsAOT.contains(function) || optimizedFunctionsSnapshot.contains(function)) {
      // We return because this information is stored only to decide which functions to optimize, and the function is already optimized
      return;
    }
    functions.get(function).totalColdStarts++;
  }

  protected void applyAOT(String function) {
    optimizedFunctionsAOT.add(function);
    functions.remove(function);
    this.totalOptimizationCost += AOT_COMPILATION_LATENCY;
  }

  protected void applySnapshot(String function) {
    optimizedFunctionsSnapshot.add(function);
    functions.remove(function);
    this.totalOptimizationCost += SNAPSHOT_CREATION_OVERHEAD;
  }

  protected void optimize(String function) {
    boolean nextIsAOT = true;

    if (USE_AOT && USE_SNAPSHOT) {
      // apply AOT and snapshotting alternating
      if (nextIsAOT) applyAOT(function);
      else applySnapshot(function);
      nextIsAOT = !nextIsAOT;
    } else if (USE_AOT) {
      applyAOT(function);
    } else if (USE_SNAPSHOT) {
      applySnapshot(function);
    }
  }

  /* This method is overriden by every subclass based on their utility calculation strategy */
  public void calculateUtilityScores(int currentTimestamp) {
    this.lastUtilityCalculation = currentTimestamp;
    // Reset so the first 10-min round can trigger immediately
    this.lastOptimizationRound = currentTimestamp - Configuration.OPTIMIZATION_INTERVAL;
  }

  public void runOptimizationRound(int currentTimestamp) {
    try {
      PriorityQueue<ForecastEntry> hotFunctions = null;
      if (forecastProvider != null) {
        hotFunctions = forecastProvider.getRelevantForecast(currentTimestamp, optimizationQueue);
      }

      int budgetRemaining = Configuration.OPTIMIZATION_BUDGET;

      // Optimize functions with highest expected imminent invocations
      if (hotFunctions != null) {
        while (!hotFunctions.isEmpty() && budgetRemaining > 0) {
          ForecastEntry entry = hotFunctions.poll();
          optimize(entry.function());
          optimizationQueue.remove(entry.function());
          budgetRemaining--;
        }
      }

      // Optimize remaining candidates randomly
      if (budgetRemaining > 0 && !optimizationQueue.isEmpty()) {
        Iterator<String> iter = optimizationQueue.iterator();
        while (iter.hasNext() && budgetRemaining > 0) {
          String function = iter.next();
          optimize(function);
          iter.remove();
          budgetRemaining--;
        }
      }

      this.lastOptimizationRound = currentTimestamp;
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }
}
