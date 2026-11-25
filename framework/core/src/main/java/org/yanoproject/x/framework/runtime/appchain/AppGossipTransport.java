package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgSubmissionConfig;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.n2n.AppMsgValidator;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * Node-local protocol-100 transport sizing (ADR-060 D1, D2, D4). These are tunables, not chain identity: they enter
 * no genesis, commitment profile or consensus rule, and a peer on an older Yaci interoperates at its own window.
 */
final class AppGossipTransport {
    /** Ids requested per round; one link carries about window / (2 × RTT) messages per second. */
    static final int WINDOW = 2_048;
    static final int MAX_BODIES_BYTES_PER_REQUEST = 2 * 1024 * 1024;
    static final int MIN_PENDING_MESSAGES = 10_000;
    static final int PENDING_PRIORITY_MESSAGES = 4_096;
    static final int PRIORITY_BLOCKS_IN_FLIGHT = 4;
    /** Below this many envelopes a reply is validated on the calling (event-loop) thread. */
    static final int PARALLEL_VALIDATION_THRESHOLD = 32;

    private AppGossipTransport() {
    }

    /** Sizing for one chain's transport; the caller sets chains, size and TTL limits and the validator. */
    static AppMsgSubmissionConfig.AppMsgSubmissionConfigBuilder sized(AppChainConfig config) {
        return sized(pendingMessages(config), PENDING_PRIORITY_MESSAGES, priorityBytes(config));
    }

    /** A shared session carries every chain, so its capacities are the sums over chains. */
    static AppMsgSubmissionConfig.AppMsgSubmissionConfigBuilder sizedForAll(Collection<AppChainConfig> configs) {
        long pending = 0;
        long priority = 0;
        long priorityBytes = 0;
        for (AppChainConfig config : configs) {
            pending += pendingMessages(config);
            priority += PENDING_PRIORITY_MESSAGES;
            priorityBytes += priorityBytes(config);
        }
        return sized((int) Math.min(Integer.MAX_VALUE, Math.max(MIN_PENDING_MESSAGES, pending)),
                (int) Math.min(Integer.MAX_VALUE, Math.max(PENDING_PRIORITY_MESSAGES, priority)),
                Math.max(1, priorityBytes));
    }

    private static AppMsgSubmissionConfig.AppMsgSubmissionConfigBuilder sized(
            int pending, int priority, long priorityBytes) {
        return AppMsgSubmissionConfig.builder()
                .batchSize(WINDOW)
                .maxUnacknowledged(WINDOW)
                .maxBodiesBytesPerRequest(MAX_BODIES_BYTES_PER_REQUEST)
                .maxPendingMessages(pending)
                .maxPendingPriorityMessages(priority)
                .maxPendingPriorityBytes(priorityBytes)
                .priority(message -> AppChainSystemTopics.isPriorityDiffusion(message.getTopic()));
    }

    static int pendingMessages(AppChainConfig config) {
        return Math.max(config.poolMaxMessages(), MIN_PENDING_MESSAGES);
    }

    private static long priorityBytes(AppChainConfig config) {
        return PRIORITY_BLOCKS_IN_FLIGHT * Math.max(1L, config.blockMaxBytes());
    }

    /**
     * A validator that checks a reply's envelopes in parallel on the common fork-join pool once the reply is large
     * enough, keeping input order (ADR-060 D4). The calling Netty thread waits as the stream's caller, never as a
     * pool task, so it cannot deadlock.
     */
    static AppMsgValidator parallel(Function<AppMessage, AppMsgValidator.Result> check) {
        return new AppMsgValidator() {
            @Override
            public Result validate(AppMessage message) {
                return check.apply(message);
            }

            @Override
            public List<Result> validateAll(List<AppMessage> messages) {
                Result[] results = new Result[messages.size()];
                IntStream range = IntStream.range(0, messages.size());
                (messages.size() >= PARALLEL_VALIDATION_THRESHOLD ? range.parallel() : range)
                        .forEach(i -> results[i] = check.apply(messages.get(i)));
                return List.of(results);
            }
        };
    }
}
