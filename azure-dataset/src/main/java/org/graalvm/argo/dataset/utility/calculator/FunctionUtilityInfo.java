package org.graalvm.argo.dataset.utility.calculator;

public class FunctionUtilityInfo {
  public String name;
  public int totalInvocations;
  public int totalColdStarts;
  public int totalSlaViolations;
  public int memory;
  public int p50duration;
  public int p99duration;
  public float utility;

  public FunctionUtilityInfo(String name, int memory, int p50duration, int p99duration) {
    this.name = name;
    this.memory = memory;
    this.p50duration = p50duration;
    this.p99duration = p99duration;
    this.totalInvocations = 0;
    this.totalColdStarts = 0;
    this.totalSlaViolations = 0;
    this.utility = 0;
  }
}
