package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Creates, opens and deletes SQS queues. Separate from {@link MessageQueue}, which only senders/receivers need. */
public final class QueueAdmin {

    private final SqsClient sqs;

    public QueueAdmin(SqsClient sqs) {
        this.sqs = sqs;
    }

    /** Uses the SDK's default credentials: the instance profile on EC2, ~/.aws/credentials locally. */
    public static QueueAdmin withDefaultClient() {
        return new QueueAdmin(SqsClient.builder().region(Region.of(Config.REGION)).build());
    }

    /** Creates the queue, or returns the existing one with that name (SQS create is idempotent). */
    public SqsQueue create(String name) {
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        return open(url);
    }

    /** A queue whose URL we were given, e.g. the reply queue inside a NewTask. */
    public SqsQueue open(String url) {
        return new SqsQueue(sqs, url);
    }

    public void delete(SqsQueue queue) {
        sqs.deleteQueue(b -> b.queueUrl(queue.url()));
    }
}
