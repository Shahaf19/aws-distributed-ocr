package com.bgu.ocr.localapp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.bgu.ocr.messages.ImageTask;
import com.bgu.ocr.messages.OcrResult;

public class HtmlReportTest {

    private static ImageTask task(int index, String url) {
        return new ImageTask("job-1", index, url);
    }

    @Test
    public void successShowsImageThenText() {
        String html = HtmlReport.render(List.of(OcrResult.success(task(0, "http://x.com/a.png"), "3D Text")));

        assertTrue(html, html.contains("<img src=\"http://x.com/a.png\"><br>\n\t\t3D Text\n"));
    }

    @Test
    public void failureShowsLinkAndDescription() {
        String html = HtmlReport.render(List.of(OcrResult.failure(task(0, "http://x.com/a.png"), "HTTP 404")));

        assertTrue(html, html.contains("<a href=\"http://x.com/a.png\">http://x.com/a.png</a> HTTP 404"));
        assertFalse(html, html.contains("<img"));
    }

    @Test
    public void entriesKeepInputOrderIncludingRepeatedUrls() {
        String html = HtmlReport.render(List.of(
                OcrResult.success(task(0, "http://x.com/a.png"), "first"),
                OcrResult.success(task(1, "http://x.com/b.png"), "second"),
                OcrResult.success(task(2, "http://x.com/a.png"), "third")));

        assertTrue(html.indexOf("first") < html.indexOf("second"));
        assertTrue(html.indexOf("second") < html.indexOf("third"));
    }

    @Test
    public void textIsEscapedAndLineBreaksKept() {
        String html = HtmlReport.render(List.of(OcrResult.success(task(0, "u"), "a<b & \"c\"\nline 2")));

        assertTrue(html, html.contains("a&lt;b &amp; &quot;c&quot;<br>"));
        assertTrue(html, html.contains("line 2"));
        assertFalse(html, html.contains("a<b"));
    }

    @Test
    public void wrapsEntriesInAPage() {
        String html = HtmlReport.render(List.of());

        assertTrue(html.startsWith("<html><head>"));
        assertTrue(html.contains("<title>OCR</title>"));
        assertTrue(html.trim().endsWith("</body></html>"));
    }
}
