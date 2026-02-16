package org.graalvm.argo.dataset.utility.utils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Extends on the naive utility calculator to incorporate additional function characteristics */
// TODO: for now, only considers memory footprint
public class ExtendedUtilityCalculator extends UtilityCalculator {
  
  /* Currently, only divides the expected number of cold starts by the memory footprint */
  /* This makes sense for snapshotting, but not for AOT. For AOT, we should divide by code size */
  /* Dividing by code size can only be done once we map functions to benchmarks */

  // TODO: later, calculate differently for AOT and snapshotting

  @Override
  public void calculateUtilityAndOptimize(int currentTimestamp) {
    for (FunctionInfo functionInfo : functions.values()) {
      int memory = functionInfo.memory;
      float coldStartRate = (float) functionInfo.totalColdStarts / functionInfo.totalInvocations;
      float invocationRate = (float) functionInfo.totalInvocations / (currentTimestamp - startTimestamp) * 1000;
      functionInfo.utility = (coldStartRate * invocationRate) / memory;
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