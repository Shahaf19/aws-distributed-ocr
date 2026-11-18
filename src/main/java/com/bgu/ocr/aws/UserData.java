package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

/**
 * Boot scripts for the instances we launch (EC2 "user-data": run once, as root, on first boot). Our AMI already has
 * Java, Tesseract and the AWS CLI (DECISIONS.md, "Bootstrapping"); the script fetches the current jar from S3 and
 * runs the right class. A restart loop brings the process back if it ever exits, so an instance never idles.
 */
public final class UserData {

    private UserData() {
    }

    /** The Manager learns its own instance id here (its queue names and its Workers' tags use it). */
    public static String forManager(int managerClientsN) {
        return script("""
                TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
                INSTANCE_ID=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-id)
                """,
                "java -cp /opt/ocr/app.jar com.bgu.ocr.manager.Manager \"$INSTANCE_ID\" " + managerClientsN);
    }

    public static String forWorker(String taskQueueUrl, String resultsQueueUrl) {
        return script("",
                "java -cp /opt/ocr/app.jar com.bgu.ocr.worker.Worker \"" + taskQueueUrl + "\" \"" + resultsQueueUrl
                        + "\" " + Config.TESSDATA_ON_AMI);
    }

    private static String script(String prepare, String javaCommand) {
        return "#!/bin/bash\n"
                // to a file, and to the console, which EC2 lets us read remotely ("Get system log"): no SSH needed
                + "exec > >(tee -a /var/log/ocr.log > /dev/console) 2>&1\n"
                + "until aws s3 cp s3://" + Config.BUCKET + "/" + Config.JAR_S3_KEY + " /opt/ocr/app.jar; do\n"
                + "  echo 'jar download failed, retrying in 5 s'; sleep 5\n"
                + "done\n"
                + prepare
                + "while true; do\n"
                + "  " + javaCommand + "\n"
                + "  echo \"process exited with code $?, restarting in 5 s\"; sleep 5\n"
                + "done\n";
    }
}
