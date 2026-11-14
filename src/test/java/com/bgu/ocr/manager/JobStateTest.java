package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;

public class JobStateTest {

    private static final String JOB = "job-1";

    private static JobState newJob() {
        return new JobState(new NewTask(JOB, "jobs/job-1/input.txt", "https://sqs/done", 5), "receipt-1");
    }

    private static OcrResult ok(int index) {
        return OcrResult.success(new ImageTask(JOB, index, "u" + index), "text" + index);
    }

    @Test
    public void completesWhenEveryIndexArrivesInAnyOrder() {
        JobState job = newJob();
        assertFalse(job.setExpected(3));

        assertFalse(job.addResult(ok(2)));
        assertFalse(job.addResult(ok(0)));
        assertFalse(job.isComplete());
        assertTrue(job.addResult(ok(1)));
        assertTrue(job.isComplete());
    }

    @Test
    public void resultsAreOrderedByIndex() {
        JobState job = newJob();
        job.setExpected(2);
        job.addResult(ok(1));
        job.addResult(ok(0));

        List<OcrResult> results = job.results();
        assertEquals(0, results.get(0).index());
        assertEquals(1, results.get(1).index());
    }

    @Test
    public void duplicatesAreIgnoredAndCompletionFiresOnce() {
        JobState job = newJob();
        job.setExpected(2);

        assertFalse(job.addResult(ok(0)));
        assertFalse(job.addResult(ok(0)));   // SQS duplicate must not count as the second result
        assertFalse(job.isComplete());
        assertTrue(job.addResult(ok(1)));
        assertFalse(job.addResult(ok(1)));   // late duplicate after completion must not re-trigger FinishJob
    }

    @Test
    public void errorResultCountsTowardCompletion() {
        JobState job = newJob();
        job.setExpected(1);

        assertTrue(job.addResult(OcrResult.failure(new ImageTask(JOB, 0, "u0"), "HTTP 404")));
        assertTrue(job.results().get(0).hasError());
    }

    @Test
    public void emptyInputIsCompleteImmediately() {
        JobState job = newJob();
        assertTrue(job.setExpected(0));
        assertTrue(job.isComplete());
        assertTrue(job.results().isEmpty());
    }

    @Test
    public void resultsOfOtherJobsOrOutOfRangeAreIgnored() {
        JobState job = newJob();
        job.setExpected(1);

        assertFalse(job.addResult(OcrResult.success(new ImageTask("other-job", 0, "u"), "t")));
        assertFalse(job.addResult(ok(5)));
        assertFalse(job.isComplete());
    }

    @Test(expected = IllegalStateException.class)
    public void resultBeforeExpectedIsABug() {
        newJob().addResult(ok(0));
    }

    @Test(expected = IllegalStateException.class)
    public void resultsOfIncompleteJobAreUnavailable() {
        JobState job = newJob();
        job.setExpected(2);
        job.addResult(ok(0));
        job.results();
    }
}
