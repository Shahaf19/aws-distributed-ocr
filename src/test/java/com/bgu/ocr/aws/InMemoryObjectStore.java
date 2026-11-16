package com.bgu.ocr.aws;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** Test fake for S3: a thread-safe map from key to text. */
public final class InMemoryObjectStore implements ObjectStore {

    private final Map<String, String> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String text) {
        objects.put(key, text);
    }

    @Override
    public String get(String key) {
        String text = objects.get(key);
        if (text == null) {
            throw new NoSuchElementException("no object " + key);
        }
        return text;
    }
}
