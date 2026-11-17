package com.bgu.ocr.aws;

/** The queue no longer exists, e.g. a Local App already deleted its reply queue. Keeps SDK types out of callers. */
public class QueueGoneException extends RuntimeException {

    public QueueGoneException(String url, Throwable cause) {
        super("queue no longer exists: " + url, cause);
    }
}
