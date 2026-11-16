package com.bgu.ocr.ocr;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.image.BufferedImage;

/**
 * Image → text with Tesseract (through Tess4J). Downloading the image is the caller's job.
 * Not thread-safe: use one instance per thread.
 */
public final class OcrService {

    private final Tesseract tesseract = new Tesseract();

    /** @param tessdataPath folder that contains {@code eng.traineddata} */
    public OcrService(String tessdataPath) {
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage("eng");
    }

    /** @return the recognized text, trimmed; empty if the image has no text */
    public String recognize(BufferedImage image) throws TesseractException {
        return tesseract.doOCR(image).trim();
    }
}
