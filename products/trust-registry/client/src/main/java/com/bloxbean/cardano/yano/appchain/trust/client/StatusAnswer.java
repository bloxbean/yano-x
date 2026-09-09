package com.bloxbean.cardano.yano.appchain.trust.client;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * One registry answer (ADR-049 §2.2): what the chain said about one key at one height, the
 * proofs it rests on, the evidence bundle whose certified block carries the root, and, for
 * governed writes, who wrote it. Every proof and the bundle are kept as the JSON the node
 * served so the SDK decoders verify exactly those bytes offline.
 */
public record StatusAnswer(
        String chainId,
        String profile,
        String genesisIdHex,
        long height,
        String stateRootHex,
        String blockHashHex,
        String collection,
        byte[] key,
        Presence presence,
        Entry entry,
        Provenance provenance,
        byte[] actionCommitment,
        byte[] authorizationEvidence,
        List<Fact> facts,
        String evidenceJson
) {
    public static final String TYPE = "trust-registry-answer-v1";
    public static final int SCHEMA_VERSION = 1;
    private static final HexFormat HEX = HexFormat.of();

    public StatusAnswer {
        chainId = nonBlank(chainId, "chainId");
        profile = nonBlank(profile, "profile");
        genesisIdHex = hex32(genesisIdHex, "genesisIdHex");
        if (height < 1) throw new IllegalArgumentException("height must be positive");
        stateRootHex = hex32(stateRootHex, "stateRootHex");
        blockHashHex = hex32(blockHashHex, "blockHashHex");
        collection = nonBlank(collection, "collection");
        key = Objects.requireNonNull(key, "key").clone();
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(provenance, "provenance");
        if ((presence == Presence.ABSENT) != (entry == null)) {
            throw new IllegalArgumentException("presence and entry disagree");
        }
        actionCommitment = actionCommitment == null ? new byte[0] : actionCommitment.clone();
        authorizationEvidence = authorizationEvidence == null ? new byte[0]
                : authorizationEvidence.clone();
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        if (facts.isEmpty() || facts.size() > 16) {
            throw new IllegalArgumentException("an answer carries 1-16 facts");
        }
        if (facts.stream().map(Fact::name).distinct().count() != facts.size()) {
            throw new IllegalArgumentException("fact names must be unique");
        }
        boolean receiptBound = provenance.kind() != ProvenanceKind.GENESIS
                && provenance.kind() != ProvenanceKind.NONE;
        if (receiptBound && actionCommitment.length != 32) {
            throw new IllegalArgumentException("receipt-bound answers carry the action commitment");
        }
        if ((provenance.kind() == ProvenanceKind.DIRECT_ROLE) != (authorizationEvidence.length > 0)) {
            throw new IllegalArgumentException(
                    "direct-role answers carry exactly the actor authorization");
        }
        evidenceJson = nonBlank(evidenceJson, "evidenceJson");
    }

    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] actionCommitment() { return actionCommitment.clone(); }
    @Override public byte[] authorizationEvidence() { return authorizationEvidence.clone(); }

    public String keyHex() {
        return HEX.formatHex(key);
    }

    /** The key as text when it is printable ASCII, else null. */
    public String keyText() {
        for (byte b : key) {
            if (b < 0x20 || b > 0x7e) return null;
        }
        return new String(key, StandardCharsets.US_ASCII);
    }

    public Fact fact(String name) {
        return facts.stream().filter(fact -> fact.name().equals(name)).findFirst().orElse(null);
    }

    public enum Presence {
        ACTIVE, REVOKED, ABSENT;

        public static Presence of(int presence) {
            return switch (presence) {
                case AuthenticatedMapContract.PRESENCE_ABSENT -> ABSENT;
                case AuthenticatedMapContract.PRESENCE_ACTIVE -> ACTIVE;
                case AuthenticatedMapContract.PRESENCE_REVOKED -> REVOKED;
                default -> throw new IllegalArgumentException("unknown presence " + presence);
            };
        }

        public int code() {
            return switch (this) {
                case ABSENT -> AuthenticatedMapContract.PRESENCE_ABSENT;
                case ACTIVE -> AuthenticatedMapContract.PRESENCE_ACTIVE;
                case REVOKED -> AuthenticatedMapContract.PRESENCE_REVOKED;
            };
        }
    }

    /**
     * {@code NONE}: an exclusion, nothing was ever written. {@code GENESIS}: seeded at height 0,
     * no receipt exists. {@code RECEIPT}: bound to the applied receipt of the command that
     * produced this revision. {@code DIRECT_ROLE}: additionally bound to the actor,
     * organization, policy, and one-use consumption records that authorized that command.
     */
    public enum ProvenanceKind { NONE, GENESIS, RECEIPT, DIRECT_ROLE }

    public record Entry(int status, long revision, byte[] controller, byte[] value,
                        byte[] logicalValueHash, long createdHeight, long lastMutationHeight) {
        public Entry {
            controller = controller == null ? new byte[0] : controller.clone();
            value = value == null ? new byte[0] : value.clone();
            logicalValueHash = Objects.requireNonNull(logicalValueHash, "logicalValueHash").clone();
        }

        @Override public byte[] controller() { return controller.clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public byte[] logicalValueHash() { return logicalValueHash.clone(); }

        public static Entry of(AuthenticatedMapContract.Entry entry) {
            return new Entry(entry.status(), entry.revision(), entry.controller(), entry.value(),
                    entry.logicalValueHash(), entry.createdHeight(), entry.lastMutationHeight());
        }

        public byte[] encode() {
            return AuthenticatedMapContract.encodeEntry(new AuthenticatedMapContract.Entry(
                    status, revision, controller, value, logicalValueHash, createdHeight,
                    lastMutationHeight));
        }
    }

    public record Provenance(ProvenanceKind kind, String messageIdHex, long appliedHeight,
                             String actorId, String organizationId, String keyId,
                             String policyId, long policyRevision, String role) {
        public Provenance {
            Objects.requireNonNull(kind, "kind");
        }

        public static Provenance none() {
            return new Provenance(ProvenanceKind.NONE, null, 0, null, null, null, null, 0, null);
        }

        public static Provenance genesis() {
            return new Provenance(ProvenanceKind.GENESIS, null, 0, null, null, null, null, 0, null);
        }

        public static Provenance receipt(String messageIdHex, long appliedHeight) {
            return new Provenance(ProvenanceKind.RECEIPT, messageIdHex, appliedHeight,
                    null, null, null, null, 0, null);
        }
    }

    /** One state proof the answer rests on, with the key and value the caller expects it to bind. */
    public record Fact(String name, byte[] expectedKey, byte[] expectedValue, String proofJson) {
        public Fact {
            name = nonBlank(name, "name");
            expectedKey = Objects.requireNonNull(expectedKey, "expectedKey").clone();
            expectedValue = expectedValue == null ? null : expectedValue.clone();
            proofJson = nonBlank(proofJson, "proofJson");
        }

        @Override public byte[] expectedKey() { return expectedKey.clone(); }
        @Override public byte[] expectedValue() {
            return expectedValue == null ? null : expectedValue.clone();
        }
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String hex32(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be 32 bytes of lowercase hex");
        }
        return value;
    }
}
