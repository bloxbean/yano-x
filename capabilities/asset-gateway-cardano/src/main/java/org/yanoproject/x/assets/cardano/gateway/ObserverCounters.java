package org.yanoproject.x.assets.cardano.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Node-local diagnostics of an observer (ADR-059 D15a), reported in its {@code status()} and never in an
 * observation: the blocks it was given, the highest slot among them and the wall-clock time it was last given one,
 * and counts by a bounded key (an output shape, a payout source). A block observed again after an L1 rollback counts
 * again. Observer lag is the node's L1 tip slot minus {@code lastSlot}.
 */
final class ObserverCounters {
    private final LongAdder blocks = new LongAdder();
    private final AtomicLong lastSlot = new AtomicLong(-1);
    private final AtomicLong lastObservedAtMs = new AtomicLong(-1);
    private final Map<String, LongAdder> counts = new ConcurrentHashMap<>();

    void block(long slot) {
        blocks.increment();
        lastSlot.accumulateAndGet(slot, Math::max);
        lastObservedAtMs.set(System.currentTimeMillis());
    }

    void count(String key) {
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blocks", blocks.sum());
        out.put("lastSlot", lastSlot.get());
        out.put("lastObservedAtMs", lastObservedAtMs.get());
        TreeMap<String, Long> byKey = new TreeMap<>();
        counts.forEach((key, value) -> byKey.put(key, value.sum()));
        out.put("counts", Map.copyOf(byKey));
        return out;
    }
}
