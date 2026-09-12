package org.yanoproject.x.stdlib;

import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Canonical, length-delimited identity encoding shared by stdlib epoch observers. */
final class ObserverConsensusIdentity {
    private ObserverConsensusIdentity() {
    }

    static L1ObserverConsensusIdentity of(String claimSchema, String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, "yano-x-stdlib-epoch-observer-v1");
                output.writeInt(fields.length);
                for (String field : fields) {
                    write(output, field);
                }
            }
            return new L1ObserverConsensusIdentity(1, claimSchema, 1, bytes.toByteArray());
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
