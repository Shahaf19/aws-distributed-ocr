package com.bgu.ocr.messages;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Single place that converts messages to and from JSON, so every component uses the same settings. */
public final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)          // omit text/error when null
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) // tolerate fields added later
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);       // Terminate has no fields

    private Json() {
    }

    public static String toJson(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize " + message, e);
        }
    }

    /** @throws MalformedMessageException if the body is not valid JSON for {@code type} */
    public static <T> T fromJson(String body, Class<T> type) {
        try {
            return MAPPER.readValue(body, type);
        } catch (JsonProcessingException e) {   // includes constructor validation failures, wrapped by Jackson
            throw new MalformedMessageException("Cannot parse " + type.getSimpleName() + ": " + body, e);
        }
    }
}
