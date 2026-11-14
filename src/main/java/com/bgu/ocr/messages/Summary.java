package com.bgu.ocr.messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/** The summary file the Manager uploads to S3: a JSON list of {@link OcrResult}, ordered by index. */
public final class Summary {

    private static final TypeReference<List<OcrResult>> RESULT_LIST = new TypeReference<>() {
    };

    private Summary() {
    }

    public static String toJson(List<OcrResult> results) {
        return Json.toJson(results);
    }

    public static List<OcrResult> fromJson(String body) {
        try {
            return Json.MAPPER.readValue(body, RESULT_LIST);
        } catch (JsonProcessingException e) {
            throw new MalformedMessageException("Cannot parse summary", e);
        }
    }
}
