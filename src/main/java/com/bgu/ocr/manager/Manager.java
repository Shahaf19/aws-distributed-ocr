package com.bgu.ocr.manager;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ObjectMissingException;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

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
    private final Runnable onFatal;
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
        this(queues, store, scaler, managerId, managerClientsN, () -> System.exit(1));
    }

    /** @param onFatal what to do when a thread dies of something uncaught; tests pass a recorder instead of exiting */
    Manager(QueueAdmin queues, ObjectStore store, WorkerScaler scaler, String managerId, int managerClientsN,
            Runnable onFatal) {
        if (managerClientsN <= 0) {
            throw new IllegalArgumentException("managerClientsN must be positive: " + managerClientsN);
        }
        this.queues = queues;
        this.store = store;
        this.scaler = scaler;
        this.managerId = managerId;
        this.managerClientsN = managerClientsN;
        this.onFatal = onFatal;
    }

    /**
     * Fail fast: a Manager with a dead thread (say, its results loop) would keep running its heartbeat and hold its
     * jobs hidden forever. Exiting instead stops the heartbeat, so the jobs reappear for another Manager.
     */
    private ThreadFactory threadsNamed(String name) {
        AtomicInteger count = new AtomicInteger();
        return work -> {
            Thread thread = new Thread(work, name + "-" + count.incrementAndGet());
            thread.setUncaughtExceptionHandler((dead, e) -> {
                System.err.println("Manager: thread " + dead.getName() + " died, shutting down: " + e);
                e.printStackTrace();
                onFatal.run();
            });
            return thread;
        };
    }

    /** This Manager's own queues: its Workers take tasks from one and put results in the other. */
    public static String taskQueueName(String managerId) {
        return "ocr-tasks-" + managerId;
    }

    public static String resultsQueueName(String managerId) {
        return "ocr-results-" + managerId;
    }

    /** A Manager's own two queues. */
    public record OwnQueues(MessageQueue tasks, MessageQueue results) {
    }

    /** Creates (or finds) a Manager's own queues: the one place that knows their names and settings. */
    public static OwnQueues ownQueues(QueueAdmin queues, String managerId) {
        return new OwnQueues(queues.create(taskQueueName(managerId), Config.TASK_VISIBILITY_SECONDS),
                queues.create(resultsQueueName(managerId)));
    }

    /** Told the number of jobs (clients) this Manager serves whenever it changes; call before {@link #start()}. */
    public void onActiveClientsChange(IntConsumer listener) {
        jobs.onSizeChange(listener);
    }

    public synchronized void start() {
        inbox = queues.create(Config.INBOX_QUEUE, Config.INBOX_VISIBILITY_SECONDS);
        OwnQueues own = ownQueues(queues, managerId);
        taskQueue = own.tasks();
        MessageQueue resultsQueue = own.results();

        startPool = Executors.newFixedThreadPool(POOL_SIZE, threadsNamed("start"));
        finishPool = Executors.newFixedThreadPool(POOL_SIZE, threadsNamed("finish"));
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(new Heartbeat(inbox, jobs, Config.INBOX_VISIBILITY_SECONDS),
                Config.INBOX_HEARTBEAT_SECONDS, Config.INBOX_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        resultsLoop = new ResultsLoop(resultsQueue, jobs, this::finishJob);
        resultsThread = threadsNamed("results-loop").newThread(resultsLoop);
        inboxThread = threadsNamed("inbox-loop").newThread(
                new InboxLoop(inbox, jobs, managerClientsN, this::startJob, this::onTerminate));
        resultsThread.start();
        inboxThread.start();
        System.out.println("Manager " + managerId + " started (up to " + managerClientsN + " clients)");
        System.out.println("  task queue:    " + taskQueue.url());
        System.out.println("  results queue: " + resultsQueue.url());
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
        startPool.execute(() -> {   // execute, not submit: submit would hide an uncaught failure in an unread Future
            try {
                new StartJob(job, store, taskQueue, scaler, this::finishJob).run();
            } catch (ObjectMissingException e) {
                reject(job, "input file not found: " + e.getMessage());   // permanent: retrying won't help
            } catch (RuntimeException e) {
                retryLaterOrReject(job, e);
            }
        });
    }

    /**
     * A failure that may be temporary (S3/SQS hiccup): drop the job but keep its NewTask, so it reappears within the
     * inbox visibility timeout and is started again, like after a crash. Tasks already sent are absorbed by index
     * deduplication. After {@code Config.MAX_START_ATTEMPTS} deliveries the client is told instead.
     */
    private void retryLaterOrReject(JobState job, RuntimeException cause) {
        if (job.inboxMessage().receiveCount() >= Config.MAX_START_ATTEMPTS) {
            reject(job, "cannot start job after " + Config.MAX_START_ATTEMPTS + " attempts: " + cause.getMessage());
        } else {
            System.err.println("Job " + job.jobId() + ": start failed, it will be retried: " + cause);
            jobs.remove(job);   // stops its heartbeat; the NewTask stays in the inbox
        }
    }

    /** Called (once per job) by the results thread, or by StartJob for an empty input. */
    private void finishJob(JobState job) {
        finishPool.execute(new FinishJob(job, store, queues, inbox, jobs));
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

    /**
     * {@code java -cp aws-distributed-ocr.jar com.bgu.ocr.manager.Manager managerId manager-clients-n}. On EC2 the
     * boot script passes the instance id as managerId (see UserData).
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: Manager managerId manager-clients-n");
            System.exit(2);
        }
        String managerId = args[0];
        QueueAdmin queues = SqsQueueAdmin.withDefaultClient();
        Ec2Helper ec2 = Ec2Helper.withDefaultClient();
        OwnQueues own = ownQueues(queues, managerId);
        WorkerScaler scaler = new Ec2WorkerScaler(ec2, managerId, own.tasks().url(), own.results().url());

        Manager manager = new Manager(queues, S3ObjectStore.withDefaultClient(), scaler, managerId,
                Integer.parseInt(args[1]));
        ActiveClientsPublisher tag = new ActiveClientsPublisher(
                n -> ec2.tag(managerId, Ec2Tags.ACTIVE_CLIENTS, String.valueOf(n)));
        manager.onActiveClientsChange(tag);
        tag.accept(0);
        manager.start();
    }
}
