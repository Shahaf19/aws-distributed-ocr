package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.MalformedMessageException;
import com.bgu.ocr.messages.OcrResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Manager's single results thread: collects Worker results into their jobs and hands each completed job to
 * the Finish pool. One poller for all jobs, so the number of SQS receives doesn't grow with the load.
 */
public final class ResultsLoop implements Runnable {

    private final MessageQueue resultsQueue;
    private final JobTable jobs;
    private final Consumer<JobState> onComplete;
    private volatile boolean running = true;

    /** @param onComplete called exactly once per job, by this thread; must be quick (the Manager submits FinishJob) */
    public ResultsLoop(MessageQueue resultsQueue, JobTable jobs, Consumer<JobState> onComplete) {
        this.resultsQueue = resultsQueue;
        this.jobs = jobs;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        while (running) {
            try {
                pollOnce();
            } catch (RuntimeException e) {   // e.g. a network error on receive: keep the loop alive
                System.err.println("Results loop: " + e);
                pause();
            }
        }
    }

    public void stop() {
        running = false;
    }

    /** One long poll and its handling. Only messages that were handled are deleted; the rest come back later. */
    void pollOnce() {
        List<ReceivedMessage> handled = new ArrayList<>();
        for (ReceivedMessage message : resultsQueue.receive()) {
            try {
                apply(message);
                handled.add(message);
            } catch (RuntimeException e) {
                System.err.println("Results loop: will retry a result after its visibility timeout: " + e);
            }
        }
        if (!handled.isEmpty()) {
            resultsQueue.deleteAll(handled);   // one call for up to 10 messages
        }
    }

    private void apply(ReceivedMessage message) {
        OcrResult result;
        try {
            result = Json.fromJson(message.body(), OcrResult.class);
        } catch (MalformedMessageException e) {
            System.err.println("Results loop: dropping malformed result: " + e.getMessage());
            return;   // retrying cannot fix it
        }
        JobState job = jobs.get(result.jobId());
        if (job == null) {
            return;   // a late duplicate of a job that already finished
        }
        if (job.addResult(result)) {   // true only for the result that completes the job
            onComplete.accept(job);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
