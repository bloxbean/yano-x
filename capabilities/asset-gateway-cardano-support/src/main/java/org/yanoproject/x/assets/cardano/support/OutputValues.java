package org.yanoproject.x.assets.cardano.support;

import com.bloxbean.cardano.yaci.core.model.Amount;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.CardanoValueCbor;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.Quantities;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Turns a parsed L1 output value into asset-gateway terms (ADR-059 D3, D5): the lovelace, a
 * canonical map of native assets, and the bounded summary that goes into a deposit claim.
 *
 * <p>Never throws on L1 content. An entry that cannot be represented (a quantity outside u64, a
 * policy id that is not 28 bytes, a name longer than 32 bytes) is counted in
 * {@link ObservedValue#unrepresentable()} and left out of the asset map, so it makes the output
 * non-creditable through the other-asset count. The Cardano ledger rejects such outputs anyway;
 * this is defence in depth. Zero quantities are ignored, and repeated entries of one asset are
 * summed with checked u64 arithmetic.
 */
public final class OutputValues {
    private static final String LOVELACE = "lovelace";

    private OutputValues() {
    }

    /** The decoded value of one output. */
    public record ObservedValue(BigInteger lovelace, NavigableMap<AssetId, BigInteger> assets,
                                int unrepresentable) {
        public ObservedValue {
            assets = Collections.unmodifiableNavigableMap(new TreeMap<>(assets));
        }

        /** {@code blake2b-256} of the canonical Cardano value of the representable part. */
        public Hash32 valueHash() {
            return CardanoValueCbor.hash(lovelace, assets);
        }
    }

    /** The bounded summary of D5: one registered asset's quantity, lovelace, other assets, value hash. */
    public record Summary(BigInteger registeredQuantity, BigInteger lovelace, long otherAssetCount, Hash32 valueHash) { }

    public static ObservedValue of(List<Amount> amounts) {
        BigInteger lovelace = BigInteger.ZERO;
        TreeMap<AssetId, BigInteger> assets = new TreeMap<>();
        int unrepresentable = 0;
        if (amounts == null) return new ObservedValue(lovelace, assets, 0);
        for (Amount amount : amounts) {
            if (amount == null) continue;
            BigInteger quantity = amount.getQuantity();
            if (quantity == null || quantity.signum() == 0) continue;
            if (quantity.signum() < 0 || quantity.compareTo(Quantities.MAX_U64) > 0) {
                unrepresentable++;
                continue;
            }
            if (LOVELACE.equals(amount.getUnit())) {
                BigInteger sum = lovelace.add(quantity);
                if (sum.compareTo(Quantities.MAX_U64) > 0) {
                    unrepresentable++;
                } else {
                    lovelace = sum;
                }
                continue;
            }
            AssetId asset = assetOf(amount);
            if (asset == null) {
                unrepresentable++;
                continue;
            }
            BigInteger sum = assets.getOrDefault(asset, BigInteger.ZERO).add(quantity);
            if (sum.compareTo(Quantities.MAX_U64) > 0) {
                unrepresentable++;
            } else {
                assets.put(asset, sum);
            }
        }
        return new ObservedValue(lovelace, assets, unrepresentable);
    }

    /** The claim summary for a vault whose registered asset is {@code registered}. */
    public static Summary summary(ObservedValue value, AssetId registered) {
        BigInteger registeredQuantity = registered.isAda()
                ? value.lovelace() : value.assets().getOrDefault(registered, BigInteger.ZERO);
        long others = value.unrepresentable();
        for (AssetId asset : value.assets().keySet()) {
            if (!asset.equals(registered)) others++;
        }
        return new Summary(registeredQuantity, value.lovelace(), others, value.valueHash());
    }

    /** The asset an amount names, or null when its policy id or name is not representable. */
    private static AssetId assetOf(Amount amount) {
        try {
            String policyHex = amount.getPolicyId();
            byte[] name = amount.getAssetNameBytes();
            String unit = amount.getUnit();
            if (policyHex == null && unit != null && unit.length() >= 56) policyHex = unit.substring(0, 56);
            if (name == null && unit != null && unit.length() > 56) {
                String rest = unit.substring(56);
                name = HexFormat.of().parseHex(rest.startsWith(".") ? rest.substring(1) : rest);
            }
            if (policyHex == null) return null;
            byte[] policy = HexFormat.of().parseHex(policyHex);
            if (policy.length != AssetId.POLICY_ID_BYTES) return null;
            return AssetId.of(policy, name == null ? new byte[0] : name);
        } catch (IllegalArgumentException unrepresentable) {
            return null;
        }
    }
}
