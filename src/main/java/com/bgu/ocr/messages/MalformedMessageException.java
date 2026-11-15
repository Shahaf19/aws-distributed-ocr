package com.bgu.ocr.messages;

/** A message body that cannot be parsed. Receivers should log it and delete it instead of retrying forever. */
public class MalformedMessageException extends RuntimeException {
    public MalformedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
