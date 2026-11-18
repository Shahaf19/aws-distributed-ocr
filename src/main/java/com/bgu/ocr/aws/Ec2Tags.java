package com.bgu.ocr.aws;

/**
 * The EC2 tags that mark our instances (the spec: "use the tags feature … to mark a specific instance as one running
 * a Manager"). Managers: role=manager, activeClients=n, and status=terminating once shutting down.
 * Workers: role=worker, managerId=the instance id of the Manager that owns them.
 */
public final class Ec2Tags {

    public static final String ROLE = "role";
    public static final String MANAGER = "manager";
    public static final String WORKER = "worker";

    /** On a Manager: how many clients (jobs) it is serving right now. */
    public static final String ACTIVE_CLIENTS = "activeClients";

    /** On a Manager: set to {@link #TERMINATING} when it stops accepting jobs. */
    public static final String STATUS = "status";
    public static final String TERMINATING = "terminating";

    /** On a Worker: its Manager's instance id. */
    public static final String MANAGER_ID = "managerId";

    private Ec2Tags() {
    }
}
