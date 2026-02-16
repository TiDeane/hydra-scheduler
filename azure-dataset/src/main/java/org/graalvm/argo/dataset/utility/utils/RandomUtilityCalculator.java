package org.graalvm.argo.dataset.utility.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/* Randomly selects functions to optimize */
public class RandomUtilityCalculator extends UtilityCalculator {
  
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    List<String> candidates = new ArrayList<>(functions.keySet());
    Collections.shuffle(candidates);

    List<String> toOptimize = candidates.stream()
            .limit(OPTIMIZATION_AMOUNT)
            .collect(Collectors.toList());
    
    for (String functionName : toOptimize) {
      optimizedFunctions.add(functionName);
      functions.remove(functionName);
    }

    super.calculateUtilityAndOptimize(currentTimestamp);
  }
}