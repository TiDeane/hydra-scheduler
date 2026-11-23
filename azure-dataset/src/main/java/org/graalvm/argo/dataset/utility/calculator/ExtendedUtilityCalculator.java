package org.graalvm.argo.dataset.utility.calculator;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.graalvm.argo.dataset.utility.Configuration;

/* Extends on the cold-start utility calculator to incorporate additional function characteristics */
// TODO: for now, only considers memory footprint
public class ExtendedUtilityCalculator extends UtilityCalculator {

  public ExtendedUtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
    super(inputFilePath, useAOT, useSnapshotting);
  }
  
  /* Currently, only divides the expected number of cold starts by the memory footprint */
  /* This makes sense for snapshotting, but not for AOT. For AOT, we should divide by code size */
  /* Dividing by code size can only be done once we map functions to benchmarks */

  // TODO: later, calculate differently for AOT and snapshotting

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
      int memory = functionInfo.memory;
      float coldStartRate = (float) functionInfo.totalColdStarts / functionInfo.invocationsBeforeOpt;
      float invocationRate = (float) functionInfo.invocationsBeforeOpt / (currentTimestamp - startTimestamp) * 1000;
      functionInfo.utility = (coldStartRate * invocationRate) / memory;
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