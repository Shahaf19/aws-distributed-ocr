package com.bgu.ocr.manager;

import com.bgu.ocr.Config;
import com.bgu.ocr.QueueNames;
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
import com.bgu.ocr.messages.NewTask;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Wires the Manager's parts: an inbox thread and a results thread,
 * a Start pool and a Finish pool, and a scheduler for the heartbeat and the lifecycle check (terminate requests,
 * idleness). The jobs table is the state they share.
 */
public final class Manager {

    /** The work is short and mostly waits on the network; more jobs than threads just queue briefly in the pool. */
    private static final int POOL_SIZE = 4;

    /** How often to check for a terminate request and for idleness, and the idle limits. */
    public record Timings(Duration lifecycleCheck, Duration workersIdle, Duration managerIdle) {

        public static Timings fromConfig() {
            return new Timings(Duration.ofSeconds(Config.LIFECYCLE_CHECK_SECONDS),
                    Duration.ofMinutes(Config.IDLE_WORKERS_MINUTES), Duration.ofMinutes(Config.IDLE_MANAGER_MINUTES));
        }
    }

    private final QueueAdmin queues;
    private final ObjectStore store;
    private final WorkerScaler scaler;
    private final Fleet fleet;
    private final String managerId;
    private final int managerClientsN;
    private final Timings timings;
    private final Runnable onFatal;
    private final JobTable jobs = new JobTable();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CountDownLatch shutDown = new CountDownLatch(1);

    private MessageQueue inbox;
    private OwnQueues own;
    private ExecutorService startPool;
    private ExecutorService finishPool;
    private ScheduledExecutorService scheduler;
    private ResultsLoop resultsLoop;
    private Thread inboxThread;
    private Thread resultsThread;

    // only touched by the scheduler thread
    private long idleSinceNanos = -1;
    private boolean idleWorkersStopped;

    public Manager(QueueAdmin queues, ObjectStore store, WorkerScaler scaler, Fleet fleet, String managerId,
                   int managerClientsN) {
        this(queues, store, scaler, fleet, managerId, managerClientsN, Timings.fromConfig(), () -> System.exit(1));
    }

    /** @param onFatal what to do when a thread dies of something uncaught; tests pass a recorder instead of exiting */
    Manager(QueueAdmin queues, ObjectStore store, WorkerScaler scaler, Fleet fleet, String managerId,
            int managerClientsN, Timings timings, Runnable onFatal) {
        if (managerClientsN <= 0) {
            throw new IllegalArgumentException("managerClientsN must be positive: " + managerClientsN);
        }
        this.queues = queues;
        this.store = store;
        this.scaler = scaler;
        this.fleet = fleet;
        this.managerId = managerId;
        this.managerClientsN = managerClientsN;
        this.timings = timings;
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
        return QueueNames.tasks(managerId);
    }

    public static String resultsQueueName(String managerId) {
        return QueueNames.results(managerId);
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
        own = ownQueues(queues, managerId);

        startPool = Executors.newFixedThreadPool(POOL_SIZE, threadsNamed("start"));
        finishPool = Executors.newFixedThreadPool(POOL_SIZE, threadsNamed("finish"));
        scheduler = Executors.newSingleThreadScheduledExecutor(threadsNamed("scheduler"));
        long beat = Config.INBOX_HEARTBEAT_SECONDS;
        scheduler.scheduleAtFixedRate(new Heartbeat(inbox, jobs, Config.INBOX_VISIBILITY_SECONDS),
                beat, beat, TimeUnit.SECONDS);
        long check = timings.lifecycleCheck().toMillis();
        scheduler.scheduleWithFixedDelay(this::checkLifecycle, check, check, TimeUnit.MILLISECONDS);

        resultsLoop = new ResultsLoop(own.results(), jobs, this::finishJob);
        resultsThread = threadsNamed("results-loop").newThread(resultsLoop);
        inboxThread = threadsNamed("inbox-loop").newThread(
                new InboxLoop(inbox, jobs, managerClientsN, this::startJob, this::onTerminate));
        resultsThread.start();
        inboxThread.start();
        System.out.println("Manager " + managerId + " started (up to " + managerClientsN + " clients)");
        System.out.println("  task queue:    " + own.tasks().url());
        System.out.println("  results queue: " + own.results().url());
    }

    /** Stops all threads abruptly. Nothing is deleted, so unfinished jobs are recovered (like after a crash). */
    public synchronized void stop() throws InterruptedException {
        inboxThread.interrupt();
        stopThreads();
        inboxThread.join();
    }

    /** Blocks until a deliberate shutdown (terminate request or idleness) has completed. */
    public boolean awaitShutdown(long timeout, TimeUnit unit) throws InterruptedException {
        return shutDown.await(timeout, unit);
    }

    // ---- jobs ----

    /** Called by the inbox thread for each accepted job. */
    private void startJob(JobState job) {
        startPool.execute(() -> {   // execute, not submit: submit would hide an uncaught failure in an unread Future
            try {
                new StartJob(job, store, own.tasks(), scaler, this::finishJob).run();
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
        try {
            rejectNewTask(job.task(), job.inboxMessage(), reason);
        } finally {
            jobs.remove(job);
        }
    }

    private void rejectNewTask(NewTask task, ReceivedMessage inboxMessage, String reason) {
        System.err.println("Job " + task.jobId() + " rejected: " + reason);
        try {
            try {
                queues.open(task.replyQueueUrl()).send(Json.toJson(JobDone.rejected(task.jobId(), reason)));
            } catch (QueueGoneException e) {
                // the client is gone: nothing to tell
            }
            inbox.delete(inboxMessage);
        } catch (RuntimeException e) {
            System.err.println("Job " + task.jobId() + ": rejecting failed, it will be retried: " + e);
        }
    }

    // ---- lifecycle: terminate (shuts down every Manager) and idleness ----

    /** Called by the inbox thread: tell every Manager (tags), then shut this one down. */
    private void onTerminate(ReceivedMessage message) {
        try {
            fleet.markAllManagersTerminating(message.sentTimestamp());
        } catch (RuntimeException e) {
            System.err.println("Could not mark the Managers terminating, will retry when the message returns: " + e);
            return;
        }
        inbox.delete(message);
        beginShutdown("terminate message", message.sentTimestamp());
    }

    /** Runs on the scheduler: other Managers learn about a terminate through their tag; idle Managers shut down. */
    private void checkLifecycle() {
        recoverWorkerIfNeeded();
        if (shuttingDown.get()) {
            return;
        }
        try {
            OptionalLong requested = fleet.terminationRequestedAt();
            if (requested.isPresent()) {
                beginShutdown("terminate requested", requested.getAsLong());
            } else {
                checkIdle();
            }
        } catch (RuntimeException e) {
            System.err.println("Lifecycle check failed (will retry): " + e);
        }
    }

    private void recoverWorkerIfNeeded() {
        synchronized (scaler) {
            if (jobs.snapshot().stream().anyMatch(JobState::hasUnfinishedImages)) {
                try {
                    scaler.ensureWorkers(1);
                } catch (RuntimeException e) {
                    System.err.println("Worker recovery check failed (will retry): " + e);
                }
            }
        }
    }

    private void checkIdle() {
        if (jobs.size() > 0) {
            idleSinceNanos = -1;
            idleWorkersStopped = false;
            return;
        }
        long now = System.nanoTime();
        if (idleSinceNanos < 0) {
            idleSinceNanos = now;
            return;
        }
        Duration idle = Duration.ofNanos(now - idleSinceNanos);
        if (!idleWorkersStopped && idle.compareTo(timings.workersIdle()) >= 0) {
            fleet.terminateMyWorkers();   // the next job launches new ones
            idleWorkersStopped = true;
        }
        if (idle.compareTo(timings.managerIdle()) >= 0) {
            fleet.markSelfTerminating();   // clients stop counting on us and start a new Manager if needed
            beginShutdown("idle for " + idle.toSeconds() + " s", Long.MIN_VALUE);
        }
    }

    private void beginShutdown(String reason, long rejectSentBefore) {
        if (shuttingDown.compareAndSet(false, true)) {
            threadsNamed("shutdown").newThread(() -> shutDown(reason, rejectSentBefore)).start();
        }
    }

    /**
     * The spec's termination steps: no more jobs; reply to waiting jobs if needed; wait for in-flight jobs (so for
     * the Workers' work); terminate the Workers; then clean up and terminate this Manager.
     */
    private void shutDown(String reason, long rejectSentBefore) {
        System.out.println("Manager " + managerId + " shutting down: " + reason);
        inboxThread.interrupt();
        joinQuietly(inboxThread);   // from here on, no job is accepted

        InboxDrainer drainer = new InboxDrainer(inbox, rejectSentBefore,
                (task, message) -> rejectNewTask(task, message, "the system is terminating"));
        while (jobs.size() > 0) {
            drainer.drainOnce();   // meanwhile the results loop and Finish pool complete the in-flight jobs
        }
        while (drainer.drainOnce() > 0) {
            // a last pass over what is still waiting in the inbox
        }

        synchronized (scaler) {
            fleet.terminateMyWorkers();
        }
        stopThreads();
        queues.delete(own.tasks());
        queues.delete(own.results());
        drainer.releaseHeld();   // jobs sent after the terminate, for the Manager their clients start
        System.out.println("Manager " + managerId + " shut down");
        try {
            terminateSelfWithRetries();
        } finally {
            shutDown.countDown();
        }
    }

    /** An instance left running here would bill while doing nothing, so a failed call is retried. */
    private void terminateSelfWithRetries() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                fleet.terminateSelf();
                return;
            } catch (RuntimeException e) {
                System.err.println("Could not terminate this instance (attempt " + attempt + "): " + e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void stopThreads() {
        resultsLoop.stop();
        resultsThread.interrupt();
        scheduler.shutdownNow();
        startPool.shutdownNow();
        finishPool.shutdownNow();
        joinQuietly(resultsThread);
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@code java -cp aws-distributed-ocr.jar com.bgu.ocr.manager.Manager managerId manager-clients-n}. On EC2 the
     * boot script passes the instance id as managerId (see UserData). Exits 0 after a deliberate shutdown, so the
     * boot script doesn't restart it.
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length != 2) {
            System.err.println("usage: Manager managerId manager-clients-n");
            System.exit(2);
        }
        String managerId = args[0];
        QueueAdmin queues = SqsQueueAdmin.withDefaultClient();
        Ec2Helper ec2 = Ec2Helper.withDefaultClient();
        OwnQueues own = ownQueues(queues, managerId);
        WorkerScaler scaler = new Ec2WorkerScaler(ec2, managerId, own.tasks().url(), own.results().url());

        Manager manager = new Manager(queues, S3ObjectStore.withDefaultClient(), scaler,
                new Ec2Fleet(ec2, managerId), managerId, Integer.parseInt(args[1]));
        ActiveClientsPublisher tag = new ActiveClientsPublisher(
                n -> ec2.tag(managerId, Ec2Tags.ACTIVE_CLIENTS, String.valueOf(n)));
        manager.onActiveClientsChange(tag);
        tag.accept(0);
        manager.start();
        manager.shutDown.await();
        System.exit(0);
    }
}
