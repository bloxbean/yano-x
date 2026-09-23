package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.CommandTarget;
import org.yanoproject.x.composite.contracts.BindingIrV1.Component;
import org.yanoproject.x.composite.contracts.BindingIrV1.ExpressionClause;
import org.yanoproject.x.composite.contracts.BindingIrV1.LookupClause;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingDocumentCompilerTest {
    @Test
    void providerConstructionFailuresRetainFullAuthoredLocationAndCause() {
        var failure = new IllegalStateException("provider construction failed");
        var catalog = new BindingDocumentCompiler.DescriptorCatalog() {
            @Override public ConfigurationDescriptor configuration(String machine) { throw failure; }
            @Override public Map<String, Type> eventFields(Component component, String event) { return Map.of(); }
        };
        assertThatThrownBy(() -> BindingDocumentCompiler.compile("""
                composite:
                  components: [{id: source, machine: fixture}]
                  bindings: []
                """, catalog)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$.composite.components[0].config: provider construction failed");
    }
    private static final BindingDocumentCompiler.DescriptorCatalog CATALOG =
            new BindingDocumentCompiler.DescriptorCatalog() {
                @Override
                public ConfigurationDescriptor configuration(String machineId) {
                    if (!machineId.equals("fixture")) throw new IllegalArgumentException("unknown machine");
                    return new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting("format",
                            TransitionScalars.Type.TEXT, "raw")));
                }

                @Override
                public Map<String, Type> eventFields(Component component, String eventId) {
                    assertThat(component.configuration().get("format").value()).isEqualTo("raw");
                    if (!eventId.equals("fixture.changed.v1")) throw new IllegalArgumentException("unknown event");
                    return Map.of("amount", Type.INTEGER, "key", Type.BYTES, "label", Type.TEXT);
                }
            };
    private static final String PREFIX = """
            components:
              - {id: source, machine: fixture}
              - {id: target, machine: fixture}
            bindings:
            """;
    private static final String BINDING = """
              - id: forward
                from: {component: source, event: fixture.changed.v1}
                to:
                  component: target
                  command: put
                  map:
                    key: {field: key}
                    amount: {expr: 'event.amount * 100'}
            """;

    @Test
    void normalizesDefaultsAndMapOrderingToIdenticalCanonicalBytes() {
        BindingIrV1 implicit = compile(PREFIX + BINDING);
        BindingIrV1 explicit = compile((PREFIX + BINDING).replace("machine: fixture}",
                        "config: {format: raw}, machine: fixture, fromHeight: 1, maxEffectsPerBlock: 0}")
                .replace("        key: {field: key}\n        amount: {expr: 'event.amount * 100'}",
                        "        amount: {expr: 'event.amount * 100'}\n        key: {field: key}"));
        assertThat(implicit.encode()).isEqualTo(explicit.encode());
        assertThat(implicit.components().getFirst().ingressTopic()).isEqualTo("source.command.v1");
        assertThat(implicit.components().getFirst().configuration().get("format").value()).isEqualTo("raw");
        assertThat(((CommandTarget) implicit.bindings().getFirst().target()).mapping().fields())
                .extracting(BindingIrV1.Assignment::field).containsExactly("amount", "key");
        assertThat(BindingIrV1.decode(implicit.encode()).encode()).isEqualTo(implicit.encode());
    }

    @Test
    void acceptsSingleCompositeWrapperAndReportsFullPath() {
        String wrapped = "composite:\n" + (PREFIX + BINDING).indent(2);
        assertThat(compile(wrapped).encode()).isEqualTo(compile(PREFIX + BINDING).encode());
        assertThatThrownBy(() -> compile(wrapped.replace("command: put", "command: put\n        typo: true")))
                .hasMessageContaining("$.composite.bindings[0].to.typo");
        assertThatThrownBy(() -> compile(wrapped + "unrelated: true\n")).hasMessageContaining("$.unrelated");
    }

    @Test
    void preservesDeclaredComponentAndBindingOrder() {
        String second = BINDING.replace("id: forward", "id: again");
        BindingIrV1 firstOrder = compile(PREFIX + BINDING + second);
        BindingIrV1 reverseOrder = compile(PREFIX + second + BINDING);
        assertThat(firstOrder.encode()).isNotEqualTo(reverseOrder.encode());
        assertThat(firstOrder.bindings()).extracting(BindingIrV1.Binding::id).containsExactly("forward", "again");
    }

    @Test
    void compilesConditionsLookupsFunctionsAndTypedByteLiterals() {
        String document = PREFIX + """
              - id: forward
                from: {component: source, event: fixture.changed.v1}
                when:
                  - {expr: 'event.amount >= 10 && event.amount < 500'}
                  - {field: label, in: [one, two]}
                  - lookup: {component: target, key: {field: key}, exists: true}
                  - lookup:
                      component: target
                      key: {literal: {bytesHex: '00ff'}}
                      eq: {literal: {bytesHex: '0102'}}
                to:
                  component: target
                  command: put
                  map:
                    amount: {literal: 3}
                    label: {fn: concat, args: [{literal: 'prefix:'}, {field: label}]}
            """;
        BindingIrV1 ir = compile(document);
        assertThat(ir.bindings().getFirst().conditions().getFirst()).isInstanceOf(ExpressionClause.class);
        LookupClause lookup = (LookupClause) ir.bindings().getFirst().conditions().get(3);
        assertThat(((BindingSourceV1.Literal) lookup.operand()).value()).isEqualTo(new byte[]{1, 2});
        assertThat(((BindingSourceV1.Literal) lookup.key()).value()).isEqualTo(new byte[]{0, (byte) 255});
    }

    @Test
    void supportsEffectIdentityAndRawBodyAndExplicitGenerationHeights() {
        String effect = PREFIX + """
              - id: notify
                from: {component: source, event: fixture.changed.v1}
                to:
                  effect: {type: webhook.post, gate: l1-final, result: chain, expiryBlocks: 20, map: identity}
            workflowFromHeight: 12
            limits: {maxCascadeDepth: 3, maxExpressionNodes: 32}
            """;
        BindingIrV1 ir = compile(effect.replace("id: target,", "id: target, fromHeight: 12,"));
        assertThat(ir.workflowFromHeight()).isEqualTo(12);
        assertThat(ir.limits().maxCascadeDepth()).isEqualTo(3);
        assertThat(ir.limits().maxExpressionNodes()).isEqualTo(32);
        assertThat(compile(PREFIX + """
              - id: forward
                from: {component: source, event: fixture.changed.v1}
                to: {component: target, command: put, rawBody: key}
            """).bindings().getFirst().target().mapping().bodyField()).isEqualTo("key");
    }

    @Test
    void rejectsDuplicateKeysAndMultipleDocuments() {
        assertInvalid(PREFIX + BINDING + "bindings: []\n", "Duplicate field");
        assertInvalid(PREFIX + BINDING + "---\ncomponents: []\nbindings: []\n", "one YAML document");
        assertInvalid(PREFIX + BINDING.replace("{field: key}", "{field: key, field: key}"), "Duplicate field");
    }

    @Test
    void rejectsAliasesAndCustomTagsWithoutObjectConstruction() {
        assertInvalid(PREFIX + BINDING.replace("field: key", "field: *source"), "aliases, anchors");
        assertInvalid(PREFIX + BINDING.replace("field: key", "field: !custom key"), "explicit tags");
    }

    @Test
    void rejectsUnknownFieldsAmbiguousSourcesOperatorsAndMappings() {
        assertInvalid(PREFIX + BINDING + "typo: 3\n", "$.typo");
        assertInvalid(PREFIX + BINDING.replace("{field: key}", "{field: key, literal: 3}"), "exactly one");
        assertInvalid(PREFIX + BINDING.replace("{field: key}", "{field: key, args: []}"), "only functions");
        assertInvalid(PREFIX + BINDING.replace("    to:", "    when: [{field: amount, eq: 3, ne: 4}]\n    to:"),
                "exactly one");
        assertInvalid(PREFIX + BINDING.replace("    to:", "    when: [{field: amount, exists: false}]\n    to:"),
                "must be true");
        assertInvalid(PREFIX + BINDING.replace("      map:", "      rawBody: key\n      map:"), "exactly one");
    }

    @Test
    void rejectsInvalidScalarTypesAndCoercions() {
        for (String value : List.of("null", "1.2", "9223372036854775808", "[]", "{bytesHex: xyz}",
                "{bytesHex: 1234}", "{bytesHex: '00', unknown: true}")) {
            assertThatThrownBy(() -> compile(PREFIX + BINDING.replace("{field: key}", "{literal: " + value + "}")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertInvalid(PREFIX + BINDING + "limits: {maxCascadeDepth: '2'}\n", "expected int64");
        assertInvalid(PREFIX.replace("source", "123") + BINDING, "expected text");
    }

    @Test
    void rejectsUnknownCatalogInputsAndUnknownSources() {
        assertInvalid(PREFIX.replace("machine: fixture", "machine: missing") + BINDING, "unknown machine");
        assertInvalid(PREFIX + BINDING.replace("fixture.changed.v1", "missing.v1"), "unknown event");
        assertInvalid(PREFIX + BINDING.replace("{field: key}", "{field: missing}"), "unknown event field");
        assertInvalid(PREFIX + BINDING.replace("{field: key}", "{fn: random, args: [{literal: 1}]}"),
                "unknown function");
        assertInvalid(PREFIX + BINDING.replace("component: target", "component: missing"), "unknown component");
        assertInvalid(PREFIX.replace("machine: fixture}", "machine: fixture, config: {typo: true}}") + BINDING,
                "unknown component setting");
    }

    @Test
    void rejectsUnsupportedCelAndNonBooleanConditions() {
        assertInvalid(PREFIX + BINDING.replace("event.amount * 100", "event.amount / 1.5"), "expression");
        assertInvalid(PREFIX + BINDING.replace("    to:", "    when: [{expr: 'event.amount + 1'}]\n    to:"),
                "boolean");
    }

    @Test
    void rejectsAllStructuralBoundsBeforeReturningIr() {
        assertInvalid(PREFIX + BINDING + "limits: {maxCascadeDepth: 33}\n", "invalid binding limit");
        assertInvalid(PREFIX + BINDING + BINDING, "duplicate");
        assertInvalid(PREFIX.replace("id: target", "id: source") + BINDING, "duplicate component");
        assertInvalid(PREFIX.replace("id: target,", "id: target, fromHeight: 2,") + BINDING, "precede");
        assertInvalid(" ".repeat(BindingDocumentCompiler.MAX_SOURCE_CHARACTERS + 1), "source limit");
        assertInvalid("components: []\nbindings: []", "component/binding limits");
        assertInvalid(PREFIX + BINDING.replace("event.amount * 100", "x".repeat(70_000)), "source limit");
    }

    @Test
    void dotGraphPreservesBindingsAndLabelsEffectSinks() {
        BindingIrV1 ir = compile(PREFIX + BINDING + """
              - id: notify
                from: {component: target, event: fixture.changed.v1}
                when: [{field: amount, gt: 0}]
                to: {effect: {type: webhook.post, map: identity}}
            """);
        String dot = BindingGraph.dot(ir);
        assertThat(dot).startsWith("digraph bindings {\n  rankdir=LR;\n").endsWith("}\n")
                .contains("\"source\" -> \"target\"", "\"effect:notify\" [shape=box,label=\"webhook.post\"]",
                        "conditions=1", "fixture.changed.v1 → put");
        assertThat(dot.indexOf("forward\\n")).isLessThan(dot.indexOf("notify\\n"));
        assertThat(BindingGraph.dot(BindingIrV1.decode(ir.encode()))).isEqualTo(dot);
    }

    private static BindingIrV1 compile(String yaml) {
        return BindingDocumentCompiler.compile(yaml, CATALOG);
    }

    private static void assertInvalid(String yaml, String message) {
        assertThatThrownBy(() -> compile(yaml)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
