package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.aws.QueueAdmin;
import com.bgu.ocr.aws.QueueGoneException;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.Summary;

/**
 * Runs on the Finish pool once a job has all its results: summary to S3 → JobDone to the client → delete the NewTask
 * from the inbox → drop the job from the table. The order matters: the NewTask (our crash backup) goes last.
 */
public final class FinishJob implements Runnable {

    private final JobState job;
    private final ObjectStore store;
    private final QueueAdmin queues;
    private final MessageQueue inbox;
    private final JobTable jobs;

    public FinishJob(JobState job, ObjectStore store, QueueAdmin queues, MessageQueue inbox, JobTable jobs) {
        this.job = job;
        this.store = store;
        this.queues = queues;
        this.inbox = inbox;
        this.jobs = jobs;
    }

    @Override
    public void run() {
        String jobId = job.jobId();
        try {
            String summaryKey = "jobs/" + jobId + "/summary.json";
            store.put(summaryKey, Summary.toJson(job.results()));
            try {
                queues.open(job.task().replyQueueUrl()).send(Json.toJson(JobDone.completed(jobId, summaryKey)));
            } catch (QueueGoneException e) {
                // a duplicate run finishing after the client already got its answer and left: nothing to tell
                System.out.println("Job " + jobId + ": reply queue is gone, client already done");
            }
            inbox.delete(job.inboxMessage());
        } catch (RuntimeException e) {
            // treated like a crash of this one job: the NewTask stays in the inbox, and since the job leaves the
            // table below, the heartbeat stops and the NewTask reappears within the visibility timeout to be redone
            System.err.println("Job " + jobId + ": finishing failed, it will be retried: " + e);
        } finally {
            jobs.remove(job);   // frees a slot: wakes the inbox loop if it was full
        }
    }
}
