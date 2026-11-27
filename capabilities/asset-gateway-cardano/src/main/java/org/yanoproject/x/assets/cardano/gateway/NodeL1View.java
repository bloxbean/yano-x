package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import org.yanoproject.api.rollback.RollbackCapableStore;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.x.assets.cardano.support.settlement.PayoutTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.AnchorObservationV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.GatewayDatums;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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

    /** What a point read of the view established. */
    enum Read {
        PRESENT,
        ABSENT,
        /** The read failed, or the view could not show that an empty answer is a real absence. */
        UNKNOWN
    }

    /**
     * The slot of the last L1 block applied to this node's UTxO view; empty when the view cannot tell (no view, no
     * applied point, or a failed read). It moves only as blocks are applied, so a stale view reports a stale tip.
     */
    OptionalLong appliedTipSlot() {
        UtxoState state = view.get();
        if (!(state instanceof RollbackCapableStore store)) return OptionalLong.empty();
        try {
            return OptionalLong.of(store.getLatestAppliedSlot());
        } catch (RuntimeException unavailable) {
            return OptionalLong.empty();
        }
    }

    /**
     * Whether the view has ever held {@code outpoint}, spent or unspent: the output of a transaction it applied. The
     * host's point reads answer empty on a failure too, so an empty answer is {@link Read#ABSENT} only while the
     * applied tip can be read, and {@link Read#UNKNOWN} otherwise.
     */
    Read known(ResolvedOutput.Outpoint outpoint) {
        UtxoState state = view.get();
        if (state == null) return Read.UNKNOWN;
        try {
            if (state.getUtxoSpentOrUnspent(new Outpoint(outpoint.txIdHex(), outpoint.index())).isPresent()) {
                return Read.PRESENT;
            }
        } catch (RuntimeException unavailable) {
            return Read.UNKNOWN;
        }
        return appliedTipSlot().isPresent() ? Read.ABSENT : Read.UNKNOWN;
    }

    /** {@link #custody} enough to pay {@code claims} and keep the continuation floor. */
    List<ResolvedOutput> custodyFor(VaultPins pins, Set<ResolvedOutput.Outpoint> held, List<ClaimLeafV1> claims) {
        BigInteger quantity = BigInteger.ZERO;
        BigInteger vaultLovelace = BigInteger.ZERO;
        for (ClaimLeafV1 leaf : claims) {
            quantity = quantity.add(leaf.value().quantityOf(pins.asset()));
            vaultLovelace = vaultLovelace.add(leaf.vaultLovelace());
        }
        return custody(pins, held, pins.adaVault() ? BigInteger.ZERO : quantity,
                (pins.adaVault() ? quantity : vaultLovelace).add(pins.continuationFloor()));
    }

    /**
     * The accepted custody (V10) at the vault address that no pending submission spends, paging through the address
     * until it covers {@code assetNeed} of the vault asset and {@code lovelaceNeed}, or the address is exhausted.
     */
    List<ResolvedOutput> custody(VaultPins pins, Set<ResolvedOutput.Outpoint> held, BigInteger assetNeed,
                                 BigInteger lovelaceNeed) {
        List<ResolvedOutput> custody = new ArrayList<>();
        BigInteger asset = BigInteger.ZERO;
        BigInteger lovelace = BigInteger.ZERO;
        int firstPage = 1;
        while (true) {
            Window window = window(pins.vaultAddress(), firstPage);
            for (ResolvedOutput out : window.outputs()) {
                if (held.contains(out.outpoint()) || !PayoutTxBuilder.accepted(out, pins)) continue;
                custody.add(out);
                asset = asset.add(pins.adaVault() ? BigInteger.ZERO : out.quantityOf(pins.asset()));
                lovelace = lovelace.add(out.lovelace());
            }
            boolean covered = asset.compareTo(assetNeed) >= 0 && lovelace.compareTo(lovelaceNeed) >= 0;
            if (covered || !window.more()) return custody;
            firstPage += MAX_PAGES;
        }
    }

    /** The first {@link #MAX_PAGES} pages of outputs at the address. */
    List<ResolvedOutput> at(byte[] rawAddress) {
        return window(rawAddress, 1).outputs();
    }

    /**
     * One window of outputs at the address: up to {@link #MAX_PAGES} pages from {@code firstPage}; {@code more} when
     * the window ended on a full page, so later pages may hold more.
     */
    record Window(List<ResolvedOutput> outputs, boolean more) {
    }

    Window window(byte[] rawAddress, int firstPage) {
        UtxoState state = view.get();
        List<ResolvedOutput> out = new ArrayList<>();
        if (state == null) return new Window(out, false);
        String bech32 = new Address(rawAddress).toBech32();
        for (int page = firstPage; page < firstPage + MAX_PAGES; page++) {
            List<Utxo> utxos = state.getUtxosByAddress(bech32, page, PAGE);
            for (Utxo utxo : utxos) {
                try {
                    out.add(convert(utxo));
                } catch (RuntimeException unreadable) {
                    // An output this node cannot represent is never selected.
                }
            }
            if (utxos.size() < PAGE) return new Window(out, false);
        }
        return new Window(out, true);
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
