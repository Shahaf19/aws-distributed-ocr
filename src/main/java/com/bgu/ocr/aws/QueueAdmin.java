package com.bgu.ocr.aws;

/** Creates, opens and deletes queues. Separate from {@link MessageQueue}, which only senders/receivers need. */
public interface QueueAdmin {

    /** Creates the queue with the default visibility timeout, or returns the existing one with that name. */
    MessageQueue create(String name);

    /**
     * Creates the queue with its own default visibility timeout, or returns the existing one.
     * Every creator must pass the same value (take it from {@code Config}).
     */
    MessageQueue create(String name, int visibilityTimeoutSeconds);

    /** A queue whose URL we were given, e.g. the reply queue inside a NewTask. No network call. */
    MessageQueue open(String url);

    void delete(MessageQueue queue);
}
