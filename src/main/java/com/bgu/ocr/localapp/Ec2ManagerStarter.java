package com.bgu.ocr.localapp;

import com.bgu.ocr.Config;
import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.UserData;

import java.util.List;
import java.util.Map;

/**
 * The spec's "checks if a Manager node is available on the EC2 cloud. If it is not, the application will start a new
 * manager node". Run at startup and about once a minute while waiting (a Manager may have died meanwhile).
 */
public final class Ec2ManagerStarter implements ManagerStarter {

    private final Ec2Helper ec2;

    public Ec2ManagerStarter(Ec2Helper ec2) {
        this.ec2 = ec2;
    }

    @Override
    public void ensureManagerRunning(int managerClientsN) {
        if (ManagerAvailability.anyAvailable(ec2.instancesWithRole(Ec2Tags.MANAGER), managerClientsN)) {
            return;
        }
        if (ec2.aliveInstanceCount() >= Config.MAX_INSTANCES) {
            System.err.println("No available Manager, and the " + Config.MAX_INSTANCES
                    + "-instance cap is reached: the job waits in the inbox until a Manager has room");
            return;
        }
        List<String> ids = ec2.launch(1, UserData.forManager(managerClientsN),
                Map.of(Ec2Tags.ROLE, Ec2Tags.MANAGER, "Name", "ocr-manager"));
        System.out.println("No available Manager: started " + ids);
    }
}
