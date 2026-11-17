package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

/** {@link QueueAdmin} for real SQS queues. All queues share one client (and its HTTP connection pool). */
public final class SqsQueueAdmin implements QueueAdmin {

    private final SqsClient sqs;

    public SqsQueueAdmin(SqsClient sqs) {
        this.sqs = sqs;
    }

    /** Uses the SDK's default credentials: the instance profile on EC2, ~/.aws/credentials locally. */
    public static SqsQueueAdmin withDefaultClient() {
        return new SqsQueueAdmin(SqsClient.builder().region(Region.of(Config.REGION)).build());
    }

    @Override
    public MessageQueue create(String name) {
        return open(sqs.createQueue(b -> b.queueName(name)).queueUrl());
    }

    /** SQS rejects a create whose attributes differ from an existing queue's, hence one value per queue in Config. */
    @Override
    public MessageQueue create(String name, int visibilityTimeoutSeconds) {
        return open(sqs.createQueue(b -> b.queueName(name).attributes(
                Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds)))).queueUrl());
    }

    @Override
    public MessageQueue open(String url) {
        return new SqsQueue(sqs, url);
    }

    @Override
    public void delete(MessageQueue queue) {
        sqs.deleteQueue(b -> b.queueUrl(queue.url()));
    }
}
