package org.yanoproject.x.eutxo.zk.indexer;

import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppQueryResult;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.plugin.domain.FinalizedChainView;
import org.yanoproject.api.plugin.domain.LocalReadModelContext;
import org.yanoproject.api.plugin.domain.LocalReadModelHost;
import org.yanoproject.api.plugin.domain.LocalReadModelResult;
import org.yanoproject.x.eutxo.indexer.EutxoIndexRequest;
import org.yanoproject.x.eutxo.indexer.EutxoLocalReadModel;
import org.yanoproject.x.eutxo.indexer.EutxoValidityLocalReadModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZerojValidityReadModelProviderTest {
    @TempDir
    Path temporary;

    @Test
    void sourceIsLazyBeforeCeremonyAndFailsClosedOnIdentityMismatch()
            throws Exception {
        var provider = new ZerojValidityReadModelProvider();
        AtomicReference<LocalReadModelHost.LocalReadModel> model =
                new AtomicReference<>();
        LocalReadModelHost host = new LocalReadModelHost() {
            @Override
            public AutoCloseable register(
                    String modelId,
                    String chainId,
                    LocalReadModel localReadModel
            ) {
                assertThat(modelId).isEqualTo(EutxoValidityLocalReadModel.MODEL_ID);
                assertThat(chainId).isEqualTo("payments");
                model.set(localReadModel);
                return () -> model.compareAndSet(localReadModel, null);
            }

            @Override
            public LocalReadModelResult query(
                    String modelId,
                    String chainId,
                    String operation,
                    byte[] request
            ) {
                return model.get().query(operation, request);
            }
        };
        AutoCloseable lifecycle = provider.start(new LocalReadModelContext(
                "devnet",
                Map.of("storage-path", temporary.toString()),
                List.of(chain("payments")),
                host));
        byte[] request = EutxoIndexRequest.defaults().encode();
        assertThat(new String(model.get().query(
                EutxoLocalReadModel.VALIDITY_BATCHES, request).payload()))
                .contains("\"items\":[]");

        Files.writeString(temporary.resolve("state.json"), """
                {
                  "schemaVersion": "yano-eutxo-validity-lifecycle-v1",
                  "chainId": "another-chain",
                  "network": "devnet"
                }
                """);
        assertThatThrownBy(() -> model.get().query(
                EutxoLocalReadModel.VALIDITY_BATCHES, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another chain or network");
        lifecycle.close();
        assertThat(model.get()).isNull();
    }

    @Test
    void aBatchReportsItsMostAdvancedJournalAndAnUnknownStatusOnlyMarksThatBatch() {
        String proof = "aa".repeat(32);
        String other = "bb".repeat(32);
        var prepared = journal(proof, "PREPARED", "");
        var submitted = journal(proof, "SUBMITTED", "cc".repeat(32));
        var stable = journal(proof, "STABLE", "dd".repeat(32));
        var otherStable = journal(other, "STABLE", "ee".repeat(32));
        var deposit = Map.<String, Object>of("kind", "deposit", "proofId", proof, "status", "STABLE");

        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(), proof)).isEmpty();
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(prepared), proof))
                .containsEntry("status", "PREPARED");
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(prepared, submitted), proof))
                .isEqualTo(submitted);
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(submitted, prepared), proof))
                .isEqualTo(submitted);
        // An unknown status is never reported as a submission, even next to a submitted journal or alone.
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(submitted, stable), proof))
                .isEqualTo(Map.of("status", ZerojValidityReadModelProvider.INVALID_JOURNAL));
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(List.of(stable), proof))
                .isEqualTo(Map.of("status", ZerojValidityReadModelProvider.INVALID_JOURNAL));
        // Another batch's journal and non-settlement journals do not touch this batch.
        assertThat(ZerojValidityReadModelProvider.ZerojSource.settlement(
                List.of(submitted, otherStable, deposit), proof)).isEqualTo(submitted);
    }

    private static Map<String, Object> journal(String proofId, String status, String transactionId) {
        return Map.of("kind", "settlement", "proofId", proofId, "status", status, "transactionId", transactionId);
    }

    private static FinalizedChainView chain(String chainId) {
        return new FinalizedChainView() {
            @Override public String chainId() { return chainId; }
            @Override public long tipHeight() { return 0; }
            @Override public Optional<AppBlock> block(long height) {
                return Optional.empty();
            }
            @Override public AppQueryResult query(String path, byte[] request) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<StateCommitmentIdentity>
            stateCommitmentIdentity() { return Optional.empty(); }
            @Override public AutoCloseable subscribe(
                    FinalizedBlockListener listener) { return () -> { }; }
        };
    }
}
