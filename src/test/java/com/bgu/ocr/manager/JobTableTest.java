package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.bgu.ocr.aws.ReceivedMessage;
import com.bgu.ocr.messages.Json;
import com.bgu.ocr.messages.NewTask;

public class JobTableTest {

    private static JobState job(String jobId) {
        NewTask task = new NewTask(jobId, "in", "reply", 5);
        return new JobState(task, new ReceivedMessage(Json.toJson(task), "receipt", 0, 1));
    }

    @Test
    public void listenerHearsEverySizeChange() {
        JobTable jobs = new JobTable();
        List<Integer> sizes = new ArrayList<>();
        jobs.onSizeChange(sizes::add);
        JobState a = job("a");
        JobState b = job("b");

        jobs.add(a);
        jobs.add(b);
        jobs.remove(a);
        jobs.remove(a);   // already gone: no change, no call

        assertEquals(List.of(1, 2, 1), sizes);
    }

    @Test
    public void listenerRunsOutsideTheLock() throws Exception {
        JobTable jobs = new JobTable();
        List<Integer> seenByOtherThread = new ArrayList<>();
        jobs.onSizeChange(size -> {
            Thread other = new Thread(() -> seenByOtherThread.add(jobs.size()));   // would block if we held the lock
            other.start();
            try {
                other.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        jobs.add(job("a"));

        assertEquals(List.of(1), seenByOtherThread);
    }
}
