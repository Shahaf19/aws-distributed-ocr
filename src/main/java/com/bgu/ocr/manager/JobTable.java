package com.bgu.ocr.manager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * The Manager's in-flight jobs (jobId → JobState), shared by all its threads. Also the room check for
 * manager-clients-n: the inbox loop waits here, locally and without SQS calls, until a finished job leaves.
 */
public final class JobTable {

    private final Map<String, JobState> jobs = new HashMap<>();   // guarded by this
    private volatile IntConsumer sizeListener = size -> { };

    /** Told the new number of jobs after each add/remove, outside the lock (it may make a network call). */
    public void onSizeChange(IntConsumer listener) {
        this.sizeListener = listener;
    }

    public synchronized JobState get(String jobId) {
        return jobs.get(jobId);
    }

    public void add(JobState job) {
        int size;
        synchronized (this) {
            if (jobs.putIfAbsent(job.jobId(), job) != null) {
                throw new IllegalStateException("job already in the table: " + job.jobId());
            }
            size = jobs.size();
        }
        sizeListener.accept(size);
    }

    /** Removes this job (not a newer one with the same id) and wakes the inbox loop, since a slot is now free. */
    public void remove(JobState job) {
        int size;
        synchronized (this) {
            if (!jobs.remove(job.jobId(), job)) {
                return;
            }
            notifyAll();
            size = jobs.size();
        }
        sizeListener.accept(size);
    }

    public synchronized int size() {
        return jobs.size();
    }

    /** A copy, so the heartbeat can walk it without holding the lock during network calls. */
    public synchronized List<JobState> snapshot() {
        return List.copyOf(jobs.values());
    }

    /** Blocks while {@code limit} jobs are in flight; returns the number of free slots (at least 1). */
    public synchronized int awaitRoom(int limit) throws InterruptedException {
        while (jobs.size() >= limit) {
            wait();
        }
        return limit - jobs.size();
    }
}
