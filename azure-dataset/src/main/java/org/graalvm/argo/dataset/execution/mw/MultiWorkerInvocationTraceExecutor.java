package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.generator.InvocationTraceGenerator;
import org.graalvm.argo.dataset.execution.Environment;
import org.graalvm.argo.dataset.execution.ExecutorConfiguration;
import org.graalvm.argo.dataset.execution.InvocationTraceExecutor;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.AbstractMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.SingleInvocationMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.OwnerCollocationMemoryManagerFactory;
import org.graalvm.argo.dataset.execution.mw.memory.MemoryManagerFactories.SingleFunctionMemoryManagerFactory;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;
import org.graalvm.argo.dataset.utils.network.SocketNetworkUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.HdrHistogram.Histogram;

public class MultiWorkerInvocationTraceExecutor extends InvocationTraceExecutor {

    private final AbstractWorker[] workers;
    private int overalloc = 0;
    private final List<String> statistics;
    final long beginningTimestamp;

    static final long BUCKET_DURATION_MS = 5 * 60 * 1000L;

    static long currentBucketStart = -1;
    static long firstTraceTimestamp = -1;
    static long lastSeenTimestamp = -1;
    static final Set<String> warmedFunctionsInCurrentBucket = new HashSet<>();
    static final Map<String, Integer> warmedFunctionMemory = new HashMap<>();
    static long totalProvisioningFootprint = 0;

    // To be removed (everything in ms):
    private static long timeInRead = 0;
    private static long timeInSchedule = 0;
    private static long timeInEnsureUploaded = 0;
    private static long timeInWait = 0;
    private static long timeInRequest = 0;
    private static long timeInEvict = 0;
    public final static Queue<Long> differences = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> networkSend = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> workerProcess = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> traceDurations = new ConcurrentLinkedQueue<>();
    public final static Queue<Long> networkRecv = new ConcurrentLinkedQueue<>();
    public final static Queue<Double> ratios = new ConcurrentLinkedQueue<>();

    public MultiWorkerInvocationTraceExecutor(ExecutorConfiguration config) {
        super(config);
        AbstractMemoryManagerFactory factory = getMemoryManagerFactory(config);
        workers = new AbstractWorker[Environment.WORKER_COUNT];
        for (int i = 0; i < Environment.WORKER_COUNT; ++i) {
            if (i == Environment.REAL_WORKER_INDEX) {
                workers[i] = getRealWorker(factory);
            } else {
                workers[i] = new PhysicalFakeWorker(factory.createMemoryManager(), this, Environment.FAKE_WORKER_HOST + ":" + (Environment.FAKE_WORKER_FIRST_PORT + i));
            }
        }
        statistics = new LinkedList<>();
        beginningTimestamp = System.currentTimeMillis();
    }

    private AbstractMemoryManagerFactory getMemoryManagerFactory(ExecutorConfiguration config) {
        if ("true".equals(config.invocationCollocation)) {
            if ("true".equals(config.functionIsolation)) {
                System.out.println("Using SingleFunctionMemoryManager");
                return new SingleFunctionMemoryManagerFactory();
            } else {
                System.out.println("Using OwnerCollocationMemoryManager");
                return new OwnerCollocationMemoryManagerFactory();
            }
        } else {
            System.out.println("Using SingleInvocationMemoryManager");
            return new SingleInvocationMemoryManagerFactory();
        }
    }

    private RealWorker getRealWorker(AbstractMemoryManagerFactory factory) {
        try {
            return new RealWorker(factory.createMemoryManager(), this, config.getLambdaManagerAddress());
        } catch (IOException e) {
            System.err.println("Couldn't create a real worker: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void closeBucket() {
        if (currentBucketStart < 0 || warmedFunctionsInCurrentBucket.isEmpty()) {
            warmedFunctionsInCurrentBucket.clear();
            return;
        }

        // We anchor bucket durations to 5-minute multiples,
        // but don't bill for time outside the trace
        long bucketNominalEnd = currentBucketStart + BUCKET_DURATION_MS;
        long billedStart = Math.max(currentBucketStart, firstTraceTimestamp);
        long billedEnd = Math.min(bucketNominalEnd, lastSeenTimestamp);
        long billedDurationMs = Math.max(0, billedEnd - billedStart);

        for (String key : warmedFunctionsInCurrentBucket) {
            int memoryMb = warmedFunctionMemory.getOrDefault(key, 0);
            totalProvisioningFootprint += (long) memoryMb * billedDurationMs;
        }
        warmedFunctionsInCurrentBucket.clear();
    }

    // HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,BenchmarkName
    @Override
    public void execute(String invocationsFilePath) {
        Map<String, String> optimizationMap = buildOptimizationMap();
        int nOptimizedAOT = 0;
        int nOptimizedSnap = 0;
        int nOptimizedWarm = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(invocationsFilePath))) {
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            int lastStatisticsTimestamp = 0;
            long beforeTmp = 0;
            /* Timestamp used to understand whether the executor is too slow or too fast compared to the trace. */
            long beginningTimestamp = System.currentTimeMillis();
            /* Used to avoid waiting on the same period multiple times. */
            int lastCheckedTimestamp = 0;
            while ((line = br.readLine()) != null) {
                // we're reading from a trace here, but in a real scenario the scheduler would be receiving invocations
                beforeTmp = System.nanoTime();
                splitRow = line.split(InvocationTraceGenerator.DELIMITER);
                String owner = getOwnerName(splitRow[0]);
                int timestamp = Integer.parseInt(splitRow[4]);

                // start of new code
                if (firstTraceTimestamp < 0) {
                    firstTraceTimestamp = timestamp;
                }
                lastSeenTimestamp = timestamp;

                if (currentBucketStart < 0) {
                    currentBucketStart = timestamp - (timestamp % BUCKET_DURATION_MS);
                }
                while (timestamp >= currentBucketStart + BUCKET_DURATION_MS) {
                    closeBucket();
                    currentBucketStart += BUCKET_DURATION_MS;
                }
                // end of new code

                String benchmarkName = splitRow[6];
                String function = getFunctionName(splitRow[1], benchmarkName);
                int functionMemory;
                timeInRead += (System.nanoTime() - beforeTmp);

                // start of new code
                String optimization = optimizationMap.getOrDefault(function, Environment.OPT_NONE);
                if (optimization.equals(Environment.OPT_SNAP)) {
                    nOptimizedSnap++;
                    benchmarkName = benchmarkName + "-snap";
                } else if (optimization.equals(Environment.OPT_AOT)) {
                    if (benchmarkName.startsWith("jv")) {
                        nOptimizedAOT++;
                        benchmarkName = benchmarkName + "-aot";
                    } else {
                        // AOT not available for Python/JavaScript, fall back to snapshot
                        nOptimizedSnap++;
                        benchmarkName = benchmarkName + "-snap";
                    }
                } else if (optimization.equals(Environment.OPT_WARM)) {
                    benchmarkName = benchmarkName + "-warm";
                    nOptimizedWarm++;
                    functionMemory = config.getBenchmarkConfiguration(benchmarkName).memory;
                    String bucketKey = owner + "_" + function;
                    warmedFunctionsInCurrentBucket.add(bucketKey);
                    warmedFunctionMemory.putIfAbsent(bucketKey, functionMemory);
                }
                // end of new code

                beforeTmp = System.nanoTime();
                functionMemory = config.getBenchmarkConfiguration(benchmarkName).memory;
                int duration = config.getBenchmarkConfiguration(benchmarkName).duration;
                timeInRead += (System.nanoTime() - beforeTmp);

                beforeTmp = System.nanoTime();
                AbstractWorker worker = schedule(owner, function, functionMemory);
                timeInSchedule += (System.nanoTime() - beforeTmp);

                if (worker != null) {
                    beforeTmp = System.nanoTime();
                    worker.ensureUploaded(owner, function, benchmarkName);
                    timeInEnsureUploaded += (System.nanoTime() - beforeTmp);
                }

                beforeTmp = System.nanoTime();
                /* Periodically check if we need to slow down the executor. */
                if ((timestamp - lastCheckedTimestamp) >= Environment.WAIT_PERIOD_MS) {
                    System.out.println(timestamp);
                    waitForInvocation(timestamp, System.currentTimeMillis() - beginningTimestamp);
                    lastCheckedTimestamp = timestamp;
                    SocketNetworkUtils.readAllAvailable();
                }
                timeInWait += (System.nanoTime() - beforeTmp);

                if (worker != null) {
                    beforeTmp = System.nanoTime();
                    worker.acceptFunctionInvocation(owner, function, functionMemory, duration, timestamp, benchmarkName);
                    timeInRequest += (System.nanoTime() - beforeTmp);
                }
//                beforeTmp = System.nanoTime();
//                evictTimedOutInvocations(timestamp);
//                timeInEvict += (System.nanoTime() - beforeTmp);
                if (Environment.COLLECT_STATISTICS && timestamp - lastStatisticsTimestamp >= Environment.STATISTICS_INTERVAL_MS) {
                    long before = System.nanoTime();
                    updateGlobalStatistics(timestamp);
                    lastStatisticsTimestamp = timestamp;
                    System.out.println("Time took to update statistics (ns): " + (System.nanoTime() - before));
                }
            }
            // Try to wait for remaining responses for 10 seconds more.
            SocketNetworkUtils.waitForResponses(10000);
        } catch (IOException e) {
            e.printStackTrace();
        }

        closeBucket();

        long globalTotalDuration = 0;
        long globalTotalFootprint = 0;
        long globalNormalInvocationFootprint = 0;
        long globalProvisionedExecutionFootprint = 0;
        int globalTotalColdStarts = 0;
        int globalTotalOptimizedColdStarts = 0;
        int globalTotalSlaViolations = 0;
        Histogram globalLatencies = new Histogram(1, TimeUnit.HOURS.toMicros(1), 3);
        //List<Long> globalColdLatencies = new LinkedList<>();
        //List<Long> globalWarmLatencies = new LinkedList<>();
        Map<String, Histogram> globalByBenchmark = new HashMap<>();
        for (AbstractWorker w : workers) {
            globalLatencies.add(w.getLatencies());
            //globalColdLatencies.addAll(w.getColdLatencies());
            //globalWarmLatencies.addAll(w.getWarmLatencies());


            for (Map.Entry<String, Histogram> entry : w.getLatenciesByBenchmark().entrySet()) {
                globalByBenchmark.computeIfAbsent(entry.getKey(), k -> new Histogram(1, TimeUnit.HOURS.toMicros(1), 3)).add(entry.getValue());
            }

            globalTotalDuration += w.totalDuration.get();
            globalTotalFootprint += w.totalFootprint.get();
            globalNormalInvocationFootprint += w.normalInvocationFootprint.get();
            globalProvisionedExecutionFootprint += w.provisionedExecutionFootprint.get();
            globalTotalColdStarts += w.totalColdStarts;
            globalTotalOptimizedColdStarts += w.totalOptimizedColdStarts;
            globalTotalSlaViolations += w.totalSlaViolations.get();
            w.printStatistics();
        }
        //Collections.sort(globalColdLatencies);
        //Collections.sort(globalWarmLatencies);
        /*-------------------------------------*/
        if (globalLatencies.getTotalCount() > 0) {
            long size = globalLatencies.getTotalCount();
            System.out.println("\n--- Global Latency Distribution (" + size + " requests) ---");
            System.out.println("Min:   " + globalLatencies.getMinValue() + " ms");
            System.out.println("p50:   " + globalLatencies.getValueAtPercentile(50) + " ms");
            System.out.println("p75:   " + globalLatencies.getValueAtPercentile(75) + " ms");
            System.out.println("p90:   " + globalLatencies.getValueAtPercentile(90) + " ms");
            System.out.println("p95:   " + globalLatencies.getValueAtPercentile(95) + " ms");
            System.out.println("p99:   " + globalLatencies.getValueAtPercentile(99) + " ms");
            System.out.println("p99.9: " + globalLatencies.getValueAtPercentile(99.9) + " ms");
            System.out.println("Max:   " + globalLatencies.getMaxValue() + " ms");
            System.out.println("Mean:  " + String.format("%.2f", globalLatencies.getMean()) + " ms");
            System.out.println("----------------------------------------------");
        }
        /*-------------------------------------*/
        // if (!globalColdLatencies.isEmpty()) {
        //     int size = globalColdLatencies.size();
        //     System.out.println("\n--- Global Cold Latency Distribution (" + size + " requests) ---");
        //     System.out.println("Min:   " + globalColdLatencies.get(0) + " ms");
        //     System.out.println("p50:   " + percentile(globalColdLatencies, 50) + " ms");
        //     System.out.println("p75:   " + percentile(globalColdLatencies, 75) + " ms");
        //     System.out.println("p90:   " + percentile(globalColdLatencies, 90) + " ms");
        //     System.out.println("p95:   " + percentile(globalColdLatencies, 95) + " ms");
        //     System.out.println("p99:   " + percentile(globalColdLatencies, 99) + " ms");
        //     System.out.println("p99.9: " + percentile(globalColdLatencies, 99.9) + " ms");
        //     System.out.println("Max:   " + globalColdLatencies.get(size - 1) + " ms");
        //     double mean = globalColdLatencies.stream().mapToLong(x -> x).average().orElse(0);
        //     System.out.println("Mean:  " + String.format("%.2f", mean) + " ms");
        //     System.out.println("----------------------------------------------");
        // }
        // /*-------------------------------------*/
        // if (!globalWarmLatencies.isEmpty()) {
        //     int size = globalWarmLatencies.size();
        //     System.out.println("\n--- Global Warm Latency Distribution (" + size + " requests) ---");
        //     System.out.println("Min:   " + globalWarmLatencies.get(0) + " ms");
        //     System.out.println("p50:   " + percentile(globalWarmLatencies, 50) + " ms");
        //     System.out.println("p75:   " + percentile(globalWarmLatencies, 75) + " ms");
        //     System.out.println("p90:   " + percentile(globalWarmLatencies, 90) + " ms");
        //     System.out.println("p95:   " + percentile(globalWarmLatencies, 95) + " ms");
        //     System.out.println("p99:   " + percentile(globalWarmLatencies, 99) + " ms");
        //     System.out.println("p99.9: " + percentile(globalWarmLatencies, 99.9) + " ms");
        //     System.out.println("Max:   " + globalWarmLatencies.get(size - 1) + " ms");
        //     double mean = globalWarmLatencies.stream().mapToLong(x -> x).average().orElse(0);
        //     System.out.println("Mean:  " + String.format("%.2f", mean) + " ms");
        //     System.out.println("----------------------------------------------");
        // }
        /*-------------------------------------*/
        System.out.println("\n--- Per-benchmark Latency Distribution ---");
        System.out.println("Number of AOT-optimized invocations: " + nOptimizedAOT);
        System.out.println("Number of Snapshot-optimized invocations: " + nOptimizedSnap);
        System.out.println("Number of Pre-warmed invocations: " + nOptimizedWarm);
        for (Map.Entry<String, Histogram> entry : globalByBenchmark.entrySet()) {
            Histogram h = entry.getValue();
            System.out.println(entry.getKey() + ": n=" + h.getTotalCount()
                + " p50=" + h.getValueAtPercentile(50)
                + " p99=" + h.getValueAtPercentile(99)
                + " mean=" + String.format("%.2f", h.getMean())
                + " max=" + h.getMaxValue());
        }
        System.out.println("----------------------------------------------");
        /*-------------------------------------*/
        System.out.println();
        System.out.println("Time In Read (ms): " + timeInRead / 1000000);
        System.out.println("Time In Schedule (ms): " + timeInSchedule / 1000000);
        System.out.println("Time In EnsureUploaded (ms): " + timeInEnsureUploaded / 1000000);
        System.out.println("Time In Request (ms): " + timeInRequest / 1000000);
        System.out.println("Time In Wait (ms): " + timeInWait / 1000000);
        System.out.println("Time In Evict (ms): " + timeInEvict / 1000000);
        System.out.println("Time total (ms): " + (timeInRead + timeInSchedule + timeInEnsureUploaded + timeInRequest + timeInWait + timeInEvict) / 1000000);
        System.out.println("Avg longer duration: " + differences.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg increase: " + ratios.stream().mapToDouble(x -> x).average().orElse(0.0));
        System.out.println("Avg network send: " + networkSend.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg worker time: " + workerProcess.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg trace duration: " + traceDurations.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Avg network recv: " + networkRecv.stream().mapToLong(x -> x).average().orElse(0));
        System.out.println("Total requests returned: " + differences.size());
        System.out.println("Total function duration (ms): " + globalTotalDuration);
        System.out.println("Total function footprint (MB-ms): " + globalTotalFootprint);

        System.out.println("--- Provisioned Concurrency Footprint ---");
        System.out.println("Normal invocation footprint (MB-ms): " + globalNormalInvocationFootprint);
        System.out.println("Provisioned execution footprint (MB-ms): " + globalProvisionedExecutionFootprint);
        System.out.println("Provisioning (standing/keep-warm) footprint (MB-ms): " + totalProvisioningFootprint);
        long footprintCheckSum = globalNormalInvocationFootprint + globalProvisionedExecutionFootprint;
        if (footprintCheckSum != globalTotalFootprint) {
            System.out.println("WARNING: footprint mismatch! normal + provisioned-execution = " + footprintCheckSum
                + " but total = " + globalTotalFootprint + " (difference: " + (globalTotalFootprint - footprintCheckSum) + ")");
        }

        System.out.println("Cold starts: " + globalTotalColdStarts);
        System.out.println("Optimized cold starts: " + globalTotalOptimizedColdStarts);
        System.out.println("SLA violations: " + globalTotalSlaViolations);
        /*-------------------------------------*/
        System.out.println("Overallocated " + overalloc + " requests.");
        if (Environment.REAL_WORKER_INDEX >= 0 && Environment.REAL_WORKER_INDEX < workers.length) {
            // Only print stats and close if real worker is injected.
            System.out.println("Real node stats:");
            workers[Environment.REAL_WORKER_INDEX].printStatistics();
            if (workers[Environment.REAL_WORKER_INDEX] instanceof RealWorker) {
                ((RealWorker) workers[Environment.REAL_WORKER_INDEX]).close();
            }
        }
        if (Environment.COLLECT_STATISTICS) {
            printGlobalStatistics();
        }
        // To allow for all async requests to finish.
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) { }
    }

//    private void evictTimedOutInvocations(int timestamp) {
//        for (AbstractWorker w : workers) {
//            if (w instanceof FakeWorker) {
//                ((FakeWorker) w).evictInvocations(timestamp);
//            }
//        }
//    }

    private void updateGlobalStatistics(int currentTimestamp) {
        StringBuilder sb = new StringBuilder("{\"").append(currentTimestamp).append("\":[");
        for (AbstractWorker w : workers) {
            int memory = w.getCurrentMemoryUtilization();
            int vms = memory / Environment.VM_MEMORY;
            sb.append("{\"VMs\":").append(vms).append(",\"memory\":").append(memory).append("},");
        }
        statistics.add(sb.append("]},").toString());
    }

    private void printGlobalStatistics() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(Environment.GLOBAL_STATISTICS_OUTPUT, false))) {
            writer.write("[");
            writer.newLine();
            for (String record : statistics) {
                writer.write(record);
                writer.newLine();
            }
            writer.write("]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private AbstractWorker schedule(String owner, String function, int invocationMemory) {
        AbstractWorker result = null;
        for (AbstractWorker w : workers) {
            if (w.canAccommodateRequest(owner, function, invocationMemory) && w.hasFunctionRegistered(owner, function)) {
                result = w;
                break;
            }
        }
        if (result == null) {
            for (AbstractWorker w : workers) {
                if (w.canAccommodateRequest(owner, function, invocationMemory) && w.hasOwnerRegistered(owner)) {
                    result = w;
                    break;
                }
            }
        }
        if (result == null) {
            result = findLeastUtilized();
            if (!result.canAccommodateRequest(owner, function, invocationMemory)) {
                overalloc++;
                result = null;
            }
        }
        return result;
    }

    private AbstractWorker findLeastUtilized() {
        AbstractWorker result = workers[0];
        for (int i = 1; i < workers.length; ++i) {
            if (workers[i].getCurrentMemoryUtilization() < result.getCurrentMemoryUtilization()) {
                result = workers[i];
            }
        }
        return result;
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private Map<String, String> buildOptimizationMap() {
        Map<String, String> optimizationMap = new HashMap<>();
        try (BufferedReader optBr = new BufferedReader(new FileReader(Environment.OPTIMIZATION_MAPPING_FILE))) {
            String optLine;
            optBr.readLine(); // skip header
            while ((optLine = optBr.readLine()) != null) {
                String[] parts = optLine.split(InvocationTraceGenerator.DELIMITER);
                if (parts.length == 2) {
                    String function = parts[0].trim();
                    String optimization = parts[1].trim();
                    optimizationMap.put(function, optimization);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not load optimization mapping: " + e.getMessage());
        }
        return optimizationMap;
    }
}
