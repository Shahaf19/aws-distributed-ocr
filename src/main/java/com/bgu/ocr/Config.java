package com.bgu.ocr;

/** Names and tuning values shared by the Local App, the Manager and the Workers. */
public final class Config {

    public static final String REGION = "us-east-1";

    /**
     * Must be unique across all of S3, so it lives in one AWS account (the one used for real runs).
     * Everything we store is under it, so it's easy to remove.
     */
    public static final String BUCKET = "dsp-ocr-a1";

    /** The shared inbox all Managers pull from. */
    public static final String INBOX_QUEUE = "ocr-manager-inbox";

    /** SQS maximum: an empty receive costs one call per 20 s instead of a busy loop. */
    public static final int LONG_POLL_SECONDS = 20;

    private Config() {
    }
}
