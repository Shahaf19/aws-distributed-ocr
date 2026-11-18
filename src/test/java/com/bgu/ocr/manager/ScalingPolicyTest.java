package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScalingPolicyTest {

    @Test
    public void noWorkersYetLaunchesAllNeeded() {
        assertEquals(5, ScalingPolicy.workersToLaunch(5, 0, 1, 19));   // 1 running instance: the Manager
    }

    @Test
    public void launchesOnlyTheDifference() {
        assertEquals(2, ScalingPolicy.workersToLaunch(5, 3, 4, 19));   // m − k
    }

    @Test
    public void enoughWorkersLaunchesNone() {
        assertEquals(0, ScalingPolicy.workersToLaunch(3, 5, 6, 19));
    }

    @Test
    public void capLimitsTheLaunch() {
        assertEquals(18, ScalingPolicy.workersToLaunch(50, 0, 1, 19));   // Manager + 18 Workers = 19
    }

    @Test
    public void capCountsOtherManagersInstancesToo() {
        assertEquals(3, ScalingPolicy.workersToLaunch(10, 2, 16, 19));
    }

    @Test
    public void capAlreadyReachedLaunchesNone() {
        assertEquals(0, ScalingPolicy.workersToLaunch(10, 0, 19, 19));
    }
}
