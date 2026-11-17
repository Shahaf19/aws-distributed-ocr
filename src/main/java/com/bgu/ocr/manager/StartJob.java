package com.bgu.ocr.manager;

import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs on the Start pool for one accepted job, in the spec's order: download the input, create an SQS message per
 * URL, then start Workers according to the count. The steps form a chain, so they stay in one task.
 */
public final class StartJob implements Runnable {

    private final JobState job;
    private final ObjectStore store;
    private final MessageQueue taskQueue;
    private final WorkerScaler scaler;
    private final Consumer<JobState> onComplete;

    /** @param onComplete called when the job is already complete here (empty input); the Manager submits FinishJob */
    public StartJob(JobState job, ObjectStore store, MessageQueue taskQueue, WorkerScaler scaler,
                    Consumer<JobState> onComplete) {
        this.job = job;
        this.store = store;
        this.taskQueue = taskQueue;
        this.scaler = scaler;
        this.onComplete = onComplete;
    }

    @Override
    public void run() {
        NewTask task = job.task();
        List<ImageTask> tasks = ImageTasks.fromInput(task.jobId(), store.get(task.inputS3Key()));

        // before sending: once a task is out, its result may come back at any moment
        if (job.setExpected(tasks.size())) {
            onComplete.accept(job);   // empty input: nothing to send, the (empty) report is ready
            return;
        }
        taskQueue.sendAll(tasks.stream().map(Json::toJson).toList());
        scaler.ensureWorkers(workersNeeded(tasks.size(), task.workerFilesN()));
    }

    /** m = ⌈K / n⌉: one Worker per worker-files-n files. */
    static int workersNeeded(int urlCount, int workerFilesN) {
        return (urlCount + workerFilesN - 1) / workerFilesN;
    }
}
