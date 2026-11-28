package org.yanoproject.x.assets.cardano.support;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * The canonical, length-delimited encoding of an observer's consensus-relevant settings
 * (ADR-059 D5, I13). Every field that changes what the observer emits must be listed, in a fixed
 * order and in normalized form, so two members with different settings disagree at startup
 * instead of emitting different observations.
 *
 * <p>Bytes: {@code len(tag) ‖ tag ‖ int32(field count) ‖ (len(field) ‖ field)*}, lengths as
 * big-endian int32 and strings as UTF-8.
 */
public final class ObserverIdentity {
    private ObserverIdentity() {
    }

    public static byte[] encode(String tag, List<String> fields) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(fields, "fields");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, tag);
                output.writeInt(fields.size());
                for (String field : fields) write(output, Objects.requireNonNull(field, "field"));
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
