package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;

import java.util.List;
import java.util.Map;

/** Fixtures the catalog seam test (in the host's package) needs from this package's test fixtures. */
public final class AnchorSeamFixtures {
    public static final String OBSERVER = "anchors";

    private AnchorSeamFixtures() {
    }

    /** The {@code asset-anchor-v1} observer's settings for the test anchor identity. */
    public static Map<String, String> observerSettings() {
        return AssetAnchorObserverTest.anchorSettings();
    }

    /**
     * The full CBOR of one L1 transaction ({@code nonce} makes its id unique) that moves the anchor thread to an
     * anchor datum v2 at {@code height} with {@code members} and {@code threshold}, for a block producer.
     */
    public static byte[] anchorAdvanceTransaction(int nonce, long height, int threshold, Hash32... members) {
        return L1BlockBytes.transaction(new L1BlockBytes.Tx(nonce, List.of(new L1BlockBytes.Output(
                SettlementFixtures.raw(AssetAnchorObserverTest.ANCHOR), 2_000_000,
                Map.of(org.yanoproject.x.assets.contracts.AssetId.of(
                        java.util.HexFormat.of().parseHex(AssetAnchorObserverTest.THREAD_POLICY),
                        AssetAnchorObserverTest.THREAD_NAME), 1L),
                AssetAnchorObserverTest.anchorDatum(height, threshold, 5_000, members)))));
    }

    /**
     * What {@code observer} reports for one L1 transaction that moves the anchor thread to an anchor datum v2 at
     * {@code height} with {@code members} (32-byte keys) and {@code threshold}.
     */
    public static List<L1Observation> anchorAdvance(L1Observer observer, int hashByte, long height, int threshold,
                                                    Hash32... members) {
        TransactionBody tx = SettlementFixtures.tx(hashByte, List.of(), SettlementFixtures.output(
                AssetAnchorObserverTest.ANCHOR, AssetAnchorObserverTest.anchorDatum(height, threshold, 5_000, members),
                SettlementFixtures.lovelace(2_000_000), SettlementFixtures.token(AssetAnchorObserverTest.THREAD_POLICY,
                        AssetAnchorObserverTest.THREAD_NAME, 1)));
        return observer.observe(SettlementFixtures.SLOT, SettlementFixtures.BLOCK_HASH,
                SettlementFixtures.block(List.of(), List.of(tx), List.of(Witnesses.builder().build())));
    }
}
