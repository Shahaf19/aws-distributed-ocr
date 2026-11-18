package com.bgu.ocr.localapp;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.MessageQueue;
import com.bgu.ocr.aws.ObjectStore;
import com.bgu.ocr.aws.QueueAdmin;
import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.aws.S3ObjectStore;
import com.bgu.ocr.aws.SqsQueueAdmin;
import com.bgu.ocr.messages.JobDone;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.MalformedMessageException;
import com.bgu.ocr.messages.NewTask;
import com.bgu.ocr.messages.OcrResult;
import com.bgu.ocr.messages.Summary;
import com.bgu.ocr.messages.Terminate;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The client: uploads the input, asks a Manager to process it, waits for the done message, and writes the HTML output.
 * Steps follow the spec's "Local Application" section.
 */
public final class LocalApp {

    private final QueueAdmin queues;
    private final ObjectStore store;
    private final ManagerStarter managers;
    private final Duration managerCheckInterval;

    /** @param managerCheckInterval how often to re-check for a Manager while waiting (it may have died) */
    public LocalApp(QueueAdmin queues, ObjectStore store, ManagerStarter managers, Duration managerCheckInterval) {
        this.queues = queues;
        this.store = store;
        this.managers = managers;
        this.managerCheckInterval = managerCheckInterval;
    }

    /** @return the done message that ended the job (completed or rejected) */
    public JobDone run(LocalAppArgs args) throws IOException {
        String jobId = UUID.randomUUID().toString();

        managers.ensureManagerRunning(args.managerClientsN());

        String inputKey = "jobs/" + jobId + "/input.txt";
        store.put(inputKey, Files.readString(args.inputFile()));
        System.out.println("Uploaded input as " + inputKey);

        MessageQueue inbox = queues.create(Config.INBOX_QUEUE, Config.INBOX_VISIBILITY_SECONDS);
        MessageQueue reply = queues.create("done-" + jobId);
        try {
            inbox.send(Json.toJson(new NewTask(jobId, inputKey, reply.url(), args.workerFilesN())));
            System.out.println("Sent job " + jobId + ", waiting for the result...");

            JobDone done = awaitDone(reply, jobId, args.managerClientsN());
            if (done.wasRejected()) {
                System.out.println("Job rejected: " + done.rejected());
            } else {
                List<OcrResult> results = Summary.fromJson(store.get(done.summaryS3Key()));
                Files.writeString(args.outputFile(), HtmlReport.render(results));
                System.out.println("Wrote " + results.size() + " results to " + args.outputFile());
            }

            if (args.terminate()) {
                inbox.send(Json.toJson(new Terminate()));
                System.out.println("Sent terminate");
            }
            return done;
        } finally {
            queues.delete(reply);   // our private queue; leaving it would leak one queue per run
        }
    }

    /**
     * Long-polls the reply queue until this job's done message arrives. Messages are not deleted one by one:
     * the whole queue is deleted afterwards, which saves the calls. A malformed message or one for another
     * job (should not happen on a private queue) is skipped.
     */
    private JobDone awaitDone(MessageQueue reply, String jobId, int managerClientsN) {
        long nextCheck = System.nanoTime() + managerCheckInterval.toNanos();
        while (true) {
            for (ReceivedMessage message : reply.receive()) {
                try {
                    JobDone done = Json.fromJson(message.body(), JobDone.class);
                    if (done.jobId().equals(jobId)) {
                        return done;
                    }
                } catch (MalformedMessageException e) {
                    System.err.println("Ignoring malformed reply: " + e.getMessage());
                }
            }
            if (System.nanoTime() >= nextCheck) {
                managers.ensureManagerAlive(managerClientsN);   // the Manager may have died while we waited
                nextCheck = System.nanoTime() + managerCheckInterval.toNanos();
            }
        }
    }

    public static void main(String[] args) {
        LocalAppArgs parsed;
        try {
            parsed = LocalAppArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        S3ObjectStore store = S3ObjectStore.withDefaultClient();
        store.ensureBucket();
        SqsQueueAdmin queues = SqsQueueAdmin.withDefaultClient();
        ManagerStarter managers = new Ec2ManagerStarter(Ec2Helper.withDefaultClient(), queues);
        LocalApp app = new LocalApp(queues, store, managers, Duration.ofMinutes(1));

        try {
            JobDone done = app.run(parsed);
            System.exit(done.wasRejected() ? 1 : 0);
        } catch (IOException e) {
            System.err.println("File error: " + e.getMessage());
            System.exit(1);
        }
    }
}
