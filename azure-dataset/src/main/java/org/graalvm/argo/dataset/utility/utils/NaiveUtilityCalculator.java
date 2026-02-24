package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Prioritizes optimizing the functions with the highest expected number of cold starts */
public class NaiveUtilityCalculator extends UtilityCalculator {

  public NaiveUtilityCalculator(boolean useAOT, boolean useSnapshotting) {
    super(useAOT, useSnapshotting);
  }
  
  /* Note: this is very inefficient and can probably be optimized */
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    int toOptimizeCount = OPTIMIZATION_AMOUNT; // new variable
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= MAX_OPTIMIZED) {
      super.calculateUtilityAndOptimize(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + OPTIMIZATION_AMOUNT >= MAX_OPTIMIZED) {
      toOptimizeCount = MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    for (FunctionInfo functionInfo : functions.values()) {
      float coldStartRate = (float) functionInfo.totalColdStarts / functionInfo.totalInvocations;
      float invocationRate = (float) functionInfo.totalInvocations / (currentTimestamp - startTimestamp) * 1000;
      functionInfo.utility = coldStartRate * invocationRate;
    }

    List<String> toOptimize = functions.entrySet().stream()
            .sorted((e1, e2) -> Float.compare(e2.getValue().utility, e1.getValue().utility))
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