package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;

import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A write buffer over a parent store that reads its own writes. A command or observation runs
 * against one of these and is committed to the parent only when it completes, so a rejection or
 * an unexpected failure leaves no partial writes. Writes are kept in key order, so a commit is
 * deterministic.
 */
final class StagedStore implements AssetAccounts.Store {
    private final AssetAccounts.Store parent;
    private final TreeMap<String, Optional<byte[]>> writes = new TreeMap<>();

    StagedStore(AssetAccounts.Store parent) {
        this.parent = parent;
    }

    static AssetAccounts.Store over(AppStateWriter writer) {
        return new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return writer.get(key); }
            @Override public void put(byte[] key, byte[] value) { writer.put(key, value); }
            @Override public void delete(byte[] key) { writer.delete(key); }
        };
    }

    /** A read-only store over committed state; any write is a programming error. */
    static AssetAccounts.Store readOnly(AppStateReader reader) {
        return new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return reader.get(key); }
            @Override public void put(byte[] key, byte[] value) { throw new IllegalStateException("read-only"); }
            @Override public void delete(byte[] key) { throw new IllegalStateException("read-only"); }
        };
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        Optional<byte[]> staged = writes.get(HexFormat.of().formatHex(key));
        if (staged != null) return staged.map(byte[]::clone);
        return parent.get(key);
    }

    @Override
    public void put(byte[] key, byte[] value) {
        writes.put(HexFormat.of().formatHex(key), Optional.of(value.clone()));
    }

    @Override
    public void delete(byte[] key) {
        writes.put(HexFormat.of().formatHex(key), Optional.empty());
    }

    void commit() {
        for (Map.Entry<String, Optional<byte[]>> write : writes.entrySet()) {
            byte[] key = HexFormat.of().parseHex(write.getKey());
            if (write.getValue().isPresent()) {
                parent.put(key, write.getValue().get());
            } else {
                parent.delete(key);
            }
        }
        writes.clear();
    }
}
