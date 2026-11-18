package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;

/**
 * Keeps every in-flight job's NewTask hidden in the inbox: each run resets it to {@code visibilitySeconds} from now.
 * Scheduled by the Manager every {@code Config.INBOX_HEARTBEAT_SECONDS}. If the Manager dies, the beats stop and the
 * NewTasks reappear in the inbox, and another Manager redoes those jobs (crash recovery).
 */
public final class Heartbeat implements Runnable {

    private final MessageQueue inbox;
    private final JobTable jobs;
    private final int visibilitySeconds;

    public Heartbeat(MessageQueue inbox, JobTable jobs, int visibilitySeconds) {
        this.inbox = inbox;
        this.jobs = jobs;
        this.visibilitySeconds = visibilitySeconds;
    }

    /** Never throws: a scheduled task that throws is silently never run again. */
    @Override
    public void run() {
        for (JobState job : jobs.snapshot()) {   // a copy: no lock held during the network calls
            try {
                inbox.extendVisibility(job.inboxMessage(), visibilitySeconds);
            } catch (RuntimeException e) {
                // e.g. the job finished during this round and its NewTask is already deleted
                System.err.println("Heartbeat: could not extend job " + job.jobId() + ": " + e);
            }
        }
    }
}
