package org.graalvm.argo.dataset.utility.utils;

public class FunctionInfo {
  public String name;
  public int totalInvocations;
  public int totalColdStarts;
  public int memory;
  public int duration;
  public float utility;

  public FunctionInfo(String name, int memory, int duration) {
    this.name = name;
    this.memory = memory;
    this.duration = duration;
    this.totalInvocations = 0;
    this.totalColdStarts = 0;
    this.utility = 0;
  }
}
