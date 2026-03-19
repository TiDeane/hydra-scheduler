package org.graalvm.argo.dataset.utility.calculator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Prioritizes optimizing the functions with the highest execution duration */
public class LongestRunningUtilityCalculator extends UtilityCalculator {

  public LongestRunningUtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
    super(inputFilePath, useAOT, useSnapshotting);
  }
  
  @Override
  public void calculateUtilityScores(int currentTimestamp) {
    int toOptimizeCount = Configuration.OPTIMIZATION_AMOUNT;
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= Configuration.MAX_OPTIMIZED) {
      super.calculateUtilityScores(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + Configuration.OPTIMIZATION_AMOUNT >= Configuration.MAX_OPTIMIZED) {
      toOptimizeCount = Configuration.MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    for (FunctionUtilityInfo info : functions.values()) {
      float coldStartRate = (float) info.totalColdStarts / info.totalInvocations;
      float invocationRate = (float) info.totalInvocations / (currentTimestamp - startTimestamp) * 1000;
      info.utility = coldStartRate * invocationRate * info.duration; // achieves the lowest total footprint and total duration
    }

    List<String> toOptimize = functions.entrySet().stream()
            .sorted((e1, e2) -> Float.compare(e2.getValue().utility, e1.getValue().utility))
            .limit(toOptimizeCount)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    
    this.optimizationQueue.addAll(toOptimize);

    super.calculateUtilityScores(currentTimestamp);
  }
}