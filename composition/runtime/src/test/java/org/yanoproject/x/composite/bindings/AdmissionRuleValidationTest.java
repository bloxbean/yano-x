package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Parameter;
import org.yanoproject.x.composite.contracts.BindingIrV1.ParameterType;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.3 Phase 3: rule validation at profile construction, one diagnostic code per failure class. */
class AdmissionRuleValidationTest {
    private static AdmissionRule rule(String id, String command, BindingExpressionV1.Node condition,
                                      Parameter... parameters) {
        return new AdmissionRule(id, "DENIED", command, List.of(parameters),
                List.of(BindingContextAndViewTest.condition(condition)));
    }

    private static BindingIrV1 document(List<AdmissionRule> rules, List<RuleAttachment> onA) {
        return new BindingIrV1(List.of(BindingContextAndViewTest.component("a", onA),
                BindingContextAndViewTest.component("b", List.of())), rules, List.of(new BindingIrV1.Binding(
                "to-b", "a", "recorded.v1", List.of(), BindingContextAndViewTest.recordTarget("b",
                new BindingSourceV1.Literal("n")))), BindingIrV1.Limits.DEFAULT, 1);
    }

    /** Two components and no bindings, for kernels that publish no {@code recorded.v1} event. */
    private static BindingIrV1 unbound(List<AdmissionRule> rules, List<RuleAttachment> onA) {
        return new BindingIrV1(List.of(BindingContextAndViewTest.component("a", onA),
                BindingContextAndViewTest.component("b", List.of())), rules, List.of(), BindingIrV1.Limits.DEFAULT, 1);
    }

    private static void rejects(BindingIrV1 ir, String code) {
        rejects(ir, Map.of(), code);
    }

    private static void rejects(BindingIrV1 ir, Map<String, TransitionKernel<?, ?>> overrides, String code) {
        Map<String, TransitionKernel<?, ?>> kernels = new LinkedHashMap<>();
        ir.components().forEach(component -> kernels.put(component.id(), new CascadeHarness.RecordKernel()));
        kernels.putAll(overrides);
        assertThatThrownBy(() -> new BindingProgram(ir, kernels)).as(code)
                .isInstanceOfSatisfying(BindingValidationException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.getMessage()).doesNotContain("secret");
                });
    }

    private static RuleAttachment attach(String rule, Map<String, Object> parameters) {
        Map<String, BindingSourceV1.Literal> values = new LinkedHashMap<>();
        parameters.forEach((name, value) -> values.put(name, new BindingSourceV1.Literal(value)));
        return new RuleAttachment(rule, values);
    }

    private static final BindingExpressionV1.Node TRUE = new Literal(true);

    @Test
    void attachmentsResolveRulesOncePerComponent() {
        rejects(document(List.of(rule("known", null, TRUE)), List.of(attach("known", Map.of()),
                attach("missing", Map.of()))), "RULE_UNKNOWN");
        rejects(document(List.of(rule("known", null, TRUE)), List.of(attach("known", Map.of()),
                attach("known", Map.of()))), "RULE_DUPLICATE");
        rejects(document(List.of(rule("attached", null, TRUE), rule("orphan", null, TRUE)),
                List.of(attach("attached", Map.of()))), "RULE_UNATTACHED");
    }

    @Test
    void commandSelectorsNeedADeclaredCommandOnASelectableKernel() {
        rejects(document(List.of(rule("r", "transfer-all", TRUE)), List.of(attach("r", Map.of()))),
                "RULE_COMMAND_UNKNOWN");
        rejects(unbound(List.of(rule("r", "append", TRUE)), List.of(attach("r", Map.of()))),
                Map.of("a", new OrderedLogKernel()), "RULE_COMMAND_UNSELECTABLE");
        // Without a selector a raw kernel still accepts rules over params, config, context and facts.
        var ir = unbound(List.of(rule("r", null, new Call("not", List.of(new Field(Scope.CONTEXT, "derived"))))),
                List.of(attach("r", Map.of())));
        Map<String, TransitionKernel<?, ?>> kernels = new LinkedHashMap<>();
        kernels.put("a", new OrderedLogKernel());
        kernels.put("b", new CascadeHarness.RecordKernel());
        assertThat(new BindingProgram(ir, kernels).rules().component("a").admission()).hasSize(1);
    }

    @Test
    void rulesReadOnlyDeclaredDataFieldsParametersConfigurationAndFacts() {
        rejects(document(List.of(rule("r", "record", new Call("eq", List.of(new Field(Scope.COMMAND, "missing"),
                new Literal(""))))), List.of(attach("r", Map.of()))), "RULE_FIELD_UNKNOWN");
        rejects(document(List.of(rule("r", "record", new Call("eq", List.of(new Field(Scope.COMMAND, "proof"),
                new Literal(new byte[0]))))), List.of(attach("r", Map.of()))), "RULE_EVIDENCE_READ");
        rejects(document(List.of(rule("r", null, new Call("eq", List.of(new Field(Scope.CONFIG, "absent"),
                new Literal(""))))), List.of(attach("r", Map.of()))), "RULE_FIELD_UNKNOWN");
        rejects(document(List.of(rule("r", null, new Call("in", List.of(new Literal("x"),
                new Field(Scope.FACTS, "roles"))))), List.of(attach("r", Map.of()))), "RULE_FACT_UNKNOWN");
        rejects(document(List.of(rule("r", null, new Call("eq", List.of(new Field(Scope.PARAMS, "limit"),
                new Literal(1L))))), List.of(attach("r", Map.of()))), "RULE_FIELD_UNKNOWN");
        // A type mismatch in a rule expression is an ordinary expression error.
        rejects(document(List.of(rule("r", "record", new Call("eq", List.of(new Field(Scope.COMMAND, "note"),
                new Literal(1L))))), List.of(attach("r", Map.of()))), "EXPRESSION_INVALID");
    }

    @Test
    void parametersAreExactlyTheDeclaredOnesWithTheirTypes() {
        var limit = rule("r", null, new Call("gt", List.of(new Field(Scope.PARAMS, "limit"), new Literal(0L))),
                new Parameter("limit", ParameterType.INTEGER, null));
        rejects(document(List.of(limit), List.of(attach("r", Map.of()))), "RULE_PARAMETER_MISSING");
        rejects(document(List.of(limit), List.of(attach("r", Map.of("limit", "ten")))), "RULE_PARAMETER_TYPE");
        rejects(document(List.of(limit), List.of(attach("r", Map.of("limit", 1L, "extra", 2L)))),
                "RULE_PARAMETER_UNKNOWN");
        var viaBinding = rule("r", null, new Call("eq", List.of(new Field(Scope.CONTEXT, "binding"),
                new Field(Scope.PARAMS, "binding"))), new Parameter("binding", ParameterType.BINDING, null));
        // to-b targets b, not a, so it cannot constrain arrivals at a.
        rejects(document(List.of(viaBinding), List.of(attach("r", Map.of("binding", "to-b")))),
                "RULE_PARAMETER_TYPE");
        rejects(document(List.of(viaBinding), List.of(attach("r", Map.of("binding", "no-such-binding")))),
                "RULE_PARAMETER_TYPE");
    }

    @Test
    void lookupsNameDeclaredComponentsAndBytesKeys() {
        var unknown = new AdmissionRule("r", "DENIED", null, List.of(), List.of(new BindingIrV1.LookupClause("nowhere",
                new BindingSourceV1.Field(Scope.CONTEXT, "sender"), BindingIrV1.Expectation.EXISTS, null)));
        rejects(document(List.of(unknown), List.of(attach("r", Map.of()))), "RULE_LOOKUP_COMPONENT_UNKNOWN");
        var textKey = new AdmissionRule("r", "DENIED", null, List.of(), List.of(new BindingIrV1.LookupClause("b",
                new BindingSourceV1.Field(Scope.CONTEXT, "binding"), BindingIrV1.Expectation.EXISTS, null)));
        rejects(document(List.of(textKey), List.of(attach("r", Map.of()))), "BINDING_TYPE_MISMATCH");
    }

    @Test
    void profileLimitsApplyToRuleExpressions() {
        BindingExpressionV1.Node deep = TRUE;
        for (int level = 0; level < 20; level++) deep = new Call("not", List.of(deep));
        var ir = document(List.of(rule("r", null, deep)), List.of(attach("r", Map.of())));
        var tight = new BindingIrV1(ir.components(), ir.rules(), ir.bindings(),
                new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 1048576, 33554432, 4), 1);
        rejects(tight, "RULE_LIMIT");
    }

    @Test
    void factDeclarationsFollowTheHostContract() {
        var duplicate = new CascadeHarness.RecordKernel();
        duplicate.declared = List.of(new RuleFact("roles", RuleFact.Type.TEXT_SET),
                new RuleFact("roles", RuleFact.Type.TEXT));
        rejects(document(List.of(rule("r", null, TRUE)), List.of(attach("r", Map.of()))), Map.of("a", duplicate),
                "KERNEL_CONTRACT_INVALID");
    }

    @Test
    void slotsAndStaticnessFollowTheRuleInputs() {
        var command = rule("static-rule", "record", new Call("ne", List.of(new Field(Scope.COMMAND, "note"),
                new Literal(""))));
        var context = rule("context-rule", null, new Field(Scope.CONTEXT, "derived"));
        var fact = rule("fact-rule", null, new Call("in", List.of(new Literal("x"), new Field(Scope.FACTS, "roles"))));
        var kernel = new CascadeHarness.RecordKernel();
        kernel.declared = List.of(new RuleFact("roles", RuleFact.Type.TEXT_SET));
        var ir = document(List.of(context, fact, command), List.of(attach("fact-rule", Map.of()),
                attach("static-rule", Map.of()), attach("context-rule", Map.of())));
        Map<String, TransitionKernel<?, ?>> kernels = new LinkedHashMap<>();
        kernels.put("a", kernel);
        kernels.put("b", new CascadeHarness.RecordKernel());
        var resolved = new BindingProgram(ir, kernels).rules().component("a");
        assertThat(resolved.admission()).extracting(attached -> attached.rule().id())
                .containsExactly("static-rule", "context-rule");
        assertThat(resolved.facts()).extracting(attached -> attached.rule().id()).containsExactly("fact-rule");
        assertThat(resolved.admission()).extracting(BindingRules.Attached::isStatic).containsExactly(true, false);
    }

    @Test
    void theContractsCopyOfCelReservedWordsEqualsTheHostContract() {
        assertThat(BindingIrV1.CEL_RESERVED_WORDS).isEqualTo(RuleFact.RESERVED_NAMES);
    }
}
