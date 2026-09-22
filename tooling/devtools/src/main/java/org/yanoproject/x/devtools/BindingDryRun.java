package org.yanoproject.x.devtools;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Executes one bounded fixture block through the real catalog-created composite state machine.
 *
 * <p>This is an execution rehearsal, not a node or cryptographic verifier. Source envelopes, base state/root,
 * membership, and consensus context are caller-supplied assumptions; no message signature, finality certificate,
 * state proof, or L1 anchor is authenticated. The engine's queried receipt bytes are returned unchanged. No
 * external effect is delivered, and no post-state commitment root is invented. A fresh machine must be supplied
 * for every run so operational caches cannot leak between fixtures.
 */
final class BindingDryRun {
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 16_384;
    private BindingDryRun() { }

    /**
     * Physical authenticated-state key/value pair, not a machine-local logical key.
     * Fixture inputs require values; a null output value denotes a committed deletion.
     */
    record Entry(String keyHex, String valueHex) { }

    /** Already-authenticated envelope assumptions; the command body uses the ordinary target machine's wire format. */
    record Message(String messageIdHex, String senderHex, long senderSeq, long expiresAt,
                   String topic, String bodyHex, String authProofHex) { }

    /** One block, preserving list order as the original global message indexes. */
    record Fixture(long height, long timestamp, String stateRootHex, long pendingEffects,
                   List<Entry> state, List<Message> messages) {
        Fixture {
            if (height < 1 || timestamp < 0 || pendingEffects < 0) {
                throw new IllegalArgumentException("fixture counters");
            }
            state = List.copyOf(Objects.requireNonNull(state, "state"));
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (state.size() > MAX_ENTRIES || messages.isEmpty() || messages.size() > 4096) {
                throw new IllegalArgumentException("fixture entry/message count limit");
            }
            hex(stateRootHex, 32, 32);
        }
    }

    /** JSON-ready rehearsal output, with canonical receipt bytes and decoded diagnostic fields. */
    record Result(String assurance, List<Map<String, Object>> receipts, List<Map<String, Object>> effects,
                  List<Entry> stateChanges) { }

    static Result execute(AppStateMachine machine, BindingCatalogSession.ContextInput context, Fixture fixture) {
        AppChainConsensusProfile consensus = context.consensusProfile();
        if (fixture.messages().size() > consensus.maxBlockMessages()) {
            throw new IllegalArgumentException("fixture exceeds consensus maxBlockMessages");
        }
        Memory state = new Memory(fixture);
        List<AppMessage> messages = new ArrayList<>();
        long bodyBytes = 0;
        for (Message input : fixture.messages()) {
            byte[] body = hex(input.bodyHex(), 0, consensus.maxMessageBytes());
            bodyBytes = Math.addExact(bodyBytes, body.length);
            if (bodyBytes > consensus.maxBlockBytes()) throw new IllegalArgumentException("fixture block byte limit");
            if (input.topic() == null || input.topic().isBlank() || input.topic().startsWith("~")
                    || input.topic().length() > 127 || input.senderSeq() < 0 || input.expiresAt() < 0) {
                throw new IllegalArgumentException("fixture supports ordinary, bounded source envelopes only");
            }
            messages.add(AppMessage.builder().messageId(hex(input.messageIdHex(), 32, 32))
                    .chainId(context.chainId()).topic(input.topic()).sender(hex(input.senderHex(), 32, 32))
                    .senderSeq(input.senderSeq()).expiresAt(input.expiresAt()).body(body).authScheme(0)
                    .authProof(input.authProofHex() == null ? new byte[0]
                            : hex(input.authProofHex(), 0, 4096)).build());
        }
        String member = context.membership().members().getFirst();
        machine.init(state, new AppChainInfo(context.chainId(), member, context.membership().members().size()));
        for (AppMessage message : messages) {
            var admission = machine.validateForBlock(message, fixture.height(), state);
            if (!admission.isAccepted()) {
                throw new IllegalArgumentException("fixture admission rejected: " + admission.reason());
            }
        }
        List<Map<String, Object>> effects = new ArrayList<>();
        AppEffectEmitter emitter = new AppEffectEmitter() {
            @Override public EffectId emit(EffectIntent intent) {
                if (!consensus.effectsEnabled() || effects.size() >= consensus.effectsMaxPerBlock()
                        || intent.payload().length > consensus.effectsMaxPayloadBytes()) {
                    throw new IllegalArgumentException("fixture effect exceeds consensus limits");
                }
                EffectId id = new EffectId(context.chainId(), fixture.height(), effects.size());
                effects.add(Map.of("effectId", id.canonical(), "type", intent.type(),
                        "payloadHex", HexFormat.of().formatHex(intent.payload()), "gate", intent.gate().name(),
                        "result", intent.result().name(), "scope", intent.scope(),
                        "expiryBlocks", intent.expiryBlocks()));
                return id;
            }
            @Override public long pendingCount() { return fixture.pendingEffects(); }
        };
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, context.chainId(), fixture.height(),
                new byte[32], 0, new byte[0], fixture.timestamp(), new byte[32], new byte[32], messages,
                hex(member, 32, 32), FinalityCert.empty());
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), state, emitter);
        List<Map<String, Object>> receipts = new ArrayList<>();
        AppQueryContext query = new AppQueryContext() {
            @Override public Optional<byte[]> get(byte[] key) { return state.get(key); }
            @Override public byte[] stateRoot() {
                throw new UnsupportedOperationException("dry-run does not calculate a post-state commitment root");
            }
            @Override public long committedHeight() { return fixture.height(); }
        };
        for (AppMessage message : messages) {
            byte[] encoded = machine.query("composite/binding-receipt-v1/"
                    + HexFormat.of().formatHex(message.getMessageId()), new byte[0], query);
            BindingReceiptV1.decode(encoded); // Refuse to present malformed plugin output as a binding receipt.
            receipts.add(Map.of("messageIdHex", HexFormat.of().formatHex(message.getMessageId()),
                    "receiptHex", HexFormat.of().formatHex(encoded),
                    "receipt", jsonValue(BindingCbor.decode(encoded, 65536))));
        }
        return new Result("execution-only; fixture inputs are not authenticated; no post-state root or finality claim",
                List.copyOf(receipts), List.copyOf(effects), state.changes());
    }

    private static Object jsonValue(Object value) {
        if (value instanceof byte[] bytes) return Map.of("bytesHex", HexFormat.of().formatHex(bytes));
        if (value instanceof List<?> list) return list.stream().map(BindingDryRun::jsonValue).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, child) -> result.put(key.toString(), jsonValue(child)));
            return result;
        }
        return value;
    }

    private static byte[] hex(String value, int minimum, int maximum) {
        if (value == null || value.length() < minimum * 2 || value.length() > maximum * 2) {
            throw new IllegalArgumentException("fixture hex byte length");
        }
        return HexFormat.of().parseHex(value);
    }

    /** Read-your-writes fixture adapter with defensive copying and fixed memory quotas. */
    private static final class Memory implements AppStateWriter {
        private final Map<String, byte[]> values = new TreeMap<>();
        private final Map<String, String> changed = new TreeMap<>();
        private final Fixture fixture;
        private int bytes;

        Memory(Fixture fixture) {
            this.fixture = fixture;
            for (Entry entry : fixture.state()) {
                byte[] key = hex(entry.keyHex(), 1, 4096);
                if (values.containsKey(HexFormat.of().formatHex(key))) {
                    throw new IllegalArgumentException("duplicate fixture state key");
                }
                put(key, hex(entry.valueHex(), 0, 1_048_576));
            }
            changed.clear();
        }

        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(HexFormat.of().formatHex(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public byte[] stateRoot() { return hex(fixture.stateRootHex(), 32, 32); }
        @Override public long committedHeight() { return fixture.height() - 1; }
        @Override public void put(byte[] key, byte[] value) {
            if (key.length == 0 || key.length > 4096 || value.length > 1_048_576) {
                throw new IllegalArgumentException("fixture state value/key limit");
            }
            String encoded = HexFormat.of().formatHex(key);
            byte[] previous = values.get(encoded);
            long size = (long) bytes + key.length + value.length
                    - (previous == null ? 0 : key.length + previous.length);
            if (size > MAX_STATE_BYTES || previous == null && values.size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException("fixture state capacity exceeded");
            }
            bytes = (int) size;
            values.put(encoded, value.clone());
            changed.put(encoded, HexFormat.of().formatHex(value));
            if (changed.size() > MAX_ENTRIES) throw new IllegalArgumentException("fixture mutation capacity exceeded");
        }
        @Override public void delete(byte[] key) {
            String encoded = HexFormat.of().formatHex(key);
            byte[] previous = values.remove(encoded);
            if (previous != null) bytes -= key.length + previous.length;
            changed.put(encoded, null);
            if (changed.size() > MAX_ENTRIES) throw new IllegalArgumentException("fixture mutation capacity exceeded");
        }
        List<Entry> changes() {
            return changed.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).toList();
        }
    }
}
