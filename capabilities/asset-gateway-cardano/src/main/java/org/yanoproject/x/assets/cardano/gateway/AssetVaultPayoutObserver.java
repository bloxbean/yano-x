package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.x.assets.cardano.support.ObserverIdentity;
import org.yanoproject.x.assets.cardano.support.OutputValues;
import org.yanoproject.x.assets.cardano.support.ValidTransactions;
import org.yanoproject.x.assets.contracts.BalanceExitObservationV1;
import org.yanoproject.x.assets.contracts.CardanoAddresses;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutClaimV1;
import org.yanoproject.x.assets.contracts.PayoutObservationV1;
import org.yanoproject.x.assets.contracts.SweepPaidV1;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The vault payout observer {@code asset-vault-payout-v1} (ADR-059 D10, D8h §4). Confirmation is authenticated by the
 * shard thread, not by tracked custody: a phase-2-valid transaction is a payout of vault {@code V} only when
 * <ul>
 *   <li>{@code V}'s shard thread policy is not in {@code tx.mint} (so the bootstrap mint is never a payout);</li>
 *   <li>exactly one output sits at {@code V}'s pinned shard address, and it holds exactly one token of the policy,
 *       quantity 1, named {@code [shardIndex]} with {@code shardIndex < 16};</li>
 *   <li>no other output holds a token of the policy.</li>
 * </ul>
 * The policy is one-shot and the tokens never leave the shard script, so such an output proves the shard validator,
 * and with it the vault validator (V4), accepted the transaction. A look-alike without the token is ignored (I2).
 *
 * <p><b>The discriminator (r15, r16).</b> On an authenticated transaction:
 * <ul>
 *   <li>a spend redeemer that names a {@code BalanceExit} holder {@code h} (constructor 7, first field a 1..64-byte
 *       byte string, read as a prefix the way the validator reads its fields by position, so trailing fields never
 *       matter) with {@code escapeNullifier(V, h)} equal to the continuation's first id: a
 *       {@link BalanceExitObservationV1}, when that continuation is output 1 and lists exactly one id. Several vault
 *       inputs may carry the same redeemer instead of {@code Follow}. The block does not carry the spent outputs, so
 *       a redeemer cannot be attributed to the vault input by its address; the nullifier binding is what makes the
 *       attribution irrelevant: a shard or foreign redeemer of the same shape (the shard validator does not check
 *       its own constructor) can match only a continuation that already lists that escape nullifier, and a claim id
 *       never equals one without a blake2b-256 collision, so it never turns a payout into a balance exit;</li>
 *   <li>otherwise, the one {@code VaultContinuationV1} output at {@code V}'s address, at index {@code k}, names the
 *       {@code k} paid claims, paid by outputs {@code 0..k-1} ({@code Settle}, {@code Exit});</li>
 *   <li>with no vault output at all: output 0's {@code SweepPaidV1(claimId)} ({@code SweepExit}), and no
 *       continuation fields (r16). A {@code SweepPaidV1} on any other transaction's output 0 is never read.</li>
 * </ul>
 * Anything else (two vault outputs, a continuation at the wrong index, an unreadable witness set) cannot come from a
 * valid vault spend and is skipped, as is an authenticated transaction with no readable spend redeemer at all. Each
 * payout output is reported as the recipient bytes the vault script compares
 * ({@link CardanoAddresses#scriptRebuiltForm}: a pointer's naturals in shortest form, as the script rebuilds them),
 * the vault asset's quantity (the lovelace, for the ADA vault) and its lovelace; {@code eventOrdinal} is the shard
 * output's index.
 *
 * <p>Pure, deterministic, and it never throws on L1 content; it reports facts and the consumer's
 * {@code PayoutConfirmation} decides (D10).
 */
final class AssetVaultPayoutObserver implements L1Observer {
    static final String IDENTITY_TAG = "yano-x-asset-vault-payout-observer-v1";

    private final String observerId;
    private final AssetVaultShardSettings settings;
    /** Node-local diagnostics only (D15a); never part of an observation. */
    private final ObserverCounters counters = new ObserverCounters();

    AssetVaultPayoutObserver(String observerId, Map<String, String> settings) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        L1Observation.transaction(observerId, new byte[Hash32.BYTES], 0, 0, new byte[Hash32.BYTES], new byte[0]);
        this.observerId = observerId;
        this.settings = AssetVaultShardSettings.parse(Objects.requireNonNull(settings, "settings"));
    }

    @Override
    public String observerId() {
        return observerId;
    }

    AssetVaultShardSettings settings() {
        return settings;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        return new L1ObserverConsensusIdentity(PayoutClaimV1.VERSION, PayoutClaimV1.CLAIM_SCHEMA, 1,
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
            Hash32 txId = L1Tx.txId(transaction.getTxHash());
            if (txId == null || transaction.getOutputs() == null) continue;
            for (AssetVaultShardSettings.Vault vault : settings.vaults()) {
                PayoutClaimV1 claim;
                try {
                    claim = claim(vault, block, indexed.blockIndex(), transaction, txId, slot, blockId);
                } catch (RuntimeException unreadable) {
                    counters.count("unreadable");
                    continue; // deterministic on every member; never a halt (I2)
                }
                if (claim == null) continue;
                counters.count(claim instanceof BalanceExitObservationV1 ? "balanceExit"
                        : ((PayoutObservationV1) claim).source().name());
                observations.add(L1Observation.transaction(observerId, txId.bytes(), claim.shardOutputIndex(), slot,
                        blockHash, claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    /** The payout claim of {@code vault} in this transaction, or null when it is not a thread-authenticated one. */
    private PayoutClaimV1 claim(AssetVaultShardSettings.Vault vault, Block block, int blockIndex,
                                TransactionBody transaction, Hash32 txId, long slot, Hash32 blockId) {
        List<L1Tx.Token> minted = L1Tx.tokensOf(transaction.getMint(), vault.shardPolicyHex());
        if (minted == null || !minted.isEmpty()) return null; // the bootstrap mint is never a payout
        List<TransactionOutput> outputs = transaction.getOutputs();
        int shardOutput = -1;
        int shardIndex = -1;
        int vaultOutputs = 0;
        int continuationIndex = -1;
        for (int index = 0; index < outputs.size(); index++) {
            TransactionOutput output = outputs.get(index);
            if (output == null) return null;
            List<L1Tx.Token> tokens = L1Tx.tokensOf(output.getAmounts(), vault.shardPolicyHex());
            if (tokens == null) return null;
            boolean atShard = vault.shardAddress().equals(output.getAddress());
            if (atShard) {
                if (shardOutput >= 0 || tokens.size() != 1) return null;
                L1Tx.Token token = tokens.getFirst();
                if (!BigInteger.ONE.equals(token.quantity()) || token.name().length != 1
                        || (token.name()[0] & 0xff) > 15) {
                    return null;
                }
                shardOutput = index;
                shardIndex = token.name()[0] & 0xff;
            } else if (!tokens.isEmpty()) {
                return null; // a shard token outside the shard address: not a vault spend this observer can read
            }
            if (vault.address().equals(output.getAddress())) {
                vaultOutputs++;
                continuationIndex = index;
            }
        }
        if (shardOutput < 0) return null; // a look-alike without the thread token (I2)
        if (vaultOutputs > 1) return null;

        if (vaultOutputs == 0) {
            Optional<SweepPaidV1> paid = SweepPaidV1.decode(L1Tx.inlineDatum(outputs.getFirst().getInlineDatum()));
            if (paid.isEmpty()) return null;
            PayoutClaimV1.Payout payout = payout(vault, outputs.getFirst());
            if (payout == null) return null;
            return new PayoutObservationV1(settings.domain().domainId(), vault.vaultId(), txId, slot, blockId,
                    shardIndex, shardOutput, PayoutObservationV1.Source.SWEEP_PAID, List.of(paid.get().claimId()),
                    List.of(payout), Optional.empty());
        }

        TransactionOutput continuationOutput = outputs.get(continuationIndex);
        Optional<List<Hash32>> ids = GatewayDatums.continuationIds(L1Tx.inlineDatum(
                continuationOutput.getInlineDatum()));
        if (ids.isEmpty() || ids.get().isEmpty()) return null;
        PayoutClaimV1.Continuation continuation = continuation(vault, continuationIndex, continuationOutput);

        List<byte[]> redeemers = L1Tx.spendRedeemers(block, blockIndex);
        // Unreadable, or no spend redeemer at all (a vault spend always has one): the discriminator is unknown, so
        // never guess (D10).
        if (redeemers == null || redeemers.isEmpty()) return null;
        Optional<byte[]> holder = balanceExitHolder(vault, redeemers, ids.get().getFirst());
        if (holder.isPresent()) {
            // The continuation lists an escape nullifier, which only a BalanceExit writes, and always at output 1.
            if (continuationIndex != 1 || ids.get().size() != 1) return null;
            PayoutClaimV1.Payout payout = payout(vault, outputs.getFirst());
            if (payout == null) return null;
            return new BalanceExitObservationV1(settings.domain().domainId(), vault.vaultId(), txId, slot, blockId,
                    shardIndex, shardOutput, ids.get().getFirst(), holder.get(), payout, continuation);
        }

        if (continuationIndex != ids.get().size()) return null; // V5/V7: payouts first, then the continuation
        List<PayoutClaimV1.Payout> payouts = new ArrayList<>();
        for (int index = 0; index < continuationIndex; index++) {
            PayoutClaimV1.Payout payout = payout(vault, outputs.get(index));
            if (payout == null) return null;
            payouts.add(payout);
        }
        return new PayoutObservationV1(settings.domain().domainId(), vault.vaultId(), txId, slot, blockId, shardIndex,
                shardOutput, PayoutObservationV1.Source.CONTINUATION, ids.get(), payouts, Optional.of(continuation));
    }

    /**
     * The holder of a spend redeemer of {@code BalanceExit} shape whose escape nullifier for {@code vault} is
     * {@code firstId}; empty when there is none. Identical redeemers on several vault inputs name the same holder.
     */
    private static Optional<byte[]> balanceExitHolder(AssetVaultShardSettings.Vault vault, List<byte[]> redeemers,
                                                      Hash32 firstId) {
        for (byte[] redeemer : redeemers) {
            Optional<byte[]> holder = GatewayDatums.balanceExitHolderId(redeemer)
                    .filter(candidate -> GatewayIds.escapeNullifier(vault.vaultId(), candidate).equals(firstId));
            if (holder.isPresent()) return holder;
        }
        return Optional.empty();
    }

    private static PayoutClaimV1.Payout payout(AssetVaultShardSettings.Vault vault, TransactionOutput output) {
        byte[] raw = L1Tx.addressBytes(output.getAddress());
        if (raw == null) return null;
        // The bytes the vault script compared with the claim, not the output's own encoding: a pointer output with
        // padded naturals pays a canonical claim. The bound applies to the rebuild, never to the raw bytes: a pointer
        // padded past 128 raw bytes still rebuilds to a canonical recipient, and dropping it would leave its batch
        // RESERVED (M4S1-F1).
        byte[] recipient = CardanoAddresses.scriptRebuiltForm(raw);
        if (recipient == null || recipient.length == 0 || recipient.length > GatewayIds.MAX_ADDRESS_BYTES) return null;
        OutputValues.Summary summary = OutputValues.summary(OutputValues.of(output.getAmounts()), vault.asset());
        return new PayoutClaimV1.Payout(recipient, summary.registeredQuantity(), summary.lovelace());
    }

    private static PayoutClaimV1.Continuation continuation(AssetVaultShardSettings.Vault vault, int index,
                                                           TransactionOutput output) {
        OutputValues.Summary summary = OutputValues.summary(OutputValues.of(output.getAmounts()), vault.asset());
        return new PayoutClaimV1.Continuation(index, summary.registeredQuantity(), summary.lovelace(),
                summary.otherAssetCount(), summary.valueHash());
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", AssetVaultPayoutObserverProvider.TYPE,
                "domainId", settings.domain().domainId().hex(),
                "vaults", settings.vaults().size(),
                "claimSchema", PayoutClaimV1.CLAIM_SCHEMA,
                "observed", counters.snapshot());
    }
}
