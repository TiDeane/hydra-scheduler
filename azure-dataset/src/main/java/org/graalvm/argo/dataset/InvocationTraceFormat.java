package org.graalvm.argo.dataset;

public final class InvocationTraceFormat {

    public static final String DELIMITER = ",";
    public static final String HEADER = "HashOwner,HashFunction,AverageAllocatedMb,AverageDuration,Timestamp";

    private InvocationTraceFormat() {
    }
}
