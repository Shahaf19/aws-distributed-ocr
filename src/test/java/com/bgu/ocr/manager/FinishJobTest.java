package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.bgu.ocr.aws.InMemoryObjectStore;
import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.InMemoryQueueAdmin;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.messages.Summary;

public class FinishJobTest {

    private final InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final JobTable jobs = new JobTable();
    private InMemoryQueue inbox;
    private InMemoryQueue reply;
    private JobState job;

    /** A complete 2-image job whose NewTask is in flight in the inbox, as it is while the Manager works on it. */
    @Before
    public void setUp() {
        inbox = queues.create("inbox");
        reply = queues.create("done-job-1");
        NewTask task = new NewTask("job-1", "jobs/job-1/input.txt", reply.url(), 5);
        inbox.send(Json.toJson(task));
        ReceivedMessage delivery = inbox.receive().get(0);

        job = new JobState(task, delivery);
        job.setExpected(2);
        job.addResult(OcrResult.failure(new ImageTask("job-1", 1, "http://b.png"), "HTTP 404"));
        job.addResult(OcrResult.success(new ImageTask("job-1", 0, "http://a.png"), "hello"));
        jobs.add(job);
    }

    private void finish(ObjectStore withStore) {
        new FinishJob(job, withStore, queues, inbox, jobs).run();
    }

    @Test
    public void publishesSummaryRepliesThenDeletesTheNewTask() {
        finish(store);

        List<OcrResult> summary = Summary.fromJson(store.get("jobs/job-1/summary.json"));
        assertEquals(List.of(0, 1), summary.stream().map(OcrResult::index).toList());

        JobDone done = Json.fromJson(reply.receive().get(0).body(), JobDone.class);
        assertEquals(JobDone.completed("job-1", "jobs/job-1/summary.json"), done);

        inbox.expireInFlight();
        assertEquals("NewTask deleted, so it can never come back", 0, inbox.visibleCount());
        assertEquals(0, jobs.size());
    }

    @Test
    public void clientAlreadyGoneStillDeletesTheNewTask() {
        queues.delete(reply);

        finish(store);

        inbox.expireInFlight();
        assertEquals(0, inbox.visibleCount());
        assertEquals(0, jobs.size());
    }

    @Test
    public void failureLeavesTheNewTaskForRecovery() {
        ObjectStore brokenS3 = new ObjectStore() {
            @Override
            public void put(String key, String text) {
                throw new IllegalStateException("S3 unavailable");
            }

            @Override
            public String get(String key) {
                throw new IllegalStateException("S3 unavailable");
            }
        };

        finish(brokenS3);

        assertEquals("job leaves the table, so the heartbeat stops", 0, jobs.size());
        assertTrue("no JobDone sent", reply.receive().isEmpty());
        inbox.expireInFlight();
        assertEquals("NewTask comes back to be redone", 1, inbox.visibleCount());
    }
}
