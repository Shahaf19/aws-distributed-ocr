package com.bgu.ocr.messages;

import java.util.Objects;

/**
 * Manager → its task queue: "OCR this one image".
 *
 * @param index 0-based position of the URL in the job's input list. Workers copy it into the {@link OcrResult}.
 */
public record ImageTask(String jobId, int index, String imageUrl) {

    public ImageTask {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(imageUrl, "imageUrl");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
    }
}
