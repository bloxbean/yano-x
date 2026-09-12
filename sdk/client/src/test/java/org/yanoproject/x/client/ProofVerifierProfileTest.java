package org.yanoproject.x.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.yanoproject.api.appchain.AppBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProofVerifierProfileTest {
    private static final String CHAIN = "proof-chain";
    private static final String GENESIS = "22".repeat(32);

    @Test
    void dispatchesClassicJmtInclusionExclusionAndTombstoneByExactProfile() {
        InMemoryJmtStore store = new InMemoryJmtStore();
        JellyfishMerkleTree tree = new JellyfishMerkleTree(
                store, JmtProfile.classicBlake2b256V1());
        byte[] presentKey = "present".getBytes(StandardCharsets.US_ASCII);
        byte[] tombstoneKey = "deleted".getBytes(StandardCharsets.US_ASCII);
        byte[] missingKey = "missing".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "value".getBytes(StandardCharsets.US_ASCII);
        byte[] tombstone = "tombstone".getBytes(StandardCharsets.US_ASCII);
        byte[] root = tree.put(1, Map.of(presentKey, value, tombstoneKey, tombstone)).rootHash();

        AppChainClient.Proof inclusion = proof(
                presentKey, value, tree.getProofWire(presentKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.PRESENT, ProofVerifier.JMT_BLAKE2B256_V1);
        AppChainClient.Proof deleted = proof(
                tombstoneKey, tombstone, tree.getProofWire(tombstoneKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.TOMBSTONED, ProofVerifier.JMT_BLAKE2B256_V1);
        AppChainClient.Proof exclusion = proof(
                missingKey, null, tree.getProofWire(missingKey, 1).orElseThrow(), root,
                AppChainClient.ProofPresence.ABSENT, ProofVerifier.JMT_BLAKE2B256_V1);
        ProofVerifier.TrustedStateRoot trusted = trusted(root, ProofVerifier.JMT_BLAKE2B256_V1);

        assertThat(ProofVerifier.verify(inclusion, trusted)).isTrue();
        assertThat(ProofVerifier.verify(deleted, trusted)).isTrue();
        assertThat(ProofVerifier.verify(exclusion, trusted)).isTrue();
        assertThat(ProofVerifier.verify(inclusion,
                trusted(root, ProofVerifier.MPF_BLAKE2B256_V1))).isFalse();
        assertThat(ProofVerifier.verify(proof(
                presentKey, value, inclusion.proofWireHex().isEmpty()
                        ? new byte[]{1} : Hex.decode(inclusion.proofWireHex()), root,
                AppChainClient.ProofPresence.PRESENT, ProofVerifier.MPF_BLAKE2B256_V1),
                trusted(root, ProofVerifier.MPF_BLAKE2B256_V1))).isFalse();
    }

    @Test
    void verifiesCertifiedMpfProofOnlyUnderPinnedMembershipAndIdentity() throws Exception {
        MemoryNodeStore store = new MemoryNodeStore();
        MpfTrie trie = new MpfTrie(store);
        byte[] key = "certified".getBytes(StandardCharsets.US_ASCII);
        byte[] value = "entry".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, value);
        byte[] root = trie.getRootHash();
        byte[] wire = trie.getProofWire(key).orElseThrow();

        AppChainClient.CertifiedBlockHeader unsignedHeader = new AppChainClient.CertifiedBlockHeader(
                AppBlock.BLOCK_VERSION, 1, "00".repeat(32), 0, "", 1234,
                "33".repeat(32), Hex.encode(root), "00".repeat(32),
                2, "77".repeat(32), "88".repeat(32), "99".repeat(32));
        byte[] blockHash = blockHash(CHAIN, unsignedHeader);
        AppChainClient.CertifiedBlockHeader header = new AppChainClient.CertifiedBlockHeader(
                AppBlock.BLOCK_VERSION, 1, unsignedHeader.prevHashHex(), 0, "", 1234,
                unsignedHeader.messagesRootHex(), Hex.encode(root), Hex.encode(blockHash),
                unsignedHeader.view(), unsignedHeader.consensusContextDigestHex(),
                unsignedHeader.proposerHex(), unsignedHeader.justificationDigestHex());
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) 7);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(header.canonicalHeader(CHAIN).commitDigest(), seed);
        AppChainClient.FinalityCertificate certificate = new AppChainClient.FinalityCertificate(
                0, List.of(new AppChainClient.FinalitySignature(
                Hex.encode(publicKey), Hex.encode(signature))));
        ProofVerifier.ProfileMetadata mpf = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        AppChainClient.Proof proof = new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root), Hex.encode(wire), Hex.encode(value),
                null, 1L, 1, ProofVerifier.MPF_BLAKE2B256_V1, "mpf",
                "mpf-blake2b256-format-v1", mpf.formatFingerprintHex(), GENESIS,
                "mpf-proof-wire-v1", false, true, 1L,
                AppChainClient.ProofPresence.PRESENT, header, certificate);
        ProofVerifier.FinalityTrustContext trust = new ProofVerifier.FinalityTrustContext(
                CHAIN, ProofVerifier.MPF_BLAKE2B256_V1, GENESIS,
                Set.of(Hex.encode(publicKey)), 1, header.consensusContextDigestHex());

        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
        assertThat(ProofVerifier.verifyCertified(proof,
                new ProofVerifier.FinalityTrustContext(CHAIN,
                        ProofVerifier.MPF_BLAKE2B256_V1, "55".repeat(32),
                        Set.of(Hex.encode(publicKey)), 1, header.consensusContextDigestHex()))).isFalse();
        assertThat(ProofVerifier.verifyCertified(proof,
                new ProofVerifier.FinalityTrustContext(CHAIN, proof.profile(), GENESIS,
                        Set.of(Hex.encode(publicKey)), 1, "ab".repeat(32)))).isFalse();
        byte[] oldSignature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(blockHash, seed);
        var oldCertificate = new AppChainClient.FinalityCertificate(0, List.of(
                new AppChainClient.FinalitySignature(Hex.encode(publicKey), Hex.encode(oldSignature))));
        var wrongSigningDomain = new AppChainClient.Proof(
                proof.keyHex(), proof.chainId(), proof.stateRootHex(), proof.proofWireHex(),
                proof.valueHex(), null, 1L, 1, proof.profile(), proof.backend(),
                proof.commitmentFormatId(), proof.formatFingerprintHex(), proof.genesisIdHex(),
                proof.proofEncodingId(), false, true, 1L, proof.presence(), header, oldCertificate);
        assertThat(ProofVerifier.verifyCertified(wrongSigningDomain, trust)).isFalse();
        AppChainClient.CertifiedBlockHeader wrongRoot = new AppChainClient.CertifiedBlockHeader(
                AppBlock.BLOCK_VERSION, 1, header.prevHashHex(), 0, "", 1234, header.messagesRootHex(),
                "66".repeat(32), header.blockHashHex(), header.view(), header.consensusContextDigestHex(),
                header.proposerHex(), header.justificationDigestHex());
        AppChainClient.Proof substituted = new AppChainClient.Proof(
                proof.keyHex(), proof.chainId(), proof.stateRootHex(), proof.proofWireHex(),
                proof.valueHex(), null, 1L, 1, proof.profile(), proof.backend(),
                proof.commitmentFormatId(), proof.formatFingerprintHex(), proof.genesisIdHex(),
                proof.proofEncodingId(), false, true, 1L,
                proof.presence(), wrongRoot, certificate);
        assertThat(ProofVerifier.verifyCertified(substituted, trust)).isFalse();
    }

    @Test
    void verifiesRecordedMainV3CertificateAndRejectsHeaderSubstitution() throws Exception {
        // Public fixture from a three-node devnet on Yano main 310b9f37b.
        String envelope;
        try (var input = getClass().getResourceAsStream("/proofs/main-310b9f37b-certified-orders.json")) {
            envelope = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var trust = new ProofVerifier.FinalityTrustContext(
                "orders", "mpf-blake2b256-v1", "65102305e1a681d0fc8e0223c387923f724e0c5bd321d3f487cdd5d247a5785f",
                Set.of("0792078bec62e68fba7e99e4378f6f0f2b29c3a6be5d8bbbcb10c3c9ef252fe3",
                        "3efd3d32ef4ad6c5577b50f6415601d11a637c65f9baa28ccffd3cf194e7f725",
                        "116dc8cc85726aca9f7c9f54c6814e46de808d186245353f8df5c4f12766a183"), 2,
                "96381c1eda9868c40ca2735bf8100c5878fa3882ae8be4976cb8f7593954fe90");
        assertThat(ProofVerifier.verifyCertified(AppChainClient.decodeProofEnvelope(envelope), trust)).isTrue();
        var json = new ObjectMapper();
        for (String field : List.of("consensusContextDigest", "proposer", "justificationDigest", "view")) {
            var changed = json.readTree(envelope);
            var block = (ObjectNode) changed.path("block");
            if (field.equals("view")) block.put(field, 1);
            else block.put(field, "ff".repeat(32));
            assertThat(ProofVerifier.verifyCertified(
                    AppChainClient.decodeProofEnvelope(json.writeValueAsString(changed)), trust))
                    .as("tampered %s", field).isFalse();
        }
    }

    private static AppChainClient.Proof proof(
            byte[] key,
            byte[] value,
            byte[] wire,
            byte[] root,
            AppChainClient.ProofPresence presence,
            String profile
    ) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(profile)
                .orElseThrow();
        return new AppChainClient.Proof(
                Hex.encode(key), CHAIN, Hex.encode(root), Hex.encode(wire),
                value != null ? Hex.encode(value) : null, null, 1L, 1,
                profile, metadata.backend(), metadata.commitmentFormatId(),
                metadata.formatFingerprintHex(), GENESIS,
                metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), 1L,
                presence, null, null);
    }

    private static ProofVerifier.TrustedStateRoot trusted(byte[] root, String profile) {
        return new ProofVerifier.TrustedStateRoot(
                CHAIN, profile, GENESIS, 1, Hex.encode(root),
                ProofVerifier.TrustedRootSource.CALLER_PINNED);
    }

    private static byte[] blockHash(
            String chainId,
            AppChainClient.CertifiedBlockHeader block
    ) throws Exception {
        return block.canonicalHeader(chainId).blockHash();
    }

    private static final class MemoryNodeStore implements NodeStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override public byte[] get(byte[] hash) { return values.get(Hex.encode(hash)); }
        @Override public void put(byte[] hash, byte[] nodeBytes) {
            values.put(Hex.encode(hash), nodeBytes);
        }
        @Override public void delete(byte[] hash) { values.remove(Hex.encode(hash)); }
    }
}
