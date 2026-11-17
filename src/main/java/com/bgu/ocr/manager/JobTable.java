package com.bgu.ocr.manager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Manager's in-flight jobs (jobId → JobState), shared by all its threads. Also the room check for
 * manager-clients-n: the inbox loop waits here, locally and without SQS calls, until a finished job leaves.
 */
public final class JobTable {

    private final Map<String, JobState> jobs = new HashMap<>();   // guarded by this

    public synchronized JobState get(String jobId) {
        return jobs.get(jobId);
    }

    public synchronized void add(JobState job) {
        if (jobs.putIfAbsent(job.jobId(), job) != null) {
            throw new IllegalStateException("job already in the table: " + job.jobId());
        }
    }

    /** Removes this job (not a newer one with the same id) and wakes the inbox loop, since a slot is now free. */
    public synchronized void remove(JobState job) {
        if (jobs.remove(job.jobId(), job)) {
            notifyAll();
        }
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
