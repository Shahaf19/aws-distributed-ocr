package com.bgu.ocr.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test fake with the SQS behavior our code relies on: a received message is hidden (not removed) until deleted,
 * and comes back if it is never deleted. Time does not pass on its own: tests call {@link #expireInFlight()}.
 */
public final class InMemoryQueue implements MessageQueue {

    private static final int MAX_RECEIVE = 10;

    private record Stored(String body, long sentTimestamp, int receiveCount) {
    }

    private final BlockingDeque<Stored> visible = new LinkedBlockingDeque<>();
    private final Map<String, Stored> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong handles = new AtomicLong();
    private final String url;
    private final long waitMillis;

    /** Short wait instead of 20 s, so loops under test stay fast. */
    public InMemoryQueue() {
        this(50);
    }

    public InMemoryQueue(long waitMillis) {
        this("memory://queue", waitMillis);
    }

    public InMemoryQueue(String url, long waitMillis) {
        this.url = url;
        this.waitMillis = waitMillis;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public void send(String body) {
        visible.add(new Stored(body, System.currentTimeMillis(), 0));
    }

    @Override
    public void sendAll(List<String> bodies) {
        bodies.forEach(this::send);
    }

    @Override
    public List<ReceivedMessage> receive() {
        List<Stored> taken = new ArrayList<>();
        try {
            Stored first = visible.poll(waitMillis, TimeUnit.MILLISECONDS);
            if (first == null) {
                return List.of();
            }
            taken.add(first);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
        visible.drainTo(taken, MAX_RECEIVE - 1);
        return taken.stream().map(this::deliver).toList();
    }

    private ReceivedMessage deliver(Stored stored) {
        Stored delivered = new Stored(stored.body(), stored.sentTimestamp(), stored.receiveCount() + 1);
        String handle = "handle-" + handles.incrementAndGet();   // a new handle per delivery, like SQS
        inFlight.put(handle, delivered);
        return new ReceivedMessage(delivered.body(), handle, delivered.sentTimestamp(), delivered.receiveCount());
    }

    @Override
    public void delete(ReceivedMessage message) {
        inFlight.remove(message.receiptHandle());
    }

    @Override
    public void deleteAll(List<ReceivedMessage> messages) {
        messages.forEach(this::delete);
    }

    @Override
    public void extendVisibility(ReceivedMessage message, int seconds) {
        if (seconds == 0) {
            makeVisible(message.receiptHandle());
        }
        // seconds > 0: nothing to do, time never runs out in the fake
    }

    /** Simulates every visibility timeout running out, e.g. after the receiver crashed. */
    public void expireInFlight() {
        inFlight.keySet().forEach(this::makeVisible);
    }

    private void makeVisible(String handle) {
        Stored stored = inFlight.remove(handle);
        if (stored != null) {
            visible.add(stored);
        }
    }

    public int visibleCount() {
        return visible.size();
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
