package com.bgu.ocr.localapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.InMemoryObjectStore;
import com.bgu.ocr.aws.InMemoryQueueAdmin;
import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.InboxMessage;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.messages.Summary;
import com.bgu.ocr.messages.Terminate;

/** The whole Local App flow against in-memory queues/storage and a fake Manager thread. */
public class LocalAppTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
    private final InMemoryObjectStore store = new InMemoryObjectStore();
    private final AtomicInteger managerChecks = new AtomicInteger();

    private LocalApp app;
    private Path input;
    private Path output;

    @Before
    public void setUp() throws Exception {
        app = new LocalApp(queues, store, n -> managerChecks.incrementAndGet(), Duration.ofMinutes(1));
        input = tmp.newFile("input.txt").toPath();
        Files.writeString(input, "http://x.com/a.png\nhttp://x.com/b.png\nhttp://x.com/a.png\n");
        output = tmp.getRoot().toPath().resolve("output.html");
    }

    private LocalAppArgs args(String... extra) {
        String[] base = {input.toString(), output.toString(), "2", "10"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return LocalAppArgs.parse(all);
    }

    /** Answers the first NewTask like a Manager would: "fake text i" for line i, or a rejection. */
    private void startFakeManager(boolean reject) {
        Thread manager = new Thread(() -> {
            MessageQueue inbox = queues.create(Config.INBOX_QUEUE);
            while (true) {
                for (ReceivedMessage m : inbox.receive()) {
                    if (Json.fromJson(m.body(), InboxMessage.class) instanceof NewTask task) {
                        inbox.delete(m);
                        queues.open(task.replyQueueUrl()).send(Json.toJson(answer(task, reject)));
                        return;
                    }
                }
            }
        });
        manager.setDaemon(true);
        manager.start();
    }

    private JobDone answer(NewTask task, boolean reject) {
        if (reject) {
            return JobDone.rejected(task.jobId(), "system is terminating");
        }
        String[] urls = store.get(task.inputS3Key()).split("\n");
        List<OcrResult> results = IntStream.range(0, urls.length)
                .mapToObj(i -> OcrResult.success(new ImageTask(task.jobId(), i, urls[i]), "fake text " + i))
                .toList();
        String key = "jobs/" + task.jobId() + "/summary.json";
        store.put(key, Summary.toJson(results));
        return JobDone.completed(task.jobId(), key);
    }

    private List<ReceivedMessage> inboxLeftovers() {
        return queues.create(Config.INBOX_QUEUE).receive();
    }

    @Test(timeout = 5000)
    public void completedJobWritesHtmlAndCleansUp() throws Exception {
        startFakeManager(false);

        JobDone done = app.run(args());

        assertFalse(done.wasRejected());
        String html = Files.readString(output);
        assertEquals(3, html.split("<img").length - 1);
        assertTrue(html.indexOf("fake text 0") < html.indexOf("fake text 1"));
        assertTrue(html.indexOf("fake text 1") < html.indexOf("fake text 2"));
        assertFalse("reply queue must be deleted", queues.exists("done-" + done.jobId()));
        assertEquals(1, managerChecks.get());
        assertTrue("no terminate without the argument", inboxLeftovers().isEmpty());
    }

    @Test(timeout = 5000)
    public void rejectedJobWritesNoOutput() throws Exception {
        startFakeManager(true);

        JobDone done = app.run(args());

        assertTrue(done.wasRejected());
        assertFalse(Files.exists(output));
        assertFalse(queues.exists("done-" + done.jobId()));
    }

    @Test(timeout = 5000)
    public void terminateArgumentSendsTerminateAfterTheJob() throws Exception {
        startFakeManager(false);

        app.run(args("terminate"));

        List<ReceivedMessage> left = inboxLeftovers();
        assertEquals(1, left.size());
        assertTrue(Json.fromJson(left.get(0).body(), InboxMessage.class) instanceof Terminate);
    }
}
