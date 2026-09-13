package org.yanoproject.x.trust.client;

import org.yanoproject.x.trust.profile.TrustRegistryProfile;
import org.yanoproject.x.trust.profile.TrustRegistryValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerCodecTest {
    private static final String PROOF = "{\"key\":\"00\",\"chainId\":\"c\",\"presence\":\"ABSENT\"}";

    @Test
    void answersRoundTripThroughJson() {
        byte[] value = new TrustRegistryValues.StatusValue(1, 4).encode();
        StatusAnswer.Entry entry = new StatusAnswer.Entry(0, 2, new byte[0], value,
                org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract
                        .logicalValueHash(value), 3, 7);
        StatusAnswer answer = new StatusAnswer("chain", "mpf-blake2b256-v1", "11".repeat(32), 9,
                "22".repeat(32), "33".repeat(32), TrustRegistryProfile.STATUS,
                TrustRegistryProfile.statusKey("list-1", 5), StatusAnswer.Presence.ACTIVE, entry,
                StatusAnswer.Provenance.receipt("44".repeat(32), 7), new byte[32], null,
                List.of(new StatusAnswer.Fact("entry", new byte[]{1, 2}, entry.encode(), PROOF),
                        new StatusAnswer.Fact("receipt", new byte[]{3}, new byte[]{9}, PROOF)),
                "{\"chainId\":\"chain\",\"blocks\":[]}");
        String json = AnswerCodec.toJson(answer);
        assertThat(json).contains("\"type\" : \"trust-registry-answer-v1\"")
                .contains("\"key\" : \"list-1/5\"").contains("\"bit\" : 1");
        StatusAnswer decoded = AnswerCodec.fromJson(json);
        assertThat(decoded.chainId()).isEqualTo("chain");
        assertThat(decoded.keyText()).isEqualTo("list-1/5");
        assertThat(decoded.presence()).isEqualTo(StatusAnswer.Presence.ACTIVE);
        assertThat(decoded.entry().encode()).isEqualTo(entry.encode());
        assertThat(decoded.provenance()).isEqualTo(answer.provenance());
        assertThat(decoded.facts()).hasSize(2);
        assertThat(decoded.fact("receipt").expectedValue()).containsExactly(9);
        assertThat(AnswerCodec.toJson(decoded)).isEqualTo(json);
    }

    @Test
    void codecRejectsForeignDocumentsAndInconsistentShapes() {
        assertThatThrownBy(() -> AnswerCodec.fromJson("{\"schemaVersion\":1,\"type\":\"other\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatusAnswer("chain", "mpf-blake2b256-v1", "11".repeat(32), 9,
                "22".repeat(32), "33".repeat(32), TrustRegistryProfile.STATUS, new byte[]{1},
                StatusAnswer.Presence.ABSENT, null, StatusAnswer.Provenance.receipt("44".repeat(32), 7),
                null, null, List.of(new StatusAnswer.Fact("entry", new byte[]{1}, null, PROOF)),
                "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action commitment");
    }
}
