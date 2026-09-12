package org.yanoproject.x.eutxo.indexer.jdbc;

import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.plugin.domain.FinalizedChainView;
import org.yanoproject.api.plugin.domain.LocalReadModelHost;
import org.yanoproject.api.plugin.domain.LocalReadModelContext;
import org.yanoproject.api.plugin.domain.LocalReadModelProvider;
import org.yanoproject.x.eutxo.contracts.EutxoL2ParameterSnapshot;
import org.yanoproject.x.eutxo.contracts.EutxoQueryCodec;
import org.yanoproject.x.eutxo.indexer.EutxoIndexCoordinator;
import org.yanoproject.x.eutxo.indexer.EutxoIndexMetrics;
import org.yanoproject.x.eutxo.indexer.EutxoIndexStore;
import org.yanoproject.x.eutxo.indexer.EutxoIndexStoreContext;
import org.yanoproject.x.eutxo.indexer.EutxoLocalReadModel;
import org.yanoproject.x.eutxo.indexer.IndexHealth;
import org.yanoproject.x.eutxo.indexer.IndexIdentity;
import org.yanoproject.x.eutxo.indexer.jdbc.SqliteEutxoIndexStoreProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Manifested lifecycle owner for optional per-chain EUTxO indexes. */
public final class EutxoLifecycleIndexerProvider implements LocalReadModelProvider {
    public static final String ID =
            "org.yanoproject.x.eutxo.indexer";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AutoCloseable start(LocalReadModelContext context) {
        Map<String, Object> config = context.bundleConfig();
        if (!booleanValue(config, "enabled", true)) {
            return () -> { };
        }
        String storeType = stringValue(config, "store-type", "jdbc");
        if (!"jdbc".equals(storeType)) {
            throw new IllegalArgumentException("EUTxO indexer store-type must be jdbc");
        }
        return ActiveIndexes.start(
                context.chains(), context.host(),
                context.network(),
                Path.of(stringValue(config, "storage-path", "appchain-indexers")),
                stringValue(config, "jdbc-url", ""));
    }

    private static String stringValue(
            Map<String, Object> config,
            String key,
            String defaultValue
    ) {
        Object value = config.get(key);
        return value == null ? defaultValue : String.valueOf(value).trim();
    }

    private static boolean booleanValue(
            Map<String, Object> config,
            String key,
            boolean defaultValue
    ) {
        String value = stringValue(config, key, Boolean.toString(defaultValue));
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static final class ActiveIndexes implements AutoCloseable {
        private final List<ActiveIndex> active;
        private final AutoCloseable telemetryRegistration;

        private ActiveIndexes(
                List<ActiveIndex> active,
                AutoCloseable telemetryRegistration
        ) {
            this.active = List.copyOf(active);
            this.telemetryRegistration = telemetryRegistration;
        }

        static ActiveIndexes start(
                List<FinalizedChainView> chains,
                LocalReadModelHost host,
                String network,
                Path indexerStorageRoot,
                String configuredJdbcUrl
        ) {
            Objects.requireNonNull(chains, "chains");
            Objects.requireNonNull(host, "host");
            List<FinalizedChainView> eutxo = chains.stream()
                    .filter(ActiveIndexes::isEutxo)
                    .toList();
            if (!configuredJdbcUrl.isBlank() && eutxo.size() > 1) {
                throw new IllegalArgumentException(
                        "one explicit SQLite URL cannot serve multiple EUTxO chains");
            }
            List<ActiveIndex> active = new ArrayList<>();
            try {
                for (FinalizedChainView gateway : eutxo) {
                    IndexIdentity identity = identity(gateway, network);
                    Path data = indexerStorageRoot.toAbsolutePath().normalize()
                            .resolve(gateway.chainId());
                    Map<String, String> settings = configuredJdbcUrl.isBlank()
                            ? Map.of()
                            : Map.of("jdbc.url", configuredJdbcUrl);
                    EutxoIndexStore store =
                            new SqliteEutxoIndexStoreProvider().open(
                                    new EutxoIndexStoreContext(
                                            identity, data, settings));
                    EutxoIndexMetrics metrics = new EutxoIndexMetrics();
                    EutxoIndexCoordinator coordinator =
                            new EutxoIndexCoordinator(gateway, store, metrics);
                    AutoCloseable registration = host.register(
                            EutxoLocalReadModel.MODEL_ID,
                            gateway.chainId(),
                            new EutxoLocalReadModel(
                                    gateway.chainId(), store,
                                    coordinator::health, metrics, host));
                    coordinator.start();
                    active.add(new ActiveIndex(
                            gateway.chainId(), coordinator, registration,
                            metrics, data.resolve("eutxo-lifecycle.db")));
                }
                AutoCloseable telemetry = EutxoIndexerTelemetry.install(active.stream()
                        .map(index -> new EutxoIndexerTelemetry.Sample(
                                index.chainId(), index.coordinator(),
                                index.metrics(), index.database()))
                        .toList());
                return new ActiveIndexes(active, telemetry);
            } catch (Throwable failure) {
                close(active);
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                telemetryRegistration.close();
            } catch (Exception ignored) {
                // Continue closing the owned coordinator/store.
            }
            close(active);
        }

        private static boolean isEutxo(FinalizedChainView gateway) {
        try {
            var result = gateway.query(
                    EutxoQueryCodec.PROFILE_PATH, new byte[0]);
            return "eutxo-ledger".equals(result.stateMachineId())
                    && result.payload().length == 64;
        } catch (AppQueryException | IllegalArgumentException failure) {
            return false;
        }
    }

        private static IndexIdentity identity(
            FinalizedChainView gateway,
            String network
    ) {
        var profile = gateway.query(
                EutxoQueryCodec.PROFILE_PATH, new byte[0]);
        String ledgerDigest = new String(
                profile.payload(), StandardCharsets.US_ASCII);
        String validityDigest = "";
        try {
            EutxoL2ParameterSnapshot parameters =
                    EutxoQueryCodec.decodeL2Parameters(gateway.query(
                            EutxoQueryCodec.L2_PARAMETERS_PATH,
                            new byte[0]).payload());
            validityDigest = parameters.validityProfileDigest();
        } catch (AppQueryException unsupported) {
            // Direct EUTxO has no optional validity identity.
        }
        return new IndexIdentity(
                network,
                gateway.chainId(),
                profile.stateMachineId(),
                ledgerDigest,
                gateway.stateCommitmentIdentity()
                        .map(identity -> HexFormat.of().formatHex(
                                identity.genesisId()))
                        .orElseThrow(() -> new IllegalStateException(
                                "EUTxO index requires authenticated-state identity")),
                1,
                validityDigest);
    }

        private static void close(List<ActiveIndex> indexes) {
        for (int index = indexes.size() - 1; index >= 0; index--) {
            ActiveIndex active = indexes.get(index);
            try {
                active.registration().close();
            } catch (Exception ignored) {
                // Continue closing the owned coordinator/store.
            }
            active.coordinator().close();
        }
    }

        private record ActiveIndex(
                String chainId,
                EutxoIndexCoordinator coordinator,
                AutoCloseable registration,
                EutxoIndexMetrics metrics,
                Path database
        ) {
        }
    }
}
