package org.graalvm.argo.dataset.utility.calculator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Prioritizes optimizing the functions with the highest expected number of cold starts */
public class ColdStartUtilityCalculator extends UtilityCalculator {

  public ColdStartUtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
    super(inputFilePath, useAOT, useSnapshotting);
  }
  
  /* Note: this is very inefficient and can probably be optimized */
  @Override
  public void calculateUtilityScores(int currentTimestamp) {
    int toOptimizeCount = Configuration.OPTIMIZATION_AMOUNT;
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= Configuration.MAX_OPTIMIZED) {
      super.calculateUtilityScores(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + Configuration.OPTIMIZATION_AMOUNT >= Configuration.MAX_OPTIMIZED) {
      toOptimizeCount = Configuration.MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    for (String function : unoptimizedFunctions) {
      FunctionUtilityInfo functionInfo = functions.get(function);
      float coldStartRate = (float) functionInfo.totalColdStarts / functionInfo.invocationsBeforeOpt;
      float invocationRate = (float) functionInfo.invocationsBeforeOpt / (currentTimestamp - startTimestamp) * 1000;
      functionInfo.utility = coldStartRate * invocationRate;
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