
package org.graalvm.argo.dataset.utility.calculator;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.graalvm.argo.dataset.utility.Configuration;

public abstract class UtilityCalculator {

  // TODO: include optimization "budget" (i.e., how many functions can be optimized at a time)
  // TODO: this way, we can use Fourier short-term to decide which functions to prioritize

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

  /* Used to calculate averages or rates, when combined with the current timestamp */
  public int startTimestamp;
  /* Used to decide when to optimize functions */
  public int lastOptimization;
  /* Total seconds used optimizing functions (creating snapshots or AOT-compiling) */
  public int totalOptimizationCost;

  public UtilityCalculator(boolean useAOT, boolean useSnapshotting) {
    this.USE_AOT = useAOT;
    this.USE_SNAPSHOT = useSnapshotting;
    this.functions = new HashMap<>();
    this.optimizedFunctionsAOT = new HashSet<>();
    this.optimizedFunctionsSnapshot = new HashSet<>();
    this.lastOptimization = 0;
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

  public void calculateUtilityAndOptimize(int currentTimestamp) {
    System.err.println("Total optimized: " + (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size()) + " functions");
    this.totalOptimizationCost += getOptimizationCost();
    this.lastOptimization = currentTimestamp;
  }

  protected void applyAOT(String functionName) {
    optimizedFunctionsAOT.add(functionName);
    functions.remove(functionName);
  }

  protected void applySnapshot(String functionName) {
    optimizedFunctionsSnapshot.add(functionName);
    functions.remove(functionName);
  }

  public float getOptimizationCost() {
    if (USE_AOT && USE_SNAPSHOT) {
      return Configuration.OPTIMIZATION_AMOUNT / 2 * (SNAPSHOT_CREATION_OVERHEAD + AOT_COMPILATION_LATENCY);
    } else if (USE_AOT) {
      return Configuration.OPTIMIZATION_AMOUNT * AOT_COMPILATION_LATENCY;
    } else if (USE_SNAPSHOT) {
      return Configuration.OPTIMIZATION_AMOUNT * SNAPSHOT_CREATION_OVERHEAD;
    }
    return 0;
  }
}
