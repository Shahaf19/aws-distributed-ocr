package com.bgu.ocr.messages;

import java.util.Objects;

/**
 * Local App → shared Manager inbox: "process the input file at {@code inputS3Key}".
 *
 * @param jobId          unique per Local App run
 * @param inputS3Key     where the Local App uploaded the list of image URLs
 * @param replyQueueUrl  the Local App's own queue, where the Manager sends {@link JobDone}
 * @param workerFilesN   max files per Worker (the {@code worker-files-n} argument), used for scaling
 */
public record NewTask(String jobId, String inputS3Key, String replyQueueUrl, int workerFilesN)
        implements InboxMessage {

    public NewTask {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(inputS3Key, "inputS3Key");
        Objects.requireNonNull(replyQueueUrl, "replyQueueUrl");
        if (workerFilesN <= 0) {
            throw new IllegalArgumentException("workerFilesN must be positive: " + workerFilesN);
        }
    }
}
