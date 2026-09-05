package com.bloxbean.cardano.yano.appchain.trust.profile;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustRegistryProfileTest {

    @Test
    void schemasAdmitCanonicalValuesAndRejectOthers() {
        byte[] subject = new TrustRegistryValues.SubjectValue(
                "registry-operator", "product", new byte[32]).encode();
        assertThat(AuthenticatedMapSchema.accepts(TrustRegistryProfile.subjectSchema(), subject))
                .isTrue();
        assertThat(AuthenticatedMapSchema.accepts(TrustRegistryProfile.statusSchema(), subject))
                .isFalse();

        byte[] status = new TrustRegistryValues.StatusValue(1, 7).encode();
        assertThat(AuthenticatedMapSchema.accepts(TrustRegistryProfile.statusSchema(), status))
                .isTrue();

        byte[] list = new TrustRegistryValues.StatusListValue(
                "revocation", TrustRegistryProfile.MIN_BIT_LENGTH, new byte[32], 12).encode();
        assertThat(AuthenticatedMapSchema.accepts(TrustRegistryProfile.statusListSchema(), list))
                .isTrue();

        byte[] issuer = new TrustRegistryValues.IssuerValue(
                "framework-a", List.of("issue:credential"), 1, 0).encode();
        assertThat(AuthenticatedMapSchema.accepts(TrustRegistryProfile.issuerSchema(), issuer))
                .isTrue();
        byte[] noAuthorizations = new TrustRegistryValues.IssuerValue(
                "framework-a", List.of(), 5, 9).encode();
        assertThat(AuthenticatedMapSchema.accepts(
                TrustRegistryProfile.issuerSchema(), noAuthorizations)).isTrue();
    }

    @Test
    void valuesRoundTripAndRejectNonCanonicalBytes() {
        TrustRegistryValues.StatusValue status = new TrustRegistryValues.StatusValue(1, 3);
        assertThat(TrustRegistryValues.StatusValue.decode(status.encode())).isEqualTo(status);
        assertThatThrownBy(() -> new TrustRegistryValues.StatusValue(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        // 0x83 0x01 0x1801 0x03: the bit encoded with a non-minimal integer header.
        byte[] nonCanonical = {(byte) 0x83, 0x01, 0x18, 0x01, 0x03};
        assertThatThrownBy(() -> TrustRegistryValues.StatusValue.decode(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class);

        TrustRegistryValues.IssuerValue issuer = new TrustRegistryValues.IssuerValue(
                "framework", List.of("a", "b"), 10, 20);
        assertThat(TrustRegistryValues.IssuerValue.decode(issuer.encode())).isEqualTo(issuer);
        assertThat(issuer.validAt(9)).isFalse();
        assertThat(issuer.validAt(10)).isTrue();
        assertThat(issuer.validAt(20)).isTrue();
        assertThat(issuer.validAt(21)).isFalse();
        assertThatThrownBy(() -> new TrustRegistryValues.IssuerValue("f", List.of(), 20, 10))
                .isInstanceOf(IllegalArgumentException.class);

        TrustRegistryValues.SubjectValue subject = new TrustRegistryValues.SubjectValue(
                "org", "kind", new byte[32]);
        assertThat(TrustRegistryValues.SubjectValue.decode(subject.encode())).isEqualTo(subject);
        TrustRegistryValues.StatusListValue list = new TrustRegistryValues.StatusListValue(
                "suspension", 200_000, new byte[32], 0);
        assertThat(TrustRegistryValues.StatusListValue.decode(list.encode())).isEqualTo(list);
        assertThatThrownBy(() -> new TrustRegistryValues.StatusListValue(
                "suspension", 100, new byte[32], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusKeysAreListAndIndex() {
        byte[] key = TrustRegistryProfile.statusKey("did:example:list-1", 42);
        assertThat(new String(key, StandardCharsets.US_ASCII)).isEqualTo("did:example:list-1/42");
        TrustRegistryProfile.StatusKey parsed = TrustRegistryProfile.parseStatusKey(key);
        assertThat(parsed.listId()).isEqualTo("did:example:list-1");
        assertThat(parsed.index()).isEqualTo(42);
        assertThatThrownBy(() -> TrustRegistryProfile.parseStatusKey(
                "list/007".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustRegistryProfile.statusKey("list", TrustRegistryProfile.MAX_BIT_LENGTH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustRegistryProfile.subjectKey("has/slash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void collectionsCarryTheProfilePoliciesAndNoRestore() {
        List<AuthenticatedMapContract.CollectionDescriptor> collections =
                TrustRegistryProfile.collections();
        assertThat(collections).extracting(AuthenticatedMapContract.CollectionDescriptor::id)
                .containsExactly("issuers", "schemas", "status", "status-lists", "subjects");
        assertThat(collections).allMatch(descriptor -> !descriptor.restoreAllowed());
        assertThat(TrustRegistryProfile.policyOf("status")).isEqualTo("issuer-write");
        assertThat(TrustRegistryProfile.roleOf("subjects")).isEqualTo("registrar");
        assertThatThrownBy(() -> TrustRegistryProfile.roleOf("issuers"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
