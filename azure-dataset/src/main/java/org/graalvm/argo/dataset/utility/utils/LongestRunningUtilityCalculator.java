package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Prioritizes optimizing the functions with the highest aggregate execution duration */
public class LongestRunningUtilityCalculator extends UtilityCalculator {

  public LongestRunningUtilityCalculator(boolean useAOT, boolean useSnapshotting) {
    super(useAOT, useSnapshotting);
  }
  
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    int toOptimizeCount = OPTIMIZATION_AMOUNT; // new variable
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= MAX_OPTIMIZED) {
      super.calculateUtilityAndOptimize(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + OPTIMIZATION_AMOUNT >= MAX_OPTIMIZED) {
      toOptimizeCount = MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    for (FunctionInfo info : functions.values()) {
      info.utility = info.duration * info.totalInvocations;
    }

    List<String> toOptimize = functions.entrySet().stream()
            .sorted((e1, e2) -> Float.compare(e2.getValue().duration, e1.getValue().duration))
            .limit(toOptimizeCount)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

    if (USE_AOT && USE_SNAPSHOT) {
      int midpoint = toOptimize.size() / 2;
      List<String> aotPart = toOptimize.subList(0, midpoint);
      List<String> snapshotPart = toOptimize.subList(midpoint, toOptimize.size());
      aotPart.forEach(this::applyAOT);
      snapshotPart.forEach(this::applySnapshot);
    } else if (USE_AOT) {
      toOptimize.forEach(this::applyAOT);
    } else if (USE_SNAPSHOT) {
      toOptimize.forEach(this::applySnapshot);
    }

    super.calculateUtilityAndOptimize(currentTimestamp);
  }
}