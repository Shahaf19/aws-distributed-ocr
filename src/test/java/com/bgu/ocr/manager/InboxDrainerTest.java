package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.Terminate;

public class InboxDrainerTest {

    private final InMemoryQueue inbox = new InMemoryQueue(10);
    private final List<String> rejected = new ArrayList<>();

    private InboxDrainer drainer(long rejectSentBefore) {
        return new InboxDrainer(inbox, rejectSentBefore, (task, message) -> {
            rejected.add(task.jobId());
            inbox.delete(message);
        });
    }

    private void sendNewTask(String jobId) {
        inbox.send(Json.toJson(new NewTask(jobId, "in", "reply", 2)));
    }

    @Test
    public void jobsSentBeforeTerminateAreRejectedLaterOnesHeldThenReleased() throws Exception {
        sendNewTask("early");
        Thread.sleep(5);
        long terminateSentAt = System.currentTimeMillis();
        Thread.sleep(5);
        sendNewTask("late");
        InboxDrainer drainer = drainer(terminateSentAt);

        while (drainer.drainOnce() > 0) {
            // drain until nothing visible is left
        }

        assertEquals(List.of("early"), rejected);
        assertEquals("the late job is hidden while held", 0, inbox.visibleCount());

        drainer.releaseHeld();

        ReceivedMessage again = inbox.receive().get(0);
        assertEquals("late", Json.fromJson(again.body(), NewTask.class).jobId());
    }

    @Test
    public void idleShutdownRejectsNothing() {
        sendNewTask("a");
        InboxDrainer drainer = drainer(Long.MIN_VALUE);

        drainer.drainOnce();
        drainer.releaseHeld();

        assertEquals(List.of(), rejected);
        assertEquals("released for another Manager", 1, inbox.visibleCount());
    }

    @Test
    public void extraTerminatesAndGarbageAreDeleted() {
        inbox.send(Json.toJson(new Terminate()));
        inbox.send("not json");
        InboxDrainer drainer = drainer(Long.MAX_VALUE);

        drainer.drainOnce();
        inbox.expireInFlight();

        assertEquals(0, inbox.visibleCount());
    }
}
