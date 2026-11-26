package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.x.assets.cardano.support.ObserverIdentity;
import org.yanoproject.x.assets.cardano.support.ValidTransactions;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.ShardBootstrapObservationV1;
import org.yanoproject.x.assets.contracts.ShardBootstrapObservationV1.Reason;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The shard bootstrap observer {@code asset-shard-bootstrap-v1} (ADR-059 D8c, D8h §3). For every phase-2-valid
 * transaction that mints a configured vault's one-shot shard thread policy it reports whether the bootstrap is
 * verified: the mint is exactly the 16 names {@code 0x00..0x0F} at +1, every output holding a token of the policy holds
 * exactly that one token at quantity 1 and no other native asset, sits at the vault's pinned shard address and carries
 * {@code ShardDatumV1(1, i, 32 zero bytes)} for its own name {@code [i]} (judged on the decoded datum), and all 16
 * shards have their output. The policy enforces all of this except the address, which it cannot pin, so a rejected
 * bootstrap is one that sent a token elsewhere; it is reported, never silent. The policy is one-shot, so the only
 * authentic mint is the seed-spending one, and the minting transaction itself is the thread authentication.
 *
 * <p>{@code eventOrdinal} is the vault's index in the settings. Pure, deterministic, and it never throws on L1
 * content.
 */
final class AssetShardBootstrapObserver implements L1Observer {
    static final String IDENTITY_TAG = "yano-x-asset-shard-bootstrap-observer-v1";

    private final String observerId;
    private final AssetVaultShardSettings settings;

    AssetShardBootstrapObserver(String observerId, Map<String, String> settings) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        L1Observation.transaction(observerId, new byte[Hash32.BYTES], 0, 0, new byte[Hash32.BYTES], new byte[0]);
        this.observerId = observerId;
        this.settings = AssetVaultShardSettings.parse(Objects.requireNonNull(settings, "settings"));
    }

    @Override
    public String observerId() {
        return observerId;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        return new L1ObserverConsensusIdentity(ShardBootstrapObservationV1.VERSION,
                ShardBootstrapObservationV1.CLAIM_SCHEMA, 1,
                ObserverIdentity.encode(IDENTITY_TAG, settings.identityFields()));
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || blockHash == null || blockHash.length != Hash32.BYTES || slot < 0) return List.of();
        Hash32 blockId = Hash32.of(blockHash);
        List<L1Observation> observations = new ArrayList<>();
        for (ValidTransactions.Indexed indexed : ValidTransactions.of(block)) {
            TransactionBody transaction = indexed.body();
            Hash32 txId = L1Tx.txId(transaction.getTxHash());
            if (txId == null || transaction.getOutputs() == null) continue;
            for (AssetVaultShardSettings.Vault vault : settings.vaults()) {
                ShardBootstrapObservationV1 claim;
                try {
                    List<L1Tx.Token> minted = L1Tx.tokensOf(transaction.getMint(), vault.shardPolicyHex());
                    if (minted == null || minted.isEmpty()) continue;
                    claim = verify(vault, minted, transaction.getOutputs(), txId, slot, blockId);
                } catch (RuntimeException unreadable) {
                    continue; // deterministic on every member; never a halt (I2)
                }
                observations.add(L1Observation.transaction(observerId, txId.bytes(), vault.index(), slot, blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    private ShardBootstrapObservationV1 verify(AssetVaultShardSettings.Vault vault, List<L1Tx.Token> minted,
                                               List<TransactionOutput> outputs, Hash32 txId, long slot,
                                               Hash32 blockId) {
        Reason reason = exactMint(minted) ? Reason.NONE : Reason.MINT_NOT_EXACT;
        Long[] shards = new Long[ShardBootstrapObservationV1.SHARDS];
        for (int index = 0; index < outputs.size() && reason == Reason.NONE; index++) {
            TransactionOutput output = outputs.get(index);
            List<L1Tx.Token> tokens = output == null ? null
                    : L1Tx.tokensOf(output.getAmounts(), vault.shardPolicyHex());
            if (tokens == null) {
                reason = Reason.NOT_ONE_TOKEN;
            } else if (!tokens.isEmpty()) {
                L1Tx.Token token = tokens.getFirst();
                int name = token.name().length == 1 ? token.name()[0] & 0xff : -1;
                Optional<GatewayDatums.ShardDatum> datum = GatewayDatums.shardDatum(
                        L1Tx.inlineDatum(output.getInlineDatum()));
                List<L1Tx.Token> all = L1Tx.tokens(output.getAmounts());
                // The shard holds its thread token and nothing else: a foreign token could only ever leave the shard
                // through a payout's change, which the settlement builders never balance (ADR-059 D8h §3).
                if (tokens.size() != 1 || all == null || all.size() != 1 || !BigInteger.ONE.equals(token.quantity())
                        || name < 0 || name > 15 || shards[name] != null) {
                    reason = Reason.NOT_ONE_TOKEN;
                } else if (!vault.shardAddress().equals(output.getAddress())) {
                    reason = Reason.WRONG_ADDRESS;
                } else if (datum.isEmpty() || datum.get().shardIndex() != name || !datum.get().emptyRoot()) {
                    reason = Reason.BAD_DATUM;
                } else {
                    shards[name] = (long) index;
                }
            }
        }
        if (reason == Reason.NONE && Arrays.stream(shards).anyMatch(Objects::isNull)) reason = Reason.MISSING_SHARD;
        return new ShardBootstrapObservationV1(settings.domain().domainId(), vault.vaultId(), txId, slot, blockId,
                reason, reason == Reason.NONE ? List.of(shards) : List.of());
    }

    /** Exactly the names {@code 0x00..0x0F}, each once, at +1. */
    private static boolean exactMint(List<L1Tx.Token> minted) {
        boolean[] seen = new boolean[ShardBootstrapObservationV1.SHARDS];
        for (L1Tx.Token token : minted) {
            if (token.name().length != 1 || !BigInteger.ONE.equals(token.quantity())) return false;
            int name = token.name()[0] & 0xff;
            if (name > 15 || seen[name]) return false;
            seen[name] = true;
        }
        return minted.size() == ShardBootstrapObservationV1.SHARDS;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", AssetShardBootstrapObserverProvider.TYPE,
                "domainId", settings.domain().domainId().hex(),
                "vaults", settings.vaults().size(),
                "claimSchema", ShardBootstrapObservationV1.CLAIM_SCHEMA);
    }
}
