package org.graalvm.argo.dataset.execution.mw;

import org.graalvm.argo.dataset.execution.mw.memory.AbstractMemoryManager;
import org.graalvm.argo.dataset.multilang.FunctionLanguage;

import java.util.Collections;
import java.util.LinkedList;
import java.util.function.Consumer;

public class PhysicalFakeWorker extends AbstractWorker {

    private final MultiWorkerInvocationTraceExecutor executor;
    private final String address;

    protected PhysicalFakeWorker(AbstractMemoryManager memoryManager, MultiWorkerInvocationTraceExecutor executor, String address) {
        super(memoryManager);
        this.executor = executor;
        this.address = address;
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
    public void acceptFunctionInvocation(String owner, String function, int functionMemory, int duration, int timestamp, String benchmarkName) {
        String baseBenchmarkName = benchmarkName.replace("-snap", "").replace("-aot", "");
        int unoptimizedP50 = executor.config.getBenchmarkConfiguration(baseBenchmarkName).duration;
        int slaThreshold = 4 * unoptimizedP50;
        boolean isColdStart = coldStartInvocations.remove(owner + "_" + function);
        boolean recordMetrics = true;

        memoryManager.startRequest(owner, function, functionMemory);
        executor.invokeFunction(address, owner, function, timestamp, duration, benchmarkName,
            new InvocationCallback(this, owner, function, benchmarkName, duration, functionMemory, slaThreshold, isColdStart, recordMetrics));
        ++totalRequests;
    }

    private static class InvocationCallback implements Consumer<String> {

        private final AbstractWorker worker;
        private final String owner;
        private final String function;
        private final String benchmarkName;
        private final long duration;
        private final int memory;
        private final int slaThreshold;
        private final long startTimestamp;
        private final boolean isColdStart;
        private final boolean recordMetrics;

        private InvocationCallback(AbstractWorker worker, String owner, String function, String benchmarkName, int duration, int memory, int slaThreshold, boolean isColdStart, boolean recordMetrics) {
            this.worker = worker;
            this.owner = owner;
            this.function = function;
            this.benchmarkName = benchmarkName;
            this.duration = duration;
            this.memory = memory;
            this.slaThreshold = slaThreshold;
            this.isColdStart = isColdStart;
            this.recordMetrics = recordMetrics;
            this.startTimestamp = System.currentTimeMillis();
        }

        @Override
        public void accept(String response) {
            long actualEndTimestamp = System.currentTimeMillis();
            String[] ts = response.split(" ");
            long msgReceivedTs = Long.parseLong(ts[0]);
            long msgRespondedTs = Long.parseLong(ts[1]);
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
            MultiWorkerInvocationTraceExecutor.differences.add(actualDuration - duration);
            MultiWorkerInvocationTraceExecutor.ratios.add((double) actualDuration / (duration == 0 ? 0.1 : (double) duration));
            MultiWorkerInvocationTraceExecutor.networkSend.add(msgReceivedTs - startTimestamp);
            MultiWorkerInvocationTraceExecutor.workerProcess.add(msgRespondedTs - msgReceivedTs);
            MultiWorkerInvocationTraceExecutor.traceDurations.add(duration);
            MultiWorkerInvocationTraceExecutor.networkRecv.add(actualEndTimestamp - msgRespondedTs);
        }
    }
}
