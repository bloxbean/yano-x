package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingIrV1.Expectation;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.Parameter;
import org.yanoproject.x.composite.contracts.BindingIrV1.ParameterType;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-031.3: YAML {@code rules} and {@code components[].admission} authoring, lowering and diagnostics. */
class BindingRuleAuthoringTest {
    private static final CommandDescriptor.Field TO = new CommandDescriptor.Field("to", TransitionScalars.Type.TEXT,
            true, CommandDescriptor.Role.DATA);
    private static final CommandDescriptor.Field AMOUNT = new CommandDescriptor.Field("amount",
            TransitionScalars.Type.INTEGER, true, CommandDescriptor.Role.DATA);
    private static final List<CommandDescriptor> LEDGER = List.of(
            new CommandDescriptor("transfer", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(TO, AMOUNT,
                    new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, false,
                            CommandDescriptor.Role.EVIDENCE))),
            new CommandDescriptor("mint", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 2, List.of(TO, AMOUNT)));
    private static final List<CommandDescriptor> LOG = List.of(new CommandDescriptor("append",
            CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));

    private static final BindingDocumentCompiler.DescriptorCatalog CATALOG =
            new BindingDocumentCompiler.DescriptorCatalog() {
                @Override
                public ConfigurationDescriptor configuration(String machineId) {
                    return switch (machineId) {
                        case "ledger" -> new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting(
                                "minter", TransitionScalars.Type.TEXT, "treasury")));
                        case "log" -> new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting(
                                "minter", TransitionScalars.Type.INTEGER, 0L)));
                        default -> throw new IllegalArgumentException("unknown machine");
                    };
                }

                @Override
                public Map<String, Type> eventFields(Component component, String eventId) {
                    if (!eventId.equals("ledger.moved.v1")) throw new IllegalArgumentException("unknown event");
                    return Map.of("amount", Type.INTEGER, "to", Type.TEXT, "key", Type.BYTES);
                }

                @Override
                public List<CommandDescriptor> commands(Component component) {
                    return component.machineId().equals("ledger") ? LEDGER : LOG;
                }

                @Override
                public Map<String, RuleFact.Type> ruleFacts(Component component) {
                    return component.machineId().equals("ledger")
                            ? Map.of("roles", RuleFact.Type.TEXT_SET, "actorCount", RuleFact.Type.INTEGER) : Map.of();
                }
            };

    private static final String DOCUMENT = """
            components:
              - id: points
                machine: ledger
                admission:
                  - rule: transfer-limit
                    params: {maxAmount: 10000}
                  - rule: known-recipient
              - id: accounts
                machine: ledger
            rules:
              - id: transfer-limit
                command: transfer
                deny: TRANSFER_LIMIT_EXCEEDED
                params:
                  maxAmount: {type: integer}
                  role: {type: text, default: operator}
                require:
                  - expr: 'command.amount <= params.maxAmount'
                  - expr: 'facts.actorCount == 0 || params.role in facts.roles'
              - id: known-recipient
                deny: SENDER_UNKNOWN
                require:
                  - lookup: {component: accounts, key: {context: sender}, exists: true}
            bindings: []
            """;

    private static final String ATTACHMENTS = "      - rule: transfer-limit\n        params: {maxAmount: 10000}\n"
            + "      - rule: known-recipient\n";
    private static final String ACCOUNTS = "  - id: accounts\n    machine: ledger\n";

    @Test
    void lowersRulesSortedByIdAndAttachmentsInDeclaredOrder() {
        assertThat(DOCUMENT).contains(ATTACHMENTS, ACCOUNTS);
        BindingIrV1 ir = compile(DOCUMENT);
        assertThat(ir.rules()).extracting(AdmissionRule::id).containsExactly("known-recipient", "transfer-limit");
        assertThat(ir.components().getFirst().admission()).extracting(RuleAttachment::rule)
                .containsExactly("transfer-limit", "known-recipient");
        AdmissionRule limit = ir.rule("transfer-limit");
        assertThat(limit.command()).isEqualTo("transfer");
        assertThat(limit.denyCode()).isEqualTo("TRANSFER_LIMIT_EXCEEDED");
        assertThat(limit.parameters()).containsExactly(new Parameter("maxAmount", ParameterType.INTEGER, null),
                new Parameter("role", ParameterType.TEXT, new BindingSourceV1.Literal("operator")));
        var second = ((ExpressionClause) limit.clauses().get(1)).expression().root();
        assertThat(((Call) second).arguments().get(1)).isEqualTo(new Call("in", List.of(
                new Field(Scope.PARAMS, "role"), new Field(Scope.FACTS, "roles"))));
        assertThat(ir.rule("known-recipient").clauses()).containsExactly(new LookupClause("accounts",
                new BindingSourceV1.Field(Scope.CONTEXT, "sender"), Expectation.EXISTS, null));
        assertThat(BindingIrV1.decode(ir.encode()).encode()).isEqualTo(ir.encode());
    }

    @Test
    void attachmentParametersAreNormalizedSoStatingADefaultEqualsOmittingIt() {
        byte[] implicit = compile(DOCUMENT).encode();
        byte[] explicit = compile(DOCUMENT.replace("params: {maxAmount: 10000}",
                "params: {role: operator, maxAmount: 10000}")).encode();
        assertThat(explicit).isEqualTo(implicit);
        assertThat(compile(DOCUMENT).components().getFirst().admission().getFirst().parameters())
                .containsEntry("role", new BindingSourceV1.Literal("operator"));
        // Declared rule order does not matter; attachment order does.
        String swapped = DOCUMENT.replace(ATTACHMENTS, "      - rule: known-recipient\n"
                + "      - rule: transfer-limit\n        params: {maxAmount: 10000}\n");
        assertThat(compile(swapped).encode()).isNotEqualTo(implicit);
    }

    @Test
    void equalityLookupsDistinguishFieldAndLiteralOperands() {
        String document = DOCUMENT.replace("key: {context: sender}, exists: true}",
                "key: {context: sender}, eq: {param: expected}}")
                .replace("    deny: SENDER_UNKNOWN\n", "    deny: SENDER_UNKNOWN\n    params:\n"
                        + "      expected: {type: bytes, default: {bytesHex: '01'}}\n");
        var lookup = (LookupClause) compile(document).rule("known-recipient").clauses().getFirst();
        assertThat(lookup.expectation()).isEqualTo(Expectation.EQUAL_FIELD);
        assertThat(lookup.operand()).isEqualTo(new BindingSourceV1.Field(Scope.PARAMS, "expected"));
        var literal = (LookupClause) compile(DOCUMENT.replace("exists: true}", "eq: {literal: {bytesHex: '0a'}}}"))
                .rule("known-recipient").clauses().getFirst();
        assertThat(literal.expectation()).isEqualTo(Expectation.EQUAL_LITERAL);
    }

    @Test
    void attachmentFailuresNameTheAttachment() {
        assertInvalid(DOCUMENT.replace("- rule: known-recipient", "- rule: nowhere"), "RULE_UNKNOWN",
                "$.components[0].admission[1].rule");
        assertInvalid(DOCUMENT.replace("params: {maxAmount: 10000}", "params: {maxAmount: 1, extra: 2}"),
                "RULE_PARAMETER_UNKNOWN", "$.components[0].admission[0].params.extra");
        assertInvalid(DOCUMENT.replace("params: {maxAmount: 10000}", "params: {}"), "RULE_PARAMETER_MISSING",
                "$.components[0].admission[0].params.maxAmount");
        assertInvalid(DOCUMENT.replace("params: {maxAmount: 10000}", "params: {maxAmount: ten}"),
                "RULE_PARAMETER_TYPE", "$.components[0].admission[0].params.maxAmount");
    }

    @Test
    void ruleFailuresNameTheRuleClause() {
        assertInvalid(DOCUMENT.replace("id: known-recipient", "id: transfer-limit"), "RULE_DUPLICATE",
                "$.rules[1].id");
        assertInvalid(DOCUMENT.replace("      - rule: known-recipient\n", ""), "RULE_UNATTACHED", "$.rules[1]");
        assertInvalid(DOCUMENT.replace("command: transfer", "command: burn"), "RULE_COMMAND_UNKNOWN",
                "$.rules[0].command");
        assertInvalid(DOCUMENT.replace("{type: integer}", "{type: decimal}"), "RULE_PARAMETER_TYPE",
                "$.rules[0].params.maxAmount.type");
        assertInvalid(DOCUMENT.replace("component: accounts,", "component: vault,"),
                "RULE_LOOKUP_COMPONENT_UNKNOWN", "$.rules[1].require[0].lookup.component");
        assertInvalid(DOCUMENT.replace("{context: sender}", "{field: key}"), "RULE_SCOPE_INVALID",
                "$.rules[1].require[0].lookup.key.field");
        assertInvalid(DOCUMENT.replace("{context: sender}", "{command: to}"), "RULE_SCOPE_INVALID",
                "$.rules[1].require[0].lookup.key.command");
        assertInvalid(DOCUMENT.replace("{context: sender}", "{context: timestamp}"), "RULE_FIELD_UNKNOWN",
                "$.rules[1].require[0].lookup.key");
        assertInvalid(DOCUMENT.replace("{context: sender}", "{fact: organization}"), "RULE_FACT_UNKNOWN",
                "$.rules[1].require[0].lookup.key");
        // Evidence is never readable, and rules have no event scope.
        assertInvalid(DOCUMENT.replace("command.amount <= params.maxAmount", "command.signature != b\"\""),
                "RULE_EVIDENCE_READ", "$.rules[0].require[0]: rule reads evidence field command.signature");
        // An undeclared member of an available rule scope names the scope's own diagnostic.
        assertInvalid(DOCUMENT.replace("command.amount <= params.maxAmount", "facts.region == \"eu\""),
                "RULE_FACT_UNKNOWN", "$.rules[0].require[0]: invalid binding expression");
        assertInvalid(DOCUMENT.replace("command.amount <= params.maxAmount", "params.missing > 0"),
                "RULE_FIELD_UNKNOWN", "$.rules[0].require[0]: invalid binding expression");
        assertInvalid(DOCUMENT.replace("command.amount <= params.maxAmount", "command.missing > 0"),
                "RULE_FIELD_UNKNOWN", "$.rules[0].require[0]: invalid binding expression");
        assertInvalid(DOCUMENT.replace("command.amount <= params.maxAmount", "event.amount > 0"),
                "RULE_SCOPE_INVALID", "$.rules[0].require[0]: event scope is not available in an admission rule");
    }

    @Test
    void rulesAreCheckedAgainstEveryAttachedComponent() {
        String journal = ACCOUNTS + "  - id: journal\n    machine: log\n    admission:\n";
        assertInvalid(DOCUMENT.replace(ACCOUNTS, journal + "      - rule: transfer-limit\n"
                + "        params: {maxAmount: 1}\n"), "RULE_COMMAND_UNSELECTABLE", "$.rules[0].command");
        // config.minter is text on the ledger and an integer on the log, so the rule type-checks only on one.
        String minter = DOCUMENT.replace("bindings: []", """
                  - id: minter-set
                    deny: NO_MINTER
                    require:
                      - expr: 'config.minter != ""'
                bindings: []
                """);
        String onLedger = minter.replace(ATTACHMENTS, ATTACHMENTS + "      - rule: minter-set\n");
        assertThat(compile(onLedger).rule("minter-set")).isNotNull();
        assertInvalid(onLedger.replace(ACCOUNTS, journal + "      - rule: minter-set\n"), "EXPRESSION_INVALID",
                "$.rules[2].require[0]");
    }

    @Test
    void perAttachmentComparisonUsesTheDocumentLimits() {
        // Three lookups exceed the default maxLookupsPerCondition (2) but not the document's own limit.
        String lookup = "      - lookup: {component: accounts, key: {context: sender}, exists: true}\n";
        String document = DOCUMENT.replace(lookup, lookup.repeat(3))
                .replace(ACCOUNTS, ACCOUNTS + "    admission:\n      - rule: known-recipient\n")
                .replace("bindings: []", "bindings: []\nlimits: {maxLookupsPerCondition: 3}");
        var ir = compile(document);
        assertThat(ir.rule("known-recipient").clauses()).hasSize(3);
        assertThat(ir.components().get(1).admission()).extracting(RuleAttachment::rule)
                .containsExactly("known-recipient");
    }

    @Test
    void bindingsCannotReadRuleScopes() {
        String bound = DOCUMENT.replace("bindings: []", """
                bindings:
                  - id: forward
                    from: {component: points, event: ledger.moved.v1}
                    to:
                      component: accounts
                      command: mint
                      map:
                        to: {param: role}
                        amount: {field: amount}
                """);
        assertInvalid(bound, "RULE_SCOPE_INVALID", "$.bindings[0].to.map.to.param");
        assertInvalid(bound.replace("{param: role}", "{expr: 'facts.roles'}"), "RULE_SCOPE_INVALID",
                "$.bindings[0].to.map.to.expr: facts scope is not available in a binding");
        assertThat(compile(bound.replace("{param: role}", "{field: to}")).bindings()).hasSize(1);
    }

    @Test
    void wrappedDocumentsReportRulePathsUnderTheWrapper() {
        String wrapped = "composite:\n" + DOCUMENT.indent(2);
        assertThat(compile(wrapped).encode()).isEqualTo(compile(DOCUMENT).encode());
        assertInvalid(wrapped.replace("- rule: known-recipient", "- rule: nowhere"), "RULE_UNKNOWN",
                "$.composite.components[0].admission[1].rule");
    }

    private static BindingIrV1 compile(String yaml) {
        return BindingDocumentCompiler.compile(yaml, CATALOG);
    }

    private static void assertInvalid(String yaml, String code, String path) {
        assertThatThrownBy(() -> compile(yaml)).isInstanceOfSatisfying(BindingAuthoringException.class, failure -> {
            assertThat(failure.code()).as(failure.getMessage()).isEqualTo(code);
            assertThat(failure.getMessage()).startsWith(path);
        });
    }
}
