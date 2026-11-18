package com.bgu.ocr.manager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Keeps the Manager's activeClients tag current, so Local Apps can tell whether it has room. Changes are published
 * by one background thread that always sends the latest count: two quick changes can't land out of order, and the
 * caller (e.g. the inbox loop) never waits for an EC2 call.
 */
public final class ActiveClientsPublisher implements IntConsumer {

    private final IntConsumer publish;
    private final AtomicInteger latest = new AtomicInteger(-1);
    private final ExecutorService thread = Executors.newSingleThreadExecutor(work -> {
        Thread t = new Thread(work, "active-clients-tag");
        t.setDaemon(true);
        return t;
    });
    private int published = -1;   // only touched by the single thread

    /** @param publish the actual update, e.g. an EC2 CreateTags call for this Manager's instance */
    public ActiveClientsPublisher(IntConsumer publish) {
        this.publish = publish;
    }

    @Override
    public void accept(int activeClients) {
        latest.set(activeClients);
        thread.execute(this::publishLatest);
    }

    private void publishLatest() {
        int value = latest.get();
        if (value == published) {
            return;   // an earlier run already sent it
        }
        try {
            publish.accept(value);
            published = value;
        } catch (RuntimeException e) {
            System.err.println("Could not publish activeClients=" + value + ": " + e);
        }
    }
}
