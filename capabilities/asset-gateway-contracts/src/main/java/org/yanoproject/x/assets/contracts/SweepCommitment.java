package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one canonical commitment of a {@code SWEEP_UNATTRIBUTED} claim (ADR-059 D8d): the exact unattributed outpoint
 * and its <b>full</b> value, foreign tokens included. The vault script rebuilds the same {@code ClaimId} and
 * {@code ClaimLeafV1} from the swept input's outpoint and value, so the reserving ledger must use this commitment and
 * nothing else.
 *
 * <ul>
 *   <li>{@code withdrawalKey} is {@link GatewayIds#outpointCbor} of the outpoint;</li>
 *   <li>for a token vault, {@code value} holds every native asset of the output (the vault's own asset among them)
 *       and {@code vaultLovelace} is the output's lovelace;</li>
 *   <li>for the ADA vault, {@code value} holds the output's lovelace as its ADA entry plus every native asset, and
 *       {@code vaultLovelace} is 0, as for every ADA-vault claim (V5).</li>
 * </ul>
 * The value is an {@link AssetValue}, so an output with more than {@value AssetValue#MAX_ENTRIES} entries cannot be
 * swept.
 */
public record SweepCommitment(byte[] withdrawalKey, AssetValue value, BigInteger vaultLovelace) {
    public SweepCommitment {
        Objects.requireNonNull(withdrawalKey, "withdrawal key");
        Objects.requireNonNull(value, "value");
        Quantities.requireU64(vaultLovelace, "vault lovelace");
        withdrawalKey = withdrawalKey.clone();
    }

    /** The commitment of the output {@code txId#index} holding {@code lovelace} and {@code nativeAssets}. */
    public static SweepCommitment of(AssetId vaultAsset, Hash32 txId, long index, BigInteger lovelace,
                                     Map<AssetId, BigInteger> nativeAssets) {
        Objects.requireNonNull(vaultAsset, "vault asset");
        Quantities.requireU64(lovelace, "lovelace");
        Map<AssetId, BigInteger> entries = new HashMap<>();
        for (Map.Entry<AssetId, BigInteger> entry : nativeAssets.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isAda()) {
                throw new IllegalArgumentException("native assets only");
            }
            entries.put(entry.getKey(), Quantities.requirePositiveU64(entry.getValue(), "asset quantity"));
        }
        if (vaultAsset.isAda()) {
            entries.put(AssetId.ADA, lovelace);
        }
        return new SweepCommitment(GatewayIds.outpointCbor(txId, index), AssetValue.of(entries),
                vaultAsset.isAda() ? BigInteger.ZERO : lovelace);
    }

    /** The claim's id; {@code sequence} is the reserving ledger's claim sequence. */
    public Hash32 claimId(Hash32 domainId, Hash32 vaultId, byte[] recipient, long sequence) {
        return GatewayIds.claimId(domainId, vaultId, ClaimKind.SWEEP_UNATTRIBUTED, withdrawalKey, recipient, value,
                vaultLovelace, sequence);
    }

    /** The claim's leaf. */
    public ClaimLeafV1 leaf(Hash32 claimId, Hash32 vaultId, byte[] recipient, long reservedHeight,
                            long reservedAtMs) {
        return new ClaimLeafV1(claimId, vaultId, ClaimKind.SWEEP_UNATTRIBUTED, value, vaultLovelace, recipient,
                reservedHeight, reservedAtMs);
    }

    @Override
    public byte[] withdrawalKey() {
        return withdrawalKey.clone();
    }
}
