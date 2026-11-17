package com.bgu.ocr.manager;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.aws.QueueAdmin;
import com.bgu.ocr.aws.QueueGoneException;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.aws.S3ObjectStore;
import com.bgu.ocr.aws.SqsQueueAdmin;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wires the Manager's parts (see DECISIONS.md, "Manager threading model"): an inbox thread and a results thread,
 * a Start pool and a Finish pool, and the heartbeat. The jobs table is the state they share.
 */
public final class Manager {

    /** The work is short and mostly waits on the network; more jobs than threads just queue briefly in the pool. */
    private static final int POOL_SIZE = 4;

    private final QueueAdmin queues;
    private final ObjectStore store;
    private final WorkerScaler scaler;
    private final String managerId;
    private final int managerClientsN;
    private final JobTable jobs = new JobTable();

    private MessageQueue inbox;
    private MessageQueue taskQueue;
    private ExecutorService startPool;
    private ExecutorService finishPool;
    private ScheduledExecutorService heartbeat;
    private ResultsLoop resultsLoop;
    private Thread inboxThread;
    private Thread resultsThread;

    public Manager(QueueAdmin queues, ObjectStore store, WorkerScaler scaler, String managerId, int managerClientsN) {
        this.queues = queues;
        this.store = store;
        this.scaler = scaler;
        this.managerId = managerId;
        this.managerClientsN = managerClientsN;
    }

    /** This Manager's own queues: its Workers take tasks from one and put results in the other. */
    public static String taskQueueName(String managerId) {
        return "ocr-tasks-" + managerId;
    }

    public static String resultsQueueName(String managerId) {
        return "ocr-results-" + managerId;
    }

    public synchronized void start() {
        inbox = queues.create(Config.INBOX_QUEUE, Config.INBOX_VISIBILITY_SECONDS);
        taskQueue = queues.create(taskQueueName(managerId), Config.TASK_VISIBILITY_SECONDS);
        MessageQueue resultsQueue = queues.create(resultsQueueName(managerId));

        startPool = Executors.newFixedThreadPool(POOL_SIZE);
        finishPool = Executors.newFixedThreadPool(POOL_SIZE);
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(new Heartbeat(inbox, jobs, Config.INBOX_VISIBILITY_SECONDS),
                Config.INBOX_HEARTBEAT_SECONDS, Config.INBOX_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        resultsLoop = new ResultsLoop(resultsQueue, jobs, this::finishJob);
        resultsThread = new Thread(resultsLoop, "results-loop");
        inboxThread = new Thread(new InboxLoop(inbox, jobs, managerClientsN, this::startJob, this::onTerminate),
                "inbox-loop");
        resultsThread.start();
        inboxThread.start();
        System.out.println("Manager " + managerId + " started (up to " + managerClientsN + " clients)");
    }

    /** Stops all threads. Nothing is deleted, so unfinished jobs are recovered from the inbox (like after a crash). */
    public synchronized void stop() throws InterruptedException {
        inboxThread.interrupt();
        resultsLoop.stop();
        resultsThread.interrupt();
        heartbeat.shutdownNow();
        startPool.shutdownNow();
        finishPool.shutdownNow();
        inboxThread.join();
        resultsThread.join();
    }

    /** Called by the inbox thread for each accepted job. */
    private void startJob(JobState job) {
        startPool.submit(() -> {
            try {
                new StartJob(job, store, taskQueue, scaler, this::finishJob).run();
            } catch (RuntimeException e) {
                reject(job, "cannot start job: " + e.getMessage());   // e.g. input missing: retrying won't help
            }
        });
    }

    /** Called (once per job) by the results thread, or by StartJob for an empty input. */
    private void finishJob(JobState job) {
        finishPool.submit(new FinishJob(job, store, queues, inbox, jobs));
    }

    /** Tells the client its job failed instead of leaving it waiting, then forgets the job for good. */
    private void reject(JobState job, String reason) {
        System.err.println("Job " + job.jobId() + " rejected: " + reason);
        try {
            try {
                queues.open(job.task().replyQueueUrl()).send(Json.toJson(JobDone.rejected(job.jobId(), reason)));
            } catch (QueueGoneException e) {
                // the client is gone: nothing to tell
            }
            inbox.delete(job.inboxMessage());
        } catch (RuntimeException e) {
            System.err.println("Job " + job.jobId() + ": rejecting failed, it will be retried: " + e);
        } finally {
            jobs.remove(job);
        }
    }

    private void onTerminate(ReceivedMessage message) {
        System.out.println("Terminate received: the terminate flow is part of the EC2 step");
    }

    /** {@code java -cp aws-distributed-ocr.jar com.bgu.ocr.manager.Manager managerId manager-clients-n} */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: Manager managerId manager-clients-n");
            System.exit(2);
        }
        WorkerScaler notYetImplemented = needed -> System.out.println("(scaling to " + needed + " Workers: EC2 step)");
        new Manager(SqsQueueAdmin.withDefaultClient(), S3ObjectStore.withDefaultClient(), notYetImplemented,
                args[0], Integer.parseInt(args[1])).start();
    }
}
