package com.bgu.ocr.aws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class InMemoryQueueTest {

    private final InMemoryQueue queue = new InMemoryQueue(10);

    @Test
    public void receivedMessageIsHiddenUntilDeleted() {
        queue.send("a");

        List<ReceivedMessage> got = queue.receive();
        assertEquals("a", got.get(0).body());
        assertTrue(queue.receive().isEmpty());          // hidden, not gone

        queue.delete(got.get(0));
        queue.expireInFlight();
        assertTrue(queue.receive().isEmpty());          // deleted is gone for good
    }

    @Test
    public void undeletedMessageComesBackWithHigherReceiveCount() {
        queue.send("a");
        assertEquals(1, queue.receive().get(0).receiveCount());

        queue.expireInFlight();                         // the receiver "crashed"
        ReceivedMessage again = queue.receive().get(0);
        assertEquals("a", again.body());
        assertEquals(2, again.receiveCount());
    }

    @Test
    public void visibilityZeroReturnsMessageImmediately() {
        queue.send("a");
        queue.extendVisibility(queue.receive().get(0), 0);

        assertEquals(1, queue.visibleCount());
        assertEquals(0, queue.inFlightCount());
    }

    @Test
    public void receiveReturnsAtMostTen() {
        queue.sendAll(Collections.nCopies(25, "x"));

        assertEquals(10, queue.receive().size());
        assertEquals(10, queue.receive().size());
        assertEquals(5, queue.receive().size());
    }

    @Test
    public void receiveTakesNoMoreThanAsked() {
        queue.sendAll(Collections.nCopies(5, "x"));

        assertEquals(2, queue.receive(2).size());
        assertEquals(3, queue.visibleCount());   // the rest stay available to others
    }
}
