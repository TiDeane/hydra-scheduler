package org.graalvm.argo.dataset.utility.utils;

import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;

public abstract class UtilityCalculator {
  /* how many functions to optimize at a time */
  protected static final int OPTIMIZATION_AMOUNT = 30;
  /* Key - function */
  protected final Map<String, FunctionInfo> functions;
  public final HashSet<String> optimizedFunctions;
  /* Used to calculate averages or rates, when combined with the current timestamp */
  public int startTimestamp;
  /* Used to decide when to optimize functions */
  public int lastOptimization;

  public UtilityCalculator() {
    this.functions = new HashMap<>();
    this.optimizedFunctions = new HashSet<>();
    this.lastOptimization = 0;
  }

  public boolean optimized(String function) {
    return optimizedFunctions.contains(function);
  }

  public void registerInvocation(String function, int memory, int duration) {
    if (optimizedFunctions.contains(function)) {
      // We return because this information is stored only to decide which functions to optimize, and the function is already optimized
      return;
    }
    if (!functions.containsKey(function)) {
      functions.put(function, new FunctionInfo(function, memory, duration));
    }
    functions.get(function).totalInvocations++;
  }

  public void registerColdStart(String function) {
    if (optimizedFunctions.contains(function)) {
      // We return because this information is stored only to decide which functions to optimize, and the function is already optimized
      return;
    }
    functions.get(function).totalColdStarts++;
  }

  public void calculateUtilityAndOptimize(int currentTimestamp) {
    this.lastOptimization = currentTimestamp;
  }
}
