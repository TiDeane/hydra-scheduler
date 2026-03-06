package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Extends on the naive utility calculator to incorporate additional function characteristics */
// TODO: for now, only considers memory footprint
public class ExtendedUtilityCalculator extends UtilityCalculator {

  public ExtendedUtilityCalculator(boolean useAOT, boolean useSnapshotting) {
    super(useAOT, useSnapshotting);
  }
  
  /* Currently, only divides the expected number of cold starts by the memory footprint */
  /* This makes sense for snapshotting, but not for AOT. For AOT, we should divide by code size */
  /* Dividing by code size can only be done once we map functions to benchmarks */

  // TODO: later, calculate differently for AOT and snapshotting

  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    int toOptimizeCount = Configuration.OPTIMIZATION_AMOUNT;
    if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() >= Configuration.MAX_OPTIMIZED) {
      super.calculateUtilityAndOptimize(currentTimestamp);
      return;
    } else if (optimizedFunctionsAOT.size() + optimizedFunctionsSnapshot.size() + Configuration.OPTIMIZATION_AMOUNT >= Configuration.MAX_OPTIMIZED) {
      toOptimizeCount = Configuration.MAX_OPTIMIZED - optimizedFunctionsAOT.size() - optimizedFunctionsSnapshot.size();
    }

    for (FunctionUtilityInfo functionUtilityInfo : functions.values()) {
      int memory = functionUtilityInfo.memory;
      float coldStartRate = (float) functionUtilityInfo.totalColdStarts / functionUtilityInfo.totalInvocations;
      float invocationRate = (float) functionUtilityInfo.totalInvocations / (currentTimestamp - startTimestamp) * 1000;
      functionUtilityInfo.utility = (coldStartRate * invocationRate) / memory;
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