package org.yanoproject.x.kafka.effects;

import org.yanoproject.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.api.appchain.effects.AppEffectExecutorFactory;
import org.yanoproject.x.integration.detail.ConnectorDetailArchive;
import org.yanoproject.x.integration.detail.FileConnectorDetailArchive;
import org.yanoproject.x.kafka.config.KafkaEffectConfig;
import org.yanoproject.x.kafka.internal.KafkaEffectProducerFactory;
import org.yanoproject.x.kafka.internal.KafkaProducerClients;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** ServiceLoader factory for the per-action {@code kafka.publish} executor. */
public final class KafkaEffectExecutorFactory implements AppEffectExecutorFactory {
    private final KafkaEffectProducerFactory producerFactory;

    /** Creates a factory whose products own fresh Kafka producers. */
    public KafkaEffectExecutorFactory() {
        this(KafkaProducerClients::open);
    }

    KafkaEffectExecutorFactory(KafkaEffectProducerFactory producerFactory) {
        this.producerFactory = Objects.requireNonNull(producerFactory, "producerFactory");
    }

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        Objects.requireNonNull(chainId, "chainId");
        KafkaEffectConfig parsed = KafkaEffectConfig.parse(config);
        if (!parsed.enabled()) {
            return List.of();
        }

        ConnectorDetailArchive archive = null;
        try {
            if (parsed.detailArchivePath().isPresent()) {
                archive = new FileConnectorDetailArchive(parsed.detailArchivePath().orElseThrow());
            }
            return List.of(new KafkaPublishExecutor(parsed, producerFactory, archive));
        } catch (IOException | RuntimeException constructionFailure) {
            closeAfterFailedConstruction(archive, constructionFailure);
            throw new IllegalArgumentException("kafka effect executor construction failed",
                    constructionFailure);
        }
    }

    private static void closeAfterFailedConstruction(ConnectorDetailArchive archive,
                                                     Throwable constructionFailure) {
        if (archive == null) {
            return;
        }
        try {
            archive.close();
        } catch (Throwable closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
        }
    }
}
