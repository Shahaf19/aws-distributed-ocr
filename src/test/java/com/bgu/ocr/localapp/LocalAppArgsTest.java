package com.bgu.ocr.localapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;

import org.junit.Test;

public class LocalAppArgsTest {

    @Test
    public void parsesFourArguments() {
        LocalAppArgs args = LocalAppArgs.parse("in.txt", "out.html", "3", "10");

        assertEquals(Path.of("in.txt"), args.inputFile());
        assertEquals(Path.of("out.html"), args.outputFile());
        assertEquals(3, args.managerClientsN());
        assertEquals(10, args.workerFilesN());
        assertFalse(args.terminate());
    }

    @Test
    public void fifthArgumentTerminate() {
        assertTrue(LocalAppArgs.parse("in.txt", "out.html", "3", "10", "terminate").terminate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void tooFewArguments() {
        LocalAppArgs.parse("in.txt", "out.html", "3");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownFifthArgument() {
        LocalAppArgs.parse("in.txt", "out.html", "3", "10", "stop");
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonNumericCount() {
        LocalAppArgs.parse("in.txt", "out.html", "three", "10");
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroWorkerFilesN() {
        LocalAppArgs.parse("in.txt", "out.html", "3", "0");
    }
}
