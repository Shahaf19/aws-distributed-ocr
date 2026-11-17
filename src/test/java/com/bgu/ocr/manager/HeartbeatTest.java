package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;

public class HeartbeatTest {

    private final InMemoryQueue inbox = new InMemoryQueue(10);
    private final JobTable jobs = new JobTable();
    private final Heartbeat heartbeat = new Heartbeat(inbox, jobs, 180);

    /** A job whose NewTask was received from the inbox and is in flight, as in the real Manager. */
    private JobState inFlightJob(String jobId) {
        NewTask task = new NewTask(jobId, "in", "reply", 5);
        inbox.send(Json.toJson(task));
        JobState job = new JobState(task, inbox.receive(1).get(0));
        jobs.add(job);
        return job;
    }

    @Test
    public void extendsEveryJobInTheTable() {
        inFlightJob("a");
        inFlightJob("b");

        heartbeat.run();

        assertEquals(List.of(180, 180), inbox.extensions());
    }

    @Test
    public void finishedJobIsNoLongerExtended() {
        JobState a = inFlightJob("a");
        inFlightJob("b");
        jobs.remove(a);   // FinishJob removed it

        heartbeat.run();

        assertEquals(List.of(180), inbox.extensions());
    }

    @Test
    public void oneFailingJobDoesNotStopTheOthers() {
        NewTask stale = new NewTask("stale", "in", "reply", 5);
        jobs.add(new JobState(stale, new ReceivedMessage(Json.toJson(stale), "no-longer-valid", 0, 1)));
        inFlightJob("b");

        heartbeat.run();   // must not throw

        assertEquals("job b was still extended", List.of(180), inbox.extensions());
    }
}
