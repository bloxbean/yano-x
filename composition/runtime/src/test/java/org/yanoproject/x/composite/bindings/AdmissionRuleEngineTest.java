package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Parameter;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.3 Phase 3 engine behaviour: two evaluation slots, receipts, atomicity, no bypass, ordering, verified
 * facts, overlays, budgets, crypto work, input views, and replay.
 */
class AdmissionRuleEngineTest {
    private static final byte[] VALUE = {5, 6};
    private static final RuleFact ROLES = new RuleFact("roles", RuleFact.Type.TEXT_SET);
    private static final RuleFact COUNT = new RuleFact("count", RuleFact.Type.INTEGER);

    // ---- documents ---------------------------------------------------------------------------------------------

    /** a -> b -> c, each via {@code recorded.v1}; rules attach to {@code c} unless stated. */
    private static BindingIrV1 chain(List<AdmissionRule> rules, Map<String, List<RuleAttachment>> attachments) {
        var first = new BindingIrV1.Binding("first", "a", "recorded.v1", List.of(),
                BindingContextAndViewTest.recordTarget("b", new BindingSourceV1.Literal("from-a")));
        var second = new BindingIrV1.Binding("second", "b", "recorded.v1", List.of(),
                BindingContextAndViewTest.recordTarget("c", new BindingSourceV1.Literal("from-b")));
        return new BindingIrV1(List.of(
                BindingContextAndViewTest.component("a", attachments.getOrDefault("a", List.of())),
                BindingContextAndViewTest.component("b", attachments.getOrDefault("b", List.of())),
                BindingContextAndViewTest.component("c", attachments.getOrDefault("c", List.of()))),
                rules, List.of(first, second), BindingIrV1.Limits.DEFAULT, 1);
    }

    private static AdmissionRule rule(String id, String deny, String command, BindingExpressionV1.Node condition,
                                      Parameter... parameters) {
        return new AdmissionRule(id, deny, command, List.of(parameters),
                List.of(BindingContextAndViewTest.condition(condition)));
    }

    private static RuleAttachment attach(String rule) { return new RuleAttachment(rule, Map.of()); }

    private static Field command(String name) { return new Field(Scope.COMMAND, name); }
    private static Field fact(String name) { return new Field(Scope.FACTS, name); }
    private static Field context(String name) { return new Field(Scope.CONTEXT, name); }

    private static CascadeHarness harness(BindingIrV1 ir, Function<String, CascadeHarness.RecordKernel> kernels) {
        return new CascadeHarness(ir, kernels);
    }

    private static CascadeHarness.RecordKernel withFacts(Function<List<Object>, Map<String, Object>> values) {
        var kernel = new CascadeHarness.RecordKernel();
        kernel.declared = List.of(ROLES, COUNT);
        kernel.factValues = values;
        return kernel;
    }

    private static AppMessage recordTo(String component, int identity, String note) {
        return CascadeHarness.message(identity, component + ".v1", CascadeHarness.record(VALUE, note));
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    /** {@link #chain} plus an effect from {@code a}'s event, so a rollback at depth two must also drop an effect. */
    private static BindingIrV1 chainWithEffect(List<AdmissionRule> rules, List<RuleAttachment> onC,
                                               BindingIrV1.Limits limits) {
        var base = chain(rules, Map.of("c", onC));
        var a = base.components().getFirst();
        List<BindingIrV1.Component> components = new ArrayList<>(base.components());
        components.set(0, new BindingIrV1.Component(a.id(), a.machineId(), a.ingressTopic(), a.configuration(), 1,
                a.fromHeight(), a.admission()));
        List<BindingIrV1.Binding> bindings = new ArrayList<>(base.bindings());
        bindings.add(new BindingIrV1.Binding("notify", "a", "recorded.v1", List.of(),
                new BindingIrV1.EffectTarget("test", "app-final", "none", 0, BindingIrV1.Mapping.identity())));
        return new BindingIrV1(components, base.rules(), bindings, limits, 1);
    }

    @Test
    void everyCodeAndSlotDeniesAtDepthTwoWithoutKeepingEarlierWritesOrEffects() {
        Function<List<Object>, Map<String, Object>> valid = ignored -> Map.of("roles", List.of("auditor"),
                "count", 1L);
        // Control: without rules the cascade writes at every depth and emits the effect.
        var control = harness(chainWithEffect(List.of(), List.of(), BindingIrV1.Limits.DEFAULT),
                id -> withFacts(valid));
        var controlMessage = recordTo("a", 1, "n");
        control.apply(1, List.of(controlMessage));
        assertThat(control.receipt(controlMessage).accepted()).isTrue();
        assertThat(control.states.values()).allSatisfy(state -> assertThat(state.businessKeys()).isNotEmpty());
        assertThat(control.emitted).hasSize(1);
        long controlWork = control.evaluationWork();

        var admissionDeny = rule("deny-derived", "DERIVED_FORBIDDEN", null,
                new Call("not", List.of(context("derived"))));
        var factDeny = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var admissionError = rule("divides", "NEVER", null, new Call("gt", List.of(new Call("div",
                List.of(new Literal(1L), new Literal(0L))), new Literal(0L))));
        var factError = rule("fact-divides", "NEVER", null, new Call("gt", List.of(new Call("div",
                List.of(fact("count"), new Literal(0L))), new Literal(0L))));
        var expensive = rule("expensive", "NEVER", null, new Call("eq", List.of(new Literal("x".repeat(64)),
                new Literal("y".repeat(64)))));
        // Enough cascade work for the three steps and the rule's charges, not for its 128-byte comparison.
        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536,
                (int) controlWork + 10, 1_000_000, 4);
        record Case(AdmissionRule rule, String code, RuleFailure failure,
                    Function<List<Object>, Map<String, Object>> facts, BindingIrV1.Limits limits) { }
        for (Case each : List.of(
                new Case(admissionDeny, "ADMISSION_RULE_DENIED", new RuleFailure("deny-derived", 0,
                        "DERIVED_FORBIDDEN"), valid, BindingIrV1.Limits.DEFAULT),
                new Case(factDeny, "ADMISSION_RULE_DENIED", new RuleFailure("needs-role", 0, "ROLE_REQUIRED"),
                        valid, BindingIrV1.Limits.DEFAULT),
                new Case(admissionError, "ADMISSION_RULE_ERROR", new RuleFailure("divides", 0, null), valid,
                        BindingIrV1.Limits.DEFAULT),
                new Case(factError, "ADMISSION_RULE_ERROR", new RuleFailure("fact-divides", 0, null), valid,
                        BindingIrV1.Limits.DEFAULT),
                new Case(factDeny, "ADMISSION_RULE_INPUT", new RuleFailure("needs-role", -1, null),
                        ignored -> Map.of("roles", List.of("b", "a")), BindingIrV1.Limits.DEFAULT),
                new Case(expensive, "EXPRESSION_CAPACITY_EXCEEDED", new RuleFailure("expensive", 0, null), valid,
                        tight))) {
            var ir = chainWithEffect(List.of(each.rule()), List.of(attach(each.rule().id())), each.limits());
            var harness = harness(ir, id -> withFacts(each.facts()));
            var message = recordTo("a", 1, "n");
            harness.apply(1, List.of(message));
            var receipt = harness.receipt(message);
            assertThat(receipt.code()).as(each.rule().id()).isEqualTo(each.code());
            // Ordinals: a (0), b (1), the effect (2), then c (3) at depth two.
            assertThat(receipt.failedStepOrdinal()).isEqualTo(3);
            assertThat(receipt.steps().getLast().depth()).isEqualTo(2);
            assertThat(receipt.steps().getLast().rules().failure()).isEqualTo(each.failure());
            assertThat(harness.states.values()).allSatisfy(state -> assertThat(state.businessKeys()).isEmpty());
            assertThat(harness.emitted).isEmpty();
        }
        // An unbuildable view: a non-canonical source body, accepted by a lenient codec, fails its source step.
        var input = rule("needs-view", "NEVER", "record", new Call("eq", List.of(command("note"),
                command("note"))));
        var inputIr = chain(List.of(input), Map.of("a", List.of(attach("needs-view"))));
        var lenient = harness(inputIr, id -> {
            var kernel = new CascadeHarness.RecordKernel();
            kernel.lenientCodec = id.equals("a");
            return kernel;
        });
        byte[] body = CascadeHarness.record(VALUE, "n");
        var message = CascadeHarness.message(2, "a.v1", Arrays.copyOf(body, body.length + 1));
        lenient.apply(1, List.of(message));
        var receipt = lenient.receipt(message);
        assertThat(receipt.code()).isEqualTo("ADMISSION_RULE_INPUT");
        assertThat(receipt.steps().getFirst().rules()).isEqualTo(new RuleTrace(0,
                new RuleFailure("needs-view", -1, null)));
        assertThat(lenient.states.values()).allSatisfy(state -> assertThat(state.businessKeys()).isEmpty());
    }

    @Test
    void anExceptionFromRuleFactValuesPropagatesInsteadOfBecomingAnOutcome() {
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(needsRole), Map.of("a", List.of(attach("needs-role"))));
        var harness = harness(ir, id -> withFacts(ignored -> {
            throw new IllegalStateException("plugin fault");
        }));
        var message = recordTo("a", 1, "n");
        assertThatThrownBy(() -> harness.apply(1, List.of(message))).isInstanceOf(IllegalStateException.class)
                .hasMessage("plugin fault");
        assertThat(harness.workflow.get(message.getMessageId())).isEmpty();
    }

    @Test
    void theSameRuleDeniesDirectAndDerivedCommandsWithTheSameCode() {
        var tooLarge = rule("small-notes", "NOTE_TOO_LONG", "record", new Call("ne", List.of(command("note"),
                new Literal("forbidden"))));
        var ir = chain(List.of(tooLarge), Map.of("b", List.of(attach("small-notes"))));
        var direct = harness(ir, id -> new CascadeHarness.RecordKernel());
        var message = recordTo("b", 1, "forbidden");
        direct.apply(1, List.of(message));
        var directReceipt = direct.receipt(message);
        assertThat(directReceipt.code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(directReceipt.steps().getFirst().rules().failure())
                .isEqualTo(new RuleFailure("small-notes", 0, "NOTE_TOO_LONG"));

        var derivedIr = new BindingIrV1(ir.components(), ir.rules(), List.of(new BindingIrV1.Binding("first", "a",
                "recorded.v1", List.of(), BindingContextAndViewTest.recordTarget("b",
                new BindingSourceV1.Literal("forbidden")))), BindingIrV1.Limits.DEFAULT, 1);
        var derived = harness(derivedIr, id -> new CascadeHarness.RecordKernel());
        var source = recordTo("a", 2, "fine");
        derived.apply(1, List.of(source));
        var derivedReceipt = derived.receipt(source);
        assertThat(derivedReceipt.code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(derivedReceipt.steps().getLast().rules().failure())
                .isEqualTo(directReceipt.steps().getFirst().rules().failure());
    }

    @Test
    void theFirstFailingRuleWinsLaterRulesAreNotChargedAndAdmissionRulesPrecedeFactRules() {
        var holds = rule("holds", "NEVER", null, new Literal(true));
        var fails = rule("fails", "FIRST", null, new Literal(false));
        var alsoFails = rule("also-fails", "SECOND", null, new Literal(false));
        var fact = rule("fact-first", "FACT", null, new Call("gt", List.of(fact("count"), new Literal(100L))));
        // Authored order puts the fact rule first; it still runs after every admission-slot rule.
        var ir = chain(List.of(alsoFails, fact, fails, holds).stream().sorted((x, y) -> x.id().compareTo(y.id()))
                .toList(), Map.of("a", List.of(attach("fact-first"), attach("holds"), attach("fails"),
                attach("also-fails"))));
        var harness = harness(ir, id -> withFacts(ignored -> Map.of("count", 1L)));
        var message = recordTo("a", 1, "n");
        harness.apply(1, List.of(message));
        var receipt = harness.receipt(message);
        assertThat(receipt.steps().getFirst().rules()).isEqualTo(new RuleTrace(1, new RuleFailure("fails", 0,
                "FIRST")));
        assertThat(harness.kernels.get("a").ruleFactCalls).isZero();

        var cheaper = chain(List.of(fails, holds).stream().sorted((x, y) -> x.id().compareTo(y.id())).toList(),
                Map.of("a", List.of(attach("holds"), attach("fails"))));
        var withLater = chain(List.of(alsoFails, fails, holds).stream().sorted((x, y) -> x.id().compareTo(y.id()))
                .toList(), Map.of("a", List.of(attach("holds"), attach("fails"), attach("also-fails"))));
        assertThat(work(withLater)).isEqualTo(work(cheaper));
    }

    private static long work(BindingIrV1 ir) {
        var harness = harness(ir, id -> new CascadeHarness.RecordKernel());
        harness.apply(1, List.of(recordTo("a", 1, "n")));
        return harness.evaluationWork();
    }

    @Test
    void factRulesRunOnlyAfterApprovalWithTheApprovingFactsInstance() {
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(needsRole), Map.of("a", List.of(attach("needs-role"))));
        var rejected = harness(ir, id -> {
            var kernel = withFacts(ignored -> Map.of("roles", List.of("operator")));
            kernel.rejectCode = "KERNEL_SAYS_NO";
            return kernel;
        });
        var message = recordTo("a", 1, "n");
        rejected.apply(1, List.of(message));
        assertThat(rejected.receipt(message).code()).isEqualTo("KERNEL_SAYS_NO");
        assertThat(rejected.receipt(message).steps().getFirst().rules()).isEqualTo(RuleTrace.NONE);
        assertThat(rejected.kernels.get("a").ruleFactCalls).isZero();

        var approved = harness(ir, id -> withFacts(ignored -> Map.of("roles", List.of("auditor", "operator"))));
        approved.apply(1, List.of(recordTo("a", 2, "n")));
        var kernel = approved.kernels.get("a");
        assertThat(kernel.ruleFactCalls).isEqualTo(1);
        assertThat(kernel.factsQueried.getFirst()).isSameAs(kernel.factsProduced.getFirst());
    }

    @Test
    void invalidFactValuesDenyWithAdmissionRuleInputOnEveryMember() {
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(needsRole), Map.of("a", List.of(attach("needs-role"))));
        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(Map.of("undeclared", 1L));
        invalid.add(Map.of("roles", "operator"));
        invalid.add(Map.of("roles", List.of("b", "a")));
        invalid.add(Map.of("roles", List.of("a", "a")));
        invalid.add(Map.of("roles", IntStream.range(0, 65).mapToObj(i -> String.format("r%03d", i))
                .toList()));
        invalid.add(Map.of("roles", List.of("x".repeat(129))));
        invalid.add(Map.of("count", "1"));
        invalid.add(null);
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("count", null);
        invalid.add(nullValue);
        for (var values : invalid) {
            for (int member = 0; member < 2; member++) {
                var harness = harness(ir, id -> withFacts(ignored -> values));
                var message = recordTo("a", 1, "n");
                harness.apply(1, List.of(message));
                var receipt = harness.receipt(message);
                assertThat(receipt.code()).as(String.valueOf(values)).isEqualTo("ADMISSION_RULE_INPUT");
                assertThat(receipt.steps().getFirst().rules()).isEqualTo(new RuleTrace(0,
                        new RuleFailure("needs-role", -1, null)));
            }
        }
    }

    @Test
    void anAbsentFactIsARuleErrorThatFailsClosed() {
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(needsRole), Map.of("a", List.of(attach("needs-role"))));
        var harness = harness(ir, id -> withFacts(ignored -> Map.of("count", 2L)));
        var message = recordTo("a", 1, "n");
        harness.apply(1, List.of(message));
        assertThat(harness.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(harness.receipt(message).steps().getFirst().rules().failure())
                .isEqualTo(new RuleFailure("needs-role", 0, null));
    }

    @Test
    void ruleLookupsSeeWritesFromEarlierStepsOfTheSameCascade() {
        // c admits only when b's write for this cascade's derived message is visible through the overlay.
        var seen = new AdmissionRule("sees-b", "NOT_SEEN", null, List.of(), List.of(new BindingIrV1.LookupClause("b",
                new BindingSourceV1.Literal(EventBindingWorkflow.derivedId(CascadeHarness.message(1, "a.v1",
                        new byte[]{1}).getMessageId(), "first", 1)), BindingIrV1.Expectation.EXISTS, null)));
        var ir = chain(List.of(seen), Map.of("c", List.of(attach("sees-b"))));
        var harness = harness(ir, id -> new CascadeHarness.RecordKernel());
        var message = recordTo("a", 1, "n");
        harness.apply(1, List.of(message));
        var receipt = harness.receipt(message);
        assertThat(receipt.accepted()).as(receipt.code()).isTrue();
        assertThat(receipt.steps().getLast().rules()).isEqualTo(new RuleTrace(1, null));
    }

    @Test
    void ruleWorkIsChargedOnDenialAndABlockOfDenialsStaysWithinTheBlockBudget() {
        var deny = rule("deny-all", "DENIED_ALWAYS", null, new Call("eq", List.of(new Literal("x".repeat(64)),
                new Literal("y".repeat(64)))));
        var ir = chain(List.of(deny), Map.of("a", List.of(attach("deny-all"))));
        var denied = harness(ir, id -> new CascadeHarness.RecordKernel());
        denied.apply(1, List.of(recordTo("a", 1, "n")));
        // The rule precharge, one clause, three nodes and a 128-byte comparison are charged to the block counter.
        assertThat(denied.evaluationWork()).isEqualTo(1 + 1 + 3 + 128);

        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 1_000, 1_000, 4);
        var boundedIr = new BindingIrV1(ir.components(), ir.rules(), ir.bindings(), tight, 1);
        var bounded = harness(boundedIr, id -> new CascadeHarness.RecordKernel());
        List<AppMessage> messages = new ArrayList<>();
        for (int identity = 1; identity <= 20; identity++) messages.add(recordTo("a", identity, "n"));
        bounded.apply(1, messages);
        assertThat(bounded.evaluationWork()).isLessThanOrEqualTo(1_000);
        List<String> codes = messages.stream().map(message -> bounded.receipt(message).code()).toList();
        assertThat(codes).contains("ADMISSION_RULE_DENIED", "EXPRESSION_CAPACITY_EXCEEDED");
    }

    /**
     * ADR-031.3 §5.11: denials draw down the shared block budget, so the budget is sized exactly here. A legitimate
     * cascade after a run of denials commits when the block budget still covers it, and is refused with
     * {@code EXPRESSION_CAPACITY_EXCEEDED} (a receipt the sender can resubmit) when it is one unit short.
     */
    @Test
    void aRunOfDenialsDrawsDownTheBlockBudgetAndAnInBudgetCascadeStillCommits() {
        var limit = rule("note-limit", "NOTE_FORBIDDEN", "record", new Call("ne", List.of(command("note"),
                new Literal("forbidden"))));
        var ir = chain(List.of(limit), Map.of("a", List.of(attach("note-limit"))));
        List<AppMessage> denials = new ArrayList<>();
        for (int identity = 1; identity <= 30; identity++) denials.add(recordTo("a", identity, "forbidden"));
        var legitimate = recordTo("a", 99, "fine");
        var deniedOnly = harness(ir, id -> new CascadeHarness.RecordKernel());
        deniedOnly.apply(1, denials);
        long deniedWork = deniedOnly.evaluationWork();
        var alone = harness(ir, id -> new CascadeHarness.RecordKernel());
        alone.apply(1, List.of(legitimate));
        long legitimateWork = alone.evaluationWork();
        for (long slack : new long[]{0, -1}) {
            var budget = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 1_048_576,
                    (int) (deniedWork + legitimateWork + slack), 4);
            var bounded = harness(new BindingIrV1(ir.components(), ir.rules(), ir.bindings(), budget, 1),
                    id -> new CascadeHarness.RecordKernel());
            List<AppMessage> block = new ArrayList<>(denials);
            block.add(legitimate);
            bounded.apply(1, block);
            assertThat(denials).allSatisfy(message ->
                    assertThat(bounded.receipt(message).code()).isEqualTo("ADMISSION_RULE_DENIED"));
            var receipt = bounded.receipt(legitimate);
            if (slack == 0) assertThat(receipt.accepted()).as(receipt.code()).isTrue();
            else assertThat(receipt.code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        }
    }

    @Test
    void anAdmissionSlotDenialReservesNoCryptoWork() {
        var deny = rule("deny-all", "DENIED_ALWAYS", null, new Literal(false));
        var ir = chain(List.of(deny), Map.of("a", List.of(attach("deny-all"))));
        var harness = harness(ir, id -> {
            var kernel = new CascadeHarness.RecordKernel();
            kernel.requestsWork = id.equals("a");
            return kernel;
        });
        harness.apply(1, List.of(recordTo("a", 1, "n")));
        assertThat(harness.states.get("a").values).isEmpty();
        assertThat(harness.kernels.get("a").factsCalls).isZero();
        // Control: a kernel rejection after workRequest keeps its non-refundable reservation (key 09).
        var rejected = harness(chain(List.of(), Map.of()), id -> {
            var kernel = new CascadeHarness.RecordKernel();
            kernel.requestsWork = id.equals("a");
            kernel.rejectCode = "KERNEL_SAYS_NO";
            return kernel;
        });
        var message = recordTo("a", 1, "n");
        rejected.apply(1, List.of(message));
        assertThat(rejected.receipt(message).code()).isEqualTo("KERNEL_SAYS_NO");
        assertThat(rejected.states.get("a").values).containsOnlyKeys("09");
    }

    @Test
    void exactSourceReplayReturnsTheStoredReceiptWithoutEvaluatingRules() {
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(needsRole), Map.of("a", List.of(attach("needs-role"))));
        var harness = harness(ir, id -> withFacts(ignored -> Map.of("roles", List.of("operator"))));
        var message = recordTo("a", 1, "n");
        harness.apply(1, List.of(message));
        byte[] stored = harness.workflow.get(message.getMessageId()).orElseThrow();
        int calls = harness.kernels.get("a").ruleFactCalls;
        harness.apply(2, List.of(message));
        assertThat(harness.workflow.get(message.getMessageId())).contains(stored);
        assertThat(harness.kernels.get("a").ruleFactCalls).isEqualTo(calls);
    }

    @Test
    void staticRulesRejectAtIngressAndOthersNeverRunThere() {
        var limit = rule("note-limit", "NOTE_FORBIDDEN", "record", new Call("ne", List.of(command("note"),
                new Literal("forbidden"))));
        var derivedOnly = rule("derived-only", "DIRECT_FORBIDDEN", null, context("derived"));
        var needsRole = rule("needs-role", "ROLE_REQUIRED", null, new Call("in", List.of(new Literal("operator"),
                fact("roles"))));
        var ir = chain(List.of(derivedOnly, needsRole, limit), Map.of("a", List.of(attach("note-limit"),
                attach("derived-only"), attach("needs-role"))));
        var harness = harness(ir, id -> withFacts(ignored -> Map.of()));
        assertThat(harness.engine.validate(recordTo("a", 1, "forbidden")).reason())
                .isEqualTo("ADMISSION_RULE_DENIED/note-limit/NOTE_FORBIDDEN");
        assertThat(harness.engine.validate(recordTo("a", 2, "fine")).isAccepted()).isTrue();
        byte[] body = CascadeHarness.record(VALUE, "n");
        var lenient = new CascadeHarness(ir, id -> {
            var kernel = withFacts(ignored -> Map.of());
            kernel.lenientCodec = true;
            return kernel;
        });
        assertThat(lenient.engine.validate(CascadeHarness.message(3, "a.v1", Arrays.copyOf(body, body.length + 1)))
                .reason()).isEqualTo("ADMISSION_RULE_INPUT/note-limit");
        assertThat(harness.kernels.get("a").ruleFactCalls).isZero();

        // A static rule that cannot evaluate, and one that exhausts the fresh local budget.
        var divides = rule("divides", "NEVER", null, new Call("gt", List.of(new Call("div",
                List.of(new Literal(1L), new Literal(0L))), new Literal(0L))));
        var failing = harness(chain(List.of(divides), Map.of("a", List.of(attach("divides")))),
                id -> new CascadeHarness.RecordKernel());
        assertThat(failing.engine.validate(recordTo("a", 4, "n")).reason()).isEqualTo("ADMISSION_RULE_ERROR/divides");
        var expensive = rule("expensive", "NEVER", null, new Call("eq", List.of(new Literal("x".repeat(64)),
                new Literal("y".repeat(64)))));
        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 10, 1_000, 4);
        var unbound = new BindingIrV1(List.of(BindingContextAndViewTest.component("a",
                List.of(attach("expensive")))), List.of(expensive), List.of(), tight, 1);
        var exhausted = harness(unbound, id -> new CascadeHarness.RecordKernel());
        assertThat(exhausted.engine.validate(recordTo("a", 5, "n")).reason())
                .isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
    }

    @Test
    void theCompactedFailureStepKeepsItsRuleTrace() {
        String name = "a".repeat(127);
        var failed = new BindingReceiptV1.Step(17, 3, name, "target", new byte[32],
                Collections.nCopies(257, name), Collections.nCopies(256, new BindingReceiptV1.Condition(name, 7)),
                new RuleTrace(15, new RuleFailure("r".repeat(63), 7, "D".repeat(63))), "REJECTED",
                "ADMISSION_RULE_DENIED", false);
        var trace = new ArrayList<>(List.of(failed));
        EventBindingWorkflow.compactFailureTrace(trace, 17);
        byte[] encoded = new BindingReceiptV1(new byte[32], 1, false, 17, "RECEIPT_CAPACITY_EXCEEDED", trace).encode();
        assertThat(encoded.length).isLessThan(BindingReceiptV1.MAX_BYTES);
        assertThat(BindingReceiptV1.decode(encoded).steps().getFirst().rules()).isEqualTo(failed.rules());
    }
}
