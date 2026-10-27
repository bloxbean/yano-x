package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingTopicRulesCompilerTest {
    private static final String CDDL = "root = {frames: uint}";
    private static final byte[] IR = CddlSchemaCompiler.compile(CDDL).definition();
    private static final String HASH = TopicWireCodec.blake2b256Hex(IR);
    private static final BindingDocumentCompiler.DescriptorCatalog CATALOG =
            new BindingDocumentCompiler.DescriptorCatalog() {
                @Override public ConfigurationDescriptor configuration(String machineId) {
                    return ConfigurationDescriptor.empty();
                }
                @Override public Map<String, Type> eventFields(BindingIrV1.Component component, String event) {
                    return Map.of("body", Type.BYTES);
                }
                @Override public List<CommandDescriptor> commands(BindingIrV1.Component component) {
                    return List.of(new CommandDescriptor("append", component.machineId().equals("typed")
                            ? CommandDescriptor.Layout.ARRAY : CommandDescriptor.Layout.RAW_BYTES, 0, List.of()));
                }
            };

    @Test
    void schemaCddlAndIrAuthoringProduceIdenticalCommittedPrograms() {
        var cddl = compile(document("payload.frames >= 1200 && writer.kind == \"delegated\""));
        var direct = compile(document("payload.frames >= 1200 && writer.kind == \"delegated\"")
                .replace("cddl: '" + CDDL + "'", "ir: '" + HexFormat.of().formatHex(IR) + "'"));
        assertThat(cddl.encode()).isEqualTo(direct.encode());
        assertThat(cddl.components().getFirst().payloadSchemas().get(HASH)).isEqualTo(IR);
        assertThat(cddl.rules().getFirst().isStatic()).isTrue();
        assertThat(cddl.rules().getFirst().scopes()).containsExactlyInAnyOrder(Scope.PAYLOAD, Scope.WRITER);
        assertThat(BindingIrV1.decode(cddl.encode()).encode()).isEqualTo(cddl.encode());
    }

    @Test
    void writerMembershipCardinalityAndSourceProvenanceCompileWithoutCommandSelector() {
        var ir = compile(document("writer.kind == \"none\" && size(writer.signers) == 0 && "
                + "\"ab\" in source.writer.signers && source.topic == \"games/rides\""));
        assertThat(ir.rules().getFirst().scopes()).containsExactlyInAnyOrder(Scope.WRITER, Scope.SOURCE);
        assertThat(ir.rules().getFirst().command()).isNull();
    }

    @Test
    void schemaReferencesAndAllSchemaTypesAreValidatedWithoutRegistryInputs() {
        String secondCddl = "root = {frames: tstr}";
        String second = TopicWireCodec.blake2b256Hex(CddlSchemaCompiler.compile(secondCddl).definition());
        assertThatThrownBy(() -> compile(document("payload.frames > 0")
                .replace("payloadSchemas: [" + HASH + "]", "payloadSchemas: [" + HASH + ", " + second + "]")
                .replace("components:", "  " + second + ": {cddl: '" + secondCddl + "'}\ncomponents:")))
                .hasMessageContaining("frames");
        assertThatThrownBy(() -> compile(document("payload.frames > 0").replace(HASH, "ff".repeat(32))))
                .hasMessageContaining("canonical IR hash");
        assertThatThrownBy(() -> compile(document("payload.frames > 0").replace("machine: ordered-log",
                "machine: typed"))).hasMessageContaining("opaque-body kernel");
    }

    @Test
    void sourceAndWriterEvidenceCannotBeAuthoredAndNestedPayloadIsNotExposed() {
        for (String read : List.of("writer.signature", "writer.proof", "source.writer.signature")) {
            assertThatThrownBy(() -> compile(document(read + " == b\"x\"")))
                    .isInstanceOfSatisfying(BindingAuthoringException.class,
                            failure -> assertThat(failure.code()).isEqualTo("RULE_EVIDENCE_READ"));
        }
        assertThatThrownBy(() -> compile(document("payload.frames.nested > 0")))
                .isInstanceOf(BindingAuthoringException.class);
        assertThatThrownBy(() -> compile(document("writer.key == \"x\"")))
                .isInstanceOf(BindingAuthoringException.class);
    }

    @Test
    void crossingFlagIsCommittedAndHasNoRegistryDependentValidation() {
        String doc = """
                components:
                  - {id: source, machine: ordered-log, topic: games/rides}
                  - {id: target, machine: ordered-log, topic: protected/audit}
                bindings:
                  - id: forward
                    from: {component: source, event: message.accepted.v1}
                    crossesTopicPolicy: true
                    to: {component: target, command: append, rawBody: body}
                """;
        var ir = compile(doc);
        assertThat(ir.bindings().getFirst().crossesTopicPolicy()).isTrue();
        assertThat(BindingIrV1.decode(ir.encode()).bindings().getFirst().crossesTopicPolicy()).isTrue();
        assertThat(compile(doc.replace("crossesTopicPolicy: true", "crossesTopicPolicy: false")).encode())
                .isEqualTo(compile(doc.replace("    crossesTopicPolicy: true\n", "")).encode())
                .isNotEqualTo(ir.encode());
        assertThatThrownBy(() -> compile(doc.replace("crossesTopicPolicy: true", "crossesTopicPolicy: \"yes\"")))
                .isInstanceOf(BindingAuthoringException.class);
    }

    private static BindingIrV1 compile(String document) {
        return BindingDocumentCompiler.compile(document, CATALOG);
    }

    private static String document(String expression) {
        return """
                payloadSchemas:
                  %s: {cddl: '%s'}
                components:
                  - id: rides
                    machine: ordered-log
                    topic: games/rides
                    payloadSchemas: [%s]
                    admission: [{rule: ride}]
                rules:
                  - id: ride
                    deny: RIDE_DENIED
                    require: [{expr: '%s'}]
                bindings: []
                """.formatted(HASH, CDDL, HASH, expression);
    }
}
