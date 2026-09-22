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
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingProgramTest {
    @Test
    void rawMappingCannotSelectEvidenceOpcodeThroughAnUnprotectedDescriptor() {
        var source = kernel(List.of());
        var target = kernel(List.of(
                new CommandDescriptor("read", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0, List.of()),
                new CommandDescriptor("approve", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(
                        new CommandDescriptor.Field("signature", TransitionScalars.Type.BYTES, true,
                                CommandDescriptor.Role.EVIDENCE)))));

        assertThatThrownBy(() -> new BindingProgram(document("source", "read"),
                Map.of("source", source, "target", target)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BINDING_EVIDENCE_UNSATISFIABLE");
    }

    @Test
    void baselineDoesNotMakeAnUnknownSourceComponentValid() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        assertThatThrownBy(() -> new BindingProgram(document("missing", "append"),
                Map.of("source", kernel(List.of()), "target", target)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown component: missing");
    }

    @Test
    void rawMappingRemainsAvailableForEvidenceFreeKernels() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        assertThatCode(() -> new BindingProgram(document("source", "append"),
                Map.of("source", kernel(List.of()), "target", target))).doesNotThrowAnyException();
    }

    @Test
    void participantReadsRejectUnknownSelfAndDuplicateIds() {
        var target = kernel(List.of(new CommandDescriptor("append", CommandDescriptor.Layout.RAW_BYTES, 0, List.of())));
        for (List<String> reads : List.of(List.of("missing"), List.of("source"), List.of("target", "target"))) {
            assertThatThrownBy(() -> new BindingProgram(document("source", "append"),
                    Map.of("source", kernel(List.of(), reads), "target", target)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid kernel read participants");
        }
        var program = new BindingProgram(document("source", "append"),
                Map.of("source", kernel(List.of(), List.of("target")), "target", target));
        assertThat(program.readParticipants("source")).containsExactly("target");
    }

    private static BindingIrV1 document(String source, String command) {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("source", "test", "source.v1", Map.of(), 0),
                new BindingIrV1.Component("target", "test", "target.v1", Map.of(), 0)),
                List.of(new BindingIrV1.Binding("forward", source, BindingProgram.BASELINE, List.of(),
                        new BindingIrV1.CommandTarget("target", command, BindingIrV1.Mapping.raw("body")))),
                BindingIrV1.Limits.DEFAULT);
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands) {
        return kernel(commands, List.of());
    }

    private static TransitionKernel<?, ?> kernel(List<CommandDescriptor> commands, List<String> reads) {
        return new TransitionKernel<byte[], Boolean>() {
            @Override public List<String> readParticipants() { return reads; }
            @Override public MessageCodec<byte[]> codec() { return new OrderedLogKernel().codec(); }
            @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
                throw new UnsupportedOperationException("schema-only fixture");
            }
            @Override public List<CommandDescriptor> commands() { return commands; }
            @Override public List<EventDescriptor> events() { return List.of(); }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
        };
    }
}
