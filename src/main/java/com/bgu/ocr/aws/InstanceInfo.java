package com.bgu.ocr.aws;

import java.util.Map;

/**
 * What we read about one EC2 instance: its id, state and tags. Keeps the decision logic (which Manager is
 * available, how many Workers to launch) plain Java, independent of the SDK.
 *
 * @param state EC2's state name: pending, running, shutting-down, terminated, stopping, stopped
 */
public record InstanceInfo(String id, String state, Map<String, String> tags) {

    public InstanceInfo {
        tags = Map.copyOf(tags);
    }

    /** Booting or up: counts toward the 19-instance cap and may be doing (or about to do) work. */
    public boolean isAlive() {
        return state.equals("pending") || state.equals("running");
    }

    public String tag(String key) {
        return tags.get(key);
    }
}
