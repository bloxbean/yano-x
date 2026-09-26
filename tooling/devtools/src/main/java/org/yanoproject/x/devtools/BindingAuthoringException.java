package org.yanoproject.x.devtools;

/**
 * Authoring failure carrying a stable diagnostic code and the compiler's own document path.
 *
 * <p>The message is exactly the historical {@code "<path>: <detail>"} text, so existing command output, logs and
 * substring-based consumers are unchanged. Structured fields are additive and never parsed back from the message.
 * Source and CEL expression positions are one-based UTF-16 positions.
 */
final class BindingAuthoringException extends IllegalArgumentException {
    private final String code;
    private final BindingDocumentPath path;
    private final Integer line;
    private final Integer column;
    private final Integer expressionLine;
    private final Integer expressionColumn;
    private final boolean detailMayContainInput;

    BindingAuthoringException(String code, BindingDocumentPath path, String message, Throwable cause) {
        this(code, path, message, cause, null, null, null, null, false);
    }

    BindingAuthoringException(String code, BindingDocumentPath path, String message, Throwable cause, Integer line,
                              Integer column, Integer expressionLine, Integer expressionColumn,
                              boolean detailMayContainInput) {
        super(message, cause);
        if (!BindingDiagnostic.CODES.containsKey(code)) throw new IllegalArgumentException("unknown code " + code);
        this.code = code;
        this.path = path == null ? BindingDocumentPath.ROOT : path;
        this.line = line;
        this.column = column;
        this.expressionLine = expressionLine;
        this.expressionColumn = expressionColumn;
        this.detailMayContainInput = detailMayContainInput;
    }

    String code() { return code; }
    BindingDocumentPath path() { return path; }
    Integer line() { return line; }
    Integer column() { return column; }

    /** Adds a source position without changing the code, path or message. */
    BindingAuthoringException at(Integer sourceLine, Integer sourceColumn) {
        return new BindingAuthoringException(code, path, getMessage(), getCause(), sourceLine, sourceColumn,
                expressionLine, expressionColumn, detailMayContainInput);
    }

    /** Re-roots the path under a wrapper while preserving the code, positions and cause. */
    BindingAuthoringException under(String wrapper, String message) {
        return new BindingAuthoringException(code, path.under(wrapper), message,
                getCause() == null ? this : getCause(), line, column, expressionLine, expressionColumn,
                detailMayContainInput);
    }

    BindingDiagnostic diagnostic() {
        return BindingDiagnostic.error(code, getMessage(), detailMayContainInput,
                BindingDiagnostic.Location.fromSegments("document", path, line, column)
                        .withExpression(expressionLine, expressionColumn));
    }
}
