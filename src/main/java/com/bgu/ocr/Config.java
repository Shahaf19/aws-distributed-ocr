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

    /**
     * Our AMI: Ubuntu 24.04 + OpenJDK 17 + Tesseract 5.3.4 (eng) + AWS CLI v2,
     * built from scripts/ami-setup.sh. The jar is not inside; instances fetch it from S3 at boot.
     */
    public static final String AMI_ID = "ami-0725885594e989fad";

    /** Where the AMI's Tesseract package puts its language data. */
    public static final String TESSDATA_ON_AMI = "/usr/share/tesseract-ocr/5/tessdata";

    /** The jar every instance downloads at boot (upload a new one after each code change). */
    public static final String JAR_S3_KEY = "app/aws-distributed-ocr.jar";

    /** 2 vCPUs, 1 GB; move Workers to t3.small if OCR runs out of memory on big images. */
    public static final String INSTANCE_TYPE = "t3.micro";

    /** AWS Academy's role for EC2: gives instances SQS/S3/EC2 access, so no credentials are ever shipped. */
    public static final String INSTANCE_PROFILE = "LabInstanceProfile";

    /** The spec's hard limit on running instances (Manager(s) + Workers). */
    public static final int MAX_INSTANCES = 19;

    /** SQS maximum: an empty receive costs one call per 20 s instead of a busy loop. */
    public static final int LONG_POLL_SECONDS = 20;

    /** A NewTask stays hidden while its job runs; the Manager's heartbeat resets it to this every beat. */
    public static final int INBOX_VISIBILITY_SECONDS = 180;
    public static final int INBOX_HEARTBEAT_SECONDS = 60;

    /** Safely above the worst case for one image (download + OCR), so no Worker heartbeat is needed. */
    public static final int TASK_VISIBILITY_SECONDS = 120;

    /** After this many deliveries an ImageTask is a poison message: the Worker reports an error instead of retrying. */
    public static final int MAX_TASK_ATTEMPTS = 3;

    /** How often a Manager checks for a terminate request (its tag) and for idleness. EC2 reads are free. */
    public static final int LIFECYCLE_CHECK_SECONDS = 30;

    /** A Manager with no jobs this long terminates its Workers (the next job launches new ones). */
    public static final int IDLE_WORKERS_MINUTES = 5;

    /** A Manager with no jobs this long shuts itself down, so an unused system stops billing. */
    public static final int IDLE_MANAGER_MINUTES = 10;

    /** After this many deliveries of a NewTask whose start keeps failing, the Manager rejects it to the client. */
    public static final int MAX_START_ATTEMPTS = 3;

    private Config() {
    }
}
