package com.bgu.ocr.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.bgu.ocr.aws.InMemoryQueue;
import com.bgu.ocr.aws.InMemoryQueueAdmin;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.ocr.OcrService;
import com.bgu.ocr.ocr.TestImages;

/** The Worker loop on in-memory queues, with hand-fed ImageTasks and real OCR. */
public class WorkerTest {

    private final InMemoryQueueAdmin queues = new InMemoryQueueAdmin(10);
    private final InMemoryQueue tasks = queues.create("tasks");
    private final InMemoryQueue results = queues.create("results");
    private Worker worker;

    @Before
    public void setUp() {
        assumeTrue("needs tessdata/eng.traineddata", TestImages.languageDataPresent());
        ImageSource source = url -> {
            if (url.contains("broken")) {
                throw new IOException("HTTP 404");
            }
            return TestImages.withText("HELLO");
        };
        worker = new Worker(tasks, results, new TaskProcessor(source, new OcrService(TestImages.TESSDATA), 3));
    }

    private List<OcrResult> resultsSent() {
        return results.receive().stream().map(m -> Json.fromJson(m.body(), OcrResult.class)).toList();
    }

    @Test
    public void eachTaskGetsOneResultAndIsDeleted() throws Exception {
        tasks.send(Json.toJson(new ImageTask("job-1", 0, "http://x.com/ok.png")));
        tasks.send(Json.toJson(new ImageTask("job-1", 1, "http://x.com/broken.png")));

        worker.pollOnce();   // one task per receive
        worker.pollOnce();

        List<OcrResult> sent = resultsSent();
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).text().contains("HELLO"));
        assertEquals("IOException: HTTP 404", sent.get(1).error());
        tasks.expireInFlight();
        assertEquals("both tasks deleted", 0, tasks.visibleCount());
    }

    @Test
    public void takesOneTaskAtATime() throws Exception {
        tasks.send(Json.toJson(new ImageTask("job-1", 0, "http://x.com/ok.png")));
        tasks.send(Json.toJson(new ImageTask("job-1", 1, "http://x.com/ok.png")));

        worker.pollOnce();

        assertEquals("the other task stays available to other Workers", 1, tasks.visibleCount());
    }

    @Test
    public void taskIsKeptWhenTheResultCannotBeSent() throws Exception {
        tasks.send(Json.toJson(new ImageTask("job-1", 0, "http://x.com/ok.png")));
        queues.delete(results);   // sending the result will fail

        try {
            worker.pollOnce();
        } catch (RuntimeException expected) {
            // the loop in run() logs it and carries on
        }

        tasks.expireInFlight();
        assertEquals("not deleted, so another Worker can redo it", 1, tasks.visibleCount());
    }

    @Test
    public void malformedTaskIsDeletedWithoutAResult() throws Exception {
        tasks.send("not json");

        worker.pollOnce();

        assertTrue(resultsSent().isEmpty());
        tasks.expireInFlight();
        assertEquals(0, tasks.visibleCount());
    }

    @Test(timeout = 5000)
    public void runsUntilInterrupted() throws Exception {
        Thread thread = new Thread(worker);
        thread.start();
        tasks.send(Json.toJson(new ImageTask("job-1", 0, "http://x.com/ok.png")));

        List<OcrResult> sent = List.of();
        while (sent.isEmpty()) {
            sent = resultsSent();
        }
        thread.interrupt();
        thread.join();

        assertEquals(0, sent.get(0).index());
    }
}
