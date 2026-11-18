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

    static boolean isAvailable(InstanceInfo manager, int managerClientsN) {
        return manager.isAlive()   // a Manager another client just launched (pending) counts: no duplicate launch
                && !Ec2Tags.TERMINATING.equals(manager.tag(Ec2Tags.STATUS))
                && activeClients(manager) < managerClientsN;
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
