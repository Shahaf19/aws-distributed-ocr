package com.bgu.ocr.worker;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Where a Worker gets an image from. The real one downloads it; tests hand one over directly. */
@FunctionalInterface
public interface ImageSource {

    BufferedImage fetch(String url) throws IOException, InterruptedException;
}
