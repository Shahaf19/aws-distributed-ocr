package com.bgu.ocr.aws;

/** Text files by key: the input URL list and the summary. Images never go through here (Workers fetch them by URL). */
public interface ObjectStore {

    void put(String key, String text);

    /** @throws RuntimeException if no object has that key */
    String get(String key);
}
