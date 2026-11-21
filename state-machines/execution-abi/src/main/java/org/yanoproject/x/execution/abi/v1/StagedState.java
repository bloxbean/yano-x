package org.yanoproject.x.execution.abi.v1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An ordered overlay of staged writes over a base view, with read-your-writes. The adapter keeps one
 * per block (Yano's {@code AppStateWriter} only shows committed state), and each host session keeps
 * one per invocation on top of it.
 */
public final class StagedState implements StateView {

    private static final byte[] TOMBSTONE = new byte[0];

    private final StateView base;
    private final Map<ByteKey, byte[]> staged = new LinkedHashMap<>();

    public StagedState(StateView base) {
        this.base = base;
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        byte[] value = staged.get(new ByteKey(key));
        if (value == TOMBSTONE) {
            return Optional.empty();
        }
        if (value != null) {
            return Optional.of(value.clone());
        }
        return base.get(key.clone());
    }

    public void put(byte[] key, byte[] value) {
        staged.put(new ByteKey(key.clone()), value.clone());
    }

    public void delete(byte[] key) {
        staged.put(new ByteKey(key.clone()), TOMBSTONE);
    }

    public void apply(List<Write> writes) {
        for (Write write : writes) {
            if (write.isDelete()) {
                delete(write.key());
            } else {
                put(write.key(), write.value());
            }
        }
    }

    public boolean touches(byte[] key) {
        return staged.containsKey(new ByteKey(key));
    }

    public int size() {
        return staged.size();
    }

    /** Final value per key, in first-write order. */
    public List<Write> writes() {
        List<Write> out = new ArrayList<>(staged.size());
        staged.forEach((k, v) -> out.add(new Write(k.bytes(), v == TOMBSTONE ? null : v)));
        return List.copyOf(out);
    }
}
