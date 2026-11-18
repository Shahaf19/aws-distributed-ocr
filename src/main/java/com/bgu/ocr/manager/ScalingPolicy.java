package com.bgu.ocr.manager;

/**
 * The spec's scaling rule: "If there are k active workers, and the new job requires m workers, then the manager should
 * create m-k new workers, if possible. For any case don't run more than 19 instances."
 */
public final class ScalingPolicy {

    private ScalingPolicy() {
    }

    /**
     * @param needed           m = ⌈K / worker-files-n⌉ for the new job
     * @param activeWorkers    k = this Manager's Workers that are booting or running
     * @param runningInstances all our instances that are booting or running (Managers and Workers)
     * @param maxInstances     the cap (Config.MAX_INSTANCES)
     */
    public static int workersToLaunch(int needed, int activeWorkers, int runningInstances, int maxInstances) {
        int wanted = needed - activeWorkers;
        int room = maxInstances - runningInstances;
        return Math.max(0, Math.min(wanted, room));
    }
}
