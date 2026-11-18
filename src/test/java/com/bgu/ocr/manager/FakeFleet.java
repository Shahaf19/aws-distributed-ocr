package com.bgu.ocr.manager;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A pretend EC2 shared by the Managers in a test: one terminate tag for all of them, and a log of what each Manager
 * did to its Workers and itself ("m1 terminated its Workers", "m1 terminated itself", ...).
 */
public final class FakeFleet {

    private volatile Long terminateSentAt;
    private final List<String> events = new CopyOnWriteArrayList<>();

    public Fleet forManager(String managerId) {
        return new Fleet() {
            @Override
            public void markAllManagersTerminating(long sentAt) {
                terminateSentAt = sentAt;
                events.add(managerId + " marked all Managers terminating");
            }

            @Override
            public void markSelfTerminating() {
                events.add(managerId + " marked itself terminating");
            }

            @Override
            public OptionalLong terminationRequestedAt() {
                Long sentAt = terminateSentAt;
                return sentAt == null ? OptionalLong.empty() : OptionalLong.of(sentAt);
            }

            @Override
            public void terminateMyWorkers() {
                events.add(managerId + " terminated its Workers");
            }

            @Override
            public void terminateSelf() {
                events.add(managerId + " terminated itself");
            }
        };
    }

    /** As if another Manager had received the terminate message. */
    public void requestTermination(long sentAt) {
        terminateSentAt = sentAt;
    }

    public List<String> events() {
        return events;
    }
}
