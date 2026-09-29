package org.graalvm.argo.dataset.utility;

public class Configuration {

  /* Maximum number of functions to optimize */
  public static final int MAX_OPTIMIZED = 50000; // uncapped in this configuration
  /* How many functions we mark for optimization at each utility calculation interval */
  public static final int OPTIMIZATION_AMOUNT = 25; // match with budget 
  /* How many functions can be optimized at a time */
  public static final int OPTIMIZATION_BUDGET = 25;
  
  /* Number of milliseconds between each round of utility calculation and marking for optimization */
  public static final int UTILITY_CALCULATION_INTERVAL = 600000; // 10 minutes
  /* Number of milliseconds between each round of applying optimizations */
  public static final int OPTIMIZATION_INTERVAL = 600000; // 10 minutes
  /* When to start optimizing functions */
  public static final int OPTIMIZATION_START_TIMESTAMP = 3600000; // 60 minutes
}
