package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * This node's own L1 UTxO view in the settlement core's protocol-neutral form (ADR-059 D8h §1: every input is resolved
 * against the member's own view). Reads are bounded pages; an unavailable view resolves nothing, so the body check
 * refuses and the builder retries.
 */
final class NodeL1View {
    static final int PAGE = 100;
    static final int MAX_PAGES = 50;

    private final Supplier<UtxoState> view;

    NodeL1View(Supplier<UtxoState> view) {
        this.view = view;
    }

    Optional<ResolvedOutput> resolve(ResolvedOutput.Outpoint outpoint) {
        UtxoState state = view.get();
        if (state == null) return Optional.empty();
        try {
            return state.getUtxo(outpoint.txIdHex(), outpoint.index()).map(NodeL1View::convert);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /** Whether {@code outpoint} is unspent in this node's view; empty when the view cannot answer. */
    Optional<Boolean> unspent(ResolvedOutput.Outpoint outpoint) {
        UtxoState state = view.get();
        if (state == null) return Optional.empty();
        try {
            return Optional.of(state.getUtxo(outpoint.txIdHex(), outpoint.index()).isPresent());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    List<ResolvedOutput> at(byte[] rawAddress) {
        UtxoState state = view.get();
        List<ResolvedOutput> out = new ArrayList<>();
        if (state == null) return out;
        String bech32 = new Address(rawAddress).toBech32();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<Utxo> utxos = state.getUtxosByAddress(bech32, page, PAGE);
            for (Utxo utxo : utxos) {
                try {
                    out.add(convert(utxo));
                } catch (RuntimeException unreadable) {
                    // An output this node cannot represent is never selected.
                }
            }
            if (utxos.size() < PAGE) break;
        }
        return out;
    }

    /** The live anchor: the one output at the pinned anchor script carrying the thread token with an accepted datum. */
    Optional<Anchored> anchor(VaultPins pins) {
        byte[] address = new byte[29];
        address[0] = (byte) (0x70 | pins.addressNetworkId());
        System.arraycopy(pins.anchor().scriptHash(), 0, address, 1, 28);
        VaultPins.Anchor pinned = pins.anchor();
        for (ResolvedOutput out : at(address)) {
            if (!out.quantityOf(pins.anchorToken()).equals(BigInteger.ONE)) continue;
            Optional<AnchorObservationV1.Anchored> datum = out.inlineDatum().flatMap(bytes ->
                    GatewayDatums.acceptedAnchorV2(bytes, pinned.genesisId(), pinned.applicationId(),
                            pinned.commitmentProfile(), pinned.formatFingerprint()));
            if (datum.isPresent()) return Optional.of(new Anchored(out, datum.get()));
        }
        return Optional.empty();
    }

    record Anchored(ResolvedOutput output, AnchorObservationV1.Anchored datum) {
    }

    Optional<ResolvedOutput> exitMode(VaultPins pins) {
        byte[] address = new byte[29];
        address[0] = (byte) (0x70 | pins.addressNetworkId());
        System.arraycopy(pins.exitModeScriptHash(), 0, address, 1, 28);
        return at(address).stream().filter(out -> out.quantityOf(pins.exitModeToken()).equals(BigInteger.ONE))
                .findFirst();
    }

    Optional<ResolvedOutput> shard(VaultPins pins, int shard) {
        return at(pins.shardAddress()).stream()
                .filter(out -> out.quantityOf(pins.shardToken(shard)).equals(BigInteger.ONE)).findFirst();
    }

    static ResolvedOutput convert(Utxo utxo) {
        TreeMap<AssetId, BigInteger> assets = new TreeMap<>();
        if (utxo.assets() != null) {
            for (AssetAmount asset : utxo.assets()) {
                if (asset.quantity().signum() <= 0) continue;
                assets.merge(AssetId.of(HexFormat.of().parseHex(asset.policyId()),
                        HexFormat.of().parseHex(asset.assetName() == null ? "" : asset.assetName())),
                        asset.quantity(), BigInteger::add);
            }
        }
        Optional<ResolvedOutput.ReferenceScript> script = Optional.empty();
        if (utxo.referenceScriptHash() != null && !utxo.referenceScriptHash().isBlank()) {
            int size = utxo.scriptRef() == null ? 0 : utxo.scriptRef().length() / 2;
            script = Optional.of(new ResolvedOutput.ReferenceScript(
                    HexFormat.of().parseHex(utxo.referenceScriptHash()), size, Optional.empty()));
        }
        return new ResolvedOutput(new ResolvedOutput.Outpoint(Hash32.ofHex(utxo.outpoint().txHash()),
                utxo.outpoint().index()), new Address(utxo.address()).getBytes(), utxo.lovelace(), assets,
                Optional.ofNullable(utxo.inlineDatum()), script);
    }
}
