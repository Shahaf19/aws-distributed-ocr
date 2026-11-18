package com.bgu.ocr.aws;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.bgu.ocr.Config;

public class UserDataTest {

    @Test
    public void managerScriptFetchesTheJarAndRunsTheManagerWithItsOwnId() {
        String script = UserData.forManager(3);

        assertTrue(script.startsWith("#!/bin/bash\n"));
        assertTrue(script.contains("aws s3 cp s3://" + Config.BUCKET + "/" + Config.JAR_S3_KEY + " /opt/ocr/app.jar"));
        assertTrue(script.contains("INSTANCE_ID=$(curl"));
        assertTrue(script.contains("com.bgu.ocr.manager.Manager \"$INSTANCE_ID\" 3"));
        assertTrue("restarts after a crash", script.contains("while true; do"));
        assertTrue("but not after a deliberate shutdown", script.contains("[ $code -eq 0 ] && break"));
    }

    @Test
    public void workerScriptRunsTheWorkerWithItsQueuesAndTheAmisLanguageData() {
        String script = UserData.forWorker("https://sqs/123/ocr-tasks-i-1", "https://sqs/123/ocr-results-i-1");

        assertTrue(script.contains("com.bgu.ocr.worker.Worker \"https://sqs/123/ocr-tasks-i-1\" "
                + "\"https://sqs/123/ocr-results-i-1\" " + Config.TESSDATA_ON_AMI));
        assertFalse("a Worker doesn't need its instance id", script.contains("INSTANCE_ID"));
    }

    @Test
    public void scriptsUseUnixLineEndings() {
        assertFalse(UserData.forManager(1).contains("\r"));
        assertFalse(UserData.forWorker("a", "b").contains("\r"));
    }
}
