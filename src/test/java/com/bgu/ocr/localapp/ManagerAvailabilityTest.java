package com.bgu.ocr.localapp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;

public class ManagerAvailabilityTest {

    private static InstanceInfo manager(String state, String activeClients, boolean terminating) {
        Map<String, String> tags = new HashMap<>(Map.of(Ec2Tags.ROLE, Ec2Tags.MANAGER));
        if (activeClients != null) {
            tags.put(Ec2Tags.ACTIVE_CLIENTS, activeClients);
        }
        if (terminating) {
            tags.put(Ec2Tags.STATUS, Ec2Tags.TERMINATING);
        }
        return new InstanceInfo("i-1", state, tags);
    }

    @Test
    public void noManagersMeansNoneAvailable() {
        assertFalse(ManagerAvailability.anyAvailable(List.of(), 2));
    }

    @Test
    public void runningManagerWithRoomIsAvailable() {
        assertTrue(ManagerAvailability.anyAvailable(List.of(manager("running", "1", false)), 2));
    }

    @Test
    public void fullManagerIsNotAvailable() {
        assertFalse(ManagerAvailability.anyAvailable(List.of(manager("running", "2", false)), 2));
    }

    @Test
    public void bootingManagerWithoutTagCountsAsAvailable() {
        assertTrue("another client just launched it", ManagerAvailability.anyAvailable(List.of(manager("pending", null, false)), 2));
    }

    @Test
    public void terminatingOrDeadManagersAreNotAvailable() {
        assertFalse(ManagerAvailability.anyAvailable(List.of(
                manager("running", "0", true),
                manager("terminated", "0", false),
                manager("shutting-down", "0", false)), 2));
    }

    @Test
    public void oneAvailableAmongSeveralIsEnough() {
        assertTrue(ManagerAvailability.anyAvailable(List.of(
                manager("running", "2", false),
                manager("running", "1", false)), 2));
    }
}
