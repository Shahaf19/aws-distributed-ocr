package com.bgu.ocr.localapp;

/**
 * "Make sure an available Manager exists" (spec: an active Manager serving fewer than manager-clients-n clients;
 * if there is none, start one). The real implementation uses EC2 tags.
 */
@FunctionalInterface
public interface ManagerStarter {

    void ensureManagerRunning(int managerClientsN);
}
