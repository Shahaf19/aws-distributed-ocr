package com.bgu.ocr.localapp;

import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;

import java.util.List;

/** The spec's "available Manager": an active Manager currently serving fewer than manager-clients-n clients. */
public final class ManagerAvailability {

    private ManagerAvailability() {
    }

    /** @param managers instances tagged role=manager, in any state */
    public static boolean anyAvailable(List<InstanceInfo> managers, int managerClientsN) {
        return managers.stream().anyMatch(m -> isAvailable(m, managerClientsN));
    }

    /** Some Manager is booting or running and not shutting down, whether or not it has room. */
    public static boolean anyAlive(List<InstanceInfo> managers) {
        return managers.stream().anyMatch(ManagerAvailability::isAliveAndStaying);
    }

    static boolean isAvailable(InstanceInfo manager, int managerClientsN) {
        return isAliveAndStaying(manager) && activeClients(manager) < managerClientsN;
    }

    /** A Manager another client just launched (pending) counts: no duplicate launch. */
    private static boolean isAliveAndStaying(InstanceInfo manager) {
        return manager.isAlive() && !Ec2Tags.TERMINATING.equals(manager.tag(Ec2Tags.STATUS));
    }

    /** A Manager still booting has no tag yet: it serves nobody. */
    private static int activeClients(InstanceInfo manager) {
        try {
            String value = manager.tag(Ec2Tags.ACTIVE_CLIENTS);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;   // unreadable: don't count on it
        }
    }
}
