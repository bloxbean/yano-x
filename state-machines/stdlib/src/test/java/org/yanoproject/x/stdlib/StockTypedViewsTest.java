package org.yanoproject.x.stdlib;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ADR-031.4 §5.4 value views and §5.10 post-state facts (bloxbean/yano-x#25) of the four stock kernels. Views decode
 * exactly the records the kernels store, and every fact equals the field the kernel's own event reports.
 */
class StockTypedViewsTest {
    private static final byte[] ALICE = repeated(0x0a);
    private static final byte[] BOB = repeated(0x0b);
    private static final String ALICE_HEX = HexFormat.of().formatHex(ALICE);
    private static final String BOB_HEX = HexFormat.of().formatHex(BOB);

    @Test
    void theStockKernelsDeclareTheViewsAndFactsOfTheAdr() {
        assertThat(StockTransitionKernels.balances(new BalancesTransitions("")).ruleFacts()).extracting(RuleFact::name)
                .containsExactly("balanceAfter", "fromBalanceAfter", "toBalanceAfter");
        assertThat(StockTransitionKernels.registry(new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.RAW))
                .ruleFacts()).extracting(RuleFact::name).containsExactly("existed", "valueLength");
        assertThat(StockTransitionKernels.documentTrail(new DocTrailTransitions()).ruleFacts())
                .extracting(RuleFact::name).containsExactly("countAfter", "first");
        assertThat(StockTransitionKernels.approvals(new ApprovalsTransitions()).ruleFacts()).extracting(RuleFact::name)
                .containsExactly("approverCountAfter", "required", "approvedNow", "proposerIsSender");
        assertThat(view(StockTransitionKernels.balances(new BalancesTransitions("")))).containsExactly(
                tuple("balance", RuleFact.Type.INTEGER));
        assertThat(view(StockTransitionKernels.registry(new KvRegistryTransitions(
                KvRegistryTransitions.ValueFormat.RAW)))).containsExactly(tuple("owner", RuleFact.Type.BYTES),
                tuple("value", RuleFact.Type.BYTES), tuple("valueLength", RuleFact.Type.INTEGER));
        assertThat(view(StockTransitionKernels.registry(new KvRegistryTransitions(
                KvRegistryTransitions.ValueFormat.parse("Utf8"))))).extracting(value -> value.toList().getFirst())
                .containsExactly("owner", "value", "valueLength", "valueText");
        assertThat(view(StockTransitionKernels.documentTrail(new DocTrailTransitions()))).containsExactly(
                tuple("count", RuleFact.Type.INTEGER), tuple("headHash", RuleFact.Type.BYTES));
        assertThat(view(StockTransitionKernels.approvals(new ApprovalsTransitions()))).containsExactly(
                tuple("status", RuleFact.Type.TEXT), tuple("required", RuleFact.Type.INTEGER),
                tuple("approverCount", RuleFact.Type.INTEGER), tuple("proposer", RuleFact.Type.BYTES),
                tuple("payloadHash", RuleFact.Type.BYTES));
        for (var kernel : List.of(StockTransitionKernels.balances(new BalancesTransitions("")),
                StockTransitionKernels.approvals(new ApprovalsTransitions()),
                StockTransitionKernels.documentTrail(new DocTrailTransitions()),
                StockTransitionKernels.registry(new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.RAW)))) {
            // No stock kernel but the map has a write view.
            assertThat(kernel.ruleWriteFields()).isEmpty();
            assertThat(kernel.ruleWriteCoverageFields()).isEmpty();
        }
    }

    @Test
    void balancesViewsAndFactsFollowTheStoredBalanceAndTheEvent() {
        var kernel = StockTransitionKernels.balances(new BalancesTransitions(""));
        var state = new MemoryState();
        var mint = step(kernel, state, BalancesContract.mint(ALICE_HEX, BigInteger.valueOf(100)), ALICE);
        assertThat(mint.facts()).isEqualTo(Map.of("balanceAfter", 100L));
        assertThat(mint.event()).containsEntry("balanceAfter", 100L);
        var transfer = step(kernel, state, BalancesContract.transfer(BOB_HEX, BigInteger.valueOf(30)), ALICE);
        assertThat(transfer.facts()).isEqualTo(Map.of("fromBalanceAfter", 70L, "toBalanceAfter", 30L));
        assertThat(transfer.event()).containsEntry("fromBalanceAfter", 70L).containsEntry("toBalanceAfter", 30L);
        var self = step(kernel, state, BalancesContract.transfer(ALICE_HEX, BigInteger.valueOf(5)), ALICE);
        assertThat(self.facts()).isEqualTo(Map.of("fromBalanceAfter", 70L, "toBalanceAfter", 70L));
        assertThat(self.event()).containsEntry("fromBalanceAfter", 70L).containsEntry("toBalanceAfter", 70L);
        assertThat(read(kernel, state, ALICE_HEX)).isEqualTo(Optional.of(Map.of("balance", 70L)));
        assertThat(read(kernel, state, BOB_HEX)).isEqualTo(Optional.of(Map.of("balance", 30L)));
        // A zero balance deletes the key, so an absent read means zero.
        step(kernel, state, BalancesContract.transfer(ALICE_HEX, BigInteger.valueOf(30)), BOB);
        assertThat(read(kernel, state, BOB_HEX)).isEmpty();
        // A stored balance beyond int64 is reported exactly, which violates the declaration (the engine refuses it).
        byte[] huge = new byte[9];
        Arrays.fill(huge, (byte) 0xff);
        assertThat(kernel.ruleValueFields("", key(kernel, "x"), huge).get("balance"))
                .isEqualTo(new BigInteger(1, huge));
    }

    @Test
    void registryViewsExposeOwnerValueAndUtf8TextWithinTheValueBound() {
        var raw = StockTransitionKernels.registry(new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.RAW));
        var text = StockTransitionKernels.registry(new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.UTF8));
        var state = new MemoryState();
        byte[] key = {1, 2};
        var put = step(text, state, KvRegistryContract.put(key, "héllo".getBytes(StandardCharsets.UTF_8)), ALICE);
        assertThat(put.facts()).isEqualTo(Map.of("existed", false, "valueLength", 6L));
        assertThat(put.event()).containsEntry("valueLength", 6L).doesNotContainKey("previousValueHash");
        byte[] stored = state.get(text.ruleValueKey("", key)).orElseThrow();
        assertThat(text.ruleValueFields("", key, stored)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "owner", ALICE, "value", "héllo".getBytes(StandardCharsets.UTF_8), "valueLength", 6L,
                "valueText", "héllo"));
        assertThat(raw.ruleValueFields("", key, stored)).doesNotContainKey("valueText");
        var replaced = step(text, state, KvRegistryContract.put(key, "x".getBytes(StandardCharsets.UTF_8)), ALICE);
        assertThat(replaced.facts()).isEqualTo(Map.of("existed", true, "valueLength", 1L));
        assertThat(replaced.event()).containsKey("previousValueHash");
        var deleted = step(text, state, KvRegistryContract.delete(key), ALICE);
        assertThat(deleted.facts()).isEqualTo(Map.of("existed", true));
        assertThat(state.get(text.ruleValueKey("", key))).isEmpty();
        // Not UTF-8: no text. Over the value bound: only the length.
        step(raw, state, KvRegistryContract.put(key, new byte[]{(byte) 0xff}), ALICE);
        assertThat(text.ruleValueFields("", key, state.get(key).orElseThrow())).doesNotContainKey("valueText")
                .containsEntry("valueLength", 1L);
        step(raw, state, KvRegistryContract.put(key, new byte[RuleFact.MAX_VALUE_BYTES + 1]), ALICE);
        assertThat(raw.ruleValueFields("", key, state.get(key).orElseThrow())).containsOnlyKeys("owner",
                "valueLength").containsEntry("valueLength", (long) RuleFact.MAX_VALUE_BYTES + 1);
        assertThat(raw.ruleValueFields("", key, new byte[]{(byte) 0xff})).isEmpty();
        // Deleting an absent key: nothing existed and no value length is reported.
        var absent = raw.codec().decode(KvRegistryContract.delete(new byte[]{9}));
        var context = new TransitionContext(99, 0, 0, repeated(9), "topic", ALICE);
        assertThat(raw.ruleFactValues(absent, context, raw.facts(absent, context, state)))
                .isEqualTo(Map.of("existed", false));
    }

    @Test
    void docTrailViewsAndFactsFollowTheHeadAndTheEvent() {
        var kernel = StockTransitionKernels.documentTrail(new DocTrailTransitions());
        var state = new MemoryState();
        for (long count = 1; count <= 3; count++) {
            var append = step(kernel, state, DocTrailContract.append("invoice-7", repeated((int) count), "ref"),
                    ALICE);
            assertThat(append.facts()).isEqualTo(Map.of("countAfter", count, "first", count == 1));
            assertThat(append.event().get("count")).isEqualTo(append.facts().get("countAfter"));
            var fields = read(kernel, state, "invoice-7").orElseThrow();
            assertThat(fields.get("count")).isEqualTo(count);
            assertThat((byte[]) fields.get("headHash")).isEqualTo((byte[]) append.event().get("headHash"));
        }
        assertThat(kernel.ruleValueFields("", key(kernel, "invoice-7"), new byte[]{1})).isEmpty();
    }

    @Test
    void approvalsViewsAndFactsFollowTheItemAndTheEvent() {
        var kernel = StockTransitionKernels.approvals(new ApprovalsTransitions());
        var state = new MemoryState();
        byte[] payload = {7};
        var proposed = step(kernel, state, ApprovalsContract.propose("item-1", payload, 2, 0), ALICE);
        assertThat(proposed.facts()).isEqualTo(Map.of("approverCountAfter", 0L, "required", 2L,
                "approvedNow", false, "proposerIsSender", true));
        assertThat(proposed.event()).containsEntry("required", 2L);
        var pending = read(kernel, state, "item-1").orElseThrow();
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("required", 2L)
                .containsEntry("approverCount", 0L);
        assertThat((byte[]) pending.get("proposer")).isEqualTo(ALICE);
        assertThat((byte[]) pending.get("payloadHash")).isEqualTo((byte[]) proposed.event().get("payloadHash"));
        var voted = step(kernel, state, ApprovalsContract.approve("item-1"), BOB);
        assertThat(voted.facts()).isEqualTo(Map.of("approverCountAfter", 1L, "required", 2L,
                "approvedNow", false, "proposerIsSender", false));
        var approved = step(kernel, state, ApprovalsContract.approve("item-1"), ALICE);
        assertThat(approved.facts()).isEqualTo(Map.of("approverCountAfter", 2L, "required", 2L,
                "approvedNow", true, "proposerIsSender", true));
        assertThat(approved.event()).containsEntry("approverCount", 2L);
        assertThat(read(kernel, state, "item-1").orElseThrow()).containsEntry("status", "APPROVED")
                .containsEntry("approverCount", 2L);
        assertThat(kernel.ruleValueFields("", key(kernel, "item-1"), new byte[]{1})).isEmpty();
        // A rejection and an expiry are stored statuses too.
        step(kernel, state, ApprovalsContract.propose("item-2", payload, 2, 0), ALICE);
        step(kernel, state, ApprovalsContract.reject("item-2"), BOB);
        assertThat(read(kernel, state, "item-2").orElseThrow()).containsEntry("status", "REJECTED");
        step(kernel, state, ApprovalsContract.propose("item-3", payload, 2, 10), ALICE);
        var expiring = step(kernel, state, ApprovalsContract.approve("item-3"), BOB, 11);
        assertThat(read(kernel, state, "item-3").orElseThrow()).containsEntry("status", "EXPIRED")
                .containsEntry("approverCount", 0L);
        assertThat(expiring.facts()).containsEntry("approvedNow", false).containsEntry("approverCountAfter", 0L);
        // No item before or after the command: the facts are absent.
        var unknown = kernel.codec().decode(ApprovalsContract.approve("item-9"));
        var context = new TransitionContext(99, 0, 0, repeated(9), "topic", BOB);
        assertThat(kernel.ruleFactValues(unknown, context, kernel.facts(unknown, context, state))).isEmpty();
    }

    private static List<Tuple> view(TransitionKernel<?, ?> kernel) {
        List<RuleValueView> views = kernel.ruleValueViews();
        assertThat(views).hasSize(1);
        assertThat(views.getFirst().namespace()).isEmpty();
        assertThat(views.getFirst().valueFields()).isEmpty();
        return views.getFirst().fields().stream().map(field -> tuple(field.name(), field.type())).toList();
    }

    private static byte[] key(TransitionKernel<?, ?> kernel, String id) {
        return kernel.ruleValueKey("", id.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<Map<String, Object>> read(TransitionKernel<?, ?> kernel, AppStateReader state,
                                                      String id) {
        byte[] key = id.getBytes(StandardCharsets.UTF_8);
        return state.get(kernel.ruleValueKey("", key)).map(stored -> declared(kernel.ruleValueFields("", key,
                stored), kernel.ruleValueViews().getFirst().fields()));
    }

    /**
     * Values as the engine admits them: every name declared, and each value of its declared type's Java class
     * ({@code Long}, {@code String}, {@code byte[]}, {@code Boolean}); otherwise every rule reading it would fail
     * with {@code ADMISSION_RULE_INPUT}.
     */
    private static Map<String, Object> declared(Map<String, Object> values, List<RuleFact> declarations) {
        Map<String, RuleFact.Type> types = new HashMap<>();
        declarations.forEach(fact -> types.put(fact.name(), fact.type()));
        values.forEach((name, value) -> {
            assertThat(types).as("declared %s", name).containsKey(name);
            assertThat(value).as(name).isInstanceOf(switch (types.get(name)) {
                case INTEGER -> Long.class;
                case TEXT -> String.class;
                case BYTES -> byte[].class;
                case BOOLEAN -> Boolean.class;
                case TEXT_SET -> List.class;
            });
        });
        return values;
    }

    record Step(Map<String, Object> facts, Map<String, Object> event) { }

    /** Decides, applies the approved plan, and returns the post-state facts with the kernel's own event. */
    private static <C, F> Step step(TransitionKernel<C, F> kernel, MemoryState state, byte[] body, byte[] sender) {
        return step(kernel, state, body, sender, 0);
    }

    private static <C, F> Step step(TransitionKernel<C, F> kernel, MemoryState state, byte[] body, byte[] sender,
                                    long timestamp) {
        var context = new TransitionContext(state.height++, timestamp, 0, repeated(state.height), "topic", sender);
        C command = kernel.codec().decode(body);
        F facts = kernel.facts(command, context, state);
        var decision = kernel.decide(command, context, facts);
        assertThat(decision).isInstanceOf(TransitionDecision.Approved.class);
        var plan = ((TransitionDecision.Approved) decision).plan();
        for (StateMutation mutation : plan.mutations()) {
            if (mutation.kind() == StateMutation.Kind.PUT) state.values.put(hex(mutation.key()), mutation.value());
            else state.values.remove(hex(mutation.key()));
        }
        Map<String, Object> event = plan.events().isEmpty() ? Map.of()
                : TransitionScalars.decode(plan.events().getFirst().payload());
        return new Step(declared(kernel.ruleFactValues(command, context, facts), kernel.ruleFacts()), event);
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }

    private static byte[] repeated(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class MemoryState implements AppStateReader {
        final Map<String, byte[]> values = new HashMap<>();
        int height = 1;
        @Override public Optional<byte[]> get(byte[] key) { return Optional.ofNullable(values.get(hex(key))); }
        @Override public byte[] stateRoot() { return new byte[32]; }
    }
}
