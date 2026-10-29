package org.yanoproject.x.appchain.cli;

/** Ends a command with an exit code and a message that tells the user what to do. */
final class CliException extends RuntimeException {
    static final int FAILURE = 1;
    static final int USAGE = 2;
    static final int NOT_READY = 3;

    private final int exitCode;

    CliException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    static CliException usage(String message) {
        return new CliException(USAGE, message);
    }

    static CliException failure(String message) {
        return new CliException(FAILURE, message);
    }

    static CliException notReady(String message) {
        return new CliException(NOT_READY, message);
    }

    int exitCode() {
        return exitCode;
    }
}
