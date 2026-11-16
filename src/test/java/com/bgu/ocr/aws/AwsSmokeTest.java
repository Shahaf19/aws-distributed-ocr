package com.bgu.ocr.aws;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.BeforeClass;
import org.junit.Test;

import com.bgu.ocr.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the real wrappers against AWS. Skipped in normal builds; run explicitly with valid credentials:
 * {@code mvn test -Dtest=AwsSmokeTest -Dsmoke=true}. Cleans up everything it creates.
 */
public class AwsSmokeTest {

    @BeforeClass
    public static void onlyWhenAsked() {
        assumeTrue("AWS smoke test: run with -Dsmoke=true", Boolean.getBoolean("smoke"));
    }

    @Test
    public void sqsSendReceiveDelete() throws InterruptedException {
        QueueAdmin admin = QueueAdmin.withDefaultClient();
        SqsQueue queue = admin.create("ocr-smoke-" + UUID.randomUUID(), 60);   // exercises the visibility attribute
        try {
            Thread.sleep(1000);   // AWS advises a short wait before using a newly created queue

            queue.send("single");
            queue.sendAll(IntStream.range(0, 12).mapToObj(i -> "batch-" + i).toList());   // 2 batch calls

            List<ReceivedMessage> got = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 60_000;
            while (got.size() < 13 && System.currentTimeMillis() < deadline) {
                got.addAll(queue.receive());
            }
            Set<String> bodies = got.stream().map(ReceivedMessage::body).collect(Collectors.toSet());
            assertEquals(13, bodies.size());
            for (ReceivedMessage m : got) {
                assertEquals(1, m.receiveCount());
                assertTrue(m.sentTimestamp() > 0);
            }

            // visibility 0 → the message comes back, delivered a second time
            ReceivedMessage first = got.get(0);
            queue.extendVisibility(first, 0);
            ReceivedMessage again = queue.receive().get(0);
            assertEquals(first.body(), again.body());
            assertEquals(2, again.receiveCount());

            queue.deleteAll(got.subList(1, got.size()));
            queue.delete(again);
            assertTrue("queue should be empty after deletes", queue.receive().isEmpty());   // one 20 s long poll
        } finally {
            admin.delete(queue);
        }
    }

    @Test
    public void s3PutGet() {
        S3Client s3 = S3Client.builder().region(Region.of(Config.REGION)).build();
        S3ObjectStore store = new S3ObjectStore(s3, Config.BUCKET);
        store.ensureBucket();

        String key = "smoke/" + UUID.randomUUID() + ".txt";
        try {
            String text = "https://example.com/a.png\nhttps://example.com/b.png\n";
            store.put(key, text);
            assertEquals(text, store.get(key));
        } finally {
            s3.deleteObject(b -> b.bucket(Config.BUCKET).key(key));
        }
    }
}
