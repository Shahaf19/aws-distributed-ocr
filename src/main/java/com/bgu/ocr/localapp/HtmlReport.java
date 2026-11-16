package com.bgu.ocr.localapp;

import com.bgu.ocr.messages.OcrResult;

import java.util.List;

/**
 * Builds the output HTML in the format of the assignment's sample file: one {@code <p>} per input line, in input order.
 * Success: the image followed by its text. Failure (spec): a link to the image and a short description of the exception.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    public static String render(List<OcrResult> results) {
        StringBuilder html = new StringBuilder()
                .append("<html><head><meta charset=\"UTF-8\"><title>OCR</title></head><body>\n");
        for (OcrResult result : results) {
            html.append(result.hasError() ? failure(result) : success(result));
        }
        return html.append("</body></html>\n").toString();
    }

    private static String success(OcrResult result) {
        return "\t<p>\n"
                + "\t\t<img src=\"" + escape(result.imageUrl()) + "\"><br>\n"
                + "\t\t" + escape(result.text()).replace("\n", "<br>\n\t\t") + "\n"
                + "\t</p>\n";
    }

    private static String failure(OcrResult result) {
        String url = escape(result.imageUrl());
        return "\t<p>\n"
                + "\t\t<a href=\"" + url + "\">" + url + "</a> " + escape(result.error()) + "\n"
                + "\t</p>\n";
    }

    /** OCR text and URLs are untrusted: {@code <}, {@code &} or quotes must not break (or inject into) the page. */
    static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
