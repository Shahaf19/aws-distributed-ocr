package com.bgu.ocr.messages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class MessagesTest {

    private static final ImageTask TASK = new ImageTask("job-1", 3, "https://example.com/a.png");

    private static <T> T roundTrip(Object message, Class<T> type) {
        return Json.fromJson(Json.toJson(message), type);
    }

    // ---- Group 1: every message survives JSON both ways ----

    @Test
    public void newTaskRoundTripsThroughInbox() {
        NewTask task = new NewTask("job-1", "jobs/job-1/input.txt", "https://sqs/reply", 5);

        String json = Json.toJson(task);
        assertTrue(json, json.contains("\"type\":\"newTask\""));
        assertEquals(task, Json.fromJson(json, InboxMessage.class));
    }

    @Test
    public void terminateRoundTripsThroughInbox() {
        String json = Json.toJson(new Terminate());

        assertEquals("{\"type\":\"terminate\"}", json);
        assertTrue(Json.fromJson(json, InboxMessage.class) instanceof Terminate);
    }

    @Test
    public void imageTaskRoundTrips() {
        assertEquals(TASK, roundTrip(TASK, ImageTask.class));
    }

    @Test
    public void successResultOmitsErrorField() {
        OcrResult result = OcrResult.success(TASK, "hello");

        String json = Json.toJson(result);
        assertFalse(json, json.contains("error"));
        assertEquals(result, Json.fromJson(json, OcrResult.class));
    }

    @Test
    public void failureResultRoundTrips() {
        OcrResult result = OcrResult.failure(TASK, "HTTP 404");

        OcrResult parsed = roundTrip(result, OcrResult.class);
        assertEquals(result, parsed);
        assertTrue(parsed.hasError());
    }

    @Test
    public void jobDoneRoundTripsBothForms() {
        JobDone completed = JobDone.completed("job-1", "jobs/job-1/summary.json");
        JobDone rejected = JobDone.rejected("job-1", "system is terminating");

        assertEquals(completed, roundTrip(completed, JobDone.class));
        assertEquals(rejected, roundTrip(rejected, JobDone.class));
        assertTrue(roundTrip(rejected, JobDone.class).wasRejected());
    }

    @Test
    public void summaryRoundTripKeepsOrder() {
        List<OcrResult> results = List.of(
                OcrResult.success(new ImageTask("job-1", 0, "u0"), "t0"),
                OcrResult.failure(new ImageTask("job-1", 1, "u1"), "timeout"),
                OcrResult.success(new ImageTask("job-1", 2, "u2"), "t2"));

        assertEquals(results, Summary.fromJson(Summary.toJson(results)));
    }

    // ---- Group 2: tolerate change ----

    @Test
    public void unknownFieldsAreIgnored() {
        String json = "{\"jobId\":\"job-1\",\"index\":3,\"imageUrl\":\"https://example.com/a.png\",\"addedLater\":42}";

        assertEquals(TASK, Json.fromJson(json, ImageTask.class));
    }

    // ---- Group 3: broken messages raise MalformedMessageException ----

    @Test(expected = MalformedMessageException.class)
    public void missingRequiredFieldIsMalformed() {
        Json.fromJson("{\"index\":3,\"imageUrl\":\"u\"}", ImageTask.class);
    }

    @Test(expected = MalformedMessageException.class)
    public void resultWithBothTextAndErrorIsMalformed() {
        Json.fromJson("{\"jobId\":\"j\",\"index\":0,\"imageUrl\":\"u\",\"text\":\"t\",\"error\":\"e\"}", OcrResult.class);
    }

    @Test(expected = MalformedMessageException.class)
    public void nonJsonIsMalformed() {
        Json.fromJson("this is not json", ImageTask.class);
    }

    @Test(expected = MalformedMessageException.class)
    public void unknownInboxTypeIsMalformed() {
        Json.fromJson("{\"type\":\"reboot\"}", InboxMessage.class);
    }

    @Test(expected = MalformedMessageException.class)
    public void brokenSummaryIsMalformed() {
        Summary.fromJson("[{\"jobId\":\"j\",\"index\":0,\"imageUrl\":\"u\"}]");   // neither text nor error
    }
}
