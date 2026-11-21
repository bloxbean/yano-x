package org.yanoproject.x.execution;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.execution.abi.v1.Receipt;
import org.yanoproject.x.execution.tck.reference.ReferenceRuntimeFactory;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectId;
import org.yanoproject.x.framework.api.appchain.effects.EffectOutcome;
import org.yanoproject.x.framework.api.appchain.effects.EffectResult;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.yanoproject.x.execution.FakeLedger.hex;
import static org.yanoproject.x.execution.TestGuests.b;

class ExecutionBackedStateMachineTest {

    private static final String CHAIN = "adapter-test";
    private static final ReferenceRuntimeFactory FACTORY = TestGuests.factory();
    private static final byte[] FINGERPRINT = TestGuests.fingerprint(FACTORY);

    private static AppStateMachine machine(String program, boolean receipts, boolean effects) {
        return machine(TestGuests.settings(TestGuests.descriptor(program, FINGERPRINT, receipts), program, effects));
    }

    private static AppStateMachine machine(Map<String, String> settings) {
        return new ExecutionStateMachineProvider(TestGuests.resolver(FACTORY))
                .create(new AppStateMachineContext() {
                    @Override public String chainId() { return CHAIN; }
                    @Override public Map<String, String> settings() { return settings; }
                });
    }

    private static void apply(AppStateMachine machine, FakeLedger ledger, List<AppMessage> messages) {
        ledger.height++;
        machine.apply(FakeLedger.block(CHAIN, ledger.height, messages), ledger.writer(), ledger.emitter(CHAIN));
        ledger.commit();
    }

    @Test
    void failedMessagesAreIsolatedAndLeaveReceipts() {
        AppStateMachine machine = machine("mixed", true, false);
        FakeLedger ledger = new FakeLedger();
        List<AppMessage> messages = List.of(
                FakeLedger.message(CHAIN, "t", 1, b("plain-1")),
                FakeLedger.message(CHAIN, "t", 2, b("reject")),
                FakeLedger.message(CHAIN, "t", 3, b("trap")),
                FakeLedger.message(CHAIN, "t", 4, b("spin")),
                FakeLedger.message(CHAIN, "t", 5, b("flood")),
                FakeLedger.message(CHAIN, "t", 6, b("output")),
                FakeLedger.message(CHAIN, "t", 7, b("~reserved")),
                FakeLedger.message(CHAIN, "t", 8, b("effect")),   // effects disabled on this chain
                FakeLedger.message(CHAIN, "~fx/result", 9, b("framework")),
                FakeLedger.message(CHAIN, "t", 10, b("plain-2")));
        apply(machine, ledger, messages);

        Map<String, byte[]> state = ledger.committed();
        assertTrue(state.containsKey(hex(b("w/plain-1"))));
        assertTrue(state.containsKey(hex(b("w/plain-2"))));
        for (String body : List.of("reject", "trap", "spin", "flood", "output", "~reserved", "effect", "framework")) {
            assertFalse(state.containsKey(hex(b("w/" + body))), body + " must not leave writes");
        }
        assertReceipt(state, messages.get(1), Receipt.Kind.REJECTED, "no-thanks");
        assertReceipt(state, messages.get(2), Receipt.Kind.TRAPPED, "guest-error");
        assertReceipt(state, messages.get(3), Receipt.Kind.OUT_OF_COMPUTE, "compute");
        assertReceipt(state, messages.get(4), Receipt.Kind.OUT_OF_HOST_FUEL, "host-fuel");
        assertReceipt(state, messages.get(5), Receipt.Kind.ABI_VIOLATION, "bad-output");
        assertReceipt(state, messages.get(6), Receipt.Kind.ABI_VIOLATION, "reserved-key");
        assertReceipt(state, messages.get(7), Receipt.Kind.ABI_VIOLATION, "effects-disabled");
        assertFalse(state.containsKey(hex(Receipt.messageKey(messages.get(8).getMessageId()))),
                "framework topics never reach the guest");
        assertEquals(2 + 7, state.size(), "two writes and seven receipts");
        @SuppressWarnings("unchecked")
        Map<String, Long> discarded = (Map<String, Long>) machine.operationalStatus().get("discardedTransitions");
        assertEquals(3L, discarded.get("ABI_VIOLATION"));
    }

    private static void assertReceipt(Map<String, byte[]> state, AppMessage message, Receipt.Kind kind, String code) {
        byte[] encoded = state.get(hex(Receipt.messageKey(message.getMessageId())));
        assertTrue(encoded != null, "receipt for " + new String(message.getBody()));
        assertEquals(new Receipt(1, kind, code), Receipt.decode(encoded));
    }

    @Test
    void receiptsCanBeTurnedOff() {
        AppStateMachine machine = machine("mixed", false, false);
        FakeLedger ledger = new FakeLedger();
        apply(machine, ledger, List.of(FakeLedger.message(CHAIN, "t", 1, b("reject")),
                FakeLedger.message(CHAIN, "t", 2, b("plain"))));
        assertEquals(List.of(hex(b("w/plain"))), List.copyOf(ledger.committed().keySet()));
    }

    @Test
    void laterMessagesSeeEarlierWritesOfTheSameBlock() {
        AppStateMachine machine = machine("counter", true, false);
        FakeLedger ledger = new FakeLedger();
        apply(machine, ledger, List.of(FakeLedger.message(CHAIN, "t", 1, b("a")),
                FakeLedger.message(CHAIN, "t", 2, b("a")), FakeLedger.message(CHAIN, "t", 3, b("a"))));
        apply(machine, ledger, List.of(FakeLedger.message(CHAIN, "t", 4, b("a"))));
        assertArrayEquals(TestGuests.be64(4), ledger.committed().get(hex(b("c/a"))));
    }

    /** Guest ABI section 2: one instance per block, replaced after every transition that is not Ok. */
    @Test
    void transitionsOfABlockShareOneInstanceUntilOneIsNotOk() {
        AppStateMachine machine = machine("static-state", true, false);
        FakeLedger ledger = new FakeLedger();
        long[] seq = {0};
        java.util.function.Function<byte[], AppMessage> msg = body -> FakeLedger.message(CHAIN, "t", ++seq[0], body);
        byte[] plain = b("a");
        byte[] reject = {0};
        byte[] trap = {1};

        apply(machine, ledger, List.of(msg.apply(plain), msg.apply(plain)));
        assertEquals(2, g(ledger), "committed transitions of a block share guest memory");
        apply(machine, ledger, List.of(msg.apply(plain), msg.apply(reject), msg.apply(plain)));
        assertEquals(1, g(ledger), "a rejected transition replaces the instance");
        apply(machine, ledger, List.of(msg.apply(plain)));
        assertEquals(1, g(ledger), "every block starts with a fresh instance");
        apply(machine, ledger, List.of(msg.apply(plain), msg.apply(trap), msg.apply(plain), msg.apply(plain)));
        assertEquals(2, g(ledger), "a trapped transition replaces the instance");

        ledger.height++;
        var context = FakeLedger.block(CHAIN, ledger.height, List.of(msg.apply(plain)));
        machine.onEffectResult(context, new EffectResult(new EffectId(CHAIN, 1, 0), "demo.pay", "s",
                EffectOutcome.CONFIRMED, b("tx"), null, ledger.height), ledger.writer(), ledger.emitter(CHAIN));
        machine.apply(context, ledger.writer(), ledger.emitter(CHAIN));
        ledger.commit();
        assertEquals(2, g(ledger), "effect results and messages of a block share the instance");

        assertEquals(7L, machine.operationalStatus().get("instances"));
        assertEquals(12L, machine.operationalStatus().get("invocations"));
    }

    private static long g(FakeLedger ledger) {
        return TestGuests.be64(ledger.committed().get(hex(b("g"))));
    }

    /**
     * ADR-058 Q4 and Q7: execution-backed machines are standalone in v1. Yano X's declarative
     * composite only accepts components with a transition kernel, so offering none keeps this
     * machine out of composite chains, whose shared per-block effect budget the adapter cannot see.
     */
    @Test
    void offersNoTransitionKernelSoCompositesRefuseIt() {
        assertTrue(machine("counter", true, false).transitionKernel().isEmpty());
    }

    @Test
    void effectsCarryTheSourceMessageAndResultsComeBack() {
        AppStateMachine machine = machine("mixed", true, true);
        FakeLedger ledger = new FakeLedger();
        AppMessage pay = FakeLedger.message(CHAIN, "t", 1, b("effect"));
        apply(machine, ledger, List.of(pay));
        assertEquals(1, ledger.emitted.size());
        var intent = ledger.emitted.getFirst();
        assertEquals("demo.pay", intent.type());
        assertEquals("s-effect", intent.scope());
        assertEquals(ResultPolicy.CHAIN, intent.result());
        assertEquals(5, intent.expiryBlocks());
        assertArrayEquals(pay.getMessageId(), intent.sourceMessageId());

        ledger.height++;
        var context = FakeLedger.block(CHAIN, ledger.height, List.of());
        machine.onEffectResult(context, new EffectResult(new EffectId(CHAIN, 1, 0), "demo.pay", "s-effect",
                EffectOutcome.CONFIRMED, b("tx"), null, ledger.height), ledger.writer(), ledger.emitter(CHAIN));
        EffectId trapId = new EffectId(CHAIN, 1, 1);
        machine.onEffectResult(context, new EffectResult(trapId, "demo.pay", "trap-1",
                EffectOutcome.FAILED, b("tx"), null, ledger.height), ledger.writer(), ledger.emitter(CHAIN));
        machine.apply(context, ledger.writer(), ledger.emitter(CHAIN));
        ledger.commit();
        assertArrayEquals(new byte[] {1}, ledger.committed().get(hex(b("fx/s-effect"))));
        assertEquals(new Receipt(ledger.height, Receipt.Kind.TRAPPED, "guest-error"),
                Receipt.decode(ledger.committed().get(hex(Receipt.effectKey(trapId.hash())))));
    }

    @Test
    void admissionAndQueryMapToYanoContracts() {
        AppStateMachine machine = machine("counter", true, false);
        FakeLedger ledger = new FakeLedger();
        apply(machine, ledger, List.of(FakeLedger.message(CHAIN, "t", 1, b("a"))));
        var reader = ledger.writer();
        assertTrue(machine.validateForBlock(FakeLedger.message(CHAIN, "t", 2, b("a")), 2, reader).isAccepted());
        var rejected = machine.validateForBlock(FakeLedger.message(CHAIN, "t", 3, new byte[0]), 2, reader);
        assertFalse(rejected.isAccepted());
        assertEquals("empty-body", rejected.reason());

        assertArrayEquals(TestGuests.be64(1), machine.query("count", b("a"), ledger.query()));
        assertEquals(AppQueryException.Code.UNSUPPORTED,
                assertThrows(AppQueryException.class, () -> machine.query("nope", new byte[0], ledger.query())).code());
        assertEquals(AppQueryException.Code.INVALID_REQUEST,
                assertThrows(AppQueryException.class, () -> machine.query("bad", new byte[0], ledger.query())).code());
    }

    @Test
    void refusesAnEngineOtherThanThePinnedOne() {
        byte[] otherEngine = FINGERPRINT.clone();
        otherEngine[0] ^= 1;
        ExecutionDescriptor descriptor = TestGuests.descriptor("counter", otherEngine, true);
        var e = assertThrows(IllegalStateException.class, () -> machine(TestGuests.settings(descriptor, "counter", false)));
        assertTrue(e.getMessage().contains("engine fingerprint"), e.getMessage());
    }

    @Test
    void refusesProgramBytesThatDoNotMatchTheDigest() {
        Map<String, String> settings = new HashMap<>(TestGuests.settings(TestGuests.descriptor("counter", FINGERPRINT, true),
                "counter", false));
        settings.put(ExecutionSettings.PREFIX + "program-hex", hex(b("mixed")));
        var e = assertThrows(IllegalStateException.class, () -> machine(settings));
        assertTrue(e.getMessage().contains("digest"), e.getMessage());
    }

    @Test
    void refusesAnUninstalledRuntime() {
        ExecutionDescriptor pinned = TestGuests.descriptor("counter", FINGERPRINT, true);
        ExecutionDescriptor other = new ExecutionDescriptor("chicory", pinned.engineFingerprint(), "application/wasm",
                pinned.programDigest(), 1, pinned.budgets(), 0, new byte[0], true);
        var e = assertThrows(IllegalStateException.class, () -> machine(TestGuests.settings(other, "counter", false)));
        assertTrue(e.getMessage().contains("not installed"), e.getMessage());
    }

    @Test
    void retriedBlockStartsFromCommittedState() {
        AppStateMachine machine = machine("counter", true, false);
        var context = FakeLedger.block(CHAIN, 1, List.of(FakeLedger.message(CHAIN, "t", 1, b("a")),
                FakeLedger.message(CHAIN, "t", 2, b("a"))));
        // First attempt: the first message is staged, then the store fails on the second write.
        FakeLedger broken = new FakeLedger();
        var failing = new org.yanoproject.x.framework.api.appchain.AppStateWriter() {
            int puts;
            @Override public void put(byte[] key, byte[] value) {
                if (++puts == 2) {
                    throw new IllegalStateException("disk full");
                }
            }
            @Override public void delete(byte[] key) { }
            @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
            @Override public byte[] stateRoot() { return new byte[32]; }
        };
        assertThrows(IllegalStateException.class, () -> machine.apply(context, failing, broken.emitter(CHAIN)));
        // Retry: the staged write of the failed attempt must not be read back.
        FakeLedger retry = new FakeLedger();
        retry.height = 1;
        machine.apply(context, retry.writer(), retry.emitter(CHAIN));
        retry.commit();
        assertArrayEquals(TestGuests.be64(2), retry.committed().get(hex(b("c/a"))));
    }
}
