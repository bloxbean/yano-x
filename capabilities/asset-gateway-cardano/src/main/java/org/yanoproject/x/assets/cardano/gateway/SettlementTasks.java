package org.yanoproject.x.assets.cardano.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The periodic settlement tasks of one chain on this node (ADR-059 D8f, D9): the owner's {@code Accept} batcher and
 * the exit relayer. One daemon thread runs them with a fixed delay between passes; each pass takes the node's
 * assembly lock itself. The executor product that owns them stops them on close, within a bounded wait.
 */
final class SettlementTasks implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementTasks.class);
    private static final long CLOSE_WAIT_MS = 5_000;

    private final ScheduledExecutorService scheduler;
    private final String chainId;

    SettlementTasks(String chainId) {
        this.chainId = chainId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asset-settlement-tasks-" + chainId);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Runs {@code pass} every {@code intervalMs}, first after one interval. A failing pass is logged, not fatal: a
     * throwable escaping a fixed-delay task would cancel every later pass, so only an {@link OutOfMemoryError} or an
     * {@link InternalError} is rethrown; a {@link StackOverflowError} of one pass is logged like any other failure.
     */
    void every(long intervalMs, String name, Callable<String> pass) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                String outcome = pass.call();
                LOG.debug("{} on {}: {}", name, chainId, outcome);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (OutOfMemoryError | InternalError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                LOG.warn("{} on {} failed: {}", name, chainId, failure.toString());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
