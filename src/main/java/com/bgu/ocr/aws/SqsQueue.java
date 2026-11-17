package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.util.ArrayList;
import java.util.List;

/** {@link MessageQueue} backed by a real SQS queue. */
public final class SqsQueue implements MessageQueue {

    /** SQS limit for receive, send-batch and delete-batch. */
    private static final int MAX_BATCH = 10;

    private final SqsClient sqs;
    private final String url;

    public SqsQueue(SqsClient sqs, String url) {
        this.sqs = sqs;
        this.url = url;
    }

    @Override
    public String url() {
        return url;
    }

    /** @throws QueueGoneException if the queue was deleted */
    @Override
    public void send(String body) {
        try {
            sqs.sendMessage(b -> b.queueUrl(url).messageBody(body));
        } catch (QueueDoesNotExistException e) {
            throw new QueueGoneException(url, e);
        }
    }

    @Override
    public void sendAll(List<String> bodies) {
        for (List<String> chunk : chunks(bodies)) {
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(SendMessageBatchRequestEntry.builder().id(String.valueOf(i)).messageBody(chunk.get(i)).build());
            }
            SendMessageBatchResponse response = sqs.sendMessageBatch(b -> b.queueUrl(url).entries(entries));
            if (!response.failed().isEmpty()) {
                // a lost task would leave its job incomplete forever, so fail loudly
                throw new IllegalStateException("SQS rejected " + response.failed().size() + " messages: "
                        + response.failed());
            }
        }
    }

    @Override
    public List<ReceivedMessage> receive(int maxMessages) {
        if (maxMessages < 1 || maxMessages > MAX_RECEIVE) {
            throw new IllegalArgumentException("maxMessages must be 1-" + MAX_RECEIVE + ": " + maxMessages);
        }
        return sqs.receiveMessage(b -> b.queueUrl(url)
                        .maxNumberOfMessages(maxMessages)
                        .waitTimeSeconds(Config.LONG_POLL_SECONDS)
                        .messageSystemAttributeNames(MessageSystemAttributeName.SENT_TIMESTAMP,
                                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT))
                .messages().stream()
                .map(SqsQueue::toReceived)
                .toList();
    }

    private static ReceivedMessage toReceived(Message m) {
        return new ReceivedMessage(m.body(), m.receiptHandle(),
                Long.parseLong(m.attributes().get(MessageSystemAttributeName.SENT_TIMESTAMP)),
                Integer.parseInt(m.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)));
    }

    @Override
    public void delete(ReceivedMessage message) {
        sqs.deleteMessage(b -> b.queueUrl(url).receiptHandle(message.receiptHandle()));
    }

    @Override
    public void deleteAll(List<ReceivedMessage> messages) {
        for (List<ReceivedMessage> chunk : chunks(messages)) {
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id(String.valueOf(i)).receiptHandle(chunk.get(i).receiptHandle()).build());
            }
            sqs.deleteMessageBatch(b -> b.queueUrl(url).entries(entries));
            // a failed delete is harmless: the message is redelivered and ignored as a duplicate
        }
    }

    @Override
    public void extendVisibility(ReceivedMessage message, int seconds) {
        sqs.changeMessageVisibility(b -> b.queueUrl(url)
                .receiptHandle(message.receiptHandle()).visibilityTimeout(seconds));
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += MAX_BATCH) {
            chunks.add(items.subList(from, Math.min(from + MAX_BATCH, items.size())));
        }
        return chunks;
    }
}
