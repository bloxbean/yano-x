package com.bloxbean.cardano.yano.appchain.attest.client;

import java.util.Objects;

/**
 * Portable attestation certificate, ADR-047 §5.
 *
 * <p>The node documents (message proof, evidence bundle, state proof) are kept
 * as the JSON text the node produced, so that verification re-decodes them with
 * the same strict decoders that produced them. The certificate itself only adds
 * the subject, the copied signed envelope, and the derived anchor reference.</p>
 */
public record AttestCertificate(String generator,
                                String issuedAt,
                                String chainId,
                                String applicationId,
                                Status status,
                                Subject subject,
                                Message message,
                                String messageProofJson,
                                String evidenceJson,
                                TrailHead trailHead,
                                AnchorReference anchorReference) {

    public static final String SCHEMA = "yano-x-attest-certificate-v1";
    public static final String HASH_ALGORITHM = "sha-256";

    public AttestCertificate {
        generator = Objects.requireNonNull(generator, "generator");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        chainId = Objects.requireNonNull(chainId, "chainId");
        status = Objects.requireNonNull(status, "status");
        subject = Objects.requireNonNull(subject, "subject");
        message = Objects.requireNonNull(message, "message");
        messageProofJson = Objects.requireNonNull(messageProofJson, "messageProofJson");
        evidenceJson = Objects.requireNonNull(evidenceJson, "evidenceJson");
    }

    public enum Status { FINALIZED, ANCHORED }

    /** What was attested: a digest plus optional descriptive metadata. */
    public record Subject(String entityId,
                          String entryHashHex,
                          String fileName,
                          Long sizeBytes,
                          String mediaType,
                          String reference,
                          String label) {
        public Subject {
            entityId = Objects.requireNonNull(entityId, "entityId");
            entryHashHex = Objects.requireNonNull(entryHashHex, "entryHashHex");
        }
    }

    /** The complete signed envelope copied from the message's evidence block. */
    public record Message(String messageIdHex,
                          long height,
                          int index,
                          String topic,
                          String senderHex,
                          long senderSeq,
                          long expiresAt,
                          String bodyHex,
                          int authScheme,
                          String authProofHex) {
        public Message {
            messageIdHex = Objects.requireNonNull(messageIdHex, "messageIdHex");
            topic = Objects.requireNonNull(topic, "topic");
            senderHex = Objects.requireNonNull(senderHex, "senderHex");
            bodyHex = Objects.requireNonNull(bodyHex, "bodyHex");
            authProofHex = Objects.requireNonNull(authProofHex, "authProofHex");
        }
    }

    /** Height-pinned proof of the entity's trail head at the message block. */
    public record TrailHead(String stateProofJson, long revision, String headDigestHex) {
        public TrailHead {
            stateProofJson = Objects.requireNonNull(stateProofJson, "stateProofJson");
            headDigestHex = Objects.requireNonNull(headDigestHex, "headDigestHex");
        }
    }

    /** The evidence segment's own anchor reference plus its last state root and the mode. */
    public record AnchorReference(String chainId,
                                  String mode,
                                  long anchoredHeight,
                                  String stateRootHex,
                                  String blockHashHex,
                                  String transactionHash,
                                  long l1Slot) {
        public AnchorReference {
            chainId = Objects.requireNonNull(chainId, "chainId");
            mode = Objects.requireNonNull(mode, "mode");
            stateRootHex = Objects.requireNonNull(stateRootHex, "stateRootHex");
            blockHashHex = Objects.requireNonNull(blockHashHex, "blockHashHex");
            transactionHash = Objects.requireNonNull(transactionHash, "transactionHash");
        }
    }
}
