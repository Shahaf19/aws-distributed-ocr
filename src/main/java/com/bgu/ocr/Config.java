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

    /** A NewTask stays hidden while its job runs; the Manager's heartbeat resets it to this every beat. */
    public static final int INBOX_VISIBILITY_SECONDS = 180;
    public static final int INBOX_HEARTBEAT_SECONDS = 60;

    /** Safely above the worst case for one image (download + OCR), so no Worker heartbeat is needed. */
    public static final int TASK_VISIBILITY_SECONDS = 120;

    /** After this many deliveries an ImageTask is a poison message: the Worker reports an error instead of retrying. */
    public static final int MAX_TASK_ATTEMPTS = 3;

    /** After this many deliveries of a NewTask whose start keeps failing, the Manager rejects it to the client. */
    public static final int MAX_START_ATTEMPTS = 3;

    private Config() {
    }
}
