package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.OcrResult;

/** Stands in for a real Worker in tests: answers every ImageTask with "fake text {index}" (result first, then delete). */
public final class FakeWorker implements Runnable {

    private final MessageQueue tasks;
    private final MessageQueue results;
    private volatile boolean running = true;

    public FakeWorker(MessageQueue tasks, MessageQueue results) {
        this.tasks = tasks;
        this.results = results;
    }

    /** Starts it on a daemon thread. */
    public FakeWorker startInBackground() {
        Thread thread = new Thread(this, "fake-worker");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    @Override
    public void run() {
        while (running) {
            for (ReceivedMessage message : tasks.receive()) {
                ImageTask task = Json.fromJson(message.body(), ImageTask.class);
                results.send(Json.toJson(OcrResult.success(task, "fake text " + task.index())));
                tasks.delete(message);
            }
        }
    }

    public void stop() {
        running = false;
    }
}
