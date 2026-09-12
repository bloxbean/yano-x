package org.yanoproject.x.attest.client;

import org.yanoproject.api.appchain.anchor.AnchorDatumV1;
import org.yanoproject.api.appchain.proof.ProofLabVocabulary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Out-of-band trust input for certificate verification, ADR-047 §6.
 *
 * <p>{@link BundleDeclared} uses the members the bundle itself claims and can
 * only establish internal consistency. {@link CallerPinned} supplies a member
 * set and threshold the caller obtained independently. {@link IndependentAnchor}
 * supplies the canonical inline datum of the chain's state-thread output as
 * read from Cardano; the datum then supplies members, threshold, and the
 * commitment identity.</p>
 */
public sealed interface AttestTrust
        permits AttestTrust.BundleDeclared, AttestTrust.CallerPinned, AttestTrust.IndependentAnchor {

    ProofLabVocabulary.TrustLevel level();

    static BundleDeclared bundleDeclared() {
        return new BundleDeclared();
    }

    record BundleDeclared() implements AttestTrust {
        @Override
        public ProofLabVocabulary.TrustLevel level() {
            return ProofLabVocabulary.TrustLevel.INTERNAL_CONSISTENCY_ONLY;
        }
    }

    /**
     * @param memberKeysHex    exact member set (32-byte lowercase hex keys)
     * @param threshold        finality threshold
     * @param profile          optional commitment profile id to pin
     * @param genesisIdHex     optional genesis id to pin, required with the profile
     */
    record CallerPinned(String chainId,
                        Set<String> memberKeysHex,
                        int threshold,
                        String profile,
                        String genesisIdHex) implements AttestTrust {
        public CallerPinned {
            chainId = Objects.requireNonNull(chainId, "chainId");
            memberKeysHex = Set.copyOf(Objects.requireNonNull(memberKeysHex, "memberKeysHex"));
            if (memberKeysHex.isEmpty() || threshold < 1 || threshold > memberKeysHex.size()) {
                throw new IllegalArgumentException("invalid trusted membership");
            }
            for (String key : memberKeysHex) {
                if (key == null || key.length() != 64 || !AttestCertificateCodec.canonicalHex(key)) {
                    throw new IllegalArgumentException("member keys must be 32-byte lowercase hex");
                }
            }
            if ((profile == null) != (genesisIdHex == null)) {
                throw new IllegalArgumentException(
                        "profile and genesisIdHex must be pinned together");
            }
            if (genesisIdHex != null && (genesisIdHex.length() != 64
                    || !AttestCertificateCodec.canonicalHex(genesisIdHex))) {
                throw new IllegalArgumentException("genesisIdHex must be 32-byte lowercase hex");
            }
        }

        @Override
        public ProofLabVocabulary.TrustLevel level() {
            return ProofLabVocabulary.TrustLevel.CALLER_PINNED_ROOT;
        }

        /**
         * Parses the members file described in ADR-047 §7:
         * {@code { "chainId", "memberKeysHex": [...], "threshold", "profile"?, "genesisIdHex"? }}.
         */
        public static CallerPinned fromJson(String json) {
            try {
                JsonNode node = new ObjectMapper().readTree(json);
                if (node == null || !node.isObject() || !node.path("memberKeysHex").isArray()) {
                    throw new IllegalArgumentException("members file must carry memberKeysHex");
                }
                Set<String> keys = new LinkedHashSet<>();
                for (JsonNode key : node.get("memberKeysHex")) {
                    if (!key.isTextual()) {
                        throw new IllegalArgumentException("member keys must be strings");
                    }
                    keys.add(key.textValue());
                }
                String profile = node.hasNonNull("profile") ? node.get("profile").asText() : null;
                String genesis = node.hasNonNull("genesisIdHex")
                        ? node.get("genesisIdHex").asText() : null;
                return new CallerPinned(node.path("chainId").asText(), keys,
                        node.path("threshold").asInt(), profile, genesis);
            } catch (IOException malformed) {
                throw new IllegalArgumentException("members file is not well-formed JSON");
            }
        }
    }

    /**
     * @param datum                 decoded canonical anchor datum
     * @param applicationIdOverride optional application id to compare when the
     *                              certificate recorded none
     */
    record IndependentAnchor(AnchorDatumV1 datum, String applicationIdOverride)
            implements AttestTrust {
        public IndependentAnchor {
            datum = Objects.requireNonNull(datum, "datum");
        }

        public static IndependentAnchor fromDatumHex(String datumHex, String applicationIdOverride) {
            byte[] cbor;
            try {
                cbor = HexFormat.of().parseHex(datumHex.trim());
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException("anchor datum must be hex-encoded CBOR");
            }
            return new IndependentAnchor(AnchorDatumV1.decode(cbor), applicationIdOverride);
        }

        @Override
        public ProofLabVocabulary.TrustLevel level() {
            return ProofLabVocabulary.TrustLevel.INDEPENDENTLY_VERIFIED_L1_ANCHOR;
        }
    }
}
