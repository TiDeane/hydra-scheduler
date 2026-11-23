package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.execution.mw.memory.AbstractMemoryManager;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;

public abstract class AbstractWorker {

    private static final long HISTOGRAM_LOWEST_VALUE = 1; // 1 millisecond
    private static final long HISTOGRAM_HIGHEST_VALUE = TimeUnit.MINUTES.toMillis(10);
    private static final int HISTOGRAM_SIGNIFICANT_DIGITS = 3; 

    protected final Set<String> owners;
    protected final Set<String> functions;

    protected int totalRequests = 0;
    protected final AtomicLong totalDuration = new AtomicLong(0);
    protected final AtomicLong totalFootprint = new AtomicLong(0);
    protected final AtomicLong normalInvocationFootprint = new AtomicLong(0);
    protected final AtomicLong provisionedExecutionFootprint = new AtomicLong(0);
    protected final AtomicInteger totalSlaViolations = new AtomicInteger(0);
    protected int totalColdStarts = 0;
    protected int totalOptimizedColdStarts = 0;
    protected int maxExperiencedMemoryUtilization = 0;

    protected final AbstractMemoryManager memoryManager;

    protected final Histogram requestLatencies = new Histogram(HISTOGRAM_LOWEST_VALUE, HISTOGRAM_HIGHEST_VALUE, HISTOGRAM_SIGNIFICANT_DIGITS);
    // protected final List<Long> coldStartLatencies = Collections.synchronizedList(new LinkedList<>());
    // protected final List<Long> warmLatencies = Collections.synchronizedList(new LinkedList<>());
    protected final Map<String, Histogram> latenciesByBenchmark = new HashMap<>();
    protected final Set<String> coldStartInvocations = ConcurrentHashMap.newKeySet();

    protected AbstractWorker(AbstractMemoryManager memoryManager) {
        this.owners = new HashSet<>();
        this.functions = new HashSet<>();
        this.memoryManager = memoryManager;
    }

    public abstract void ensureUploaded(String owner, String function, String benchmarkName);

    public abstract void acceptFunctionInvocation(String owner, String function, int allocatedMemoryMb, int duration, int timestamp, String benchmarkName) throws IOException;

    public boolean hasFunctionRegistered(String owner, String function) {
        return functions.contains(owner + "_" + function);
    }

    public boolean hasOwnerRegistered(String owner) {
        return owners.contains(owner);
    }

    public int getCurrentMemoryUtilization() {
        int currentMemoryUtilization = memoryManager.getCurrentMemoryConsumption();
        if (currentMemoryUtilization > maxExperiencedMemoryUtilization) {
            maxExperiencedMemoryUtilization = currentMemoryUtilization;
        }
        return currentMemoryUtilization;
    }

    public boolean canAccommodateRequest(String owner, String function, int memory) {
        return memoryManager.canAccommodateRequest(owner, function, memory);
    }

    public void recordLatency(String benchmarkName, long latencyMs) {
        requestLatencies.recordValue(latencyMs);
        latenciesByBenchmark.computeIfAbsent(benchmarkName, k -> new Histogram(HISTOGRAM_LOWEST_VALUE, HISTOGRAM_HIGHEST_VALUE, HISTOGRAM_SIGNIFICANT_DIGITS)).recordValue(latencyMs);
    }

    public Histogram getLatencies() {
        return requestLatencies;
    }

    // public List<Long> getColdLatencies() {
    //     return coldStartLatencies;
    // }

    // public List<Long> getWarmLatencies() {
    //     return warmLatencies;
    // }

    public Map<String, Histogram> getLatenciesByBenchmark() {
        return latenciesByBenchmark;
    }

    public void printStatistics() {
        System.out.println("###################################");
        System.out.println("Registered functions:        " + functions.size());
        System.out.println("Registered owners:           " + owners.size());
        System.out.println("Total requests:              " + totalRequests);
        System.out.println("Total duration (ms):         " + totalDuration);
        System.out.println("Total footprint (MB-ms):     " + totalFootprint);
        System.out.println("Total cold starts:           " + totalColdStarts);
        System.out.println("Total optimized cold starts: " + totalOptimizedColdStarts);
        System.out.println("Total SLA violations:        " + totalSlaViolations);
        System.out.println("Max memory:                  " + maxExperiencedMemoryUtilization);
        System.out.println("###################################");
    }
}
