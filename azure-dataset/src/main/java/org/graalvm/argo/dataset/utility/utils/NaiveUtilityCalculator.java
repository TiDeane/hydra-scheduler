package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Prioritizes optimizing the functions with the highest expected number of cold starts */
public class NaiveUtilityCalculator extends UtilityCalculator {
  
  /* Note: this is very inefficient and can probably be optimized */
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    for (FunctionInfo functionInfo : functions.values()) {
      float coldStartRate = (float) functionInfo.totalColdStarts / functionInfo.totalInvocations;
      float invocationRate = (float) functionInfo.totalInvocations / (currentTimestamp - startTimestamp) * 1000;
      functionInfo.utility = coldStartRate * invocationRate;
    }

    List<String> toOptimize = functions.entrySet().stream()
            .sorted((e1, e2) -> Float.compare(e2.getValue().utility, e1.getValue().utility))
            .limit(OPTIMIZATION_AMOUNT)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    
    for (String functionName : toOptimize) {
      optimizedFunctions.add(functionName);
      functions.remove(functionName);
    }

    super.calculateUtilityAndOptimize(currentTimestamp);
  }
}