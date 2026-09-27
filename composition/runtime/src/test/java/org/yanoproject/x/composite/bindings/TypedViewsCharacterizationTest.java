package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.4 Phase 0 characterization: ADR-031.3 rule behaviour that the typed views must not change. Rules without
 * reads or quantifiers keep their slots, outcomes, receipt traces and exact work charges; an accepted receipt keeps
 * its bytes; and an ingress refusal keeps naming its code, rule and deny code.
 */
class TypedViewsCharacterizationTest {
    private static final byte[] VALUE = {5, 6};
    private static final RuleFact ROLES = new RuleFact("roles", RuleFact.Type.TEXT_SET);

    /** a -> b -> c through {@code recorded.v1}; the b -> c edge maps the note literal {@code from-b}. */
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

    private static AdmissionRule rule(String id, String deny, String command, BindingExpressionV1.Node condition) {
        return new AdmissionRule(id, deny, command, List.of(), List.of(BindingContextAndViewTest.condition(condition)));
    }

    private static AdmissionRule lookupRule(String id, String deny) {
        return new AdmissionRule(id, deny, null, List.of(), List.of(new BindingIrV1.LookupClause("b",
                new BindingSourceV1.Literal(new byte[]{1, 2, 3}), BindingIrV1.Expectation.ABSENT, null)));
    }

    private static RuleAttachment attach(String rule) { return new RuleAttachment(rule, Map.of()); }

    private static final AdmissionRule NOTE_LIMIT = rule("note-limit", "NOTE_FORBIDDEN", "record",
            new Call("ne", List.of(new Field(Scope.COMMAND, "note"), new Literal("forbidden"))));
    private static final AdmissionRule NOT_FROM_B = rule("not-from-b", "FROM_B_FORBIDDEN", "record",
            new Call("ne", List.of(new Field(Scope.COMMAND, "note"), new Literal("from-b"))));
    private static final AdmissionRule NEEDS_ROLE = rule("needs-role", "ROLE_REQUIRED", null,
            new Call("in", List.of(new Literal("operator"), new Field(Scope.FACTS, "roles"))));
    private static final AdmissionRule UNUSED_KEY = lookupRule("unused-key", "KEY_TAKEN");

    private static AppMessage recordTo(String component, int identity, String note) {
        return CascadeHarness.message(identity, component + ".v1", CascadeHarness.record(VALUE, note));
    }

    private record Outcome(BindingReceiptV1 receipt, long work, byte[] stored) { }

    private static Outcome run(BindingIrV1 ir, Function<String, CascadeHarness.RecordKernel> kernels, String note) {
        var harness = new CascadeHarness(ir, kernels);
        var message = recordTo("a", 1, note);
        harness.apply(1, List.of(message));
        return new Outcome(harness.receipt(message), harness.evaluationWork(),
                harness.workflow.get(message.getMessageId()).orElseThrow());
    }

    private static CascadeHarness.RecordKernel roles(List<String> roles) {
        var kernel = new CascadeHarness.RecordKernel();
        kernel.declared = List.of(ROLES);
        kernel.factValues = ignored -> Map.of("roles", roles);
        return kernel;
    }

    private static List<RuleTrace> traces(Outcome outcome) {
        return outcome.receipt().steps().stream().map(BindingReceiptV1.Step::rules).toList();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void acceptedCascadeKeepsItsTracesWorkAndBytes() {
        var ir = chain(List.of(NOTE_LIMIT), Map.of("a", List.of(attach("note-limit"))));
        var accepted = run(ir, id -> new CascadeHarness.RecordKernel(), "fine");
        assertThat(accepted.receipt().accepted()).isTrue();
        assertThat(traces(accepted)).containsExactly(new RuleTrace(1, null), RuleTrace.NONE, RuleTrace.NONE);
        assertThat(accepted.work()).isEqualTo(300);
        // A receipt without a rule failure has no structure ADR-031.4 amends, so its bytes do not change.
        assertThat(sha256(accepted.stored()))
                .isEqualTo("8d8040b3e86038485a0e23e041680c0c6b8b68df5b3c941bd1abf685eb5de52a");
    }

    @Test
    void denialsAtEachDepthKeepTheirTraceAndWork() {
        var source = run(chain(List.of(NOTE_LIMIT), Map.of("a", List.of(attach("note-limit")))),
                id -> new CascadeHarness.RecordKernel(), "forbidden");
        assertThat(source.receipt().code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(source.receipt().failedStepOrdinal()).isZero();
        assertThat(traces(source)).containsExactly(new RuleTrace(0, failure("note-limit", 0, "NOTE_FORBIDDEN")));
        assertThat(source.work()).isEqualTo(96);

        var derived = run(chain(List.of(NOT_FROM_B), Map.of("c", List.of(attach("not-from-b")))),
                id -> new CascadeHarness.RecordKernel(), "fine");
        assertThat(derived.receipt().code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(derived.receipt().failedStepOrdinal()).isEqualTo(2);
        assertThat(traces(derived)).containsExactly(RuleTrace.NONE, RuleTrace.NONE,
                new RuleTrace(0, failure("not-from-b", 0, "FROM_B_FORBIDDEN")));
        assertThat(derived.work()).isEqualTo(284);
    }

    @Test
    void factAndLookupRulesKeepTheirSlotsAndWork() {
        // The lookup rule is in the admission slot, the fact rule in the verified-fact slot, whatever their order.
        var ir = chain(List.of(NEEDS_ROLE, UNUSED_KEY), Map.of("a", List.of(attach("needs-role"),
                attach("unused-key"))));
        var held = run(ir, id -> roles(List.of("auditor", "operator")), "fine");
        assertThat(held.receipt().accepted()).isTrue();
        assertThat(traces(held)).containsExactly(new RuleTrace(2, null), RuleTrace.NONE, RuleTrace.NONE);
        assertThat(held.work()).isEqualTo(331);

        var denied = run(ir, id -> roles(List.of("auditor")), "fine");
        assertThat(denied.receipt().code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(traces(denied)).containsExactly(new RuleTrace(1, failure("needs-role", 0, "ROLE_REQUIRED")));
        assertThat(denied.work()).isEqualTo(79);
    }

    @Test
    void ingressRefusalsNameTheirCodeRuleAndDenyCode() {
        var ir = chain(List.of(NEEDS_ROLE, NOTE_LIMIT), Map.of("a", List.of(attach("note-limit"),
                attach("needs-role"))));
        var harness = new CascadeHarness(ir, id -> roles(List.of()));
        assertThat(refusal(harness.engine.validate(recordTo("a", 1, "forbidden"))))
                .containsExactly("ADMISSION_RULE_DENIED", "note-limit", "NOTE_FORBIDDEN");
        // The fact rule never runs at ingress, so an empty role set does not refuse there.
        assertThat(harness.engine.validate(recordTo("a", 2, "fine")).isAccepted()).isTrue();
        assertThat(harness.kernels.get("a").ruleFactCalls).isZero();
    }

    @Test
    void missingFieldsErrorUnlessMaskedAndSkippedRulesDoNotCount() {
        var noteNotX = rule("note-not-x", "NOTE_X", "record", new Call("ne", List.of(new Field(Scope.COMMAND, "note"),
                new Literal("x"))));
        var masked = rule("masked", "NEVER", "record", new Call("or", List.of(new Call("ne", List.of(
                new Field(Scope.COMMAND, "note"), new Literal("x"))), new Literal(true))));
        var transfers = rule("transfers-only", "NEVER", "transfer", new Call("gt", List.of(
                new Field(Scope.COMMAND, "amount"), new Literal(0L))));
        var harness = new CascadeHarness(chain(List.of(masked, noteNotX, transfers), Map.of("a", List.of(
                attach("transfers-only"), attach("masked"), attach("note-not-x")))),
                id -> new CascadeHarness.RecordKernel());
        var message = CascadeHarness.message(1, "a.v1", CascadeHarness.record(VALUE, null));
        harness.apply(1, List.of(message));
        var receipt = harness.receipt(message);
        // "transfers-only" selects another command and is skipped: it is neither held nor failed.
        assertThat(receipt.code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(receipt.steps().getFirst().rules()).isEqualTo(new RuleTrace(1, failure("note-not-x", 0, null)));
        // "masked" held: an error on the left of || is masked by a true right side.
        assertThat(harness.evaluationWork()).isEqualTo(65);
    }

    @Test
    void inputFailuresAndExhaustedWorkKeepTheirClauseAndCode() {
        var lenient = new CascadeHarness(chain(List.of(NOTE_LIMIT), Map.of("a", List.of(attach("note-limit")))),
                id -> {
                    var kernel = new CascadeHarness.RecordKernel();
                    kernel.lenientCodec = true;
                    return kernel;
                });
        byte[] body = CascadeHarness.record(VALUE, "fine");
        var message = CascadeHarness.message(1, "a.v1", Arrays.copyOf(body, body.length + 1));
        lenient.apply(1, List.of(message));
        // A body the codec accepts but no canonical view exists for: no rule runs, the first selecting rule is named.
        assertThat(lenient.receipt(message).code()).isEqualTo("ADMISSION_RULE_INPUT");
        assertThat(lenient.receipt(message).steps().getFirst().rules())
                .isEqualTo(new RuleTrace(0, failure("note-limit", -1, null)));
        assertThat(lenient.evaluationWork()).isEqualTo(13);

        var expensive = rule("expensive", "NEVER", null, new Call("eq", List.of(new Literal("x".repeat(64)),
                new Literal("y".repeat(64)))));
        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 60, 1_000, 4);
        var exhausted = new CascadeHarness(new BindingIrV1(List.of(BindingContextAndViewTest.component("a",
                List.of(attach("expensive")))), List.of(expensive), List.of(), tight, 1),
                id -> new CascadeHarness.RecordKernel());
        var costly = recordTo("a", 2, "n");
        exhausted.apply(1, List.of(costly));
        assertThat(exhausted.receipt(costly).code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(exhausted.receipt(costly).steps().getFirst().rules())
                .isEqualTo(new RuleTrace(0, failure("expensive", 0, null)));
        assertThat(exhausted.evaluationWork()).isEqualTo(133);
    }

    @Test
    void lookupAndContextRulesNeverRunAtIngress() {
        var derivedOnly = rule("derived-only", "DIRECT_FORBIDDEN", null, new Field(Scope.CONTEXT, "derived"));
        var taken = new AdmissionRule("key-taken", "KEY_TAKEN", null, List.of(), List.of(new BindingIrV1.LookupClause(
                "b", new BindingSourceV1.Literal(new byte[]{1, 2, 3}), BindingIrV1.Expectation.EXISTS, null)));
        var ir = chain(List.of(derivedOnly, taken), Map.of("a", List.of(attach("derived-only"), attach("key-taken"))));
        var harness = new CascadeHarness(ir, id -> new CascadeHarness.RecordKernel());
        assertThat(harness.engine.validate(recordTo("a", 1, "fine")).isAccepted()).isTrue();
        var message = recordTo("a", 2, "fine");
        harness.apply(1, List.of(message));
        assertThat(harness.receipt(message).steps().getFirst().rules())
                .isEqualTo(new RuleTrace(0, failure("derived-only", 0, "DIRECT_FORBIDDEN")));
    }

    /**
     * The code, rule and deny code an ingress refusal identifies. ADR-031.4 §5.9 moved the rule and deny code from
     * the reason string into structured details; the triple is unchanged.
     */
    private static List<String> refusal(AdmissionResult result) {
        assertThat(result.isAccepted()).isFalse();
        return List.of(result.reason(), (String) result.details().get("rule"), (String) result.details().get("deny"));
    }

    private static RuleFailure failure(String rule, int clause, String deny) {
        return new RuleFailure(rule, clause, deny);
    }
}
