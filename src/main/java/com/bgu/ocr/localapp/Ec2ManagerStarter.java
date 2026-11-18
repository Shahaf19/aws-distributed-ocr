package com.bgu.ocr.localapp;

import com.bgu.ocr.Config;
import com.bgu.ocr.QueueNames;
import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;
import com.bgu.ocr.aws.QueueAdmin;
import com.bgu.ocr.aws.UserData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The spec's "checks if a Manager node is available on the EC2 cloud. If it is not, the application will start a new
 * manager node". Run at startup and about once a minute while waiting (a Manager may have died meanwhile). Each run
 * also removes what crashed Managers left behind: their Workers and their queues.
 */
public final class Ec2ManagerStarter implements ManagerStarter {

    private final Ec2Helper ec2;
    private final QueueAdmin queues;

    public Ec2ManagerStarter(Ec2Helper ec2, QueueAdmin queues) {
        this.ec2 = ec2;
        this.queues = queues;
    }

    @Override
    public void ensureManagerRunning(int managerClientsN) {
        List<InstanceInfo> managers = ec2.instancesWithRole(Ec2Tags.MANAGER);
        cleanUpOrphans(Orphans.aliveIds(managers));
        if (!ManagerAvailability.anyAvailable(managers, managerClientsN)) {
            launchManager(managerClientsN);
        }
    }

    @Override
    public void ensureManagerAlive(int managerClientsN) {
        List<InstanceInfo> managers = ec2.instancesWithRole(Ec2Tags.MANAGER);
        cleanUpOrphans(Orphans.aliveIds(managers));
        if (!ManagerAvailability.anyAlive(managers)) {
            launchManager(managerClientsN);
        }
    }

    private void launchManager(int managerClientsN) {
        if (ec2.aliveInstanceCount() >= Config.MAX_INSTANCES) {
            System.err.println("No available Manager, and the " + Config.MAX_INSTANCES
                    + "-instance cap is reached: the job waits in the inbox until a Manager has room");
            return;
        }
        List<String> ids = ec2.launch(1, UserData.forManager(managerClientsN),
                Map.of(Ec2Tags.ROLE, Ec2Tags.MANAGER, "Name", "ocr-manager"));
        System.out.println("No available Manager: started " + ids);
    }

    /** Best effort: a failure here must never stop the client's own job. */
    private void cleanUpOrphans(Set<String> aliveManagerIds) {
        try {
            List<String> workers = Orphans.workers(ec2.instancesWithRole(Ec2Tags.WORKER), aliveManagerIds);
            if (!workers.isEmpty()) {
                ec2.terminate(workers);
                System.out.println("Terminated orphan Workers (their Manager is gone): " + workers);
            }
            List<String> urls = new ArrayList<>(queues.urlsStartingWith(QueueNames.TASKS_PREFIX));
            urls.addAll(queues.urlsStartingWith(QueueNames.RESULTS_PREFIX));
            for (String url : Orphans.queues(urls, aliveManagerIds)) {
                queues.delete(queues.open(url));
                System.out.println("Deleted orphan queue " + url);
            }
        } catch (RuntimeException e) {
            System.err.println("Orphan cleanup failed (will retry next check): " + e);
        }
    }
}
