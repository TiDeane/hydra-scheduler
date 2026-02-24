package org.graalvm.argo.dataset;

import org.graalvm.argo.dataset.generator.InvocationTraceGenerator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * This class loads an invocation trace and replays each invocation, one at a
 * time. Periodically, it will generate information regarding the number of
 * users, functions, and invocations that would be active on a real platform.
 */
public class InvocationTraceSimulator {

    protected Invocation createInvocation(String owner, String function, int memory, int duration, int timestamp) {
        return new Invocation(owner, function, memory, duration, timestamp);
    }

    protected Invocation createInvocation(String owner, String function, int memory, int p25duration, int p99duration, int timestamp) {
        return new Invocation(owner, function, memory, p25duration, p99duration, timestamp);
    }

    protected List<Invocation> loadInvocations(String invocationsFile) {
        List<Invocation> invocations = new LinkedList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(invocationsFile))) {
            String line;
            String[] splitRow;
            br.readLine(); // To skip the header
            while ((line = br.readLine()) != null) {
                splitRow = line.split(InvocationTraceGenerator.DELIMITER);
                invocations.add(createInvocation(splitRow[0], splitRow[1], Integer.valueOf(splitRow[2]), Integer.valueOf(splitRow[3]), Integer.valueOf(splitRow[4]), Integer.valueOf(splitRow[5])));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return invocations;
    }

    protected void evictTimedOutInvocations(TreeSet<? extends Invocation> activeInvocations, int timestamp, int keepalive) {
        List<Invocation> evict = new LinkedList<>();
        for (Invocation invocation : activeInvocations) {
            if (timestamp >= invocation.getEndTimestamp() + keepalive) {
                evict.add(invocation);
            } else {
                // The activeInvocations tree is ordered. If we fail the above check, later elements will also fail.
                break;
            }
        }
        activeInvocations.removeAll(evict);
    }

    // TODO - for these, I don't see a clear reason not to doit in a stream.
    protected Invocation findWarmInvocation(TreeSet<? extends Invocation> activeInvocations, int timestamp, String function) {
        for (Invocation invocation : activeInvocations) {
            if (timestamp < invocation.getEndTimestamp()) {
                continue;
            } else if (invocation.getFunction().equals(function)) {
                return invocation;
            }
        }
        return null;
    }

    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, SimulationState ss) {
        return updateStatistics(activeInvocations, runningInvocations, new OutputEntry(), ss);
    }

    protected OutputEntry updateStatistics(TreeSet<Invocation> activeInvocations, List<Invocation> runningInvocations, OutputEntry outputEntry, SimulationState ss) {
        outputEntry.timestamp = ss.currentTimestamp;
        outputEntry.invocationsProcessed = ss.invocationsProcessed - ss.lastInvocationsProcessed;
        outputEntry.coldStarts = ss.coldStarts;
        outputEntry.totalDuration = ss.totalDuration;
        outputEntry.totalFootprint = ss.totalFootprint;
        outputEntry.runningUsers = (int) runningInvocations.parallelStream().map(Invocation::getOwner).distinct().count();
        outputEntry.runningFunctions  = (int) runningInvocations.parallelStream().map(Invocation::getFunction).distinct().count();
        outputEntry.runningInvocations = runningInvocations.size();
        outputEntry.runningInvocationsFootprint = (int) runningInvocations.parallelStream().mapToInt(Invocation::getMemory).sum();
        int totalUsers = (int) activeInvocations.parallelStream().map(Invocation::getOwner).distinct().count();
        int totalFunctions = (int) activeInvocations.parallelStream().map(Invocation::getFunction).distinct().count();
        outputEntry.cachedUsers = totalUsers - outputEntry.runningUsers;
        outputEntry.cachedFunctions = totalFunctions - outputEntry.runningFunctions;
        outputEntry.cachedInvocationsFootprint = activeInvocations.parallelStream().filter(i -> i.getEndTimestamp() < ss.currentTimestamp).mapToInt(Invocation::getMemory).sum();
        return outputEntry;
    }

    protected void resetSimulationStateAfterUpdateStatistics(SimulationState ss) {
        ss.previousTimestamp = ss.currentTimestamp;
        ss.lastInvocationsProcessed = ss.invocationsProcessed;
        ss.coldStarts = 0;
        ss.totalDuration = 0;
        ss.totalFootprint = 0;
    }

    protected void updateAfterWarmCheck(SimulationState ss, Invocation currentInvocation, Invocation warm) {
        if (warm == null) {
            ss.coldStarts++;
        } else {
            currentInvocation.setDuration(currentInvocation.getP25Duration());
            currentInvocation.setEndTimestamp(currentInvocation.getP25Duration());
            ss.activeInvocations.remove(warm);
        }
    }

    protected List<OutputEntry> simulateInvocations(List<Invocation> invocations, int keepalive, int interval) {
        return simulateInvocations(invocations, new SimulationState(), keepalive, interval);
    }

    protected List<OutputEntry> simulateInvocations(List<Invocation> invocations, SimulationState ss, int keepalive, int interval) {
        List<OutputEntry> statistics = new LinkedList<>();

        System.err.println("Simulating trace with " + invocations.size() + " invocations and keepalive of " + keepalive);
        for (Invocation currentInvocation : invocations) {
            ss.currentTimestamp = currentInvocation.getTimestamp();

            // Remove invocations that have past their keep alive time.
            evictTimedOutInvocations(ss.activeInvocations, ss.currentTimestamp, keepalive);

            // We try to find an inactive invocation that can be replaced with the new one.
            Invocation warm = findWarmInvocation(ss.activeInvocations, ss.currentTimestamp, currentInvocation.getFunction());
            updateAfterWarmCheck(ss, currentInvocation, warm);

            // Add invocation to array of active invocations.
            ss.activeInvocations.add(currentInvocation);
            ss.invocationsProcessed++;
            ss.totalDuration += currentInvocation.getDuration();
            ss.totalFootprint += currentInvocation.getMemory() * currentInvocation.getDuration() / 1000.0;

            if (ss.currentTimestamp - ss.previousTimestamp > interval) {
                // Calculate and update statistics.
                List<Invocation> runningInvocations = ss.activeInvocations.parallelStream().filter(i -> i.getEndTimestamp() > ss.currentTimestamp).collect(Collectors.toList());
                statistics.add(updateStatistics(ss.activeInvocations, runningInvocations, ss));

                // Reset values until the next round.
                resetSimulationStateAfterUpdateStatistics(ss);
            }

            // Progress update...
            if (ss.invocationsProcessed % Math.max(invocations.size() / 100, 1) == 0) {
                System.err.println(String.format("Processed %s (%.2f %%)", ss.invocationsProcessed, ((float) ss.invocationsProcessed / (float)invocations.size() * 100)));
            }
        }

        // Final update to statistics.
        statistics.add(updateStatistics(ss.activeInvocations, ss.runningInvocations(), ss));

        return statistics;
    }

    public List<OutputEntry> simulate(String inputFile, int keepAlive, int sampleInterval) {
        return simulateInvocations(loadInvocations(inputFile), keepAlive, sampleInterval);
    }
}
