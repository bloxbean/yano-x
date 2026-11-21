package org.yanoproject.x.execution;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.execution.abi.v1.Hashes;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.effects.EffectId;
import org.yanoproject.x.framework.api.appchain.effects.EffectIntent;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A minimal committed store, writer and emitter. Writes become visible only on {@link #commit()}, as in Yano. */
final class FakeLedger {

    final TreeMap<String, byte[]> committed = new TreeMap<>();
    private final TreeMap<String, byte[]> staged = new TreeMap<>();
    final List<EffectIntent> emitted = new ArrayList<>();
    long height;

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    AppStateWriter writer() {
        return new AppStateWriter() {
            @Override public void put(byte[] key, byte[] value) { staged.put(hex(key), value.clone()); }
            @Override public void delete(byte[] key) { staged.put(hex(key), null); }
            @Override public Optional<byte[]> get(byte[] key) { return Optional.ofNullable(committed.get(hex(key))).map(byte[]::clone); }
            @Override public byte[] stateRoot() { return new byte[32]; }
        };
    }

    AppEffectEmitter emitter(String chainId) {
        return new AppEffectEmitter() {
            @Override public EffectId emit(EffectIntent intent) {
                emitted.add(intent);
                return new EffectId(chainId, height, emitted.size() - 1);
            }
            @Override public long pendingCount() { return 0; }
        };
    }

    AppQueryContext query() {
        return new AppQueryContext() {
            @Override public long committedHeight() { return height; }
            @Override public Optional<byte[]> get(byte[] key) { return Optional.ofNullable(committed.get(hex(key))).map(byte[]::clone); }
            @Override public byte[] stateRoot() { return new byte[32]; }
        };
    }

    void commit() {
        staged.forEach((k, v) -> {
            if (v == null) {
                committed.remove(k);
            } else {
                committed.put(k, v);
            }
        });
        staged.clear();
    }

    Map<String, byte[]> committed() {
        return committed;
    }

    static AppMessage message(String chainId, String topic, long seq, byte[] body) {
        byte[] id = Hashes.blake2b256(ByteBuffer.allocate(8 + body.length).putLong(seq).put(body).array());
        return AppMessage.builder().version(1).messageId(id).chainId(chainId).topic(topic).sender(new byte[32])
                .senderSeq(seq).expiresAt(0).body(body).authScheme(0).authProof(new byte[0]).build();
    }

    static AppBlockExecutionContext block(String chainId, long height, List<AppMessage> messages) {
        return AppBlockExecutionContext.fromValidatedBlock(new AppBlock(AppBlock.BLOCK_VERSION, chainId, height,
                new byte[32], 100 + height, new byte[32], 1_700_000_000_000L + height, new byte[32], new byte[32],
                messages, new byte[32], null));
    }
}
