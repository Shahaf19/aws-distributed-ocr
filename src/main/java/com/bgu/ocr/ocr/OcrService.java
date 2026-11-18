package com.bgu.ocr.ocr;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Image → text with Tesseract (through Tess4J). Downloading the image is the caller's job.
 * Not thread-safe: use one instance per thread.
 *
 * <p>The image is handed to Tesseract as raw 8-bit grayscale pixels. Tess4J's {@code doOCR(BufferedImage)} would
 * convert it through Leptonica instead, and Tess4J 5.20 needs Leptonica ≥ 1.83, while Ubuntu 24.04 (our AMI) ships
 * 1.82 ("undefined symbol: returnErrorFloat1"). Tesseract binarizes the image itself, so grayscale loses nothing.
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
        BufferedImage gray = toGrayscale(image);
        byte[] pixels = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();   // one byte per pixel, row by row
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length);
        buffer.put(pixels).flip();
        return tesseract.doOCR(gray.getWidth(), gray.getHeight(), buffer, 8, "", List.of()).trim();
    }

    /** Draws the image onto white, so transparent areas (e.g. in PNG/GIF) become background, not black. */
    private static BufferedImage toGrayscale(BufferedImage image) {
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, gray.getWidth(), gray.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return gray;
    }
}
