package com.bgu.ocr.aws;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test fake for {@link QueueAdmin}: queues live in a map keyed by URL, so {@link #open} returns the same queue object
 * {@link #create} made. A fake Manager can therefore reach a Local App's reply queue through the URL in its NewTask.
 */
public final class InMemoryQueueAdmin implements QueueAdmin {

    private final Map<String, InMemoryQueue> queues = new ConcurrentHashMap<>();
    private final long waitMillis;

    public InMemoryQueueAdmin() {
        this(50);
    }

    public InMemoryQueueAdmin(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    @Override
    public InMemoryQueue create(String name) {
        return queues.computeIfAbsent(urlOf(name), url -> new InMemoryQueue(url, waitMillis));
    }

    /** Visibility is not simulated by time in the fake (see {@link InMemoryQueue#expireInFlight()}). */
    @Override
    public InMemoryQueue create(String name, int visibilityTimeoutSeconds) {
        return create(name);
    }

    @Override
    public InMemoryQueue open(String url) {
        InMemoryQueue queue = queues.get(url);
        if (queue == null) {
            throw new NoSuchElementException("no queue " + url);
        }
        return queue;
    }

    @Override
    public void delete(MessageQueue queue) {
        queues.remove(queue.url());
    }

    public boolean exists(String name) {
        return queues.containsKey(urlOf(name));
    }

    private static String urlOf(String name) {
        return "memory://" + name;
    }
}
