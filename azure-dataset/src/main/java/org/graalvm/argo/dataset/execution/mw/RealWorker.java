package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.execution.Environment;
import org.graalvm.argo.dataset.execution.mw.memory.AbstractMemoryManager;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;

import java.util.Collections;
import java.util.LinkedList;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Consumer;

public class RealWorker extends AbstractWorker {

    private static final String TRACE_INVOCATION_RECORD = "%s,%s,%d,%d,%d,%s,%s";

    private final MultiWorkerInvocationTraceExecutor executor;
    private final BufferedWriter bw;
    private int conc;
    private final String address;

    protected RealWorker(AbstractMemoryManager memoryManager, MultiWorkerInvocationTraceExecutor executor, String address) throws IOException {
        super(memoryManager);
        this.executor = executor;
        // Initialize file writer for a downscaled trace.
        File outputTraceFile = new File(Environment.REAL_WORKER_TRACE_OUTPUT);
        outputTraceFile.createNewFile();
        this.bw = new BufferedWriter(new FileWriter(outputTraceFile));
        bw.write("HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp,Language,Function");
        bw.newLine();
        this.address = address;
        conc = 0;
    }

    @Override
    public void ensureUploaded(String owner, String function, String benchmarkName) {
        if (!functions.contains(owner + "_" + function)) {
            executor.uploadFunction(address, owner, function, benchmarkName);
            owners.add(owner);
            functions.add(owner + "_" + function);

            if (!benchmarkName.endsWith("-warm")) {
                coldStartInvocations.add(owner + "_" + function);
                ++totalColdStarts;
                if (benchmarkName.endsWith("-snap") || benchmarkName.endsWith("-aot")) {
                    ++totalOptimizedColdStarts;
                }
            }
        }
    }

    @Override
    public void acceptFunctionInvocation(String owner, String function, int functionMemory, int duration, int timestamp, String benchmarkName) throws IOException {
        String language = executor.config.getBenchmarkConfiguration(benchmarkName).language;

        bw.write(String.format(TRACE_INVOCATION_RECORD, owner, function, functionMemory, duration, (System.currentTimeMillis() - executor.beginningTimestamp), language, benchmarkName));
        bw.newLine();
        ++conc;
        System.out.println("conc: " + conc);

        String baseBenchmarkName = benchmarkName.replace("-snap", "").replace("-aot", "");
        int unoptimizedP50 = executor.config.getBenchmarkConfiguration(baseBenchmarkName).duration;
        int slaThreshold = 4 * unoptimizedP50;
        boolean isColdStart = coldStartInvocations.remove(owner + "_" + function);
        boolean recordMetrics = true;

        memoryManager.startRequest(owner, function, functionMemory);
        executor.invokeFunction(address, owner, function, timestamp, duration, benchmarkName, new InvocationCallback(this, owner, function, benchmarkName, functionMemory, slaThreshold, isColdStart, recordMetrics));

        ++totalRequests;
    }

    private static class InvocationCallback implements Consumer<String> {

        private final AbstractWorker worker;
        private final String owner;
        private final String function;
        private final String benchmarkName;
        private final int memory;
        private final int slaThreshold;
        private final long startTimestamp;
        private final boolean isColdStart;
        private final boolean recordMetrics;

        private InvocationCallback(AbstractWorker worker, String owner, String function, String benchmarkName, int memory, int slaThreshold, boolean isColdStart, boolean recordMetrics) {
            this.worker = worker;
            this.owner = owner;
            this.function = function;
            this.benchmarkName = benchmarkName;
            this.memory = memory;
            this.slaThreshold = slaThreshold;
            this.isColdStart = isColdStart;
            this.recordMetrics = recordMetrics;
            this.startTimestamp = System.currentTimeMillis();
        }

        @Override
        public void accept(String s) {
            long actualEndTimestamp = System.currentTimeMillis();
            --((RealWorker)worker).conc;
            worker.memoryManager.finishRequest(owner, function);

            if (!recordMetrics) {
                // Warm-up invocation
                return;
            }

            long actualDuration = actualEndTimestamp - startTimestamp;
            worker.recordLatency(benchmarkName, actualDuration);
            worker.totalDuration.addAndGet(actualDuration);

            long footprintContribution = actualDuration * memory;           
            worker.totalFootprint.addAndGet((footprintContribution));
            if (benchmarkName.endsWith("-warm")) {
                worker.provisionedExecutionFootprint.addAndGet(footprintContribution);
            } else {
                worker.normalInvocationFootprint.addAndGet(footprintContribution);
            }

            // if (isColdStart) {
            //     worker.coldStartLatencies.add(actualDuration);
            // } else {
            //     worker.warmLatencies.add(actualDuration);
            // }

            if (actualDuration > slaThreshold) {
                worker.totalSlaViolations.incrementAndGet();
            }
        }
    }

    public void close() {
        try {
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
