package com.bgu.ocr.messages;

import java.util.Objects;

/**
 * Manager → the Local App's reply queue. Either the job completed ({@code summaryS3Key} set) or it was
 * rejected because the system is terminating ({@code rejected} holds the reason).
 */
public record JobDone(String jobId, String summaryS3Key, String rejected) {

    public JobDone {
        Objects.requireNonNull(jobId, "jobId");
        if ((summaryS3Key == null) == (rejected == null)) {
            throw new IllegalArgumentException("exactly one of summaryS3Key/rejected must be set");
        }
    }

    public static JobDone completed(String jobId, String summaryS3Key) {
        return new JobDone(jobId, summaryS3Key, null);
    }

    public static JobDone rejected(String jobId, String reason) {
        return new JobDone(jobId, null, reason);
    }

    public boolean wasRejected() {
        return rejected != null;
    }
}
