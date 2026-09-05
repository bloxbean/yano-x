package com.bloxbean.cardano.yano.appchain.explorer;

import java.util.List;
import java.util.Objects;

/**
 * One finalized block as the index stores it: the header as verified (or as the node's JSON view
 * when nothing could be verified), the canonical bytes when they were obtained, and the trust
 * material the evidence bundle declared.
 */
public record IndexedBlock(
        long height,
        String blockHashHex,
        String prevHashHex,
        long timestamp,
        String messagesRootHex,
        String stateRootHex,
        String proposerHex,
        int messageCount,
        int certSignatures,
        VerificationLevel level,
        String canonicalHex,
        List<String> memberKeysHex,
        int threshold,
        String anchorJson,
        String blockRecordProofJson,
        String diagnostic
) {
    public IndexedBlock {
        if (height < 1) throw new IllegalArgumentException("height must be positive");
        blockHashHex = Objects.requireNonNullElse(blockHashHex, "");
        prevHashHex = Objects.requireNonNullElse(prevHashHex, "");
        messagesRootHex = Objects.requireNonNullElse(messagesRootHex, "");
        stateRootHex = Objects.requireNonNullElse(stateRootHex, "");
        proposerHex = Objects.requireNonNullElse(proposerHex, "");
        level = Objects.requireNonNull(level, "level");
        canonicalHex = Objects.requireNonNullElse(canonicalHex, "");
        memberKeysHex = memberKeysHex == null ? List.of() : List.copyOf(memberKeysHex);
        anchorJson = Objects.requireNonNullElse(anchorJson, "");
        blockRecordProofJson = Objects.requireNonNullElse(blockRecordProofJson, "");
        diagnostic = Objects.requireNonNullElse(diagnostic, "");
    }
}
