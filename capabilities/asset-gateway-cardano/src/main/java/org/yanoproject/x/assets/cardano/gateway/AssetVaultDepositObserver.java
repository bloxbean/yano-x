package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.x.assets.cardano.support.ObserverIdentity;
import org.yanoproject.x.assets.cardano.support.OutputValues;
import org.yanoproject.x.assets.cardano.support.ValidTransactions;
import org.yanoproject.x.assets.contracts.AssetDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositClaimV1;
import org.yanoproject.x.assets.contracts.ProgrammableDepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.VaultOutputShape;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The generic multi-asset vault deposit observer (ADR-059 D5). It reports facts and never decides
 * creditability: for every phase-2-valid transaction it emits one {@link AssetDepositClaimV1} per
 * output at a configured vault address, anchored to the transaction, with
 * {@code eventOrdinal = output index}, whatever the output's value, except a
 * {@code VaultContinuationV1} output with a clean value ({@code DepositAdmission.isCleanCustodyValue}):
 * that is the vault's own custody, created by {@code Settle}, {@code Exit} or {@code Accept} or
 * donated, and never a deposit (D5, I16), so it is not reported. A continuation carrying junk is
 * reported so the consumer records the junk as unattributed custody.
 * The consumer's {@code DepositAdmission} decides what to credit (D6).
 *
 * <p>Pure and deterministic, and it never throws on L1 content (I2): a datum outside the bounds
 * or one that does not decode becomes a {@code MALFORMED} beneficiary, unrepresentable value
 * entries count as other assets, and an output the observer cannot read at all is skipped, the
 * same way on every member.
 *
 * <p>Its status counts, node-locally, the blocks it read, the last slot, and the vault outputs it reported by shape,
 * skipped as clean custody, or could not read (D15a); the ledger's diagnostics count what each became.
 */
final class AssetVaultDepositObserver implements L1Observer {
    static final String IDENTITY_TAG = "yano-x-asset-vault-deposit-observer-v1";

    private final String observerId;
    private final AssetVaultDepositSettings settings;
    private final Map<String, AssetVaultDepositSettings.Vault> vaultsByAddress;
    /** Node-local diagnostics only (D15a); never part of an observation. */
    private final ObserverCounters counters = new ObserverCounters();

    AssetVaultDepositObserver(String observerId, Map<String, String> settings) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        // Validates the id the same way every emitted observation will, so observe() never throws on it.
        L1Observation.transaction(observerId, new byte[Hash32.BYTES], 0, 0, new byte[Hash32.BYTES], new byte[0]);
        this.observerId = observerId;
        this.settings = AssetVaultDepositSettings.parse(Objects.requireNonNull(settings, "settings"));
        Map<String, AssetVaultDepositSettings.Vault> byAddress = new TreeMap<>();
        this.settings.vaults().forEach(vault -> byAddress.put(vault.address(), vault));
        this.vaultsByAddress = Map.copyOf(byAddress);
    }

    @Override
    public String observerId() {
        return observerId;
    }

    AssetVaultDepositSettings settings() {
        return settings;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        return new L1ObserverConsensusIdentity(AssetDepositClaimV1.VERSION, AssetDepositClaimV1.CLAIM_SCHEMA, 1,
                ObserverIdentity.encode(IDENTITY_TAG, settings.identityFields()));
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || blockHash == null || blockHash.length != Hash32.BYTES || slot < 0) return List.of();
        counters.block(slot);
        Hash32 blockId = Hash32.of(blockHash);
        List<L1Observation> observations = new ArrayList<>();
        for (ValidTransactions.Indexed indexed : ValidTransactions.of(block)) {
            TransactionBody transaction = indexed.body();
            Hash32 txId = txId(transaction.getTxHash());
            if (txId == null || transaction.getOutputs() == null) continue;
            List<TransactionOutput> outputs = transaction.getOutputs();
            for (int index = 0; index < outputs.size(); index++) {
                TransactionOutput output = outputs.get(index);
                AssetVaultDepositSettings.Vault vault = output == null || output.getAddress() == null
                        ? null : vaultsByAddress.get(output.getAddress());
                if (vault == null) continue;
                if (vault.cip113().isPresent()) {
                    // ADR-061 D6: a CIP-113 vault's outputs, every shape, with the transaction's raw facts.
                    byte[] programmable;
                    try {
                        programmable = programmableClaim(vault, txId, index, slot, blockId, output, transaction,
                                block, indexed.blockIndex()).encode();
                    } catch (RuntimeException unreadable) {
                        continue; // deterministic on every member; never a halt (I2)
                    }
                    observations.add(L1Observation.transaction(observerId, txId.bytes(), index, slot, blockHash,
                            programmable));
                    continue;
                }
                AssetDepositClaimV1 claim;
                try {
                    claim = claim(vault, txId, index, slot, blockId, output);
                } catch (RuntimeException unreadable) {
                    counters.count("unreadable");
                    continue; // deterministic on every member; never a halt (I2)
                }
                if (claim == null) {
                    counters.count("custody");
                    continue; // vault custody, not a fact about deposits
                }
                counters.count(claim.shape().name());
                observations.add(L1Observation.transaction(observerId, txId.bytes(), index, slot, blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    /** The claim for one vault output, or null for a clean continuation (vault custody). */
    private AssetDepositClaimV1 claim(AssetVaultDepositSettings.Vault vault, Hash32 txId, int index, long slot,
                                      Hash32 blockId, TransactionOutput output) {
        byte[] datum = inlineDatum(output.getInlineDatum());
        VaultOutputShape shape = VaultOutputShape.classify(datum);
        OutputValues.Summary summary = OutputValues.summary(OutputValues.of(output.getAmounts()), vault.asset());
        if (shape == VaultOutputShape.CONTINUATION && DepositAdmission.isCleanCustodyValue(vault.asset(),
                summary.registeredQuantity(), summary.lovelace(), summary.otherAssetCount())) {
            return null;
        }
        AssetDepositClaimV1.Beneficiary beneficiary;
        if (shape != VaultOutputShape.DEPOSIT) {
            beneficiary = new AssetDepositClaimV1.NotApplicable();
        } else if (!settings.datumBounds().accepts(datum)) {
            beneficiary = new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_OUT_OF_BOUNDS);
        } else {
            AssetDepositClaimV1.Beneficiary decoded;
            try {
                decoded = new AssetDepositClaimV1.Account(DepositDatumV1.decode(datum).beneficiaryAccount());
            } catch (IllegalArgumentException undecodable) {
                decoded = new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_UNDECODABLE);
            }
            beneficiary = decoded;
        }
        return new AssetDepositClaimV1(GatewayIds.depositId(vault.vaultId(), txId, index), vault.vaultId(), txId,
                index, slot, blockId, shape, beneficiary, summary.registeredQuantity(), summary.lovelace(),
                summary.otherAssetCount(), summary.valueHash());
    }

    /**
     * ADR-061 D6: the raw facts of one output at a CIP-113 vault's smart wallet, read from the block alone: the shape
     * by the shared deposit-shaped predicate, the datum and {@code originSpent}, the value summary, every script
     * withdrawal with the outer constructor of its reward redeemer (located by its withdrawal's ledger position), the
     * positive-mint flag of the vault asset's policy and the reference-input outpoints. Lists over their caps are
     * {@code OVERFLOW}.
     */
    private ProgrammableDepositClaimV1 programmableClaim(AssetVaultDepositSettings.Vault vault, Hash32 txId, int index,
                                                         long slot, Hash32 blockId, TransactionOutput output,
                                                         TransactionBody transaction, Block block, int blockIndex) {
        byte[] plb = vault.cip113().orElseThrow().plbHash();
        byte[] datum = inlineDatum(output.getInlineDatum());
        ProgrammableDepositClaimV1.Shape shape = ProgrammableDepositClaimV1.Shape.of(datum, plb,
                settings.addressNetworkId());
        OutputValues.Summary summary = OutputValues.summary(OutputValues.of(output.getAmounts()), vault.asset());
        List<ProgrammableDepositDatumV1.Outpoint> inputs = new ArrayList<>();
        if (transaction.getInputs() != null) {
            for (var input : transaction.getInputs()) {
                Hash32 id = txId(input.getTransactionId());
                if (id != null) inputs.add(new ProgrammableDepositDatumV1.Outpoint(id, input.getIndex()));
            }
        }
        Optional<ProgrammableDepositClaimV1.Deposit> deposit = Optional.empty();
        if (shape == ProgrammableDepositClaimV1.Shape.DEPOSIT) {
            ProgrammableDepositDatumV1 decoded = ProgrammableDepositDatumV1.depositShaped(datum, plb,
                    settings.addressNetworkId()).orElseThrow();
            deposit = Optional.of(new ProgrammableDepositClaimV1.Deposit(decoded, decoded.originSpent(inputs, index)));
        }
        List<ProgrammableDepositDatumV1.Outpoint> references = new ArrayList<>();
        if (transaction.getReferenceInputs() != null) {
            for (var input : transaction.getReferenceInputs()) {
                Hash32 id = txId(input.getTransactionId());
                if (id != null) references.add(new ProgrammableDepositDatumV1.Outpoint(id, input.getIndex()));
            }
        }
        references.sort(java.util.Comparator.comparing((ProgrammableDepositDatumV1.Outpoint o) -> o.txId().hex())
                .thenComparingLong(ProgrammableDepositDatumV1.Outpoint::index));
        List<L1Tx.Token> mint = L1Tx.tokensOf(transaction.getMint(),
                HexFormat.of().formatHex(vault.asset().policyId()));
        boolean positiveMint = mint != null && mint.stream().anyMatch(token -> token.quantity().signum() > 0);
        return new ProgrammableDepositClaimV1(vault.vaultId(), txId, index, slot, blockId, shape, deposit,
                summary.registeredQuantity(), summary.lovelace(), summary.otherAssetCount(), summary.valueHash(),
                L1Tx.scriptWithdrawals(transaction, block, blockIndex, ProgrammableDepositClaimV1.MAX_WITHDRAWALS),
                positiveMint, references.size() > ProgrammableDepositClaimV1.MAX_REFERENCE_INPUTS
                        ? Optional.empty() : Optional.of(references));
    }

    /** The inline datum bytes, or null when there is none or it is not hex (then the shape is OTHER). */
    private static byte[] inlineDatum(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            return null;
        }
    }

    private static Hash32 txId(String hex) {
        try {
            return hex == null ? null : Hash32.of(HexFormat.of().parseHex(hex));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", AssetVaultDepositObserverProvider.TYPE,
                "domainId", settings.domainId().hex(),
                "chainId", settings.chainId(),
                "vaults", settings.vaults().size(),
                "claimSchema", AssetDepositClaimV1.CLAIM_SCHEMA,
                "observed", counters.snapshot());
    }
}
