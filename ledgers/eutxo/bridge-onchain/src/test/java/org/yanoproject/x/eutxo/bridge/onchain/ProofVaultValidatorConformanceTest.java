package org.yanoproject.x.eutxo.bridge.onchain;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.MpfProofConverter;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.proofs.MpfNormalizedProof;
import org.yanoproject.x.eutxo.contracts.EutxoOutpoint;
import org.yanoproject.x.eutxo.contracts.EutxoStateKeys;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalClaim;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalCommitment;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PolicyId;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TokenName;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class ProofVaultValidatorConformanceTest extends ContractTest {
    private static final long MAX_TX_CPU = 10_000_000_000L;
    private static final long MAX_TX_MEM = 14_000_000L;
    private static final byte[] ROOT_POLICY = filled(0x51, 28);
    private static final byte[] NULLIFIER_POLICY = filled(0x52, 28);
    private static final byte[] VAULT_SCRIPT = filled(0x53, 28);
    private static final byte[] ROOT_SCRIPT = filled(0x54, 28);
    private static final byte[] NULLIFIER_SCRIPT = filled(0x55, 28);
    private static final String DESTINATION =
            "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";
    private static final BigInteger CLAIM_LOVELACE =
            BigInteger.valueOf(30_000_000);
    private static final BigInteger FEE = BigInteger.valueOf(1_000_000);
    private static final BigInteger VAULT_INPUT =
            BigInteger.valueOf(100_000_000);
    private static Program program;

    @BeforeAll
    static void crypto() {
        initCrypto();
    }

    @Test
    void realMpfClaimSettlesPermissionlesslyWithinBudget() {
        Fixture fixture = fixture();
        var result = evaluate(
                program(),
                context(fixture, false, false, false));

        assertSuccess(result);
        assertBudgetUnder(result, MAX_TX_CPU, MAX_TX_MEM);
        assertThat(fixture.withdrawal().encode().length)
                .as("normalized proof redeemer bytes")
                .isLessThan(16 * 1024);
        System.out.println("[ProofVaultValidator] folds="
                + fixture.proof().folds().size()
                + ", redeemerBytes=" + fixture.withdrawal().encode().length
                + ", budget=" + result.budgetConsumed());
    }

    @Test
    void wrongRootReplayOldEpochAndDuplicatePayoutFail() {
        Fixture fixture = fixture();

        assertFailure(evaluate(
                program(), context(fixture, true, false, false)));
        assertFailure(evaluate(
                program(), context(fixture, false, true, false)));
        assertFailure(evaluate(
                program(), context(fixture, false, false, true)));
        assertFailure(evaluate(
                program(), context(fixture, false, false, false, true)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, true, false, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, false, true, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, false, false, false, true)));
    }

    @Test
    void rootAndNullifierThreadsMustUseConfiguredScripts() {
        Fixture fixture = fixture();

        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        true, false, false, false, false)));
        assertFailure(evaluate(
                program(),
                context(
                        fixture,
                        false, false, false, false,
                        false, true, false, false, false)));
    }

    /**
     * A fold's 32-byte branch value hash is part of the committed root. Julc 0.1.0-pre16 silently dropped the
     * conditional branch-value update in the compiled fold loop (later versions reject that shape), so the vault
     * accepted junk in that field and rejected a root that commits to it.
     */
    @Test
    void foldBranchValueHashIsCommittedToTheRoot() {
        Fixture honest = fixture();
        int top = honest.proof().folds().size() - 1;
        byte[] branchValue = filled(0x77, 32);
        assertThat(foldRoot(honest.proof(), honest.proof().folds())).isEqualTo(honest.proof().stateRoot());

        Fixture junk = fixture(proof -> withBranchValue(proof, top, branchValue, false));
        assertFailure(evaluate(program(), context(junk, false, false, false)));
        Fixture committed = fixture(proof -> withBranchValue(proof, top, branchValue, true));
        assertSuccess(evaluate(program(), context(committed, false, false, false)));
    }

    private static MpfNormalizedProof withBranchValue(
            MpfNormalizedProof proof, int foldIndex, byte[] branchValue, boolean commitRoot) {
        List<MpfNormalizedProof.FoldStep> folds = new ArrayList<>(proof.folds());
        MpfNormalizedProof.FoldStep fold = folds.get(foldIndex);
        folds.set(foldIndex, new MpfNormalizedProof.FoldStep(
                fold.cursor(), fold.prefix(), fold.nibble(), fold.neighbors(), branchValue));
        return new MpfNormalizedProof(commitRoot ? foldRoot(proof, folds) : proof.stateRoot(),
                proof.key(), proof.value(), proof.leafSuffix(), folds, proof.committedHeight());
    }

    /** The mpf-blake2b256-v1 root of {@code proof}'s leaf under {@code folds}, branch values included. */
    private static byte[] foldRoot(MpfNormalizedProof proof, List<MpfNormalizedProof.FoldStep> folds) {
        byte[] child = hash(proof.leafSuffix(), Blake2bUtil.blake2bHash256(proof.value()));
        for (MpfNormalizedProof.FoldStep fold : folds) {
            byte[] merkle = child;
            for (int level = 0; level < 4; level++) {
                byte[] sibling = fold.neighbors().get(3 - level);
                merkle = ((fold.nibble() >> level) & 1) == 0 ? hash(merkle, sibling) : hash(sibling, merkle);
            }
            if (fold.branchValueHash().length == 32) {
                merkle = hash(merkle, hash(new byte[] {(byte) 0xff}, fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
        }
        return child;
    }

    private static byte[] hash(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return Blake2bUtil.blake2bHash256(joined);
    }

    private Program program() {
        if (program == null) {
            program = compileValidator(ProofVaultValidator.class)
                    .program()
                    .applyParams(
                            PlutusData.bytes(ROOT_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.bytes(ROOT_SCRIPT),
                            PlutusData.bytes(NULLIFIER_POLICY),
                            PlutusData.bytes(new byte[0]),
                            PlutusData.bytes(NULLIFIER_SCRIPT),
                            PlutusData.bytes(
                                    EutxoWithdrawalCommitment.DOMAIN),
                            PlutusData.bytes(
                                    EutxoStateKeys
                                            .withdrawalCommitmentPrefix()));
        }
        return program;
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch
    ) {
        return context(fixture, wrongRoot, replay, oldEpoch, false);
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch,
            boolean duplicatePayout
    ) {
        return context(
                fixture,
                wrongRoot,
                replay,
                oldEpoch,
                duplicatePayout,
                false,
                false,
                false,
                false,
                false);
    }

    private PlutusData context(
            Fixture fixture,
            boolean wrongRoot,
            boolean replay,
            boolean oldEpoch,
            boolean duplicatePayout,
            boolean rootAtWrongScript,
            boolean nullifierAtWrongScript,
            boolean mismatchedSettlementDestination,
            boolean proofUnderUnrelatedKey,
            boolean vaultPaysRelayerFee
    ) {
        Address vaultAddress = scriptAddress(VAULT_SCRIPT);
        Address rootAddress = scriptAddress(
                rootAtWrongScript ? filled(0x56, 28) : ROOT_SCRIPT);
        Address nullifierAddress = scriptAddress(
                nullifierAtWrongScript
                        ? filled(0x57, 28) : NULLIFIER_SCRIPT);
        BigInteger epoch = oldEpoch ? BigInteger.valueOf(6) : BigInteger.valueOf(7);
        BigInteger sequence = replay ? BigInteger.valueOf(4) : BigInteger.valueOf(3);
        byte[] stateRoot = wrongRoot ? new byte[32] : fixture.proof().stateRoot();

        PlutusData rootDatum = rootDatum(epoch, stateRoot);
        PlutusData currentNullifier = nullifierDatum(epoch, sequence);
        PlutusData nextNullifier = nullifierDatum(
                epoch, sequence.add(BigInteger.ONE));
        PlutusData settlement = PlutusData.constr(
                2,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(7),
                PlutusData.bytes(fixture.commitment().claimId()),
                mismatchedSettlementDestination
                        ? scriptAddress(filled(0x58, 28))
                        .toPlutusData()
                        : fixture.destination().toPlutusData(),
                PlutusData.integer(CLAIM_LOVELACE));

        TxOutRef vaultRef = new TxOutRef(
                new TxId(filled(0x61, 32)), BigInteger.ZERO);
        TxOutRef nullifierRef = new TxOutRef(
                new TxId(filled(0x62, 32)), BigInteger.ZERO);
        TxOutRef rootRef = new TxOutRef(
                new TxId(filled(0x63, 32)), BigInteger.ZERO);
        Value nullifierValue = threadedValue(
                BigInteger.valueOf(2_000_000), NULLIFIER_POLICY);
        Value rootValue = threadedValue(
                BigInteger.valueOf(2_000_000), ROOT_POLICY);
        TxOut payout = new TxOut(
                fixture.destination(),
                Value.lovelace(CLAIM_LOVELACE),
                new OutputDatum.NoOutputDatum(),
                Optional.empty());

        var builder = spendingContext(vaultRef, PlutusData.UNIT)
                .redeemer(proofUnderUnrelatedKey
                        ? fixture.unrelatedKeyProofData()
                        : fixture.proofData())
                .fee(FEE)
                .input(new TxInInfo(
                        vaultRef,
                        new TxOut(
                                vaultAddress,
                                Value.lovelace(VAULT_INPUT),
                                new OutputDatum.OutputDatumInline(
                                        PlutusData.UNIT),
                                Optional.empty())))
                .input(new TxInInfo(
                        nullifierRef,
                        new TxOut(
                                nullifierAddress,
                                nullifierValue,
                                new OutputDatum.OutputDatumInline(
                                        currentNullifier),
                                Optional.empty())))
                .referenceInput(new TxInInfo(
                        rootRef,
                        new TxOut(
                                rootAddress,
                                rootValue,
                                new OutputDatum.OutputDatumInline(rootDatum),
                                Optional.empty())))
                .output(payout)
                .output(new TxOut(
                        vaultAddress,
                        Value.lovelace(
                                VAULT_INPUT.subtract(
                                        CLAIM_LOVELACE).subtract(
                                        vaultPaysRelayerFee
                                                ? FEE
                                                : BigInteger.ZERO)),
                        new OutputDatum.OutputDatumInline(settlement),
                        Optional.empty()))
                .output(new TxOut(
                        nullifierAddress,
                        nullifierValue,
                        new OutputDatum.OutputDatumInline(nextNullifier),
                        Optional.empty()));
        if (duplicatePayout) {
            builder.output(payout);
        }
        return builder.buildPlutusData();
    }

    private static Fixture fixture() {
        return fixture(proof -> proof);
    }

    private static Fixture fixture(UnaryOperator<MpfNormalizedProof> adjust) {
        EutxoWithdrawalClaim claim = new EutxoWithdrawalClaim(
                1,
                "payments",
                7,
                new EutxoOutpoint("71".repeat(32), 0),
                DESTINATION,
                CLAIM_LOVELACE,
                filled(0x72, 32),
                3,
                42);
        EutxoWithdrawalCommitment commitment =
                EutxoWithdrawalCommitment.fromClaim(claim);
        byte[] key = EutxoStateKeys.withdrawalCommitment(claim.claimId());
        byte[] value = commitment.encode();
        byte[] unrelatedKey =
                "unrelated-state-key".getBytes(StandardCharsets.US_ASCII);
        MpfTrie trie = new MpfTrie(new MapNodeStore());
        trie.put(key, value);
        trie.put(unrelatedKey, value);
        for (int i = 0; i < 3; i++) {
            trie.put(
                    ("neighbor-" + i).getBytes(StandardCharsets.US_ASCII),
                    ("value-" + i).getBytes(StandardCharsets.US_ASCII));
        }
        byte[] root = trie.getRootHash();
        AppChainClient.Proof wire = clientProof(
                HexFormat.of().formatHex(key),
                "payments",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(key).orElseThrow()),
                HexFormat.of().formatHex(value),
                42L,
                42L);
        MpfNormalizedProof converted = MpfProofConverter.convert(wire);
        // The off-chain withdrawal envelope verifies its proof, so it keeps the unadjusted one.
        MpfNormalizedProof proof = adjust.apply(converted);
        AppChainClient.Proof unrelatedWire = clientProof(
                HexFormat.of().formatHex(unrelatedKey),
                "payments",
                HexFormat.of().formatHex(root),
                HexFormat.of().formatHex(
                        trie.getProofWire(unrelatedKey).orElseThrow()),
                HexFormat.of().formatHex(value),
                42L,
                42L);
        MpfNormalizedProof unrelatedProof =
                MpfProofConverter.convert(unrelatedWire);
        org.yanoproject.x.eutxo.contracts.EutxoProofWithdrawal withdrawal =
                new org.yanoproject.x.eutxo.contracts.EutxoProofWithdrawal(
                        1, commitment, converted);
        Address destination = destinationAddress();
        PlutusData claimData = PlutusData.constr(
                3,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(7),
                PlutusData.integer(3),
                PlutusData.bytes(commitment.claimId()),
                destination.toPlutusData(),
                PlutusData.integer(CLAIM_LOVELACE));
        PlutusData proofData = proofData(claimData, proof);
        PlutusData unrelatedProofData =
                proofData(claimData, unrelatedProof);
        return new Fixture(
                commitment,
                withdrawal,
                proof,
                destination,
                proofData,
                unrelatedProofData);
    }

    private static PlutusData proofData(
            PlutusData claimData,
            MpfNormalizedProof proof
    ) {
        PlutusData folds = PlutusData.list(proof.folds().stream()
                .map(ProofVaultValidatorConformanceTest::foldData)
                .toArray(PlutusData[]::new));
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                claimData,
                PlutusData.bytes(proof.key()),
                PlutusData.bytes(proof.value()),
                PlutusData.bytes(proof.leafSuffix()),
                folds);
    }

    private static PlutusData foldData(MpfNormalizedProof.FoldStep fold) {
        List<byte[]> neighbors = fold.neighbors();
        return PlutusData.constr(
                0,
                PlutusData.integer(fold.cursor()),
                PlutusData.bytes(fold.prefix()),
                PlutusData.integer(fold.nibble()),
                PlutusData.bytes(neighbors.get(0)),
                PlutusData.bytes(neighbors.get(1)),
                PlutusData.bytes(neighbors.get(2)),
                PlutusData.bytes(neighbors.get(3)),
                PlutusData.bytes(fold.branchValueHash()));
    }

    private static PlutusData rootDatum(
            BigInteger epoch,
            byte[] root
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(42),
                PlutusData.bytes(root),
                PlutusData.list(PlutusData.bytes(filled(1, 32))),
                PlutusData.integer(1),
                PlutusData.integer(0));
    }

    private static PlutusData nullifierDatum(
            BigInteger epoch,
            BigInteger sequence
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes("payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(epoch),
                PlutusData.integer(sequence),
                PlutusData.integer(0));
    }

    private static Address destinationAddress() {
        byte[] raw = Bech32.decode(DESTINATION).data;
        assertThat((raw[0] & 0xF0) >>> 4).isEqualTo(7);
        return new Address(
                new Credential.ScriptCredential(
                        new ScriptHash(Arrays.copyOfRange(raw, 1, 29))),
                Optional.empty());
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static Value threadedValue(
            BigInteger lovelace,
            byte[] policy
    ) {
        return Value.lovelace(lovelace).merge(Value.singleton(
                new PolicyId(policy),
                new TokenName(new byte[0]),
                BigInteger.ONE));
    }

    private static byte[] filled(int value, int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static AppChainClient.Proof clientProof(
            String keyHex,
            String chainId,
            String stateRootHex,
            String proofWireHex,
            String valueHex,
            Long finalizedAtHeight,
            Long committedHeight
    ) {
        ProofVerifier.ProfileMetadata metadata = ProofVerifier.profileMetadata(
                ProofVerifier.MPF_BLAKE2B256_V1).orElseThrow();
        return new AppChainClient.Proof(
                keyHex, chainId, stateRootHex, proofWireHex, valueHex,
                finalizedAtHeight, committedHeight, 1,
                ProofVerifier.MPF_BLAKE2B256_V1, metadata.backend(),
                metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                "11".repeat(32), metadata.proofEncodingId(),
                metadata.nativeVersioning(), metadata.physicalDelete(), committedHeight,
                AppChainClient.ProofPresence.PRESENT, null, null);
    }

    private record Fixture(
            EutxoWithdrawalCommitment commitment,
            org.yanoproject.x.eutxo.contracts.EutxoProofWithdrawal withdrawal,
            MpfNormalizedProof proof,
            Address destination,
            PlutusData proofData,
            PlutusData unrelatedKeyProofData
    ) {
    }

    private static final class MapNodeStore implements NodeStore {
        private final Map<String, byte[]> nodes = new HashMap<>();

        @Override
        public byte[] get(byte[] hash) {
            return nodes.get(HexFormat.of().formatHex(hash));
        }

        @Override
        public void put(byte[] hash, byte[] nodeBytes) {
            nodes.put(HexFormat.of().formatHex(hash), nodeBytes);
        }

        @Override
        public void delete(byte[] hash) {
            nodes.remove(HexFormat.of().formatHex(hash));
        }
    }
}
