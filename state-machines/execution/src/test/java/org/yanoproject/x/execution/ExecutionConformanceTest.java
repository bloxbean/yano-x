package org.yanoproject.x.execution;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.runtime.appchain.StateMachineConformance;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.yanoproject.x.execution.TestGuests.b;

/**
 * Yano's own conformance harness over the adapter: deterministic across runs and a restart, and
 * the same state roots as a plain Java machine with the same semantics. Root equality shows the
 * adapter writes exactly what the guest asked for, through the real kernel path.
 */
class ExecutionConformanceTest {

    private static final Map<String, String> SETTINGS = TestGuests.settings(
            TestGuests.descriptor("counter", TestGuests.fingerprint(TestGuests.factory()), true), "counter", false);

    private static StateMachineConformance.Builder harness(AppStateMachineProvider provider) {
        return StateMachineConformance.builder(provider)
                .settings(SETTINGS)
                .blocks(40)
                .messagesPerBlock(8)
                .seed(11)
                .runs(3)
                .bodyGenerator((height, index, random) -> b("key-" + random.nextInt(6)))
                .stateProbe("key-0", b("c/key-0"));
    }

    @Test
    void executionBackedCounterMatchesJavaCounter() {
        var factory = TestGuests.factory();
        AppStateMachineProvider execution = new ExecutionStateMachineProvider(
                TestGuests.resolver(factory));
        StateMachineConformance.Result guest = harness(execution).run();
        assertTrue(guest.deterministic(), guest::describeDivergence);
        StateMachineConformance.Result java = harness(new JavaCounterProvider()).run();
        assertTrue(java.deterministic(), java::describeDivergence);
        assertEquals(java.outcomesPerRun().getFirst(), guest.outcomesPerRun().getFirst());
    }

    /** The same counter written directly against AppStateMachine. */
    static final class JavaCounterProvider implements AppStateMachineProvider {
        @Override public String id() { return "java-counter"; }

        @Override public AppStateMachine create() {
            return new AppStateMachine() {
                @Override public String id() { return "java-counter"; }

                @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
                    java.util.Map<String, Long> pending = new java.util.HashMap<>();
                    for (AppMessage message : context.messages()) {
                        if (message.getTopic().startsWith("~")) {
                            continue;
                        }
                        byte[] key = TestGuests.concat(b("c/"), message.getBody());
                        String k = FakeLedger.hex(key);
                        long n = pending.containsKey(k) ? pending.get(k) : TestGuests.be64(writer.get(key).orElse(null));
                        pending.put(k, n + 1);
                        writer.put(key, TestGuests.be64(n + 1));
                    }
                }
            };
        }
    }
}
