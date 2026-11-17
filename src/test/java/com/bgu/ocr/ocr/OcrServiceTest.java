package com.bgu.ocr.ocr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.BeforeClass;
import org.junit.Test;

/** The spec's first step: "apply OCR on an image file, run it on your computer and make sure it works". */
public class OcrServiceTest {

    @BeforeClass
    public static void requireLanguageData() {
        assumeTrue("download eng.traineddata into tessdata/ to run OCR tests", TestImages.languageDataPresent());
    }

    @Test
    public void readsTextFromImage() throws Exception {
        String text = new OcrService(TestImages.TESSDATA).recognize(TestImages.withText("HELLO 2026"));

        assertTrue("got: " + text, text.contains("HELLO 2026"));
    }

    @Test
    public void blankImageGivesEmptyText() throws Exception {
        assertEquals("", new OcrService(TestImages.TESSDATA).recognize(TestImages.withText("")));
    }
}
