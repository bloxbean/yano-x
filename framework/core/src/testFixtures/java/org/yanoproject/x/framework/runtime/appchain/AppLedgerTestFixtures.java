package org.yanoproject.x.framework.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.api.appchain.state.AuthenticatedStateBackend;
import org.yanoproject.x.framework.api.appchain.state.PreparedStateCommit;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Test-only setup and fault injection shared with Cardano and host integration
 * tests. Keeps package-private engine/storage internals out of the production API.
 */
public final class AppLedgerTestFixtures {
    private AppLedgerTestFixtures() { }

    public static SignerProvider resolveSigner(String key, PluginProviderRegistry providers, Logger log) {
        return SignerProviders.resolveFromRegistry(key, providers, log);
    }

    public static AppMessageSigner signer(String seed) { return new AppMessageSigner(seed); }

    public static AppLedgerStore openStore(String path, Logger log) {
        return new AppLedgerStore(path, log);
    }

    public static AppLedgerStore openStore(String path, Logger log, StateCommitmentIdentity identity) {
        return new AppLedgerStore(path, log, identity);
    }

    public static AuthenticatedStateBackend stateBackend(AppLedgerStore store) { return store.stateBackend(); }

    public static void commitBlock(AppLedgerStore store, AppBlock block, byte[] hash,
                                   byte[] root, WriteBatch batch) {
        store.commitBlock(block, hash, root, batch);
    }

    public static void commitPreparedBlock(AppLedgerStore store, AppBlock block, byte[] hash,
                                           PreparedStateCommit prepared, WriteBatch batch) {
        store.commitBlock(block, hash, (StagedStateCommit) prepared, batch, List.of());
    }

    public static void metaPutBytes(AppLedgerStore store, String key, byte[] value) {
        store.metaPutBytes(key, value);
    }

    public static void injectMetaWriteFault(AppLedgerStore store, Runnable fault) {
        store.injectMetaWriteFault(fault);
    }

    public static String sinkCursorKey(String id) { return SinkRunner.cursorKeyFor(id); }

    public static String effectRuntimeOwner(String identity, Set<String> types) {
        return AppLedgerRuntime.effectRuntimeOwner(identity, types);
    }

    public static void verifySnapshotPostOpen(JsonNode manifest, long height, byte[] hash,
            byte[] root, byte[] memberEpochs, StateCommitmentIdentity identity) {
        SnapshotManifest.verifyPostOpen(manifest, height, hash, root, memberEpochs, identity);
    }

    public static AppMessage decodeConsensusEnvelope(byte[] bytes) { return ConsensusCodec.decodeEnvelope(bytes); }

    public static byte[] encodeConsensusEnvelope(AppMessage message) { return ConsensusCodec.encodeEnvelope(message); }

    public static byte[] stateRoot(AppLedgerStore store) { return store.stateRoot(); }

    public static Optional<byte[]> voteLockEnvelope(AppLedgerStore store, long height) {
        return store.voteLockEnvelope(height);
    }

    public static void putVoteLockEnvelope(AppLedgerStore store, long height, byte[] envelope) {
        store.putVoteLockEnvelope(height, envelope);
    }

    public static int pruneBodiesBelow(AppLedgerStore store, long height) { return store.pruneBodiesBelow(height); }

    public static void metaDeleteSync(AppLedgerStore store, String key) { store.metaDeleteSync(key); }
    public static void deliverInbound(AppLedgerRuntime runtime, List<AppMessage> messages) {
        runtime.onInboundMessages(messages);
    }

    public static int pruneRuntimeBodies(AppLedgerRuntime runtime, long horizon) {
        return runtime.pruneBodiesBelowForTesting(horizon);
    }

    public static CompletableFuture<Void> closeRestartCriticalResourceAfter(AppLedgerRuntime runtime,
            CompletableFuture<Void> quiescence, String resource, Runnable close, Throwable failure) {
        return runtime.closeRestartCriticalResourceAfter(quiescence, resource, close, failure);
    }
    public static byte[] governanceCommand(int operation, byte[] member, int threshold, long activationLag) {
        return GovernedMembership.encodeCommand(operation, member, threshold, activationLag);
    }
    public static String submitGovernance(AppLedgerRuntime runtime, String topic, byte[] command) {
        return runtime.submitGovernance(topic, command);
    }
}
