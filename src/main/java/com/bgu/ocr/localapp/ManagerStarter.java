package com.bgu.ocr.localapp;

/**
 * "Make sure an available Manager exists" (spec: an active Manager serving fewer than manager-clients-n clients;
 * if there is none, start one). The real implementation uses EC2 tags.
 */
@FunctionalInterface
public interface ManagerStarter {

    /** Before sending the job: the spec's rule, a Manager with room for one more client. */
    void ensureManagerRunning(int managerClientsN);

    /**
     * While waiting for our answer, about once a minute: only make sure some Manager is alive. Our job may be the
     * reason the Managers are full, so the startup rule would launch one nobody needs (seen in the final EC2 run).
     * If our Manager died, our job returns to the inbox and a living Manager takes it once it has room.
     */
    default void ensureManagerAlive(int managerClientsN) {
        ensureManagerRunning(managerClientsN);
    }
}
