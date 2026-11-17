package com.bgu.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.bgu.ocr.aws.InMemoryObjectStore;
import com.bgu.ocr.aws.InMemoryQueueAdmin;
import com.bgu.ocr.localapp.LocalApp;
import com.bgu.ocr.localapp.LocalAppArgs;
import com.bgu.ocr.manager.FakeWorker;
import com.bgu.ocr.manager.Manager;
import com.bgu.ocr.messages.JobDone;

/**
 * The spec's "run the manager, the local application, together with a worker on your computer": the real
 * Local App and Manager, a fake Worker, and in-memory queues/storage instead of AWS.
 */
public class SystemTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test(timeout = 10000)
    public void localAppGetsItsHtmlThroughTheManager() throws Exception {
        InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
        InMemoryObjectStore store = new InMemoryObjectStore();
        Manager manager = new Manager(queues, store, needed -> { }, "m1", 2);
        manager.start();
        FakeWorker worker = new FakeWorker(queues.create(Manager.taskQueueName("m1")),
                queues.create(Manager.resultsQueueName("m1"))).startInBackground();

        Path input = tmp.newFile("input.txt").toPath();
        Files.writeString(input, "http://x.com/a.png\nhttp://x.com/b.png\n\nhttp://x.com/a.png\n");
        Path output = tmp.getRoot().toPath().resolve("output.html");
        try {
            JobDone done = new LocalApp(queues, store, n -> { }, Duration.ofMinutes(1))
                    .run(LocalAppArgs.parse(input.toString(), output.toString(), "2", "2"));

            assertFalse(done.wasRejected());
            String html = Files.readString(output);
            assertEquals("one entry per non-blank line", 3, html.split("<img").length - 1);
        } finally {
            worker.stop();
            manager.stop();
        }
    }
}
