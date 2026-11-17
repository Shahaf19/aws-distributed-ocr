package com.bgu.ocr.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.ocr.OcrService;
import com.bgu.ocr.ocr.TestImages;

public class TaskProcessorTest {

    private static final ImageTask TASK = new ImageTask("job-1", 4, "http://x.com/a.png");
    private final OcrService ocr = new OcrService(TestImages.TESSDATA);

    @Test
    public void downloadedImageIsRecognized() throws Exception {
        assumeTrue("needs tessdata/eng.traineddata", TestImages.languageDataPresent());
        TaskProcessor processor = new TaskProcessor(url -> TestImages.withText("HELLO 2026"), ocr, 3);

        OcrResult result = processor.process(TASK, 1);

        assertFalse(result.hasError());
        assertTrue(result.text(), result.text().contains("HELLO 2026"));
        assertEquals(4, result.index());
        assertEquals("http://x.com/a.png", result.imageUrl());
    }

    @Test
    public void downloadFailureBecomesAnErrorResult() throws Exception {
        TaskProcessor processor = new TaskProcessor(url -> {
            throw new IOException("HTTP 404");
        }, ocr, 3);

        OcrResult result = processor.process(TASK, 1);

        assertEquals("IOException: HTTP 404", result.error());
        assertEquals(4, result.index());
    }

    @Test
    public void poisonTaskIsReportedWithoutTryingAgain() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        TaskProcessor processor = new TaskProcessor(url -> {
            fetches.incrementAndGet();
            return TestImages.withText("x");
        }, ocr, 3);

        OcrResult result = processor.process(TASK, 4);

        assertEquals("failed after 3 attempts", result.error());
        assertEquals("no download on the 4th delivery", 0, fetches.get());
    }

    @Test(expected = InterruptedException.class)
    public void shutdownGivesNoResult() throws Exception {
        new TaskProcessor(url -> {
            throw new InterruptedException();
        }, ocr, 3).process(TASK, 1);
    }

    @Test
    public void descriptionIsTypeAndMessage() {
        assertEquals("IOException: HTTP 500", TaskProcessor.describe(new IOException("HTTP 500")));
        assertEquals("NullPointerException", TaskProcessor.describe(new NullPointerException()));
    }
}
