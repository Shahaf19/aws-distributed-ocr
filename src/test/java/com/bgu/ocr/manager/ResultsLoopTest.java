package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;

public class ResultsLoopTest {

    private final InMemoryQueue resultsQueue = new InMemoryQueue(10);
    private final JobTable jobs = new JobTable();
    private final List<JobState> completed = new CopyOnWriteArrayList<>();
    private final ResultsLoop loop = new ResultsLoop(resultsQueue, jobs, completed::add);

    private JobState job(String jobId) {
        NewTask task = new NewTask(jobId, "in", "reply", 5);
        JobState job = new JobState(task, new ReceivedMessage(Json.toJson(task), "receipt", 0, 1));
        jobs.add(job);
        return job;
    }

    private static String result(String jobId, int index) {
        return Json.toJson(OcrResult.success(new ImageTask(jobId, index, "u" + index), "text" + index));
    }

    @Test
    public void completesOnceDespiteDuplicatesGarbageAndUnknownJobs() {
        JobState job = job("job-1");
        job.setExpected(2);
        resultsQueue.sendAll(List.of(
                result("job-1", 0),
                result("job-1", 0),          // SQS duplicate
                "not json",                  // malformed
                result("finished-job", 0),   // late duplicate of a job no longer in the table
                result("job-1", 1)));

        loop.pollOnce();

        assertEquals(List.of(job), completed);
        resultsQueue.expireInFlight();
        assertEquals("everything was handled, so everything was deleted", 0, resultsQueue.visibleCount());
    }

    @Test
    public void resultThatCannotBeAppliedIsKeptForRetry() {
        job("job-1");   // expected count not set: addResult throws
        resultsQueue.send(result("job-1", 0));

        loop.pollOnce();

        assertTrue(completed.isEmpty());
        resultsQueue.expireInFlight();
        assertEquals("not deleted, so it comes back", 1, resultsQueue.visibleCount());
    }

    @Test(timeout = 5000)
    public void runsUntilStopped() throws Exception {
        JobState job = job("job-1");
        job.setExpected(1);
        Thread thread = new Thread(loop);
        thread.start();

        resultsQueue.send(result("job-1", 0));
        while (completed.isEmpty()) {
            Thread.sleep(5);
        }
        loop.stop();
        thread.join();

        assertEquals(List.of(job), completed);
    }
}
