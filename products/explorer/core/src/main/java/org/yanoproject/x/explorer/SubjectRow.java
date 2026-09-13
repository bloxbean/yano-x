package org.yanoproject.x.explorer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One typed row a module derived from a finalized message (ADR-050 §2.2). A message yields zero
 * or more rows; a batch action on the authenticated map yields one per mutation.
 *
 * @param module  the module id, for example {@code doc-trail}
 * @param kind    the subject kind the module names, for example {@code entity} or {@code key}
 * @param subject the subject id (entity id, key hex, account, item id, or collection/key)
 * @param op      the operation the command carried, upper case
 * @param fields  decoded fields, JSON-safe scalars only
 */
public record SubjectRow(String module, String kind, String subject, String op,
                         Map<String, Object> fields) {
    public static final int MAX_SUBJECT_CHARS = 512;

    public SubjectRow {
        module = Objects.requireNonNull(module, "module");
        kind = Objects.requireNonNull(kind, "kind");
        subject = Objects.requireNonNull(subject, "subject");
        op = Objects.requireNonNull(op, "op");
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT_CHARS) {
            throw new IllegalArgumentException("subject must contain 1-" + MAX_SUBJECT_CHARS + " characters");
        }
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
