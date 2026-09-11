package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationFixedPoint;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import org.yanoproject.x.client.ObservationReporterJournal;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(90)
class AdaUsdReferenceRuntimeTest {
    @Test
    void reporterSdkFeedsPluginAndRestartPreservesTerminalResult(@TempDir Path directory) throws Exception {
        String seed = "41".repeat(32);
        TestSigner member = new TestSigner(seed);
        List<TestSigner> reporters = IntStream.range(51, 56)
                .mapToObj(value -> new TestSigner(Integer.toHexString(value).repeat(32))).toList();
        var parameters = AdaUsdReferenceStateMachine.parameters();
        var definition = definition(reporters);
        ObservationProfileV1 profile = new ObservationProfileV1(1, true, 1, 2, 1, 1, 2, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 15, 3,
                4096, 1024, 16_384, 10, 32_768, 1, 20, 3);
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("observations.profile-cbor-hex", HexUtil.encodeHexString(profile.encode()));
        settings.put("observations.reporters.ada-usd", reporters.stream()
                .map(TestSigner::publicKeyHex).collect(Collectors.joining(",")));
        settings.put("observations.policy.ada-usd", HexUtil.encodeHexString(parameters.encode()));
        AppChainConfig config = AppChainConfig.builder("ada-usd-example")
                .signingKeyHex(seed).memberKeysHex(Set.of(member.publicKeyHex()))
                .proposerKeyHex(member.publicKeyHex()).threshold(1).blockIntervalMs(200)
                .stateCommitmentIdentity(StdlibTestStateCommitments.mpf("ada-usd-example"))
                .stateMachineId(AdaUsdReferenceStateMachine.ID).pluginSettings(settings).build();
        Path ledger = directory.resolve("ledger");
        byte[] root;
        byte[] encodedResult;
        long height;
        try (AppChainSubsystem node = start(config, ledger)) {
            advance(node, 1);
            byte[] subscription = node.query("subscription", new byte[0]).payload();
            assertThat(subscription).hasSize(32);
            advance(node, 2);
            ObservationRound round = ObservationRound.decode(node.query("yano/observations/round",
                    ByteBuffer.allocate(40).put(subscription).putLong(0).array()).payload());
            byte[] consensus = AppChainConsensusProfileCommitment.digest(
                    AppChainEffectsConfig.from(config).consensusProfile(config));
            for (int reporterIndex = 0; reporterIndex < 4; reporterIndex++) {
                TestSigner signer = reporters.get(reporterIndex);
                for (int sourceIndex = 0; sourceIndex < 3; sourceIndex++) {
                    ObservationReport unsigned = new ObservationReport(1,
                            node.stateCommitmentIdentity().orElseThrow().genesisId(), config.chainId(), consensus,
                            profile.digest(), definition.digest(), subscription, 0, round.membershipDigest(),
                            round.reporterSetDigest(), signer.publicKey(), parameters.sources().get(sourceIndex).id(),
                            ObservationFixedPoint.parse(List.of("0.500000", "0.501000", "0.502000")
                                    .get(sourceIndex), 6).encode(), new byte[0], new byte[]{1}, 0, 2, new byte[64]);
                    try (ObservationReporterJournal journal = new ObservationReporterJournal(
                            directory.resolve("reporter-" + reporterIndex), unsigned, 100, 1_000_000)) {
                        node.submitObservationReport(journal.sign(round, unsigned, 2, 2, signer::sign).encode());
                    }
                }
            }
            await(() -> node.query("latest", new byte[0]).payload().length != 0);
            encodedResult = node.query("latest", new byte[0]).payload();
            ObservationResult result = ObservationResult.decode(encodedResult);
            assertThat(result.status()).isEqualTo(ObservationResultStatus.VALUE);
            assertThat(result.value()).isEqualTo(ObservationFixedPoint.parse("0.501000", 6).encode());
            assertThat(result.sourceCount()).isEqualTo(3);
            assertThat(result.reporterCount()).isEqualTo(4);
            assertThat(node.query("yano/observations/result", result.resultId()).payload()).isEqualTo(encodedResult);
            root = node.stateRoot();
            height = node.tipHeight();
        }
        try (AppChainSubsystem node = start(config, ledger)) {
            assertThat(node.tipHeight()).isEqualTo(height);
            assertThat(node.stateRoot()).isEqualTo(root);
            assertThat(node.query("latest", new byte[0]).payload()).isEqualTo(encodedResult);
            // The second scheduled round has no reports. It must replace, not re-label, the old price.
            while (node.tipHeight() < 19) advance(node, node.tipHeight() + 1);
            await(() -> ObservationResult.decode(node.query("latest", new byte[0]).payload()).roundNumber() == 1);
            ObservationResult absent = ObservationResult.decode(node.query("latest", new byte[0]).payload());
            assertThat(absent.status()).isNotEqualTo(ObservationResultStatus.VALUE);
            assertThat(absent.value()).isEmpty();
        }
    }

    static ObservationDefinition definition(List<TestSigner> reporters) {
        var parameters = AdaUsdReferenceStateMachine.parameters();
        return new ObservationDefinition(1, "ada-usd", 1, digest("pair-utf8-v1"), digest("fixed-point-v1"),
                digest("fixed-point-v1"), digest("source-id-v1"), ObservationReporterMode.EXTERNAL_REPORTERS,
                ObservationHashes.reporterSetDigest(reporters.stream().map(TestSigner::publicKey).toList()),
                1, 4, 3, true, "external-reporters-v1", parameters.sourceSetDigest(), "fixed-point-v1",
                "external-reporter-claim-v1", "complete-source-median-v1", parameters.digest(), digest("round-v2"),
                "pinned-groups-v1", "round-anchor-v1", "inline-v1", 1, 128, 18, 0, 15, 3);
    }

    private static byte[] digest(String name) {
        return ObservationHashes.digest(name.getBytes(StandardCharsets.UTF_8));
    }

    private record TestSigner(String seedHex) {
        byte[] publicKey() { return KeyGenUtil.getPublicKeyFromPrivateKey(HexUtil.decodeHexString(seedHex)); }
        String publicKeyHex() { return HexUtil.encodeHexString(publicKey()); }
        byte[] sign(byte[] digest) {
            return CryptoConfiguration.INSTANCE.getSigningProvider().sign(digest, HexUtil.decodeHexString(seedHex));
        }
    }

    private static AppChainSubsystem start(AppChainConfig config, Path ledger) {
        AppChainSubsystem node = new AppChainSubsystem(config, 42, null, null, ledger.toString(), null,
                StdlibTestPluginProviders.registry(), LoggerFactory.getLogger(AdaUsdReferenceRuntimeTest.class));
        node.start();
        return node;
    }

    private static void advance(AppChainSubsystem node, long height) throws Exception {
        node.submit(AdaUsdReferenceStateMachine.ADVANCE_TOPIC, new byte[]{1});
        await(() -> node.tipHeight() >= height);
    }

    private static void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("ADA/USD reference did not reach expected finalized state");
    }
}
