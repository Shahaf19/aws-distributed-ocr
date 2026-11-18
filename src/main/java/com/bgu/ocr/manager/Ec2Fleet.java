package com.bgu.ocr.manager;

import com.bgu.ocr.aws.Ec2Helper;
import com.bgu.ocr.aws.Ec2Tags;
import com.bgu.ocr.aws.InstanceInfo;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** {@link Fleet} on EC2, through instance tags (EC2 API calls are free). */
public final class Ec2Fleet implements Fleet {

    private final Ec2Helper ec2;
    private final String managerId;

    public Ec2Fleet(Ec2Helper ec2, String managerId) {
        this.ec2 = ec2;
        this.managerId = managerId;
    }

    @Override
    public void markAllManagersTerminating(long terminateSentAt) {
        List<String> managers = ec2.instancesWithRole(Ec2Tags.MANAGER).stream()
                .filter(InstanceInfo::isAlive).map(InstanceInfo::id).toList();
        ec2.tagAll(managers, Map.of(Ec2Tags.STATUS, Ec2Tags.TERMINATING,
                Ec2Tags.TERMINATE_SENT_AT, String.valueOf(terminateSentAt)));
    }

    @Override
    public void markSelfTerminating() {
        ec2.tag(managerId, Ec2Tags.STATUS, Ec2Tags.TERMINATING);
    }

    @Override
    public OptionalLong terminationRequestedAt() {
        return ec2.instance(managerId)
                .map(self -> self.tag(Ec2Tags.TERMINATE_SENT_AT))
                .map(sentAt -> OptionalLong.of(Long.parseLong(sentAt)))
                .orElse(OptionalLong.empty());
    }

    @Override
    public void terminateMyWorkers() {
        List<String> mine = ec2.instancesWithRole(Ec2Tags.WORKER).stream()
                .filter(InstanceInfo::isAlive)
                .filter(worker -> managerId.equals(worker.tag(Ec2Tags.MANAGER_ID)))
                .map(InstanceInfo::id)
                .toList();
        ec2.terminate(mine);
        if (!mine.isEmpty()) {
            System.out.println("Terminated Workers " + mine);
        }
    }

    @Override
    public void terminateSelf() {
        System.out.println("Manager " + managerId + " terminating its instance");
        ec2.terminate(List.of(managerId));
    }
}
