package com.bloxbean.cardano.yano.appchain.trust.profile;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pure fold behind status lists (ADR-049 §2.3): applied {@code status} mutations, in
 * finalization order, become one bit per credential index. {@code PUT}, {@code PUT_IF_ABSENT},
 * and {@code COMPARE_AND_SET} take the bit from the value; {@code REVOKE} of an index is
 * terminal and sets the bit. Mutations of other collections are ignored. The caller (the
 * client's replay, or {@code publish-list}) confirms each mutation through its receipt
 * before applying it.
 */
public final class StatusProjection {
    public static final int MAX_MUTATIONS = 5_000;

    private final Map<String, TreeMap<Long, Boolean>> lists = new TreeMap<>();
    private final Map<String, Long> lastMutationHeights = new TreeMap<>();
    private long replayedHeight;
    private int mutationCount;

    /** Applies one applied mutation finalized at {@code height}; returns false if it was not a status write. */
    public boolean apply(long height, AuthenticatedMapContract.Mutation mutation) {
        if (!TrustRegistryProfile.STATUS.equals(mutation.collectionId())) {
            return false;
        }
        if (mutationCount >= MAX_MUTATIONS) {
            throw new IllegalStateException(
                    "status projection exceeds " + MAX_MUTATIONS + " mutations");
        }
        TrustRegistryProfile.StatusKey key = TrustRegistryProfile.parseStatusKey(
                mutation.applicationKey());
        boolean bit = switch (mutation.operation()) {
            case AuthenticatedMapContract.OP_PUT, AuthenticatedMapContract.OP_PUT_IF_ABSENT,
                 AuthenticatedMapContract.OP_COMPARE_AND_SET,
                 AuthenticatedMapContract.OP_RESTORE ->
                    TrustRegistryValues.StatusValue.decode(mutation.value()).set();
            case AuthenticatedMapContract.OP_REVOKE -> true;
            case AuthenticatedMapContract.OP_TRANSFER_CONTROLLER -> {
                yield current(key);
            }
            default -> throw new IllegalArgumentException("unsupported status mutation");
        };
        lists.computeIfAbsent(key.listId(), ignored -> new TreeMap<>()).put(key.index(), bit);
        lastMutationHeights.merge(key.listId(), height, Math::max);
        mutationCount++;
        return true;
    }

    public void markReplayed(long height) {
        if (height < replayedHeight) {
            throw new IllegalArgumentException("replay height must not move backwards");
        }
        replayedHeight = height;
    }

    public long replayedHeight() {
        return replayedHeight;
    }

    public int mutationCount() {
        return mutationCount;
    }

    public Set<String> listIds() {
        return Collections.unmodifiableSet(lists.keySet());
    }

    public Map<Long, Boolean> bits(String listId) {
        TreeMap<Long, Boolean> bits = lists.get(listId);
        return bits == null ? Map.of() : Collections.unmodifiableMap(bits);
    }

    /** Height of the last applied status write for a list, or 0 when none was applied. */
    public long lastMutationHeight(String listId) {
        return lastMutationHeights.getOrDefault(listId, 0L);
    }

    public StatusBitstring bitstring(String listId, long bitLength) {
        StatusBitstring bitstring = new StatusBitstring(bitLength);
        bits(listId).forEach((index, bit) -> {
            if (index < bitLength) {
                bitstring.set(index, bit);
            }
        });
        return bitstring;
    }

    private boolean current(TrustRegistryProfile.StatusKey key) {
        TreeMap<Long, Boolean> bits = lists.get(key.listId());
        return bits != null && bits.getOrDefault(key.index(), false);
    }
}
