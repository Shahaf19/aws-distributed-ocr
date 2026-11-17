package com.bgu.ocr.worker;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.QueueAdmin;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.aws.SqsQueueAdmin;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.MalformedMessageException;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.ocr.OcrService;

/**
 * The Worker's life cycle (spec): repeatedly get a message, download the image, OCR it, notify the Manager, remove
 * the message. Takes one task at a time (DECISIONS.md: "Workers take one task per receive"). Runs until its
 * instance is terminated; locally, stop it by interrupting its thread.
 */
public final class Worker implements Runnable {

    private final MessageQueue tasks;
    private final MessageQueue results;
    private final TaskProcessor processor;

    public Worker(MessageQueue tasks, MessageQueue results, TaskProcessor processor) {
        this.tasks = tasks;
        this.results = results;
        this.processor = processor;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException | Error e) {   // e.g. SQS unreachable: the task (if any) comes back; keep working
                System.err.println("Worker: " + e);
                pause();
            }
        }
    }

    /** One long poll for one task, and its handling. */
    void pollOnce() throws InterruptedException {
        for (ReceivedMessage message : tasks.receive(1)) {
            ImageTask task;
            try {
                task = Json.fromJson(message.body(), ImageTask.class);
            } catch (MalformedMessageException e) {
                System.err.println("Worker: deleting malformed task: " + e.getMessage());
                tasks.delete(message);   // retrying cannot fix it
                continue;
            }
            OcrResult result = processor.process(task, message.receiveCount());
            results.send(Json.toJson(result));   // first the result...
            tasks.delete(message);               // ...then the task: a crash in between means a duplicate, never a loss
            System.out.println("Job " + task.jobId() + " #" + task.index() + ": "
                    + (result.hasError() ? "error: " + result.error() : "ok"));
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code java -cp aws-distributed-ocr.jar com.bgu.ocr.worker.Worker taskQueueUrl resultsQueueUrl tessdataPath} */
    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: Worker taskQueueUrl resultsQueueUrl tessdataPath");
            System.exit(2);
        }
        QueueAdmin queues = SqsQueueAdmin.withDefaultClient();
        TaskProcessor processor = new TaskProcessor(ImageDownloader.withDefaults(), new OcrService(args[2]),
                Config.MAX_TASK_ATTEMPTS);
        new Worker(queues.open(args[0]), queues.open(args[1]), processor).run();
    }
}
