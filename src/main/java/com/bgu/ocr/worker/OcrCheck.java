package com.bgu.ocr.worker;

import com.bgu.ocr.ocr.OcrService;

/**
 * Diagnostic: downloads one image and prints its OCR text, to verify a machine's Java + Tesseract setup
 * (used when building the AMI). {@code java -cp aws-distributed-ocr.jar com.bgu.ocr.worker.OcrCheck imageUrl tessdataPath}
 */
public final class OcrCheck {

    private OcrCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: OcrCheck imageUrl tessdataPath");
            System.exit(2);
        }
        String text = new OcrService(args[1]).recognize(ImageDownloader.withDefaults().fetch(args[0]));
        System.out.println("OCR OK: " + text.replace("\n", " | "));
    }
}
