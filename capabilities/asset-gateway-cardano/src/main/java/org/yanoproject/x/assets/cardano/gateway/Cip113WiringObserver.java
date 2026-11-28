package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.yanoproject.x.assets.cardano.support.ObserverIdentity;
import org.yanoproject.x.assets.cardano.support.ValidTransactions;
import org.yanoproject.x.assets.contracts.Cip113WiringClaimV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code asset-cip113-wiring-v1} observer (ADR-061 D6): for every phase-2-valid transaction, one
 * {@link Cip113WiringClaimV1} per output that carries the pinned params policy's one-shot {@code ProtocolParams}
 * token, with the output's outpoint, its L1 position (slot and transaction index in the block) and the wiring fields
 * of its datum, or {@code UNDECODABLE}. It never throws on a malformed datum, and a look-alike without the token is
 * never reported. Its consensus identity pins the domain and the params policy only, never a dispatcher or delegate
 * credential: those move with the wiring and live in ledger state.
 *
 * <p>Settings ({@code yano.app-chain.observers.<id>.*}): {@code domain.*} as for the deposit observer,
 * {@code params-policy} (56 hex) and {@code min-stability-depth}.
 */
final class Cip113WiringObserver implements L1Observer {
    static final String IDENTITY_TAG = "yano-x-asset-cip113-wiring-observer-v1";
    static final byte[] TOKEN_NAME = "ProtocolParams".getBytes(StandardCharsets.US_ASCII);

    private final String observerId;
    private final Map<String, String> identity;
    private final String paramsPolicyHex;

    Cip113WiringObserver(String observerId, Map<String, String> settings) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        L1Observation.transaction(observerId, new byte[Hash32.BYTES], 0, 0, new byte[Hash32.BYTES], new byte[0]);
        this.observerId = observerId;
        Objects.requireNonNull(settings, "settings");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String key : List.of("domain.address-network-id", "domain.network-magic", "domain.chain-id",
                "domain.genesis-id")) {
            String value = settings.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
            fields.put(key, value.trim());
        }
        String policy = settings.getOrDefault("params-policy", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (policy.length() != 56) throw new IllegalArgumentException("params-policy must be 56 hex");
        HexFormat.of().parseHex(policy);
        fields.put("params-policy", policy);
        fields.put("min-stability-depth", settings.getOrDefault("min-stability-depth", "1").trim());
        for (String key : settings.keySet()) {
            if (!fields.containsKey(key) && !key.equals("type")) {
                throw new IllegalArgumentException("unknown wiring observer setting " + key);
            }
        }
        this.identity = Map.copyOf(fields);
        this.paramsPolicyHex = policy;
    }

    @Override
    public String observerId() {
        return observerId;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        List<String> fields = new ArrayList<>();
        new java.util.TreeMap<>(identity).forEach((key, value) -> fields.add(key + "=" + value));
        return new L1ObserverConsensusIdentity(Cip113WiringClaimV1.VERSION, Cip113WiringClaimV1.CLAIM_SCHEMA, 1,
                ObserverIdentity.encode(IDENTITY_TAG, fields));
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || blockHash == null || blockHash.length != Hash32.BYTES || slot < 0) return List.of();
        List<L1Observation> observations = new ArrayList<>();
        for (ValidTransactions.Indexed indexed : ValidTransactions.of(block)) {
            TransactionBody transaction = indexed.body();
            Hash32 txId = L1Tx.txId(transaction.getTxHash());
            if (txId == null || transaction.getOutputs() == null) continue;
            List<TransactionOutput> outputs = transaction.getOutputs();
            for (int index = 0; index < outputs.size(); index++) {
                TransactionOutput output = outputs.get(index);
                if (output == null || !carriesToken(output)) continue;
                byte[] datum = L1Tx.inlineDatum(output.getInlineDatum());
                Cip113WiringClaimV1 claim = new Cip113WiringClaimV1(txId, index, slot, indexed.blockIndex(),
                        Hash32.of(blockHash), datum == null ? java.util.Optional.empty()
                        : Cip113WiringClaimV1.Fields.ofDatum(datum));
                observations.add(L1Observation.transaction(observerId, txId.bytes(), index, slot, blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    private boolean carriesToken(TransactionOutput output) {
        List<L1Tx.Token> tokens = L1Tx.tokensOf(output.getAmounts(), paramsPolicyHex);
        if (tokens == null) return false;
        for (L1Tx.Token token : tokens) {
            if (Arrays.equals(token.name(), TOKEN_NAME) && BigInteger.ONE.equals(token.quantity())) return true;
        }
        return false;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of("type", Cip113WiringObserverProvider.TYPE, "paramsPolicy", paramsPolicyHex,
                "claimSchema", Cip113WiringClaimV1.CLAIM_SCHEMA);
    }
}
