package com.bgu.ocr;

/** Names and tuning values shared by the Local App, the Manager and the Workers. */
public final class Config {

    public static final String REGION = "us-east-1";

    /** Must be globally unique across all of S3; finalized at the first real AWS run. */
    public static final String BUCKET = "bgu-dsp-ocr-change-me";

    /** The shared inbox all Managers pull from. */
    public static final String INBOX_QUEUE = "ocr-manager-inbox";

    /** SQS maximum: an empty receive costs one call per 20 s instead of a busy loop. */
    public static final int LONG_POLL_SECONDS = 20;

    private Config() {
    }
}
