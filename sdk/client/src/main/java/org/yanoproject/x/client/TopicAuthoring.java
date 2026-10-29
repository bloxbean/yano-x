package org.yanoproject.x.client;

import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWire.WriteStatement;
import org.yanoproject.api.appchain.topics.TopicWire.WriterProof;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.util.StrictEd25519;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Client-side topic signing. Wire encodings and verification use the release-matched core implementation. */
public final class TopicAuthoring {
    private static final SecureRandom NONCES = new SecureRandom();

    private TopicAuthoring() {
    }

    /** A caller-owned Ed25519 signer, for example a key-file reader, wallet or HSM. */
    public interface Signer {
        String publicKey();

        byte[] sign(byte[] statement);
    }

    /** A fresh 128-bit write/command nonce. Reuse the complete frame only to retry the same submission. */
    public static String nonce() {
        byte[] nonce = new byte[16];
        NONCES.nextBytes(nonce);
        return Hex.encode(nonce);
    }

    public static WriteRequest prepareWrite(String chainId, String generationId, String topic, byte[] payload,
                                            String nonce, long notAfter, Delegation delegation) {
        Objects.requireNonNull(payload, "payload");
        // WriteRequest owns all validation, including external construction of offline requests.
        return new WriteRequest(new WriteStatement(chainId, generationId, topic,
                TopicWireCodec.blake2b256Hex(payload), nonce, notAfter, delegation == null ? null
                : TopicWireCodec.delegationHash(chainId, generationId, delegation)), payload, delegation);
    }

    /** Complete an offline k-of-n signing round; caller input order never changes the wire bytes. */
    public static Frame completeWrite(WriteRequest request, List<Signature> signatures) {
        List<Signature> ordered = canonicalSignatures(signatures, request.signingBytes());
        Delegation delegation = request.delegation();
        if (delegation != null && (ordered.size() != 1
                || !ordered.getFirst().key().equals(delegation.delegate()))) {
            throw new IllegalArgumentException("delegated writes require exactly the session key signature");
        }
        return new Frame(request.payload(), new WriterProof(request.statement().nonce(),
                request.statement().notAfter(), ordered, delegation));
    }

    public static Frame signWrite(WriteRequest request, List<? extends Signer> signers) {
        byte[] bytes = request.signingBytes();
        return completeWrite(request, signers.stream().map(signer -> sign(bytes, signer)).toList());
    }

    public static byte[] delegationSigningBytes(String chainId, String generationId, String delegate,
                                                String scope, long notBefore, long notAfter, String issuer) {
        return TopicWireCodec.delegationSignedBytes(chainId, generationId,
                unsignedDelegation(delegate, scope, notBefore, notAfter, issuer));
    }

    public static Delegation completeDelegation(String chainId, String generationId, String delegate,
                                                String scope, long notBefore, long notAfter, String issuer,
                                                byte[] signature) {
        Delegation delegation = new Delegation(delegate, scope, notBefore, notAfter, issuer, signature);
        if (!validDelegation(chainId, generationId, delegation)) {
            throw new IllegalArgumentException("delegation signature or scope is invalid");
        }
        return delegation;
    }

    public static Delegation issueDelegation(String chainId, String generationId, String delegate,
                                             String scope, long notBefore, long notAfter, Signer issuer) {
        byte[] bytes = delegationSigningBytes(chainId, generationId, delegate, scope, notBefore,
                notAfter, issuer.publicKey());
        return completeDelegation(chainId, generationId, delegate, scope, notBefore, notAfter,
                issuer.publicKey(), issuer.sign(bytes));
    }

    public static RegistryCommand signRegistry(CommandStatement statement, Signer signer) {
        return completeRegistry(statement, List.of(sign(TopicWireCodec.encodeCommandStatement(statement), signer)));
    }

    public static RegistryCommand addRegistrySignature(RegistryCommand command, Signer signer) {
        List<Signature> signatures = new ArrayList<>(command.signatures());
        signatures.add(sign(TopicWireCodec.encodeCommandStatement(command.statement()), signer));
        return completeRegistry(command.statement(), signatures);
    }

    public static RegistryCommand completeRegistry(CommandStatement statement, List<Signature> signatures) {
        return new RegistryCommand(statement, canonicalSignatures(signatures,
                TopicWireCodec.encodeCommandStatement(statement)));
    }

    /**
     * Checks signed authorship only. This does not establish policy authority, revocation, replay freedom,
     * current validity or finality; those require the committed registry and a trusted block proof.
     */
    public static boolean verifySignatures(String chainId, String generationId, String topic, Frame frame) {
        try {
            WriterProof proof = frame.proof();
            WriteRequest request = prepareWrite(chainId, generationId, topic, frame.payload(), proof.nonce(),
                    proof.notAfter(), proof.delegation());
            completeWrite(request, proof.signatures());
            return true;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }

    private static Delegation unsignedDelegation(String delegate, String scope, long notBefore,
                                                  long notAfter, String issuer) {
        if (!TopicNames.isValidEntryName(scope)) {
            throw new IllegalArgumentException("invalid delegation scope");
        }
        return new Delegation(delegate, scope, notBefore, notAfter, issuer, new byte[64]);
    }

    private static boolean validDelegation(String chainId, String generationId, Delegation delegation) {
        return TopicNames.isValidEntryName(delegation.scope())
                && StrictEd25519.verify(Hex.decode(delegation.issuer()), delegation.sig(),
                TopicWireCodec.delegationSignedBytes(chainId, generationId, delegation));
    }

    private static Signature sign(byte[] statement, Signer signer) {
        return new Signature(signer.publicKey(), signer.sign(statement.clone()));
    }

    private static List<Signature> canonicalSignatures(List<Signature> signatures, byte[] statement) {
        List<Signature> ordered = new ArrayList<>(Objects.requireNonNull(signatures, "signatures"));
        ordered.sort(Comparator.comparing(Signature::key));
        if (ordered.isEmpty() || ordered.size() > 16) {
            throw new IllegalArgumentException("signatures must contain 1..16 keys");
        }
        String previous = null;
        for (Signature signature : ordered) {
            if (signature.key().equals(previous) || !StrictEd25519.verify(Hex.decode(signature.key()),
                    signature.sig(), statement)) {
                throw new IllegalArgumentException("signature is invalid or its key is duplicated");
            }
            previous = signature.key();
        }
        return List.copyOf(ordered);
    }

    public record WriteRequest(WriteStatement statement, byte[] payload, Delegation delegation) {
        public WriteRequest {
            Objects.requireNonNull(statement, "statement");
            payload = Objects.requireNonNull(payload, "payload").clone();
            String delegationHash = delegation == null ? null : TopicWireCodec.delegationHash(
                    statement.chainId(), statement.generationId(), delegation);
            if (!TopicNames.isValidTopic(statement.topic())
                    || !statement.payloadHash().equals(TopicWireCodec.blake2b256Hex(payload))
                    || !Objects.equals(statement.delegationHash(), delegationHash)) {
                throw new IllegalArgumentException("write statement differs from payload or delegation");
            }
            if (delegation != null && (!validDelegation(statement.chainId(), statement.generationId(), delegation)
                    || !TopicNames.covers(delegation.scope(), statement.topic())
                    || statement.notAfter() < delegation.notBefore()
                    || statement.notAfter() > delegation.notAfter())) {
                throw new IllegalArgumentException("delegation does not cover this write");
            }
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public byte[] signingBytes() {
            return TopicWireCodec.encodeWriteStatement(statement);
        }
    }
}
