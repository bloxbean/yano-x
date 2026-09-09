package com.bloxbean.cardano.yano.appchain.feed.client;

import com.bloxbean.cardano.yano.appchain.feed.profile.FeedStarterProfile;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The feeds and closed rounds seen in finalized, receipt-confirmed map commands (ADR-052 §2.2).
 * A bounded cache for discovery only: every key it names is answered against the chain's root
 * with a proof before it is shown, and a round's bundle never depends on it.
 */
public final class FeedProjection {
    private final Set<String> feedIds = new LinkedHashSet<>();
    private final Map<String, TreeSet<Long>> rounds = new TreeMap<>();
    private long replayedHeight;
    private int commandCount;

    public synchronized long replayedHeight() {
        return replayedHeight;
    }

    public synchronized int commandCount() {
        return commandCount;
    }

    synchronized void countCommand() {
        commandCount++;
    }

    synchronized void markReplayed(long height) {
        replayedHeight = Math.max(replayedHeight, height);
    }

    synchronized void apply(AuthenticatedMapContract.Mutation mutation) {
        String collection = mutation.collectionId();
        byte[] key = mutation.applicationKey();
        try {
            switch (collection) {
                case FeedStarterProfile.FEEDS -> feedIds.add(FeedStarterProfile.requireFeedId(
                        FeedStarterProfile.text(key)));
                case FeedStarterProfile.ROUNDS -> {
                    FeedStarterProfile.RoundKey parsed = FeedStarterProfile.parseRoundKey(key);
                    TreeSet<Long> known = rounds.computeIfAbsent(parsed.feedId(), id -> new TreeSet<>());
                    if (mutation.operation() == AuthenticatedMapContract.OP_REVOKE) {
                        known.remove(parsed.round());
                    } else {
                        known.add(parsed.round());
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException malformed) {
            // A key the profile cannot parse is not a feed or a round.
        }
    }

    public synchronized List<String> feedIds() {
        return List.copyOf(feedIds);
    }

    /** The rounds with a record, ascending. */
    public synchronized List<Long> rounds(String feedId) {
        TreeSet<Long> known = rounds.get(feedId);
        return known == null ? List.of() : List.copyOf(known);
    }
}
