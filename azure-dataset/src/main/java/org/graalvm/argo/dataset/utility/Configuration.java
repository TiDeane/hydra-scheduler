package org.graalvm.argo.dataset.utility;

public class Configuration {

  /* Maximum number of functions to optimize */
  public static final int MAX_OPTIMIZED = 4000;
  
  /* Number of milliseconds between each round of utility calculation and marking for optimization */
  public static final int UTILITY_CALCULATION_INTERVAL = 3600000; // 60 minutes
  /* How many functions we mark for optimization at each utility calculation interval */
  public static final int OPTIMIZATION_AMOUNT = 1000;
  /* How many functions can be optimized at a time */
  public static final int OPTIMIZATION_BUDGET = 500;
  /* Number of milliseconds between each round of applying optimizations */
  public static final int OPTIMIZATION_INTERVAL = 600000; // 10 minutes
}
