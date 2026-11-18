package com.bgu.ocr.localapp;

import com.bgu.ocr.QueueNames;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a Manager that died without shutting down leaves behind: Workers still running (and billing) and its two
 * queues (DECISIONS.md, "Orphan Workers and queues"). A Manager that shuts down normally cleans up after itself.
 */
public final class Orphans {

    private Orphans() {
    }

    public static Set<String> aliveIds(List<InstanceInfo> managers) {
        return managers.stream().filter(InstanceInfo::isAlive).map(InstanceInfo::id).collect(Collectors.toSet());
    }

    /** Running Workers whose Manager is not alive (or that don't say whose they are). */
    public static List<String> workers(List<InstanceInfo> workers, Set<String> aliveManagerIds) {
        return workers.stream()
                .filter(InstanceInfo::isAlive)
                .filter(worker -> !aliveManagerIds.contains(worker.tag(Ec2Tags.MANAGER_ID)))
                .map(InstanceInfo::id)
                .toList();
    }

    /** Task/results queues whose Manager is not alive. */
    public static List<String> queues(List<String> queueUrls, Set<String> aliveManagerIds) {
        return queueUrls.stream()
                .filter(url -> {
                    String owner = QueueNames.ownerOf(url);
                    return owner != null && !aliveManagerIds.contains(owner);
                })
                .toList();
    }
}
