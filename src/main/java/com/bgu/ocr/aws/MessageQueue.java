package com.bgu.ocr.aws;

import java.util.List;

/** A queue of text messages with SQS semantics: at-least-once delivery, no ordering, receive hides until delete. */
public interface MessageQueue {

    void send(String body);

    /** Sends many messages; implementations batch them to save API calls. */
    void sendAll(List<String> bodies);

    /** Waits up to the long-poll time for messages; returns an empty list if none arrived. */
    List<ReceivedMessage> receive();

    void delete(ReceivedMessage message);

    void deleteAll(List<ReceivedMessage> messages);

    /** Keeps a message hidden for {@code seconds} more from now (heartbeat for long work). 0 makes it visible again. */
    void extendVisibility(ReceivedMessage message, int seconds);
}
