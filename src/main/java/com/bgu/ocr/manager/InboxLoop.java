package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.InboxMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.MalformedMessageException;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.Terminate;

import java.util.function.Consumer;

/**
 * The Manager's single inbox thread: accepts new jobs from the shared inbox, never more than manager-clients-n at
 * once. While full it makes no SQS calls; it waits on the job table until a job finishes. Stop it by interrupting.
 */
public final class InboxLoop implements Runnable {

    private final MessageQueue inbox;
    private final JobTable jobs;
    private final int managerClientsN;
    private final Consumer<JobState> onNewJob;
    private final Consumer<ReceivedMessage> onTerminate;

    /**
     * @param onNewJob    called for each accepted job (the Manager submits StartJob)
     * @param onTerminate called with the terminate message; it stays in flight until the handler deals with it
     */
    public InboxLoop(MessageQueue inbox, JobTable jobs, int managerClientsN,
                     Consumer<JobState> onNewJob, Consumer<ReceivedMessage> onTerminate) {
        this.inbox = inbox;
        this.jobs = jobs;
        this.managerClientsN = managerClientsN;
        this.onNewJob = onNewJob;
        this.onTerminate = onTerminate;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {   // e.g. a network error on receive: keep the loop alive
                System.err.println("Inbox loop: " + e);
            }
        }
    }

    /** Waits for room, then one long poll that takes at most as many messages as there are free slots. */
    void pollOnce() throws InterruptedException {
        int free = jobs.awaitRoom(managerClientsN);
        for (ReceivedMessage message : inbox.receive(Math.min(free, MessageQueue.MAX_RECEIVE))) {
            handle(message);
        }
    }

    private void handle(ReceivedMessage message) {
        InboxMessage parsed;
        try {
            parsed = Json.fromJson(message.body(), InboxMessage.class);
        } catch (MalformedMessageException e) {
            System.err.println("Inbox loop: deleting malformed message: " + e.getMessage());
            inbox.delete(message);   // retrying cannot fix it
            return;
        }

        if (parsed instanceof Terminate) {
            onTerminate.accept(message);
            return;
        }

        NewTask task = (NewTask) parsed;
        JobState running = jobs.get(task.jobId());
        if (running != null) {
            running.updateInboxMessage(message);   // a redelivery of a job we're already doing: keep the newest handle
            return;
        }
        JobState job = new JobState(task, message);   // the NewTask stays in flight: it's the job's crash backup
        jobs.add(job);
        onNewJob.accept(job);
    }
}
