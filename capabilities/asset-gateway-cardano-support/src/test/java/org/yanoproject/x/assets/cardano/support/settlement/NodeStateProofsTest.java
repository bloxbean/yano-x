package org.yanoproject.x.assets.cardano.support.settlement;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Node proofs at the referenced anchor's height (ADR-059 D8b, D9 step 2): a native proof normalizes to exactly the
 * folds the client SDK's converter produces; the anchored source answers only proofs of the requested key at the
 * anchored height and root; and an {@code Exit} whose claim and status proofs come from the node at the anchored
 * height passes the compiled validators.
 */
class NodeStateProofsTest {
    @Test
    void nativeProofsNormalizeToTheSdkFolds() {
        PayoutWorld.StateTrie trie = new PayoutWorld.StateTrie();
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] key = ("ap/k/" + i).getBytes(StandardCharsets.US_ASCII);
            trie.put(key, ("value-" + i).getBytes(StandardCharsets.US_ASCII));
            keys.add(key);
        }
        for (byte[] key : keys) {
            StateProof sdk = trie.proof(key);
            StateProof node = NodeStateProofs.normalize(trie.nativeProof(key, 42));
            assertThat(node.toPlutusData().serializeToHex()).isEqualTo(sdk.toPlutusData().serializeToHex());
        }
    }

    @Test
    void theAnchoredSourceAnswersOnlyItsHeightRootAndKey() {
        PayoutWorld.StateTrie trie = new PayoutWorld.StateTrie();
        byte[] key = "ap/k/1".getBytes(StandardCharsets.US_ASCII);
        byte[] other = "ap/k/2".getBytes(StandardCharsets.US_ASCII);
        trie.put(key, new byte[] {1});
        trie.put(other, new byte[] {2});
        byte[] root = trie.root();
        List<Long> asked = new ArrayList<>();
        StateProof.Source source = NodeStateProofs.at(7, root, (height, k) -> {
            asked.add(height);
            return Optional.of(trie.nativeProof(k, height));
        });
        assertThat(source.inclusion(key)).isPresent();
        assertThat(asked).containsOnly(7L);
        // Another height, root or key, or a tampered value, reads as unavailable.
        assertThat(NodeStateProofs.at(7, root, (height, k) -> Optional.of(trie.nativeProof(k, 8))).inclusion(key))
                .isEmpty();
        assertThat(NodeStateProofs.at(7, new byte[32], (height, k) -> Optional.of(trie.nativeProof(k, 7)))
                .inclusion(key)).isEmpty();
        assertThat(NodeStateProofs.at(7, root, (height, k) -> Optional.of(trie.nativeProof(other, 7)))
                .inclusion(key)).isEmpty();
        NodeStateProofs.NativeProof honest = trie.nativeProof(key, 7);
        NodeStateProofs.NativeProof tampered = new NodeStateProofs.NativeProof(7, root, key, new byte[] {9},
                honest.wire());
        assertThat(NodeStateProofs.at(7, root, (height, k) -> Optional.of(tampered)).inclusion(key)).isEmpty();
        assertThatThrownBy(() -> NodeStateProofs.normalize(tampered)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NodeStateProofs.at(7, root, (height, k) -> { throw new IllegalStateException("pruned"); })
                .inclusion(key)).isEmpty();
    }

    @Test
    void anExitProvedByTheNodeAtTheAnchoredHeightPassesTheCompiledValidators() throws Exception {
        PayoutWorld world = new PayoutWorld(PayoutWorld.TOKEN);
        int shard = 6;
        List<ClaimLeafV1> claims = world.claims(shard, 2);
        world.anchor(claims, 40_000);
        long anchored = world.anchorHeight;
        List<Long> asked = new ArrayList<>();
        StateProof.Source node = NodeStateProofs.at(anchored, world.state.root(), (height, key) -> {
            asked.add(height);
            return Optional.of(world.state.nativeProof(key, height));
        });
        DomainStatusV1 active = DomainStatusV1.decode(node.inclusion(PayoutWorld.STATUS_KEY).orElseThrow().value());
        List<ExitRelayer.Batch> batches = ExitRelayer.plan(claims.stream()
                        .map(leaf -> new ExitRelayer.Candidate(leaf, ClaimStatus.RESERVED)).toList(), world.pins,
                world.exitMode, anchored, active, PayoutWorld.NOW_MS, index -> world.mirrors[index]);
        PayoutTxBuilder.Built built = ExitRelayer.build(world.context(shard), batches.getFirst(), node,
                world.mirrors[shard], world.evaluator());
        world.evaluate(built);
        assertThat(built.claimIds()).hasSize(2);
        assertThat(asked).isNotEmpty().containsOnly(anchored);
    }
}
