package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evidence declarations constrain mappings even when their computed bytes could look like a valid signature. */
class BindingEvidenceMappingTest {
    @Test
    void evidenceTypeMismatchIdentifiesTheEvidenceFieldAfterAValidDataAssignment() {
        assertThatThrownBy(() -> program(new BindingSourceV1.Field("topic"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("binding 'authorize' (source/composite.command-accepted.v1): "
                        + "target field 'signature' (evidence): binding type mismatch");
    }

    @Test
    void literalFunctionExpressionAndMissingEvidenceFailConstructionIncludingOptionalEvidence() {
        List<BindingSourceV1> manufactured = List.of(new BindingSourceV1.Literal(new byte[32]),
                new BindingSourceV1.Function("sha-256", List.of(new BindingSourceV1.Field("body"))),
                new BindingSourceV1.Expression(new BindingExpressionV1(BindingExpressionV1.Type.BYTES,
                        new BindingExpressionV1.Field("body"))));
        for (boolean required : List.of(true, false)) {
            for (BindingSourceV1 source : manufactured) {
                assertThatThrownBy(() -> program(source, required))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("binding 'authorize' (source/composite.command-accepted.v1): "
                                + "target field 'signature' (evidence): BINDING_EVIDENCE_UNSATISFIABLE");
            }
            assertThatThrownBy(() -> program(null, required))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("binding 'authorize' (source/composite.command-accepted.v1): "
                            + "target field 'signature' (evidence): BINDING_EVIDENCE_UNSATISFIABLE");
            assertThatCode(() -> program(new BindingSourceV1.Field("body"), required))
                    .doesNotThrowAnyException();
        }
    }

    private static BindingProgram program(BindingSourceV1 evidence, boolean required) {
        var assignments = new ArrayList<BindingIrV1.Assignment>();
        assignments.add(new BindingIrV1.Assignment("payload", new BindingSourceV1.Field("body")));
        if (evidence != null) assignments.add(new BindingIrV1.Assignment("signature", evidence));
        var binding = new BindingIrV1.Binding("authorize", "source", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("target", "signed", BindingIrV1.Mapping.fields(assignments)));
        var ir = new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "ordered-log", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "signed-target", "target.v1", Map.of(), 0)),
                List.of(binding), BindingIrV1.Limits.DEFAULT);
        TransitionKernel<byte[], Boolean> target = new TransitionKernel<>() {
            @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
            @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                throw new AssertionError("profile validation must not execute a target");
            }
            @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                throw new AssertionError("profile validation must not authorize a target");
            }
            @Override public List<CommandDescriptor> commands() {
                return List.of(new CommandDescriptor("signed", CommandDescriptor.Layout.MAP, 0, List.of(
                        new CommandDescriptor.Field("payload", TransitionScalars.Type.BYTES, true,
                                CommandDescriptor.Role.DATA),
                        new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, required,
                                CommandDescriptor.Role.EVIDENCE))));
            }
            @Override public List<EventDescriptor> events() { return List.of(); }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
        };
        return new BindingProgram(ir, Map.of("source", new OrderedLogKernel(), "target", target));
    }
}
