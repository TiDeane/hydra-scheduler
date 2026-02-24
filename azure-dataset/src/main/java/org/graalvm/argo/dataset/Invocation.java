package org.graalvm.argo.dataset;

import java.util.Comparator;

public class Invocation {
    // Function owner identifier.
    private final String owner;
    // Function identifier.
    private final String function;
    // Memory footprint in MBs.
    protected int memory;
    // Function execution time in ms.
    protected int duration;
    // Function warm start execution time in ms.
    protected int p25duration;
    // Function cold start execution time in ms.
    protected int p99duration;
    // Function start timestamp in ms.
    private final int timestamp;
    // Function finish timestamp in ms.
    protected int endTimestamp; // not final, because it depends on warm or cold start

    public Invocation(String owner, String function, int memory, int duration, int timestamp) {
        this.owner = owner;
        this.function = function;
        this.memory = memory;
        this.duration = duration;
        this.p25duration = duration;
        this.p99duration = duration;
        this.timestamp = timestamp;
        this.endTimestamp = timestamp + duration;
    }

    public Invocation(String owner, String function, int memory, int p25duration, int p99duration, int timestamp) {
        this.owner = owner;
        this.function = function;
        this.memory = memory;
        this.duration = p99duration;
        this.p25duration = p25duration;
        this.p99duration = p99duration;
        this.timestamp = timestamp;
        /* We assume cold start initially, but this value is changed when processing the invocation */
        this.endTimestamp = timestamp + p99duration;
    }

    public String getOwner() {
        return owner;
    }

    public String getFunction() {
        return function;
    }

    public int getMemory() {
        return memory;
    }

    public int getDuration() {
        return duration;
    }

    public int getP25Duration() {
        return p25duration;
    }

    public int getP99Duration() {
        return p99duration;
    }

    public int getTimestamp() {
        return timestamp;
    }

    public int getEndTimestamp() {
        return endTimestamp;
    }

    /* When it is determined whether it's a cold or warm start */
    public void setDuration(int duration) {
        this.duration = duration;
    }

    public void setEndTimestamp(int duration) {
        this.endTimestamp = this.timestamp + duration;
    }

    @Override
    public String toString() {
        return String.format("%s,%s,%d,%d,%d,%d", owner, function, memory, p25duration, p99duration, timestamp);
    }

    public static Comparator<Invocation> comparator() {
        return new Comparator<Invocation>() {

            @Override
            public int compare(Invocation o1, Invocation o2) {
                return o1.endTimestamp - o2.endTimestamp;
            }
        };
    }

    public String toString(int firstTimestamp) {
        return String.format("%s,%s,%d,%d,%d,%d", owner, function, memory, p25duration, p99duration, (timestamp - firstTimestamp));
    }
}
