
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
  
  // TODO: this is also present in UtilityInvocation, maybe store it in Configuration?
  private static final double OPTIMIZED_AOT_FOOTPRINT_RATIO = 0.235;
  private static final double OPTIMIZED_AOT_DURATION_RATIO = 0.773;
  private static final double OPTIMIZED_SNAPSHOT_FOOTPRINT_RATIO = 0.284;
  private static final double SNAPSHOT_RESTORE_PENALTY = 33.2;

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

  public void registerInvocation(String function, int memory, int p50duration, int p99duration) {
    if (!functions.containsKey(function)) {
      // First invocation
      FunctionUtilityInfo functionInfo = new FunctionUtilityInfo(function, memory, p50duration, p99duration);
      unoptimizedFunctions.add(function);
      functions.put(function, functionInfo);
    }

    if (functions.get(function).isOptimized) {
      functions.get(function).invocationsAfterOpt++;
    } else {
      functions.get(function).invocationsBeforeOpt++;
    }
  }

  public void registerColdStart(String function) {
    functions.get(function).totalColdStarts++;
  }

  public void registerSlaViolations(String function) {
    if (functions.get(function).isOptimized) {
      functions.get(function).slaViolationsAfterOpt++;
    } else {
      functions.get(function).slaViolationsBeforeOpt++;
    }
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
    functions.get(function).isOptimized = true;
    if (USE_AOT && USE_SNAPSHOT) {
      String optimization = getBestOptimization(function);
      if (optimization.equals("AOT")) {
        return applyAOT(function);
      } else {
        return applySnapshot(function);
      }
    } else if (USE_AOT) {
      return applyAOT(function);
    } else if (USE_SNAPSHOT) {
      return applySnapshot(function);
    } else {
      return 0;
    }
  }

  protected String getBestOptimization(String function) {
    FunctionUtilityInfo functionInfo = functions.get(function);
    int p50duration = functionInfo.p50duration;
    int p99duration = functionInfo.p99duration;
    int memory = functionInfo.memory;

    int threshold = Math.max(p50duration * 4, 4);
    float dur_snapshot = (float) (p50duration + SNAPSHOT_RESTORE_PENALTY);
    float dur_aot = (float) (p99duration * OPTIMIZED_AOT_DURATION_RATIO);

    boolean snapshot_cures = dur_snapshot <= threshold;
    boolean aot_cures = dur_aot <= threshold;

    // prioritize "curing" SLA violation
    if (snapshot_cures && !aot_cures) return "SNAPSHOT";
    if (aot_cures && !snapshot_cures) return "AOT";

    // tie-breaker: choose the one with the lower footprint
    float footprint_snapshot = (float) (dur_snapshot * (memory * OPTIMIZED_SNAPSHOT_FOOTPRINT_RATIO));
    float footprint_aot = (float) (dur_aot * (memory * OPTIMIZED_AOT_FOOTPRINT_RATIO));
    
    if (footprint_aot < footprint_snapshot) return "AOT";
    else return "SNAPSHOT";
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
