package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.bgu.ocr.aws.InMemoryObjectStore;
import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;

public class StartJobTest {

    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final InMemoryQueue taskQueue = new InMemoryQueue(10);
    private final List<String> events = new ArrayList<>();   // scaler calls and completions, in order
    private final List<JobState> completed = new ArrayList<>();

    private JobState job(String input, int workerFilesN) {
        store.put("jobs/job-1/input.txt", input);
        NewTask task = new NewTask("job-1", "jobs/job-1/input.txt", "memory://done-job-1", workerFilesN);
        return new JobState(task, new ReceivedMessage(Json.toJson(task), "receipt-1", 0, 1));
    }

    private void start(JobState job) {
        WorkerScaler scaler = needed -> events.add("ensureWorkers(" + needed + ") after " + taskQueue.visibleCount() + " tasks");
        new StartJob(job, store, taskQueue, scaler, completed::add).run();
    }

    @Test
    public void sendsOneTaskPerUrlThenScales() {
        JobState job = job("http://a.png\nhttp://b.png\nhttp://a.png\n", 2);

        start(job);

        assertEquals(List.of("ensureWorkers(2) after 3 tasks"), events);   // ⌈3/2⌉ = 2, requested after sending
        List<ImageTask> sent = taskQueue.receive().stream()
                .map(m -> Json.fromJson(m.body(), ImageTask.class)).toList();
        assertEquals(3, sent.size());
        assertEquals(new ImageTask("job-1", 2, "http://a.png"), sent.get(2));
        assertFalse(job.isComplete());
        assertTrue(completed.isEmpty());
    }

    @Test
    public void expectedCountIsSetSoResultsCanBeAdded() {
        JobState job = job("http://a.png\n", 5);

        start(job);

        // would throw IllegalStateException if setExpected had not been called
        assertFalse(job.addResult(OcrResult.success(new ImageTask("job-1", 5, "x"), "ignored")));
    }

    @Test
    public void emptyInputCompletesAtOnceWithoutWorkers() {
        JobState job = job("\n\n", 2);

        start(job);

        assertEquals(List.of(job), completed);
        assertTrue(job.isComplete());
        assertTrue(events.isEmpty());
        assertEquals(0, taskQueue.visibleCount());
    }

    @Test
    public void workersNeededRoundsUp() {
        assertEquals(1, StartJob.workersNeeded(1, 10));
        assertEquals(1, StartJob.workersNeeded(10, 10));
        assertEquals(2, StartJob.workersNeeded(11, 10));
    }
}
