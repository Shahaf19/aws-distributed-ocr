package com.bgu.ocr.localapp;

import java.nio.file.Path;

/** The Local App's command line: {@code inputFileName outputFileName manager-clients-n worker-files-n [terminate]}. */
public record LocalAppArgs(Path inputFile, Path outputFile, int managerClientsN, int workerFilesN, boolean terminate) {

    public static final String USAGE =
            "usage: java -jar aws-distributed-ocr.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]";

    /** @throws IllegalArgumentException with a message that includes the usage line */
    public static LocalAppArgs parse(String... args) {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("expected 4 or 5 arguments, got " + args.length + "\n" + USAGE);
        }
        if (args.length == 5 && !args[4].equals("terminate")) {
            throw new IllegalArgumentException("unknown 5th argument '" + args[4] + "'\n" + USAGE);
        }
        return new LocalAppArgs(Path.of(args[0]), Path.of(args[1]),
                positive(args[2], "manager-clients-n"), positive(args[3], "worker-files-n"), args.length == 5);
    }

    private static int positive(String value, String name) {
        try {
            int n = Integer.parseInt(value);
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException(name + " must be a positive integer, got '" + value + "'\n" + USAGE);
    }
}
