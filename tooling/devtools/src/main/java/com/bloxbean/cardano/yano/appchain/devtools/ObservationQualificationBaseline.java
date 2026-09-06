package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusContext;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusQuorum;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationFixedPoint;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ObservationReporterJournal;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.stdlib.AdaUsdReferenceStateMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One synthetic-source round through five packaged REST nodes, with independently pinned certified proofs. */
public final class ObservationQualificationBaseline {
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObservationQualificationBaseline() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected qualification directory");
        Path root = Path.of(args[0]).toRealPath();
        JsonNode manifest = JSON.readTree(root.resolve("qualification.json").toFile());
        JsonNode settings = manifest.path("chainSettings");
        if (!ObservationQualificationConfig.CHAIN_ID.equals(settings.path("chain-id").asText())
                || !"false".equals(settings.path("effects.enabled").asText())
                || !"false".equals(settings.path("anchor.enabled").asText())) {
            throw new IllegalArgumentException("Expected non-spending observation qualification fixture");
        }
        Properties privateSettings = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("reporters.private.properties"))) {
            privateSettings.load(reader);
        }
        List<String> members = new ArrayList<>();
        manifest.path("validators").forEach(value -> members.add(value.asText()));
        if (members.size() != 5) throw new IllegalArgumentException("Expected five initial validators");
        Map<String, String> pinnedSettings = new LinkedHashMap<>();
        settings.properties().forEach(entry -> pinnedSettings.put(entry.getKey(), entry.getValue().asText()));
        byte[] genesis = ObservationQualificationConfig.effectiveIdentity(pinnedSettings).genesisId();
        byte[] consensus = HEX.parseHex(manifest.path("consensusProfileDigest").asText());
        ObservationProfileV1 profile = ObservationProfileV1.decode(
                HEX.parseHex(settings.path("observations.profile-cbor-hex").asText()));
        var definition = profile.definitions().getFirst();
        var parameters = AdaUsdReferenceStateMachine.parameters();
        List<AppChainClient> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clients.add(AppChainClient.builder("http://127.0.0.1:" + (manifest.path("httpBase").asInt() + i)
                            + "/api/v1").chainId(ObservationQualificationConfig.CHAIN_ID)
                    .apiKey(privateSettings.getProperty("api-key")).build());
        }
        for (var client : clients) {
            JsonNode status = client.status();
            if (!status.path("running").asBoolean() || status.path("tipHeight").asLong() != 0
                    || !status.path("genericObservations").path("ready").asBoolean()
                    || !HEX.formatHex(consensus).equals(status.path("consensusProfile").path("digest").asText())
                    || !HEX.formatHex(genesis).equals(status.path("stateCommitment").path("genesisId").asText())) {
                throw new IllegalStateException("Baseline requires five ready, pinned, pristine app chains");
            }
        }
        advance(clients, 1);
        byte[] subscription = prove(clients.getFirst(), "ada-usd/subscription".getBytes(StandardCharsets.UTF_8),
                1, members, genesis, consensus, profile.digest());
        advance(clients, 2);
        ObservationRound round = ObservationRound.decode(prove(clients.getFirst(),
                ObservationKeys.round(subscription, 0), 2, members, genesis, consensus, profile.digest()));
        if (round.memberCount() != 5 || round.finalityQuorum() != 4 || round.maxByzantineMembers() != 1
                || round.roundNumber() != 0 || round.openingHeight() != 2) {
            throw new IllegalStateException("Authenticated round has unexpected opening/quorum");
        }
        for (int reporter = 0; reporter < 4; reporter++) {
            byte[] seed = HEX.parseHex(privateSettings.getProperty("reporter." + reporter));
            byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            if (!HEX.formatHex(publicKey).equals(manifest.path("reporters").get(reporter).asText())) {
                throw new IllegalStateException("Reporter key differs from fixture identity");
            }
            for (int source = 0; source < 3; source++) {
                ObservationReport unsigned = new ObservationReport(1, genesis, ObservationQualificationConfig.CHAIN_ID,
                        consensus, profile.digest(), definition.digest(), subscription, 0, round.membershipDigest(),
                        round.reporterSetDigest(), publicKey, parameters.sources().get(source).id(),
                        ObservationFixedPoint.parse(List.of("0.500000", "0.501000", "0.502000").get(source), 6)
                                .encode(), new byte[0], new byte[]{1}, 0, round.dueAnchor(), new byte[64]);
                try (var journal = new ObservationReporterJournal(root.resolve("reporter-journal-" + reporter),
                        unsigned, 1000, 4_000_000)) {
                    var signed = journal.sign(round, unsigned, 2, 2,
                            digest -> CryptoConfiguration.INSTANCE.getSigningProvider().sign(digest, seed));
                    clients.get((reporter + source + 1) % 5).submitObservationReport(signed);
                }
            }
        }
        await(() -> clients.stream().allMatch(client -> client.query("latest", new byte[0]).payload().length > 0));
        ObservationResult result = ObservationResult.decode(clients.getFirst().query("latest", new byte[0]).payload());
        if (result.status() != ObservationResultStatus.VALUE || result.sourceCount() != 3
                || !Arrays.equals(result.value(), ObservationFixedPoint.parse("0.501000", 6).encode())) {
            throw new IllegalStateException("Unexpected certified synthetic median");
        }
        byte[] expected = result.encode();
        String stateRoot = null;
        for (var client : clients) {
            byte[] value = prove(client, "ada-usd/latest-result".getBytes(StandardCharsets.UTF_8),
                    result.finalizedHeight(), members, genesis, consensus, profile.digest());
            if (!Arrays.equals(value, expected)) throw new IllegalStateException("Certified results differ");
            var block = client.block(result.finalizedHeight()).orElseThrow();
            if (stateRoot != null && !stateRoot.equals(block.stateRootHex())) {
                throw new IllegalStateException("Same-height state roots differ");
            }
            stateRoot = block.stateRootHex();
        }
        var evidence = Map.of("height", result.finalizedHeight(), "resultId", HEX.formatHex(result.resultId()),
                "stateRoot", stateRoot, "certifiedProofs", 5, "sourceCount", result.sourceCount(),
                "scope", "packaged five-node synthetic-source baseline; L1 catch-up status is separate evidence");
        Files.writeString(root.resolve("baseline-certified-result.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence), StandardOpenOption.CREATE_NEW);
        System.out.println(JSON.writeValueAsString(evidence));
    }

    static byte[] noObserverProfileDigest(long protocolMagic) {
        byte[] domain = "yano-l1-network-no-observers-v1\0".getBytes(StandardCharsets.US_ASCII);
        byte[] network = ObservationHashes.digest(ByteBuffer.allocate(domain.length + 8)
                .put(domain).putLong(protocolMagic).array());
        byte[] profileDomain = "yano-observer-profile-v2\0".getBytes(StandardCharsets.US_ASCII);
        return ObservationHashes.digest(ByteBuffer.allocate(profileDomain.length + network.length)
                .put(profileDomain).put(network).array());
    }

    static byte[] prove(AppChainClient client, byte[] key, long height, List<String> members,
                                byte[] genesis, byte[] consensus, byte[] observationProfile) {
        return HEX.parseHex(certifiedProof(client, key, height, members,
                genesis, consensus, observationProfile).valueHex());
    }

    static AppChainClient.Proof certifiedProof(AppChainClient client, byte[] key, long height, List<String> members,
                                               byte[] genesis, byte[] consensus, byte[] observationProfile) {
        // Derive context independently from pinned fixture inputs, never from the proof's own header.
        byte[] context = new ConsensusContext(3, ObservationQualificationConfig.CHAIN_ID, genesis, height,
                new ConsensusQuorum(members.size(), 4, 1), members.stream().map(HEX::parseHex).toList(), consensus,
                noObserverProfileDigest(1), observationProfile).digest();
        var proof = client.proof(key, height).orElseThrow();
        var trust = new ProofVerifier.FinalityTrustContext(ObservationQualificationConfig.CHAIN_ID,
                "mpf-blake2b256-v1", HEX.formatHex(genesis), Set.copyOf(members), 4, HEX.formatHex(context));
        if (!ProofVerifier.verifyCertified(proof, trust)) throw new IllegalStateException("Certified proof rejected");
        if (proof.presence() != AppChainClient.ProofPresence.PRESENT) {
            throw new IllegalStateException("Required authenticated value is absent");
        }
        return proof;
    }

    static void advance(List<AppChainClient> clients, long height) throws Exception {
        clients.getFirst().submit(AdaUsdReferenceStateMachine.ADVANCE_TOPIC, new byte[]{1});
        await(() -> clients.stream().allMatch(client -> client.status().path("tipHeight").asLong() >= height));
    }

    static void await(BooleanSupplier condition) throws Exception {
        // A bounded multi-view wait: live L1 catch-up can outlast the first 90 seconds.
        long deadline = System.nanoTime() + checkpointTimeout(System.getProperty(
                "yano.qualification.checkpoint-timeout-seconds", "300")).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Qualification checkpoint timed out; preserve state and inspect nodes");
    }

    static Duration checkpointTimeout(String seconds) {
        int value = Integer.parseInt(seconds);
        if (value < 1 || value > 1800) throw new IllegalArgumentException("Checkpoint timeout must be 1..1800 seconds");
        return Duration.ofSeconds(value);
    }
}
