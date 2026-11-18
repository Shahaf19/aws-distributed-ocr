package com.bgu.ocr.localapp;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;

public class OrphansTest {

    private static InstanceInfo manager(String id, String state) {
        return new InstanceInfo(id, state, Map.of(Ec2Tags.ROLE, Ec2Tags.MANAGER));
    }

    private static InstanceInfo worker(String id, String state, String managerId) {
        return new InstanceInfo(id, state, Map.of(Ec2Tags.ROLE, Ec2Tags.WORKER, Ec2Tags.MANAGER_ID, managerId));
    }

    @Test
    public void onlyBootingOrRunningManagersCountAsAlive() {
        assertEquals(Set.of("m-run", "m-boot"), Orphans.aliveIds(List.of(
                manager("m-run", "running"), manager("m-boot", "pending"), manager("m-dying", "shutting-down"))));
    }

    @Test
    public void runningWorkersOfDeadManagersAreOrphans() {
        List<String> orphans = Orphans.workers(List.of(
                worker("w1", "running", "m-alive"),
                worker("w2", "running", "m-dead"),
                worker("w3", "pending", "m-dead"),
                worker("w4", "shutting-down", "m-dead")), Set.of("m-alive"));

        assertEquals("w4 is already going away", List.of("w2", "w3"), orphans);
    }

    @Test
    public void queuesOfDeadManagersAreOrphansOthersAreLeftAlone() {
        String base = "https://sqs.us-east-1.amazonaws.com/123/";
        List<String> orphans = Orphans.queues(List.of(
                base + "ocr-tasks-m-alive", base + "ocr-results-m-alive",
                base + "ocr-tasks-m-dead", base + "ocr-results-m-dead",
                base + "ocr-manager-inbox", base + "done-job-1"), Set.of("m-alive"));

        assertEquals(List.of(base + "ocr-tasks-m-dead", base + "ocr-results-m-dead"), orphans);
    }
}
