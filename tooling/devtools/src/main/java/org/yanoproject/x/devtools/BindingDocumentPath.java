package org.yanoproject.x.devtools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact location inside an authored binding document, as a list of field-name and list-index segments.
 *
 * <p>{@link #toString()} renders the historical {@code $.bindings[0].to.map.entityId} notation used in compiler
 * messages. The segment list is the structured form: field names may contain {@code .}, so the rendered text is
 * for display only and is never parsed back.
 *
 * @param segments field names ({@link String}) and zero-based list indexes ({@link Integer})
 */
record BindingDocumentPath(List<Object> segments) {
    static final BindingDocumentPath ROOT = new BindingDocumentPath(List.of());

    BindingDocumentPath {
        segments = List.copyOf(segments);
        for (Object segment : segments) {
            if (!(segment instanceof String) && !(segment instanceof Integer)) {
                throw new IllegalArgumentException("path segments are names or indexes");
            }
        }
    }

    BindingDocumentPath field(String name) { return append(Objects.requireNonNull(name, "name")); }

    BindingDocumentPath index(int index) { return append(index); }

    /** Places this path under a wrapper field, for example {@code composite}. */
    BindingDocumentPath under(String wrapper) {
        List<Object> result = new ArrayList<>();
        result.add(wrapper);
        result.addAll(segments);
        return new BindingDocumentPath(result);
    }

    private BindingDocumentPath append(Object segment) {
        List<Object> result = new ArrayList<>(segments);
        result.add(segment);
        return new BindingDocumentPath(result);
    }

    @Override public String toString() {
        StringBuilder text = new StringBuilder("$");
        for (Object segment : segments) {
            if (segment instanceof Integer index) text.append('[').append(index).append(']');
            else text.append('.').append(segment);
        }
        return text.toString();
    }
}
