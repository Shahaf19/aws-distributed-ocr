package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.Test;

public class ActiveClientsPublisherTest {

    @Test(timeout = 5000)
    public void lastPublishedValueIsAlwaysTheLatestCount() throws Exception {
        List<Integer> published = new CopyOnWriteArrayList<>();
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ActiveClientsPublisher publisher = new ActiveClientsPublisher(n -> {
            firstCallStarted.countDown();
            try {
                release.await();   // a slow EC2 call...
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            published.add(n);
        });

        publisher.accept(1);
        firstCallStarted.await();
        publisher.accept(2);   // ...while the count keeps changing
        publisher.accept(3);
        publisher.accept(2);
        release.countDown();

        while (published.isEmpty() || published.get(published.size() - 1) != 2) {
            Thread.sleep(5);
        }
        Thread.sleep(50);
        assertEquals("ends on the latest value, intermediate ones skipped", List.of(1, 2), published);
    }

    @Test(timeout = 5000)
    public void failedPublishIsRetriedOnTheNextChange() throws Exception {
        List<Integer> published = new CopyOnWriteArrayList<>();
        boolean[] failOnce = {true};
        ActiveClientsPublisher publisher = new ActiveClientsPublisher(n -> {
            if (failOnce[0]) {
                failOnce[0] = false;
                throw new IllegalStateException("EC2 unavailable");
            }
            published.add(n);
        });

        publisher.accept(1);
        publisher.accept(1);

        while (published.isEmpty()) {
            Thread.sleep(5);
        }
        assertEquals(List.of(1), published);
    }
}
