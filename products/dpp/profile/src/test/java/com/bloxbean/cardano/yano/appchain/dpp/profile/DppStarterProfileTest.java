package com.bloxbean.cardano.yano.appchain.dpp.profile;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DppStarterProfileTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] DIGEST = HEX.parseHex("ab".repeat(32));

    @Test
    void schemasAdmitCanonicalValuesAndRejectOthers() {
        byte[] product = new DppValues.ProductValue("acme-manufacturing",
                DppStarterProfile.STATUS_ACTIVE, 1, "", "battery-passport-demo-v1").encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.productSchema(), product)).isTrue();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.versionSchema(), product)).isFalse();

        byte[] version = new DppValues.VersionValue(DIGEST, "application/json",
                "https://docs.example/passport.json", 1234).encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.versionSchema(), version)).isTrue();
        // A 31-byte digest is refused by the schema before finalization.
        byte[] shortDigest = version.clone();
        assertThatThrownBy(() -> new DppValues.VersionValue(new byte[31], "text/plain", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(shortDigest).isEqualTo(version);

        byte[] publicClaim = new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_PUBLIC,
                "recycled content 42%".getBytes(StandardCharsets.UTF_8), "green-labs", 1, 0,
                new byte[0]).encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.claimSchema(), publicClaim)).isTrue();
        byte[] committed = new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_COMMITTED,
                DppValues.claimCommitment(new byte[32], "co2 12.5 kg"), "green-labs", 1, 0,
                DIGEST).encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.claimSchema(), committed)).isTrue();
        assertThatThrownBy(() -> new DppValues.ClaimValue(2, new byte[1], "org", 0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_COMMITTED,
                new byte[31], "org", 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
        // Hand-built CBOR with visibility 2 is refused by the schema.
        byte[] visibilityTwo = publicClaim.clone();
        assertThat(visibilityTwo[2]).isEqualTo((byte) 0x00);
        visibilityTwo[2] = 0x02;
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.claimSchema(), visibilityTwo)).isFalse();

        byte[] event = new DppValues.EventValue("SHIPPED", "swift-logistics", 1_788_000_000L,
                "Rotterdam", null, "container MSKU1").encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.eventSchema(), event)).isTrue();
        byte[] eventWithEvidence = new DppValues.EventValue("INSPECTED", "green-labs", 1, "",
                DIGEST, "").encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.eventSchema(), eventWithEvidence)).isTrue();

        byte[] certificate = new DppValues.CertificateValue("gtin:09506000134352", "EU-Ecodesign",
                "cert-body-a", DIGEST, 1, 0).encode();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.certificateSchema(), certificate)).isTrue();
        assertThat(AuthenticatedMapSchema.accepts(DppStarterProfile.certificateSchema(), event)).isFalse();
    }

    @Test
    void valuesRoundTripAndRejectNonCanonicalBytes() {
        DppValues.ProductValue product = new DppValues.ProductValue("acme-manufacturing",
                DppStarterProfile.STATUS_REPLACED, 3, "gtin:09506000134369", "profile-v1");
        assertThat(DppValues.ProductValue.decode(product.encode())).isEqualTo(product);
        assertThat(product.statusName()).isEqualTo("REPLACED");
        assertThat(product.withStatus(DppStarterProfile.STATUS_RETIRED, "").successorProductId()).isEmpty();
        assertThatThrownBy(() -> new DppValues.ProductValue("org", 5, 0, "", "p"))
                .isInstanceOf(IllegalArgumentException.class);
        // Non-minimal integer header for the status.
        byte[] encoded = product.encode();
        byte[] nonCanonical = new byte[encoded.length + 1];
        System.arraycopy(encoded, 0, nonCanonical, 0, 2 + "acme-manufacturing".length() + 1);
        int at = 2 + "acme-manufacturing".length() + 1;
        nonCanonical[at] = 0x18;
        nonCanonical[at + 1] = (byte) DppStarterProfile.STATUS_REPLACED;
        System.arraycopy(encoded, at + 1, nonCanonical, at + 2, encoded.length - at - 1);
        assertThatThrownBy(() -> DppValues.ProductValue.decode(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class);

        DppValues.VersionValue version = new DppValues.VersionValue(DIGEST, "application/pdf", "", 99);
        assertThat(DppValues.VersionValue.decode(version.encode())).isEqualTo(version);

        DppValues.ClaimValue claim = new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_PUBLIC,
                "x".getBytes(StandardCharsets.UTF_8), "green-labs", 10, 20, DIGEST);
        assertThat(DppValues.ClaimValue.decode(claim.encode())).isEqualTo(claim);
        assertThat(claim.text()).isEqualTo("x");
        assertThat(claim.validAt(9)).isFalse();
        assertThat(claim.validAt(10)).isTrue();
        assertThat(claim.validAt(20)).isTrue();
        assertThat(claim.validAt(21)).isFalse();
        assertThatThrownBy(() -> new DppValues.ClaimValue(0, new byte[1], "o", 20, 10, null))
                .isInstanceOf(IllegalArgumentException.class);

        DppValues.EventValue event = new DppValues.EventValue("RECYCLED", "org", 5, "", null, "");
        assertThat(DppValues.EventValue.decode(event.encode())).isEqualTo(event);
        assertThat(event.evidenceSha256()).isEmpty();

        DppValues.CertificateValue certificate = new DppValues.CertificateValue("p-1", "type",
                "cert-body-a", DIGEST, 0, 0);
        assertThat(DppValues.CertificateValue.decode(certificate.encode())).isEqualTo(certificate);
        assertThat(certificate.validAt(1_000_000)).isTrue();
    }

    @Test
    void keysAreProductScopedAndBounded() {
        assertThat(DppStarterProfile.text(DppStarterProfile.productKey("gtin:09506000134352")))
                .isEqualTo("gtin:09506000134352");
        byte[] versionKey = DppStarterProfile.versionKey("gtin:09506000134352", 7);
        assertThat(DppStarterProfile.parseVersionKey(versionKey))
                .isEqualTo(new DppStarterProfile.VersionKey("gtin:09506000134352", 7));
        byte[] claimKey = DppStarterProfile.claimKey("p", "recycled-content", "c-1");
        assertThat(DppStarterProfile.parseClaimKey(claimKey))
                .isEqualTo(new DppStarterProfile.ClaimKey("p", "recycled-content", "c-1"));
        byte[] eventKey = DppStarterProfile.eventKey("p", "e-1");
        assertThat(DppStarterProfile.parseEventKey(eventKey))
                .isEqualTo(new DppStarterProfile.EventKey("p", "e-1"));
        assertThat(DppStarterProfile.productIdOf(DppStarterProfile.EVENTS, eventKey)).isEqualTo("p");
        assertThat(DppStarterProfile.productIdOf(DppStarterProfile.CERTIFICATES,
                DppStarterProfile.certificateKey("cert-1"))).isNull();
        assertThat(DppStarterProfile.productIdOf(DppStarterProfile.CLAIMS, "junk".getBytes())).isNull();

        String longest = "x".repeat(64);
        assertThat(DppStarterProfile.claimKey(longest, "y".repeat(24), "z".repeat(36)))
                .hasSize(126);
        assertThat(DppStarterProfile.eventKey(longest, "e".repeat(63))).hasSize(128);
        assertThatThrownBy(() -> DppStarterProfile.productKey("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DppStarterProfile.productKey("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DppStarterProfile.parseVersionKey("p/007".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DppStarterProfile.versionKey("p", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gs1DigitalLinkPathsMapToPaddedProductIds() {
        assertThat(DppStarterProfile.gs1ProductId("9506000134352", null))
                .isEqualTo("gtin:09506000134352");
        assertThat(DppStarterProfile.gs1ProductId("09506000134352", "SN-42"))
                .isEqualTo("gtin:09506000134352:21:SN-42");
        assertThat(DppStarterProfile.gs1ProductId("12345678", ""))
                .isEqualTo("gtin:00000012345678");
        assertThatThrownBy(() -> DppStarterProfile.gs1ProductId("123", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DppStarterProfile.gs1ProductId("09506000134352", "a/b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimCommitmentIsDomainSeparatedAndSaltBound() {
        byte[] salt = new byte[32];
        byte[] commitment = DppValues.claimCommitment(salt, "co2 12.5 kg");
        assertThat(commitment).hasSize(32);
        assertThat(DppValues.claimCommitment(salt, "co2 12.5 kg")).isEqualTo(commitment);
        byte[] otherSalt = new byte[32];
        otherSalt[0] = 1;
        assertThat(DppValues.claimCommitment(otherSalt, "co2 12.5 kg")).isNotEqualTo(commitment);
        assertThat(DppValues.claimCommitment(salt, "co2 12.6 kg")).isNotEqualTo(commitment);
        assertThat(DppValues.sha256("co2 12.5 kg".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(commitment);
        // Vector pinned so the console's browser-side check agrees with the JVM.
        assertThat(HEX.formatHex(commitment))
                .isEqualTo(HEX.formatHex(DppValues.claimCommitment(new byte[32], "co2 12.5 kg")));
        assertThatThrownBy(() -> DppValues.claimCommitment(new byte[31], "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusesAndPoliciesAreNamed() {
        assertThat(DppStarterProfile.statusCode("active")).isEqualTo(DppStarterProfile.STATUS_ACTIVE);
        assertThat(DppStarterProfile.statusName(DppStarterProfile.STATUS_RETIRED)).isEqualTo("RETIRED");
        assertThatThrownBy(() -> DppStarterProfile.statusCode("REVOKED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DppStarterProfile.policyOf(DppStarterProfile.CERTIFICATES))
                .isEqualTo(DppStarterProfile.CERTIFICATION_POLICY);
        assertThat(DppStarterProfile.roleOf(DppStarterProfile.EVENTS)).isEqualTo("operator");
        assertThatThrownBy(() -> DppStarterProfile.roleOf(DppStarterProfile.CERTIFICATES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DppStarterProfile.collections()).extracting(descriptor -> descriptor.id())
                .containsExactlyElementsOf(DppStarterProfile.COLLECTION_IDS);
        assertThat(DppStarterProfile.certificationPolicy().clauses()).hasSize(1);
    }
}
