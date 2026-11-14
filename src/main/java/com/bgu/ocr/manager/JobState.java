package com.bgu.ocr.manager;

import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One job's progress inside a Manager (an entry in the jobs table).
 *
 * <p>Lifecycle: created by the inbox loop when a {@link NewTask} is accepted → {@link #setExpected} by StartJob once
 * the input file is parsed → {@link #addResult} by the results loop → {@link #results()} read by FinishJob.
 *
 * <p>Completion is tracked by distinct {@code index}, not by message count, because SQS may deliver a result twice.
 */
public final class JobState {

    private final NewTask task;
    private final String inboxReceiptHandle;

    private OcrResult[] results;   // null until the number of URLs is known
    private int received;

    /**
     * @param inboxReceiptHandle handle of the NewTask message; used for the visibility heartbeat and to delete
     *                           the message once the job is finished (crash recovery, see DECISIONS.md)
     */
    public JobState(NewTask task, String inboxReceiptHandle) {
        this.task = Objects.requireNonNull(task, "task");
        this.inboxReceiptHandle = Objects.requireNonNull(inboxReceiptHandle, "inboxReceiptHandle");
    }

    public NewTask task() {
        return task;
    }

    public String jobId() {
        return task.jobId();
    }

    public String inboxReceiptHandle() {
        return inboxReceiptHandle;
    }

    /**
     * Sets the number of URLs in the input file. Must be called before any task is sent.
     *
     * @return true if the job is already complete (an empty input file), so the caller should finish it right away
     */
    public synchronized boolean setExpected(int urlCount) {
        if (results != null) {
            throw new IllegalStateException("expected already set for job " + jobId());
        }
        if (urlCount < 0) {
            throw new IllegalArgumentException("urlCount must be >= 0: " + urlCount);
        }
        results = new OcrResult[urlCount];
        return urlCount == 0;
    }

    /**
     * Records one Worker result.
     *
     * @return true only for the result that completes the job, so FinishJob is submitted exactly once.
     *         False for duplicates, results of another job, and out-of-range indexes (all ignored).
     */
    public synchronized boolean addResult(OcrResult result) {
        if (results == null) {
            throw new IllegalStateException("result arrived before expected count was set for job " + jobId());
        }
        if (!jobId().equals(result.jobId()) || result.index() >= results.length || results[result.index()] != null) {
            return false;
        }
        results[result.index()] = result;
        received++;
        return received == results.length;
    }

    public synchronized boolean isComplete() {
        return results != null && received == results.length;
    }

    /** All results ordered by index. Only valid once the job is complete. */
    public synchronized List<OcrResult> results() {
        if (!isComplete()) {
            throw new IllegalStateException("job " + jobId() + " is not complete");
        }
        return List.copyOf(Arrays.asList(results));
    }
}
