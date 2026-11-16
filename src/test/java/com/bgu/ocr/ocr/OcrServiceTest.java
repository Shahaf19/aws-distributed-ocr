package com.bgu.ocr.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The spec's first step: "apply OCR on an image file, run it on your computer and make sure it works".
 * Needs tessdata/eng.traineddata (gitignored; from github.com/tesseract-ocr/tessdata_fast).
 */
public class OcrServiceTest {

    private static final String TESSDATA = new File("tessdata").getAbsolutePath();

    @BeforeClass
    public static void requireLanguageData() {
        assumeTrue("download eng.traineddata into tessdata/ to run OCR tests",
                new File(TESSDATA, "eng.traineddata").isFile());
    }

    /** Black text on a white background, large enough for clean recognition. */
    private static BufferedImage imageWithText(String text) {
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

    @Test
    public void readsTextFromImage() throws Exception {
        String text = new OcrService(TESSDATA).recognize(imageWithText("HELLO 2026"));

        assertTrue("got: " + text, text.contains("HELLO 2026"));
    }

    @Test
    public void blankImageGivesEmptyText() throws Exception {
        assertEquals("", new OcrService(TESSDATA).recognize(imageWithText("")));
    }
}
