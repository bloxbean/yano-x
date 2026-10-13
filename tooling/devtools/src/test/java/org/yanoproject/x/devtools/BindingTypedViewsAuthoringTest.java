package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Read;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.4: YAML {@code reads} and write-view quantifiers in admission rules, typed from the kernels' views. */
class BindingTypedViewsAuthoringTest {
    private static final List<CommandDescriptor> LEDGER = List.of(new CommandDescriptor("transfer",
            CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(
            new CommandDescriptor.Field("to", TransitionScalars.Type.TEXT, true, CommandDescriptor.Role.DATA),
            new CommandDescriptor.Field("amount", TransitionScalars.Type.INTEGER, true, CommandDescriptor.Role.DATA),
            new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, false,
                    CommandDescriptor.Role.EVIDENCE))));

    /** {@code ledger} has a transfer command; {@code registry} has two views and a write view with coverage. */
    private static final BindingDocumentCompiler.DescriptorCatalog CATALOG =
            new BindingDocumentCompiler.DescriptorCatalog() {
                @Override public ConfigurationDescriptor configuration(String machineId) {
                    return ConfigurationDescriptor.empty();
                }
                @Override public Map<String, Type> eventFields(Component component, String eventId) {
                    throw new IllegalArgumentException("no events");
                }
                @Override public List<CommandDescriptor> commands(Component component) {
                    return component.machineId().equals("ledger") ? LEDGER : List.of();
                }
                @Override public Map<String, Map<String, RuleFact.Type>> ruleValueViews(Component component) {
                    if (!component.machineId().equals("registry")) return Map.of();
                    Map<String, Map<String, RuleFact.Type>> views = new LinkedHashMap<>();
                    views.put("holders", Map.of("status", RuleFact.Type.TEXT, "value.maxTransfer",
                            RuleFact.Type.INTEGER));
                    views.put("settings", Map.of("value.max", RuleFact.Type.INTEGER));
                    return views;
                }
                @Override public Map<String, RuleFact.Type> ruleWriteFields(Component component) {
                    if (!component.machineId().equals("registry")) return Map.of();
                    Map<String, RuleFact.Type> fields = new LinkedHashMap<>();
                    fields.put("index", RuleFact.Type.INTEGER);
                    fields.put("op", RuleFact.Type.TEXT);
                    fields.put("keyText", RuleFact.Type.TEXT);
                    fields.put("value.maxTransfer", RuleFact.Type.INTEGER);
                    fields.put("coverage", RuleFact.Type.TEXT);
                    fields.put("actorRoles", RuleFact.Type.TEXT_SET);
                    return fields;
                }
            };

    private static final String DOCUMENT = """
            components:
              - id: token
                machine: ledger
                admission:
                  - rule: tier-limit
              - id: registry
                machine: registry
                admission:
                  - rule: operators-only
            rules:
              - id: tier-limit
                command: transfer
                deny: TIER_LIMIT_EXCEEDED
                reads:
                  limits: {component: registry, namespace: settings, key: {literal: transfer}}
                  holder: {component: registry, namespace: holders, key: {context: sender}}
                require:
                  - expr: 'reads.holder.present && reads.holder.status == "ACTIVE"'
                  - expr: 'command.amount <= reads.holder.value.maxTransfer && command.amount <= reads.limits.value.max'
              - id: operators-only
                deny: ROLE_REQUIRED
                require:
                  - expr: 'writes.all(w, w.op != "REVOKE" || (w.coverage == "direct" && "operator" in w.actorRoles))'
            bindings: []
            """;

    @Test
    void readsLowerSortedByNameWithTheirKeysAndClausesTypedFromTheViews() {
        BindingIrV1 ir = compile(DOCUMENT);
        var tier = ir.rule("tier-limit");
        assertThat(tier.reads()).containsExactly(
                new Read("holder", "registry", "holders", new BindingSourceV1.Field(Scope.CONTEXT, "sender")),
                new Read("limits", "registry", "settings", new BindingSourceV1.Literal("transfer")));
        var first = ((ExpressionClause) tier.clauses().getFirst()).expression().root();
        assertThat(first).isEqualTo(new Call("and", List.of(Field.read("holder", "present"),
                new Call("eq", List.of(Field.read("holder", "status"), new Literal("ACTIVE"))))));
        var quantified = ((ExpressionClause) ir.rule("operators-only").clauses().getFirst()).expression().root();
        assertThat(quantified).isInstanceOf(Quantifier.class);
        assertThat(ir.rule("operators-only").readsWrites()).isTrue();
        assertThat(BindingIrV1.decode(ir.encode()).encode()).isEqualTo(ir.encode());
        // An omitted namespace is the default one.
        assertThat(code(DOCUMENT.replace("namespace: settings, ", ""))).isEqualTo(
                "RULE_READ_UNKNOWN_NAMESPACE $.rules[0].reads.limits");
    }

    @Test
    void readsAndTheirKeysAreCheckedAtTheirAuthoredPositions() {
        assertThat(code(DOCUMENT.replace("{component: registry, namespace: settings",
                "{component: nowhere, namespace: settings"))).isEqualTo(
                "RULE_READ_UNKNOWN_COMPONENT $.rules[0].reads.limits.component");
        assertThat(code(DOCUMENT.replace("namespace: settings", "namespace: other"))).isEqualTo(
                "RULE_READ_UNKNOWN_NAMESPACE $.rules[0].reads.limits.namespace");
        assertThat(code(DOCUMENT.replace("reads.holder.status", "reads.holder.owner"))).isEqualTo(
                "RULE_READ_UNKNOWN_FIELD $.rules[0].require[0]");
        // Keys read no read and no write, and never evidence.
        assertThat(code(DOCUMENT.replace("key: {literal: transfer}", "key: {expr: 'reads.holder.status'}")))
                .startsWith("RULE_SCOPE_INVALID $.rules[0].reads.limits.key");
        assertThat(code(DOCUMENT.replace("key: {literal: transfer}",
                "key: {expr: 'writes.exists(w, w.op == \"PUT\") ? \"a\" : \"b\"'}")))
                .startsWith("RULE_SCOPE_INVALID $.rules[0].reads.limits.key");
        assertThat(code(DOCUMENT.replace("key: {literal: transfer}", "key: {command: signature}"))).isEqualTo(
                "RULE_EVIDENCE_READ $.rules[0].reads.limits.key");
        assertThat(code(DOCUMENT.replace("  holder: {component: registry, namespace: holders, key: {context: sender}}",
                "  holder: {component: registry, namespace: holders, key: {context: sender}}\n"
                        + "      a: {component: registry, namespace: holders, key: {literal: a}}\n"
                        + "      b: {component: registry, namespace: holders, key: {literal: b}}\n"
                        + "      c: {component: registry, namespace: holders, key: {literal: c}}")))
                .isEqualTo("EXPECTED_OBJECT $.rules[0].reads");
        assertThat(code(DOCUMENT.replace("key: {literal: transfer}", "key: {literal: transfer}, extra: 1")))
                .contains("$.rules[0].reads.limits");
    }

    @Test
    void writeFieldsAreTypedOnlyWhereTheKernelDeclaresAWriteView() {
        assertThat(code(DOCUMENT.replace("w.op != \"REVOKE\"", "w.owner != \"x\""))).isEqualTo(
                "RULE_FIELD_UNKNOWN $.rules[1].require[0]");
        String onLedger = DOCUMENT.replace("    admission:\n      - rule: operators-only\n", "")
                .replace("      - rule: tier-limit\n", "      - rule: tier-limit\n      - rule: operators-only\n");
        assertThat(code(onLedger)).isEqualTo("RULE_WRITES_UNSUPPORTED $.rules[1].require[0]");
    }

    private static BindingIrV1 compile(String yaml) { return BindingDocumentCompiler.compile(yaml, CATALOG); }

    private static String code(String yaml) {
        try {
            compile(yaml);
        } catch (BindingAuthoringException failure) {
            return failure.code() + " " + failure.getMessage().split(":")[0];
        }
        throw new AssertionError("expected an authoring failure");
    }
}
