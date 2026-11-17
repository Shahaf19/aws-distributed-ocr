package com.bgu.ocr.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.bgu.ocr.messages.ImageTask;

public class ImageTasksTest {

    @Test
    public void numbersUrlsInFileOrder() {
        List<ImageTask> tasks = ImageTasks.fromInput("job-1", "http://a.png\nhttp://b.png\n");

        assertEquals(List.of(new ImageTask("job-1", 0, "http://a.png"), new ImageTask("job-1", 1, "http://b.png")), tasks);
    }

    @Test
    public void skipsBlankLinesAndTrimsWithoutGapsInIndexes() {
        List<ImageTask> tasks = ImageTasks.fromInput("job-1", "\r\n  http://a.png  \r\n\r\n\thttp://b.png\r\n   \r\n");

        assertEquals(2, tasks.size());
        assertEquals("http://a.png", tasks.get(0).imageUrl());
        assertEquals(1, tasks.get(1).index());
        assertEquals("http://b.png", tasks.get(1).imageUrl());
    }

    @Test
    public void repeatedUrlKeepsSeparateIndexes() {
        List<ImageTask> tasks = ImageTasks.fromInput("job-1", "http://a.png\nhttp://a.png\n");

        assertEquals(0, tasks.get(0).index());
        assertEquals(1, tasks.get(1).index());
    }

    @Test
    public void emptyInputGivesNoTasks() {
        assertTrue(ImageTasks.fromInput("job-1", "").isEmpty());
        assertTrue(ImageTasks.fromInput("job-1", "\n \n").isEmpty());
    }
}
