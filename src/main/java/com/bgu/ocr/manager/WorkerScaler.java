package com.bgu.ocr.manager;

/**
 * Makes sure enough Workers run for a job. The spec's rule: if {@code k} Workers are active and the job needs
 * {@code m}, start {@code m - k} more, never exceeding 19 instances in total. The EC2 implementation counts {@code k}.
 */
@FunctionalInterface
public interface WorkerScaler {

    /** @param needed minimum number of Workers to have booting or running */
    void ensureWorkers(int needed);
}
