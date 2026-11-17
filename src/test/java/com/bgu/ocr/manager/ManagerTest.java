package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** The whole Manager on in-memory queues/storage, with fake Workers. */
public class ManagerTest {

    private final InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final InMemoryQueue inbox = queues.create(Config.INBOX_QUEUE);
    private final List<Integer> scalerRequests = new CopyOnWriteArrayList<>();
    private final List<Manager> managers = new ArrayList<>();
    private final List<FakeWorker> workers = new ArrayList<>();

    private Manager startManager(String id) {
        return startManager(id, store);
    }

    private Manager startManager(String id, ObjectStore withStore) {
        Manager manager = new Manager(queues, withStore, scalerRequests::add, id, 2);
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
    public void missingInputIsRejectedInsteadOfLeavingTheClientWaiting() {
        startManager("m1");
        InMemoryQueue reply = queues.create("done-job-1");
        inbox.send(Json.toJson(new NewTask("job-1", "jobs/job-1/missing.txt", reply.url(), 2)));

        JobDone done = awaitDone(reply);

        assertTrue(done.wasRejected());
        assertEquals("NewTask deleted: retrying can't help", 0, inbox.inFlightCount() + inbox.visibleCount());
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
}
