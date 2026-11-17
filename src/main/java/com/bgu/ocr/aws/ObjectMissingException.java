package com.bgu.ocr.aws;

/** No object under that key (a permanent condition, unlike a network failure). Keeps SDK types out of callers. */
public class ObjectMissingException extends RuntimeException {

    public ObjectMissingException(String key, Throwable cause) {
        super("no object " + key, cause);
    }
}
