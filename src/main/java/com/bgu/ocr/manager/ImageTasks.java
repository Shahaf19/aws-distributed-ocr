package com.bgu.ocr.manager;

import com.bgu.ocr.messages.ImageTask;

import java.util.ArrayList;
import java.util.List;

/** Turns an input file (one image URL per line) into the job's ImageTasks. */
public final class ImageTasks {

    private ImageTasks() {
    }

    /**
     * Blank lines are skipped and the rest are numbered 0..K-1 in file order, so {@code index} can size and fill
     * the job's result array. A URL that appears twice gets two indexes: each line is its own output entry.
     */
    public static List<ImageTask> fromInput(String jobId, String inputText) {
        List<ImageTask> tasks = new ArrayList<>();
        for (String line : inputText.split("\\R")) {   // \R: any line break (\n, \r\n)
            String url = line.strip();
            if (!url.isEmpty()) {
                tasks.add(new ImageTask(jobId, tasks.size(), url));
            }
        }
        return tasks;
    }
}
