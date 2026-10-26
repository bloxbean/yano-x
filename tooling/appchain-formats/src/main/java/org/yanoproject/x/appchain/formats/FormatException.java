package org.yanoproject.x.appchain.formats;

/** A setup file is malformed or fails a check; the message tells the user what to do. */
public final class FormatException extends IllegalArgumentException {
    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
