package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockHeader;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusDigests;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.jmt.JellyfishMerkleTree;
import com.bloxbean.cardano.vds.jmt.JmtProfile;
import com.bloxbean.cardano.vds.jmt.store.InMemoryJmtStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Arrays;
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

        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, CHAIN, 1, Hex.decode("44".repeat(32)), 2,
                new byte[32], 0, new byte[0], 1234, Hex.decode("33".repeat(32)), root, List.of(), publicKey,
                new byte[]{1, 2, 3}, FinalityCert.empty());
        AppChainClient.CertifiedBlockHeader header = header(block);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(ConsensusDigests.commit(block), seed);
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
                Set.of(Hex.encode(publicKey)), 1, "44".repeat(32));

        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
        assertThat(ProofVerifier.verifyCertified(proof,
                new ProofVerifier.FinalityTrustContext(CHAIN,
                        ProofVerifier.MPF_BLAKE2B256_V1, "55".repeat(32),
                        Set.of(Hex.encode(publicKey)), 1, "44".repeat(32)))).isFalse();
        assertThat(ProofVerifier.verifyCertified(proof,
                new ProofVerifier.FinalityTrustContext(CHAIN, ProofVerifier.MPF_BLAKE2B256_V1, GENESIS,
                        Set.of(Hex.encode(publicKey)), 1, "45".repeat(32)))).isFalse();
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
        byte[] bareSignature = CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(Hex.decode(header.blockHashHex()), seed);
        AppChainClient.FinalityCertificate bareCertificate = new AppChainClient.FinalityCertificate(0,
                List.of(new AppChainClient.FinalitySignature(Hex.encode(publicKey), Hex.encode(bareSignature))));
        AppChainClient.Proof bareHashProof = new AppChainClient.Proof(
                proof.keyHex(), proof.chainId(), proof.stateRootHex(), proof.proofWireHex(), proof.valueHex(),
                null, 1L, 1, proof.profile(), proof.backend(), proof.commitmentFormatId(),
                proof.formatFingerprintHex(), proof.genesisIdHex(), proof.proofEncodingId(), false, true, 1L,
                proof.presence(), header, bareCertificate);
        assertThat(ProofVerifier.verifyCertified(bareHashProof, trust)).isFalse();
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

    private static AppChainClient.CertifiedBlockHeader header(AppBlock block) {
        AppBlockHeader header = AppBlockHeader.from(block);
        return new AppChainClient.CertifiedBlockHeader(header.version(), header.height(), Hex.encode(header.prevHash()),
                header.l1Slot(), Hex.encode(header.l1BlockHash()), header.timestamp(), Hex.encode(header.messagesRoot()),
                Hex.encode(header.stateRoot()), Hex.encode(header.blockHash()), header.view(),
                Hex.encode(header.consensusContextDigest()), Hex.encode(header.proposer()),
                Hex.encode(header.justificationDigest()));
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
