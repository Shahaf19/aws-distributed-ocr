package com.bgu.ocr.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/** Images drawn in code for OCR tests, so the expected text is known exactly and no binary files are needed. */
public final class TestImages {

    /** Folder with eng.traineddata (gitignored; from github.com/tesseract-ocr/tessdata_fast). */
    public static final String TESSDATA = new File("tessdata").getAbsolutePath();

    private TestImages() {
    }

    public static boolean languageDataPresent() {
        return new File(TESSDATA, "eng.traineddata").isFile();
    }

    /** Black text on a white background, large enough for clean recognition. Empty text gives a blank image. */
    public static BufferedImage withText(String text) {
        BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        if (!text.isEmpty()) {
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            g.drawString(text, 40, 130);
        }
        g.dispose();
        return image;
    }
}
