package org.graalvm.argo.dataset.utility.calculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/* Doesn't optimize any functions */
public class NoOptUtilityCalculator extends UtilityCalculator {

  public NoOptUtilityCalculator(String inputFilePath, boolean useAOT, boolean useSnapshotting) {
    super(inputFilePath, useAOT, useSnapshotting);
  }
  
  @Override
  public void calculateUtilityScores(int currentTimestamp) {
    super.calculateUtilityScores(currentTimestamp);
  }
}