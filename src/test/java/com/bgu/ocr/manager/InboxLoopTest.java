package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.Terminate;

public class InboxLoopTest {

    private final InMemoryQueue inbox = new InMemoryQueue(10);
    private final JobTable jobs = new JobTable();
    private final List<JobState> started = new CopyOnWriteArrayList<>();
    private final List<ReceivedMessage> terminates = new CopyOnWriteArrayList<>();

    private InboxLoop loop(int managerClientsN) {
        return new InboxLoop(inbox, jobs, managerClientsN, started::add, terminates::add);
    }

    private void sendNewTask(String jobId) {
        inbox.send(Json.toJson(new NewTask(jobId, "jobs/" + jobId + "/input.txt", "reply-" + jobId, 5)));
    }

    @Test
    public void neverTakesMoreJobsThanItHasRoomFor() throws Exception {
        sendNewTask("a");
        sendNewTask("b");
        sendNewTask("c");

        loop(2).pollOnce();

        assertEquals(2, started.size());
        assertEquals(2, jobs.size());
        assertEquals("the third stays in the inbox for another Manager", 1, inbox.visibleCount());
        assertEquals("accepted NewTasks stay in flight as crash backups", 2, inbox.inFlightCount());
    }

    @Test(timeout = 5000)
    public void finishingAJobWakesTheLoopForTheNext() throws Exception {
        sendNewTask("a");
        sendNewTask("b");
        Thread thread = new Thread(loop(1));
        thread.start();

        while (started.size() < 1) {
            Thread.sleep(5);
        }
        Thread.sleep(50);
        assertEquals("full: the second must wait", 1, started.size());

        jobs.remove(started.get(0));   // what FinishJob does at the end
        while (started.size() < 2) {
            Thread.sleep(5);
        }
        assertEquals("b", started.get(1).jobId());

        thread.interrupt();
        thread.join();
    }

    @Test
    public void redeliveryOfARunningJobSwitchesToTheNewHandle() throws Exception {
        sendNewTask("a");
        InboxLoop loop = loop(2);
        loop.pollOnce();
        String firstHandle = started.get(0).inboxMessage().receiptHandle();

        inbox.expireInFlight();   // we "stalled" past the visibility timeout
        loop.pollOnce();

        assertEquals("not started twice", 1, started.size());
        assertNotEquals(firstHandle, started.get(0).inboxMessage().receiptHandle());
        assertEquals(2, started.get(0).inboxMessage().receiveCount());
    }

    @Test
    public void malformedMessageIsDeleted() throws Exception {
        inbox.send("not json");

        loop(2).pollOnce();

        inbox.expireInFlight();
        assertEquals(0, inbox.visibleCount());
        assertTrue(started.isEmpty());
    }

    @Test
    public void terminateGoesToItsHandler() throws Exception {
        inbox.send(Json.toJson(new Terminate()));

        loop(2).pollOnce();

        assertEquals(1, terminates.size());
        assertTrue(started.isEmpty());
    }
}
