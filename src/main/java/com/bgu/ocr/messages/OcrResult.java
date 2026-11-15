package com.bgu.ocr.messages;

import java.util.Objects;

/**
 * Worker → its Manager's results queue: the outcome for one image. Exactly one of {@code text} / {@code error} is set.
 *
 * @param error short description of the exception, when download or OCR failed (the spec's error line)
 */
public record OcrResult(String jobId, int index, String imageUrl, String text, String error) {

    public OcrResult {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(imageUrl, "imageUrl");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
        if ((text == null) == (error == null)) {
            throw new IllegalArgumentException("exactly one of text/error must be set");
        }
    }

    public static OcrResult success(ImageTask task, String text) {
        return new OcrResult(task.jobId(), task.index(), task.imageUrl(), text, null);
    }

    public static OcrResult failure(ImageTask task, String error) {
        return new OcrResult(task.jobId(), task.index(), task.imageUrl(), null, error);
    }

    public boolean hasError() {
        return error != null;
    }
}
