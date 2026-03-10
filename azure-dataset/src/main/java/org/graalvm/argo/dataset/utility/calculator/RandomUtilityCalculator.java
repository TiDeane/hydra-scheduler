package org.graalvm.argo.dataset.utility.calculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Randomly selects functions to optimize */
public class RandomUtilityCalculator extends UtilityCalculator {

  public RandomUtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
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

    List<String> candidates = new ArrayList<>(functions.keySet());
    Collections.shuffle(candidates);

    List<String> toOptimize = candidates.stream()
            .limit(toOptimizeCount)
            .collect(Collectors.toList());
    
    this.optimizationQueue.addAll(toOptimize);

    super.calculateUtilityScores(currentTimestamp);
  }
}