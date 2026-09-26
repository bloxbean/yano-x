package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Generates Studio's copy of the controlled diagnostic messages and part vocabulary (ADR-031.2 contract C3).
 *
 * <p>Studio shows these messages for report diagnostics instead of the message text carried by an imported,
 * unauthenticated report. The module is generated from {@link BindingDiagnostic} and drift-checked by
 * {@code BindingEditorContractsTest}; it is never edited by hand.
 */
public final class StudioDiagnosticCodes {
    static final String STUDIO_MODULE = "../studio/src/main/web/binding-diagnostic-codes.mjs";
    private static final ObjectMapper JSON = new ObjectMapper();

    private StudioDiagnosticCodes() { }

    public static void main(String[] args) throws IOException {
        Files.writeString(Path.of(args.length == 0 ? STUDIO_MODULE : args[0]), module(), StandardCharsets.UTF_8);
    }

    static String module() throws JsonProcessingException {
        StringBuilder text = new StringBuilder("""
                // Generated from BindingDiagnostic by StudioDiagnosticCodes (ADR-031.2 contract C3). Do not edit.
                // Regenerate: ./gradlew :tooling:devtools:generateStudioDiagnosticCodes
                export const DIAGNOSTIC_CODES = Object.freeze({
                """);
        var codes = new TreeMap<>(BindingDiagnostic.CODES);
        int index = 0;
        for (var entry : codes.entrySet()) {
            text.append("  ").append(JSON.writeValueAsString(entry.getKey())).append(": ")
                    .append(JSON.writeValueAsString(entry.getValue()))
                    .append(++index == codes.size() ? "\n" : ",\n");
        }
        text.append("});\n");
        text.append("export const DIAGNOSTIC_PARTS = Object.freeze(")
                .append(JSON.writeValueAsString(BindingDiagnostic.PARTS)).append(");\n");
        return text.toString();
    }
}
