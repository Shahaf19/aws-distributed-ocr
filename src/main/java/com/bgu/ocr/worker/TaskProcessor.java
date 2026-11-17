package com.bgu.ocr.worker;

import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.ocr.OcrService;

/**
 * One ImageTask → exactly one OcrResult: download, then OCR. Any exception becomes an error result with a short
 * description (the spec: "send a message to the manager … together with a short description of the exception,
 * and continue working on the next message").
 */
public final class TaskProcessor {

    private final ImageSource images;
    private final OcrService ocr;
    private final int maxAttempts;

    /** @param maxAttempts deliveries after which a task is treated as a poison message (Config.MAX_TASK_ATTEMPTS) */
    public TaskProcessor(ImageSource images, OcrService ocr, int maxAttempts) {
        this.images = images;
        this.ocr = ocr;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param attempt how many times SQS has delivered this task, including now (its ApproximateReceiveCount)
     * @throws InterruptedException if the Worker is shutting down: no result, so the task is retried elsewhere
     *                              instead of a good image being reported as an error
     */
    public OcrResult process(ImageTask task, int attempt) throws InterruptedException {
        if (attempt > maxAttempts) {
            // earlier attempts crashed or hung their Worker: report instead of trying again forever
            return OcrResult.failure(task, "failed after " + maxAttempts + " attempts");
        }
        try {
            return OcrResult.success(task, ocr.recognize(images.fetch(task.imageUrl())));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return OcrResult.failure(task, describe(e));
        }
    }

    /** e.g. "IOException: HTTP 404", "UnknownHostException: example.invalid", "HttpTimeoutException: request timed out" */
    static String describe(Exception e) {
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null ? type : type + ": " + e.getMessage();
    }
}
