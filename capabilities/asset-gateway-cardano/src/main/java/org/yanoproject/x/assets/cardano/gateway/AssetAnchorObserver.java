package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.x.assets.cardano.support.ObserverIdentity;
import org.yanoproject.x.assets.cardano.support.ValidTransactions;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The anchor observer {@code asset-anchor-v1} (ADR-059 D8b, D8h §3). For every phase-2-valid transaction it reports
 * each output that holds the pinned anchor thread token (policy and name, quantity 1) at an address whose payment
 * credential is the pinned anchor script, as {@code MpfAnchorLib} locates the anchor: the bootstrap (it mints the
 * thread policy), every advance and every heartbeat. Anything without the token is not the anchor and is ignored.
 *
 * <p>The claim carries the anchored height, block hash, state root, {@code updatedAtMs}, threshold and members when
 * the datum is a v2 anchor the vault accepts, and is {@code UNACCEPTED} otherwise ({@link AnchorObservationV1}). The
 * framework releases an observation only at {@code l1.stability-depth}, so a finalized one is an L1-stable anchor
 * output: the D8b deposit gate, the membership handoff (step 3) and the proof floor read it.
 * {@code eventOrdinal} is the output index. Pure, deterministic, and it never throws on L1 content.
 */
final class AssetAnchorObserver implements L1Observer {
    static final String IDENTITY_TAG = "yano-x-asset-anchor-observer-v1";

    private final String observerId;
    private final AssetAnchorSettings settings;
    private final byte[] applicationId;
    private final byte[] commitmentProfile;

    AssetAnchorObserver(String observerId, Map<String, String> settings) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        L1Observation.transaction(observerId, new byte[Hash32.BYTES], 0, 0, new byte[Hash32.BYTES], new byte[0]);
        this.observerId = observerId;
        this.settings = AssetAnchorSettings.parse(Objects.requireNonNull(settings, "settings"));
        this.applicationId = this.settings.applicationId().getBytes(StandardCharsets.UTF_8);
        this.commitmentProfile = this.settings.commitmentProfile().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String observerId() {
        return observerId;
    }

    AssetAnchorSettings settings() {
        return settings;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        return new L1ObserverConsensusIdentity(AnchorObservationV1.VERSION, AnchorObservationV1.CLAIM_SCHEMA, 1,
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
            List<L1Tx.Token> minted = L1Tx.tokensOf(transaction.getMint(), settings.threadPolicyHex());
            List<TransactionOutput> outputs = transaction.getOutputs();
            for (int index = 0; index < outputs.size(); index++) {
                AnchorObservationV1 claim;
                try {
                    claim = claim(outputs.get(index), index, minted, txId, slot, blockId);
                } catch (RuntimeException unreadable) {
                    continue; // deterministic on every member; never a halt (I2)
                }
                if (claim == null) continue;
                observations.add(L1Observation.transaction(observerId, txId.bytes(), index, slot, blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    private AnchorObservationV1 claim(TransactionOutput output, int index, List<L1Tx.Token> minted, Hash32 txId,
                                      long slot, Hash32 blockId) {
        if (output == null) return null;
        List<L1Tx.Token> tokens = L1Tx.tokensOf(output.getAmounts(), settings.threadPolicyHex());
        if (tokens == null) return null;
        boolean holdsThread = tokens.stream().anyMatch(token -> Arrays.equals(token.name(), settings.threadAssetName())
                && BigInteger.ONE.equals(token.quantity()));
        if (!holdsThread) return null;
        if (!Arrays.equals(L1Tx.scriptPaymentCredential(output.getAddress()), settings.scriptHash())) return null;
        return new AnchorObservationV1(txId, index, slot, blockId, minted != null && !minted.isEmpty(),
                GatewayDatums.acceptedAnchorV2(L1Tx.inlineDatum(output.getInlineDatum()), settings.genesisId(),
                        applicationId, commitmentProfile, settings.formatFingerprint()));
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", AssetAnchorObserverProvider.TYPE,
                "threadPolicyId", settings.threadPolicyHex(),
                "claimSchema", AnchorObservationV1.CLAIM_SCHEMA);
    }
}
