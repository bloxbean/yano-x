package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixtures the catalog seam test (in the host's package) needs from this package's test fixtures. */
public final class AnchorSeamFixtures {
    public static final String OBSERVER = "anchors";

    private AnchorSeamFixtures() {
    }

    /**
     * One anchor identity (D8b): what the {@code asset-anchor-v1} observer watches and what the chain pins with
     * {@code anchor-profile.pinned.*}; {@code chainId} is the anchor datum's chain id. Hex values are lowercase.
     */
    public record Identity(String chainId, String threadPolicyHex, byte[] threadAssetName, byte[] scriptHash,
                           String genesisHex, String applicationId, String commitmentProfile,
                           String formatFingerprintHex) {
        /** The {@code asset-anchor-v1} observer's settings for this identity. */
        public Map<String, String> observerSettings() {
            Map<String, String> settings = new LinkedHashMap<>();
            settings.put("type", AssetAnchorObserverProvider.TYPE);
            pin().forEach((key, value) -> settings.put("anchor." + key, value));
            return settings;
        }

        /** The chain's {@code anchor-profile.pinned.*} settings naming this identity. */
        public Map<String, String> pinnedSettings() {
            Map<String, String> settings = new LinkedHashMap<>();
            pin().forEach((key, value) -> settings.put(AnchorHandoffGate.PINNED_PREFIX + key, value));
            return settings;
        }

        private Map<String, String> pin() {
            Map<String, String> pin = new LinkedHashMap<>();
            pin.put("thread-policy-id", threadPolicyHex);
            pin.put("thread-asset-name", HexFormat.of().formatHex(threadAssetName));
            pin.put("script-hash", HexFormat.of().formatHex(scriptHash));
            pin.put("genesis-id", genesisHex);
            pin.put("application-id", applicationId);
            pin.put("commitment-profile", commitmentProfile);
            pin.put("format-fingerprint", formatFingerprintHex);
            return pin;
        }

        String anchorAddress() {
            return SettlementFixtures.enterprise(scriptHash);
        }

        /** An anchor datum v2 of this identity in the on-chain ABI. */
        byte[] anchorDatum(long height, int threshold, long updatedAtMs, Hash32... members) {
            byte[][] keys = new byte[members.length][];
            for (int i = 0; i < members.length; i++) keys[i] = SettlementFixtures.bytes(members[i].bytes());
            return SettlementFixtures.constr(0, SettlementFixtures.integer(2),
                    SettlementFixtures.bytes(chainId.getBytes(StandardCharsets.UTF_8)),
                    SettlementFixtures.bytes(HexFormat.of().parseHex(genesisHex)),
                    SettlementFixtures.bytes(applicationId.getBytes(StandardCharsets.UTF_8)),
                    SettlementFixtures.bytes(commitmentProfile.getBytes(StandardCharsets.UTF_8)),
                    SettlementFixtures.bytes(HexFormat.of().parseHex(formatFingerprintHex)),
                    SettlementFixtures.integer(height), SettlementFixtures.bytes(SettlementFixtures.filled(32, 0xbb)),
                    SettlementFixtures.bytes(SettlementFixtures.filled(32, 0x55)), SettlementFixtures.list(keys),
                    SettlementFixtures.integer(threshold), SettlementFixtures.integer(updatedAtMs));
        }
    }

    /**
     * The full CBOR of one L1 transaction ({@code nonce} makes its id unique) that moves the anchor thread of
     * {@code identity} to an anchor datum v2 at {@code height} with {@code members} and {@code threshold}, for a
     * block producer.
     */
    public static byte[] anchorAdvanceTransaction(Identity identity, int nonce, long height, int threshold,
                                                  Hash32... members) {
        return L1BlockBytes.transaction(new L1BlockBytes.Tx(nonce, List.of(new L1BlockBytes.Output(
                SettlementFixtures.raw(identity.anchorAddress()), 2_000_000,
                Map.of(AssetId.of(HexFormat.of().parseHex(identity.threadPolicyHex()), identity.threadAssetName()),
                        1L),
                identity.anchorDatum(height, threshold, 5_000, members)))));
    }

    /**
     * What {@code observer} reports for one L1 transaction that moves the anchor thread of {@code identity} to an
     * anchor datum v2 at {@code height} with {@code members} (32-byte keys) and {@code threshold}.
     */
    public static List<L1Observation> anchorAdvance(L1Observer observer, Identity identity, int hashByte, long height,
                                                    int threshold, Hash32... members) {
        TransactionBody tx = SettlementFixtures.tx(hashByte, List.of(), SettlementFixtures.output(
                identity.anchorAddress(), identity.anchorDatum(height, threshold, 5_000, members),
                SettlementFixtures.lovelace(2_000_000), SettlementFixtures.token(identity.threadPolicyHex(),
                        identity.threadAssetName(), 1)));
        return observer.observe(SettlementFixtures.SLOT, SettlementFixtures.BLOCK_HASH,
                SettlementFixtures.block(List.of(), List.of(tx), List.of(Witnesses.builder().build())));
    }
}
