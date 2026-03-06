package org.graalvm.argo.dataset.utility.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Randomly selects functions to optimize */
public class RandomUtilityCalculator extends UtilityCalculator {

  public RandomUtilityCalculator(boolean useAOT, boolean useSnapshotting) {
    super(useAOT, useSnapshotting);
  }
  
  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    int toOptimizeCount = Configuration.OPTIMIZATION_AMOUNT;
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= Configuration.MAX_OPTIMIZED) {
      super.calculateUtilityAndOptimize(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + Configuration.OPTIMIZATION_AMOUNT >= Configuration.MAX_OPTIMIZED) {
      toOptimizeCount = Configuration.MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    List<String> candidates = new ArrayList<>(functions.keySet());
    Collections.shuffle(candidates);

    List<String> toOptimize = candidates.stream()
            .limit(toOptimizeCount)
            .collect(Collectors.toList());
    
    if (USE_AOT && USE_SNAPSHOT) {
      // half of the functions are AOT-optimized, the other half are snapshot-optimized
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