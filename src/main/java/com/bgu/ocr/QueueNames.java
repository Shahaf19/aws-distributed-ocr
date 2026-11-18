package com.bgu.ocr;

/** Names of each Manager's own queues: "ocr-tasks-{managerId}" and "ocr-results-{managerId}". */
public final class QueueNames {

    public static final String TASKS_PREFIX = "ocr-tasks-";
    public static final String RESULTS_PREFIX = "ocr-results-";

    private QueueNames() {
    }

    public static String tasks(String managerId) {
        return TASKS_PREFIX + managerId;
    }

    public static String results(String managerId) {
        return RESULTS_PREFIX + managerId;
    }

    /** The Manager id a queue URL or name belongs to, or null if it isn't one of a Manager's own queues. */
    public static String ownerOf(String queueUrlOrName) {
        String name = queueUrlOrName.substring(queueUrlOrName.lastIndexOf('/') + 1);
        for (String prefix : new String[] {TASKS_PREFIX, RESULTS_PREFIX}) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                return name.substring(prefix.length());
            }
        }
        return null;
    }
}
