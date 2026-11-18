package com.bgu.ocr.manager;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;
import com.bgu.ocr.aws.UserData;

import java.util.List;
import java.util.Map;

/**
 * Launches this Manager's Workers by the spec's rule (see {@link ScalingPolicy}). Synchronized: two jobs starting at
 * once must not both count the same k and launch for the same gap.
 */
public final class Ec2WorkerScaler implements WorkerScaler {

    private final Ec2Helper ec2;
    private final String managerId;
    private final String workerUserData;

    public Ec2WorkerScaler(Ec2Helper ec2, String managerId, String taskQueueUrl, String resultsQueueUrl) {
        this.ec2 = ec2;
        this.managerId = managerId;
        this.workerUserData = UserData.forWorker(taskQueueUrl, resultsQueueUrl);
    }

    @Override
    public synchronized void ensureWorkers(int needed) {
        int active = (int) ec2.instancesWithRole(Ec2Tags.WORKER).stream()
                .filter(InstanceInfo::isAlive)
                .filter(worker -> managerId.equals(worker.tag(Ec2Tags.MANAGER_ID)))
                .count();
        int toLaunch = ScalingPolicy.workersToLaunch(needed, active, ec2.aliveInstanceCount(), Config.MAX_INSTANCES);
        if (toLaunch == 0) {
            return;
        }
        List<String> ids = ec2.launch(toLaunch, workerUserData,
                Map.of(Ec2Tags.ROLE, Ec2Tags.WORKER, Ec2Tags.MANAGER_ID, managerId, "Name", "ocr-worker"));
        System.out.println("Needed " + needed + " Workers, had " + active + ": launched " + ids);
    }
}
