package org.yanoproject.x.stdlib;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.composite.bindings.BindingCommandView;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.3 §5.4 kernel conformance: for every stock command whose decoded fields the descriptor can represent,
 * the descriptor view of {@code codec.encode(c)} equals the codec's decoded fields and round-trips byte-exactly.
 *
 * <p>Documented representable ranges: every approvals, kv-registry and doc-trail command the codec accepts is
 * representable. A balances amount at or above 2^63 is accepted by the codec (an unbounded unsigned integer) but
 * not by the descriptor's signed 64-bit {@code amount}; its view fails closed, and a command-selecting rule on a
 * balances component refuses it with {@code ADMISSION_RULE_INPUT} before the kernel would. The authenticated map
 * (three map commands) is not command-selectable.
 */
class StockCommandViewConformanceTest {
    private static final byte[] KEY = {1, 2, 3};
    private static final byte[] VALUE = {9, 8};

    @Test
    void approvalsCommandsMatchTheCodec() {
        var kernel = StockTransitionKernels.approvals(new ApprovalsTransitions());
        for (byte[] body : List.of(ApprovalsContract.propose("item-1", VALUE, 2, 0),
                ApprovalsContract.propose("item-é", new byte[0], 1, Long.MAX_VALUE),
                ApprovalsContract.approve("item-1"), ApprovalsContract.reject("item-1"))) {
            var command = kernel.codec().decode(body);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("itemId", command.itemId());
            if (command.operation() == 0) {
                fields.put("payload", command.payload());
                fields.put("required", (long) command.required());
                fields.put("deadlineMillis", command.deadlineMillis());
            }
            conforms(kernel, body, List.of("propose", "approve", "reject").get(command.operation()), fields);
        }
    }

    @Test
    void balancesCommandsMatchTheCodecWithinTheSigned64BitRange() {
        var kernel = StockTransitionKernels.balances(new BalancesTransitions(""));
        for (byte[] body : List.of(BalancesContract.mint("alice", BigInteger.ONE),
                BalancesContract.transfer("bob", BigInteger.valueOf(Long.MAX_VALUE)))) {
            var command = kernel.codec().decode(body);
            conforms(kernel, body, command.mint() ? "mint" : "transfer",
                    Map.of("to", command.account(), "amount", command.amount().longValueExact()));
        }
        byte[] beyond = BalancesContract.transfer("bob", BigInteger.ONE.shiftLeft(63));
        assertThat(kernel.codec().decode(beyond).amount()).isEqualTo(BigInteger.ONE.shiftLeft(63));
        assertThatThrownBy(() -> BindingCommandView.decode(kernel.commands(), beyond))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void kvRegistryCommandsMatchTheCodec() {
        var kernel = StockTransitionKernels.registry(new KvRegistryTransitions(KvRegistryTransitions.ValueFormat.RAW));
        for (byte[] body : List.of(KvRegistryContract.put(KEY, VALUE), KvRegistryContract.delete(KEY))) {
            var command = kernel.codec().decode(body);
            conforms(kernel, body, command.operation() == 0 ? "put" : "delete",
                    Map.of("key", command.key(), "value", command.value()));
        }
    }

    @Test
    void docTrailCommandsMatchTheCodec() {
        var kernel = StockTransitionKernels.documentTrail(new DocTrailTransitions());
        byte[] body = DocTrailContract.append("invoice-7", new byte[32], "ref:1");
        var command = kernel.codec().decode(body);
        conforms(kernel, body, "append", Map.of("entityId", command.entityId(), "entryHash", command.entryHash(),
                "reference", command.reference()));
    }

    @Test
    void authenticatedMapIsNotCommandSelectable() {
        var collection = new AuthenticatedMapContract.CollectionDescriptor("records",
                AuthenticatedMapContract.AUTH_OPEN, "", true, 64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE,
                "");
        var genesis = new AuthenticatedMapContract.Genesis("chain", StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(), new byte[32], new byte[32], new byte[32], 16, 32768,
                List.of(collection), List.of(), List.of(), null);
        var kernel = new AuthenticatedMapTransitionKernel(new AuthenticatedMapStateMachine(genesis), "", "");
        assertThat(BindingCommandView.unselectableReason(kernel.commands()))
                .isEqualTo("several commands without distinct opcodes");
    }

    /** The view selects {@code command}, equals the codec's fields, and round-trips the codec's own encoding. */
    private static <C> void conforms(TransitionKernel<C, ?> kernel, byte[] body, String command,
                                     Map<String, Object> codecFields) {
        assertThat(BindingCommandView.selectable(kernel.commands())).isTrue();
        byte[] encoded = kernel.codec().encode(kernel.codec().decode(body));
        assertThat(encoded).as("the stock codec is canonical").containsExactly(body);
        var view = BindingCommandView.decode(kernel.commands(), encoded);
        assertThat(view.command().commandName()).isEqualTo(command);
        assertThat(view.data()).usingRecursiveComparison().isEqualTo(codecFields);
    }
}
