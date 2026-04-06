package org.graalvm.argo.dataset.utility.calculator;

public class FunctionUtilityInfo {
  public String name;
  public int invocationsBeforeOpt;
  public int invocationsAfterOpt;
  public int totalColdStarts;
  public int slaViolationsBeforeOpt;
  public int slaViolationsAfterOpt;
  public int memory;
  public int p50duration;
  public int p99duration;
  public float utility;
  public boolean isOptimized;

  public FunctionUtilityInfo(String name, int memory, int p50duration, int p99duration) {
    this.name = name;
    this.memory = memory;
    this.p50duration = p50duration;
    this.p99duration = p99duration;
    this.invocationsBeforeOpt = 0;
    this.invocationsAfterOpt = 0;
    this.totalColdStarts = 0;
    this.slaViolationsBeforeOpt = 0;
    this.slaViolationsAfterOpt = 0;
    this.utility = 0;
    this.isOptimized = false;
  }
}
