package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.InMemoryObjectStore;
import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.InMemoryQueueAdmin;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.messages.Summary;
import com.bgu.ocr.messages.Terminate;

/** The whole Manager on in-memory queues/storage, with fake Workers. */
public class ManagerTest {

    private final InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final InMemoryQueue inbox = queues.create(Config.INBOX_QUEUE);
    private final List<Integer> scalerRequests = new CopyOnWriteArrayList<>();
    private final List<Manager> managers = new ArrayList<>();
    private final List<FakeWorker> workers = new ArrayList<>();
    private final FakeFleet fleet = new FakeFleet();

    /** Lifecycle checks are rare and idle limits long here, so they never interfere with the job tests. */
    private static final Manager.Timings QUIET =
            new Manager.Timings(Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(1));

    /** Fast checks, for the terminate tests. */
    private static final Manager.Timings ALERT =
            new Manager.Timings(Duration.ofMillis(20), Duration.ofHours(1), Duration.ofHours(1));

    private Manager startManager(String id) {
        return startManager(id, store, 2, QUIET);
    }

    private Manager startManager(String id, ObjectStore withStore) {
        return startManager(id, withStore, 2, QUIET);
    }

    private Manager startManager(String id, ObjectStore withStore, int managerClientsN, Manager.Timings timings) {
        Manager manager = new Manager(queues, withStore, scalerRequests::add, fleet.forManager(id), id,
                managerClientsN, timings, () -> { });
        manager.start();
        managers.add(manager);
        return manager;
    }

    private void startWorkerFor(String managerId) {
        workers.add(new FakeWorker(queues.create(Manager.taskQueueName(managerId)),
                queues.create(Manager.resultsQueueName(managerId))).startInBackground());
    }

    /** What a Local App does: input to the store, own reply queue, NewTask to the inbox. */
    private InMemoryQueue submit(String jobId, String... urls) {
        String key = "jobs/" + jobId + "/input.txt";
        store.put(key, String.join("\n", urls));
        InMemoryQueue reply = queues.create("done-" + jobId);
        inbox.send(Json.toJson(new NewTask(jobId, key, reply.url(), 2)));
        return reply;
    }

    private static JobDone awaitDone(InMemoryQueue reply) {
        while (true) {
            List<ReceivedMessage> got = reply.receive();
            if (!got.isEmpty()) {
                return Json.fromJson(got.get(0).body(), JobDone.class);
            }
        }
    }

    /** Like awaitDone, but lets "time pass": every in-flight inbox message's visibility keeps running out. */
    private JobDone awaitDoneWhileTimePasses(InMemoryQueue reply) {
        while (true) {
            inbox.expireInFlight();
            List<ReceivedMessage> got = reply.receive();
            if (!got.isEmpty()) {
                return Json.fromJson(got.get(0).body(), JobDone.class);
            }
        }
    }

    /** The real store, except that reading inputs fails {@code failures} times with a temporary-looking error. */
    private ObjectStore flakyStore(int failures) {
        AtomicInteger left = new AtomicInteger(failures);
        return new ObjectStore() {
            @Override
            public void put(String key, String text) {
                store.put(key, text);
            }

            @Override
            public String get(String key) {
                if (key.endsWith("input.txt") && left.getAndDecrement() > 0) {
                    throw new IllegalStateException("S3 temporarily unavailable");
                }
                return store.get(key);
            }
        };
    }

    private List<OcrResult> summaryOf(JobDone done) {
        assertFalse("expected completed, got rejected: " + done.rejected(), done.wasRejected());
        return Summary.fromJson(store.get(done.summaryS3Key()));
    }

    @After
    public void tearDown() throws Exception {
        workers.forEach(FakeWorker::stop);
        for (Manager manager : managers) {
            manager.stop();
        }
    }

    @Test(timeout = 5000)
    public void oneJobEndToEnd() {
        startManager("m1");
        startWorkerFor("m1");

        JobDone done = awaitDone(submit("job-1", "http://a.png", "http://b.png", "http://a.png"));

        List<OcrResult> results = summaryOf(done);
        assertEquals(List.of("fake text 0", "fake text 1", "fake text 2"),
                results.stream().map(OcrResult::text).toList());
        assertEquals("⌈3/2⌉ Workers requested", List.of(2), scalerRequests);
        assertEquals("NewTask deleted after the job", 0, inbox.inFlightCount() + inbox.visibleCount());
    }

    @Test(timeout = 5000)
    public void twoJobsAtOnceDoNotMix() {
        startManager("m1");
        startWorkerFor("m1");

        InMemoryQueue replyA = submit("job-a", "http://a1.png", "http://a2.png");
        InMemoryQueue replyB = submit("job-b", "http://b1.png", "http://b2.png", "http://b3.png");

        List<OcrResult> a = summaryOf(awaitDone(replyA));
        List<OcrResult> b = summaryOf(awaitDone(replyB));
        assertEquals(2, a.size());
        assertEquals(3, b.size());
        assertTrue(a.stream().allMatch(r -> r.jobId().equals("job-a") && r.imageUrl().startsWith("http://a")));
        assertTrue(b.stream().allMatch(r -> r.jobId().equals("job-b") && r.imageUrl().startsWith("http://b")));
    }

    @Test(timeout = 5000)
    public void anotherManagerRecoversTheJobOfADeadOne() throws Exception {
        Manager dying = startManager("m1");   // no Worker: the job can't finish here
        InMemoryQueue reply = submit("job-1", "http://a.png", "http://b.png");
        while (inbox.inFlightCount() == 0) {
            Thread.sleep(5);   // wait until m1 has accepted the job
        }

        dying.stop();             // m1 "dies": its heartbeat stops...
        inbox.expireInFlight();   // ...so the NewTask's visibility runs out
        startManager("m2");
        startWorkerFor("m2");

        assertEquals(2, summaryOf(awaitDone(reply)).size());
    }

    @Test(timeout = 5000)
    public void replacesLastWorkerWhileDrainingAnAcceptedJob() throws Exception {
        AtomicBoolean workerRunning = new AtomicBoolean();
        CountDownLatch originalWorker = new CountDownLatch(1);
        CountDownLatch replacementWorker = new CountDownLatch(1);
        WorkerScaler scaler = needed -> {
            if (workerRunning.compareAndSet(false, true)) {
                if (originalWorker.getCount() == 0) {
                    replacementWorker.countDown();
                } else {
                    originalWorker.countDown();
                }
            }
        };
        Manager manager = new Manager(queues, store, scaler, fleet.forManager("m1"), "m1", 2, ALERT, () -> { });
        manager.start();
        managers.add(manager);

        InMemoryQueue reply = submit("job-1", "http://a.png");
        InMemoryQueue tasks = queues.create(Manager.taskQueueName("m1"));
        assertTrue("original Worker requested", originalWorker.await(1, TimeUnit.SECONDS));
        while (tasks.receive(1).isEmpty()) {
            Thread.sleep(5);
        }

        inbox.send(Json.toJson(new Terminate()));
        while (!fleet.events().contains("m1 marked all Managers terminating")) {
            Thread.sleep(5);
        }
        workerRunning.set(false);
        tasks.expireInFlight();

        assertTrue("replacement Worker requested", replacementWorker.await(1, TimeUnit.SECONDS));
        startWorkerFor("m1");
        assertEquals(1, summaryOf(awaitDone(reply)).size());
        assertTrue(manager.awaitShutdown(2, TimeUnit.SECONDS));
    }

    @Test(timeout = 5000)
    public void missingInputIsRejectedInsteadOfLeavingTheClientWaiting() {
        startManager("m1");
        InMemoryQueue reply = queues.create("done-job-1");
        inbox.send(Json.toJson(new NewTask("job-1", "jobs/job-1/missing.txt", reply.url(), 2)));

        JobDone done = awaitDone(reply);

        assertTrue(done.wasRejected());
        assertEquals("NewTask deleted: retrying can't help", 0, inbox.inFlightCount() + inbox.visibleCount());
    }

    @Test(timeout = 5000)
    public void aDyingThreadShutsTheManagerDownInsteadOfLeavingAZombie() throws Exception {
        CountDownLatch fatal = new CountDownLatch(1);
        ObjectStore failingHard = new ObjectStore() {
            @Override
            public void put(String key, String text) {
                store.put(key, text);
            }

            @Override
            public String get(String key) {
                throw new AssertionError("simulated JVM-level failure");   // an Error, not an Exception
            }
        };
        Manager manager = new Manager(queues, failingHard, scalerRequests::add, fleet.forManager("m1"), "m1", 2,
                QUIET, fatal::countDown);
        manager.start();
        managers.add(manager);

        submit("job-1", "http://a.png");

        fatal.await();   // the Start pool thread died, so the Manager asked to exit
    }

    @Test(timeout = 5000)
    public void temporaryStartFailureIsRetriedNotRejected() {
        startManager("m1", flakyStore(1));
        startWorkerFor("m1");

        JobDone done = awaitDoneWhileTimePasses(submit("job-1", "http://a.png", "http://b.png"));

        assertEquals(2, summaryOf(done).size());
    }

    @Test(timeout = 5000)
    public void startThatKeepsFailingIsRejectedAfterMaxAttempts() {
        startManager("m1", flakyStore(Integer.MAX_VALUE));

        JobDone done = awaitDoneWhileTimePasses(submit("job-1", "http://a.png"));

        assertTrue(done.wasRejected());
        assertTrue(done.rejected(), done.rejected().contains("after " + Config.MAX_START_ATTEMPTS + " attempts"));
    }

    // ---- terminate and idleness ----

    @Test(timeout = 10000)
    public void terminateLetsTheInFlightJobFinishThenShutsEverythingDown() throws Exception {
        Manager m1 = startManager("m1", store, 2, ALERT);
        startWorkerFor("m1");
        InMemoryQueue reply = submit("job-1", "http://a.png", "http://b.png");
        inbox.send(Json.toJson(new Terminate()));

        assertEquals("the in-flight job still completes", 2, summaryOf(awaitDone(reply)).size());
        assertTrue(m1.awaitShutdown(5, TimeUnit.SECONDS));
        assertTrue(fleet.events().toString(), fleet.events().containsAll(List.of(
                "m1 marked all Managers terminating", "m1 terminated its Workers", "m1 terminated itself")));
        assertFalse("own queues deleted", queues.exists(Manager.taskQueueName("m1")));
        assertFalse(queues.exists(Manager.resultsQueueName("m1")));
    }

    @Test(timeout = 10000)
    public void everyManagerShutsDownNotOnlyTheOneThatGotTheMessage() throws Exception {
        Manager m1 = startManager("m1", store, 1, ALERT);
        Manager m2 = startManager("m2", store, 1, ALERT);

        inbox.send(Json.toJson(new Terminate()));   // only one of them can receive it

        assertTrue(m1.awaitShutdown(5, TimeUnit.SECONDS));
        assertTrue(m2.awaitShutdown(5, TimeUnit.SECONDS));
        assertTrue(fleet.events().toString(),
                fleet.events().containsAll(List.of("m1 terminated itself", "m2 terminated itself")));
    }

    @Test(timeout = 10000)
    public void waitingJobsAreRejectedIfSentBeforeTheTerminateAndLeftForANewManagerIfAfter() throws Exception {
        Manager m1 = startManager("m1", store, 1, ALERT);   // room for one job, and no Worker yet
        InMemoryQueue replyA = submit("job-a", "http://a.png");
        while (inbox.inFlightCount() == 0) {
            Thread.sleep(5);   // m1 accepted job a and is now full
        }
        InMemoryQueue replyB = submit("job-b", "http://b.png");   // waits in the inbox
        Thread.sleep(5);
        fleet.requestTermination(System.currentTimeMillis());   // as if another Manager got the terminate
        Thread.sleep(5);
        submit("job-c", "http://c.png");   // sent after the terminate

        assertTrue("waiting and sent before: rejected (the spec's response message)", awaitDone(replyB).wasRejected());
        startWorkerFor("m1");   // only now: earlier, job a could finish first and m1 would rightly accept job b
        assertFalse("in flight: completes", awaitDone(replyA).wasRejected());
        assertTrue(m1.awaitShutdown(5, TimeUnit.SECONDS));
        assertEquals("job c was released for a new Manager", 1, inbox.visibleCount());
    }

    @Test(timeout = 10000)
    public void idleManagerStopsItsWorkersThenItself() throws Exception {
        Manager m1 = startManager("m1", store, 2,
                new Manager.Timings(Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(300)));

        assertTrue(m1.awaitShutdown(5, TimeUnit.SECONDS));
        assertEquals(List.of("m1 terminated its Workers", "m1 marked itself terminating",
                "m1 terminated its Workers", "m1 terminated itself"), fleet.events());
        assertFalse(queues.exists(Manager.taskQueueName("m1")));
    }
}
