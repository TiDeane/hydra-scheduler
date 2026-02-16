package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Prioritizes optimizing the functions with the highest aggregate execution duration */
public class LongestRunningUtilityCalculator extends UtilityCalculator {
  
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    for (FunctionInfo info : functions.values()) {
      info.utility = info.duration * info.totalInvocations;
    }

    List<String> toOptimize = functions.entrySet().stream()
            .sorted((e1, e2) -> Float.compare(e2.getValue().duration, e1.getValue().duration))
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