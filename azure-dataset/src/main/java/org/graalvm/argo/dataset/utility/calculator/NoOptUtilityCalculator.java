package org.graalvm.argo.dataset.utility.calculator;

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