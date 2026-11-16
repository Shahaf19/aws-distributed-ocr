package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

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

    /** Creates the queue with SQS's default 30 s visibility, or returns the existing one with that name. */
    public SqsQueue create(String name) {
        String url = sqs.createQueue(b -> b.queueName(name)).queueUrl();
        return open(url);
    }

    /**
     * Creates the queue with its own default visibility timeout, or returns the existing one.
     * The value must be the same for every creator (take it from {@link Config}): SQS rejects a create
     * whose attributes differ from the existing queue's.
     */
    public SqsQueue create(String name, int visibilityTimeoutSeconds) {
        String url = sqs.createQueue(b -> b.queueName(name).attributes(
                Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds)))).queueUrl();
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
