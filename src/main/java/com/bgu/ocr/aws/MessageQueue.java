package com.bgu.ocr.aws;

import java.util.List;

/** A queue of text messages with SQS semantics: at-least-once delivery, no ordering, receive hides until delete. */
public interface MessageQueue {

    /** Address other components use to reach this queue (e.g. the reply queue URL inside a NewTask). */
    String url();

    void send(String body);

    /** Sends many messages; implementations batch them to save API calls. */
    void sendAll(List<String> bodies);

    /** SQS limit for one receive. */
    int MAX_RECEIVE = 10;

    /** Waits up to the long-poll time for up to 10 messages; returns an empty list if none arrived. */
    default List<ReceivedMessage> receive() {
        return receive(MAX_RECEIVE);
    }

    /**
     * Like {@link #receive()}, but takes at most {@code maxMessages} (1–10). The Manager's inbox uses it to never
     * take more NewTasks than it has room for (manager-clients-n).
     */
    List<ReceivedMessage> receive(int maxMessages);

    void delete(ReceivedMessage message);

    void deleteAll(List<ReceivedMessage> messages);

    /** Keeps a message hidden for {@code seconds} more from now (heartbeat for long work). 0 makes it visible again. */
    void extendVisibility(ReceivedMessage message, int seconds);
}
