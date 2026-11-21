package org.yanoproject.x.execution.abi.v1;

import java.util.Map;
import java.util.Objects;

/** The finalized block a transition runs in. Every field is consensus data from the app block. */
public record BlockInfo(String chainId, long height, long timestampMillis, long l1Slot,
                        byte[] l1BlockHash, byte[] prevHash, byte[] proposer) {

    public BlockInfo {
        Objects.requireNonNull(chainId, "chainId");
        l1BlockHash = l1BlockHash.clone();
        prevHash = prevHash.clone();
        proposer = proposer.clone();
        if (height < 1 || timestampMillis < 0 || l1Slot < 0) {
            throw new IllegalArgumentException("invalid block info");
        }
    }

    Map<Long, Object> toCbor() {
        return Map.of(0L, chainId, 1L, height, 2L, timestampMillis, 3L, l1Slot,
                4L, l1BlockHash.clone(), 5L, prevHash.clone(), 6L, proposer.clone());
    }

    static BlockInfo fromCbor(Object value) {
        Fields f = Fields.of("block-info", value, Fields.keys(0, 1, 2, 3, 4, 5, 6));
        long height = f.uint(1);
        if (height < 1) {
            throw Fields.schema("block-info", "height must be >= 1");
        }
        return new BlockInfo(f.text(0), height, f.uint(2), f.uint(3), f.bytes(4), f.bytes(5), f.bytes(6));
    }

    @Override public byte[] l1BlockHash() { return l1BlockHash.clone(); }
    @Override public byte[] prevHash() { return prevHash.clone(); }
    @Override public byte[] proposer() { return proposer.clone(); }

    @Override public boolean equals(Object o) {
        return o instanceof BlockInfo b && chainId.equals(b.chainId) && height == b.height
                && timestampMillis == b.timestampMillis && l1Slot == b.l1Slot
                && java.util.Arrays.equals(l1BlockHash, b.l1BlockHash) && java.util.Arrays.equals(prevHash, b.prevHash)
                && java.util.Arrays.equals(proposer, b.proposer);
    }

    @Override public int hashCode() {
        return Objects.hash(chainId, height, timestampMillis);
    }
}
