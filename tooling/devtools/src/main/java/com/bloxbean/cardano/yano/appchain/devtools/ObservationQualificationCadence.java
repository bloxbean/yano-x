package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationFixedPoint;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ObservationReporterJournal;
import com.bloxbean.cardano.yano.appchain.stdlib.AdaUsdReferenceStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Synthetic fault/cadence driver. Never resets, spends, or silently resumes a partly signed round. */
public final class ObservationQualificationCadence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] LATEST = "ada-usd/latest-result".getBytes(StandardCharsets.UTF_8);

    private ObservationQualificationCadence() { }

    enum Scenario { SOURCE_UNAVAILABLE, SOURCE_DISAGREEMENT, DELAYED_REPORTER, COMPLETE }

    record Claim(int reporter, int source, String value) { }

    static Scenario scenario(long round) {
        if (round < 1 || round > 99) throw new IllegalArgumentException("Expected retained round 1..99");
        return Scenario.values()[(int) ((round - 1) % 4)];
    }

    static List<Claim> claims(Scenario scenario) {
        List<Claim> claims = new ArrayList<>();
        for (int reporter = 0; reporter < 4; reporter++) {
            int sources = scenario == Scenario.SOURCE_UNAVAILABLE ? 2 : 3;
            for (int source = 0; source < sources; source++) {
                String value = scenario == Scenario.SOURCE_DISAGREEMENT && source == 2 && reporter >= 2
                        ? "0.503000" : List.of("0.500000", "0.501000", "0.502000").get(source);
                claims.add(new Claim(reporter, source, value));
            }
        }
        return List.copyOf(claims);
    }

    static boolean expectsExpiry(Scenario scenario) {
        return scenario == Scenario.SOURCE_UNAVAILABLE || scenario == Scenario.SOURCE_DISAGREEMENT;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected qualification directory and round count");
        int count = Integer.parseInt(args[1]);
        if (count < 1 || count > 99) throw new IllegalArgumentException("Round count must be 1..99");
        Path root = Path.of(args[0]).toRealPath();
        var manifest = JSON.readTree(root.resolve("qualification.json").toFile());
        var settings = manifest.path("chainSettings");
        if (!ObservationQualificationConfig.CHAIN_ID.equals(settings.path("chain-id").asText())
                || !"false".equals(settings.path("effects.enabled").asText())
                || !"false".equals(settings.path("anchor.enabled").asText())) {
            throw new IllegalArgumentException("Expected non-spending observation qualification fixture");
        }
        Map<String, String> pinned = new LinkedHashMap<>();
        settings.properties().forEach(entry -> pinned.put(entry.getKey(), entry.getValue().asText()));
        byte[] genesis = ObservationQualificationConfig.effectiveIdentity(pinned).genesisId();
        byte[] consensus = HEX.parseHex(manifest.path("consensusProfileDigest").asText());
        var profile = ObservationProfileV1.decode(HEX.parseHex(settings.path("observations.profile-cbor-hex").asText()));
        var definition = profile.definitions().getFirst();
        List<String> members = new ArrayList<>();
        manifest.path("validators").forEach(value -> members.add(value.asText()));
        if (members.size() != 5) throw new IllegalArgumentException("Expected five pinned validators");
        Properties secrets = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("reporters.private.properties"))) {
            secrets.load(reader);
        }
        List<AppChainClient> clients = new ArrayList<>();
        for (int node = 0; node < 5; node++) {
            clients.add(AppChainClient.builder("http://127.0.0.1:" + (manifest.path("httpBase").asInt() + node)
                            + "/api/v1").chainId(ObservationQualificationConfig.CHAIN_ID)
                    .apiKey(secrets.getProperty("api-key")).build());
        }
        var previous = ObservationResult.decode(clients.getFirst().query("latest", new byte[0]).payload());
        verifyResult(clients, previous, members, genesis, consensus, profile);
        long firstRound = previous.roundNumber() + 1;
        long lastRound = Math.addExact(firstRound, count - 1L);
        scenario(lastRound);
        long initialHeight = clients.getFirst().status().path("tipHeight").asLong();
        for (var client : clients) {
            var status = client.status();
            if (!status.path("running").asBoolean() || !status.path("genericObservations").path("ready").asBoolean()
                    || status.path("tipHeight").asLong() != initialHeight
                    || initialHeight >= 2 + 10 * firstRound
                    || status.path("genericObservations").path("openRounds").asLong() != 0) {
                throw new IllegalStateException("Requires converged, ready nodes before the next round opens");
            }
        }
        Path evidence = root.resolve("cadence-rounds-" + firstRound + "-" + lastRound + ".jsonl");
        Files.writeString(evidence, "", StandardOpenOption.CREATE_NEW);
        for (long number = firstRound; number <= lastRound; number++) {
            long opening = 2 + 10 * number;
            for (long height = clients.getFirst().status().path("tipHeight").asLong() + 1;
                 height <= opening; height++) ObservationQualificationBaseline.advance(clients, height);
            byte[] subscription = previous.subscriptionId();
            var round = ObservationRound.decode(ObservationQualificationBaseline.prove(clients.getFirst(),
                    ObservationKeys.round(subscription, number), opening, members, genesis, consensus, profile.digest()));
            if (round.memberCount() != 5 || round.finalityQuorum() != 4 || round.maxByzantineMembers() != 1
                    || round.roundNumber() != number || round.openingHeight() != opening) {
                throw new IllegalStateException("Authenticated round changed the pinned quorum/schedule");
            }
            Scenario selected = scenario(number);
            System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "authenticated-round",
                    "round", number, "height", opening, "scenario", selected.name())));
            for (Claim claim : claims(selected)) {
                if (selected == Scenario.DELAYED_REPORTER && claim.reporter() == 3 && claim.source() == 0) {
                    // Move one committed height with only three reporters per source. A quorum needs four.
                    ObservationQualificationBaseline.advance(clients, opening + 1);
                    for (var client : clients) {
                        var latest = ObservationResult.decode(client.query("latest", new byte[0]).payload());
                        if (latest.roundNumber() >= number) throw new IllegalStateException("Premature result");
                    }
                }
                byte[] seed = HEX.parseHex(secrets.getProperty("reporter." + claim.reporter()));
                byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
                if (!HEX.formatHex(publicKey).equals(manifest.path("reporters").get(claim.reporter()).asText())) {
                    throw new IllegalStateException("Reporter key differs from pinned fixture");
                }
                var unsigned = new ObservationReport(1, genesis, ObservationQualificationConfig.CHAIN_ID,
                        consensus, profile.digest(), definition.digest(), subscription, number, round.membershipDigest(),
                        round.reporterSetDigest(), publicKey,
                        AdaUsdReferenceStateMachine.parameters().sources().get(claim.source()).id(),
                        ObservationFixedPoint.parse(claim.value(), 6).encode(), new byte[0], new byte[]{1},
                        0, round.dueAnchor(), new byte[64]);
                try (var journal = new ObservationReporterJournal(root.resolve("reporter-journal-" + claim.reporter()),
                        unsigned, 1000, 4_000_000)) {
                    long height = clients.getFirst().status().path("tipHeight").asLong();
                    var signed = journal.sign(round, unsigned, height, height,
                            digest -> CryptoConfiguration.INSTANCE.getSigningProvider().sign(digest, seed));
                    clients.get((claim.reporter() + claim.source() + (int) number) % 5).submitObservationReport(signed);
                }
            }
            if (expectsExpiry(selected)) {
                System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "reports-submitted",
                        "round", number, "count", claims(selected).size())));
                long expiry = Math.min(round.absoluteMaxRoundHeight(),
                        round.reportDeadlineAnchor() + 1 + round.resultInclusionGraceHeights());
                for (long height = opening + 1; height <= expiry + 1; height++) {
                    ObservationQualificationBaseline.advance(clients, height);
                }
            }
            long expectedRound = number;
            ObservationQualificationBaseline.await(() -> clients.stream().allMatch(client ->
                    ObservationResult.decode(client.query("latest", new byte[0]).payload()).roundNumber() == expectedRound));
            var result = ObservationResult.decode(clients.getFirst().query("latest", new byte[0]).payload());
            var expectedStatus = expectsExpiry(selected) ? ObservationResultStatus.EXPIRED : ObservationResultStatus.VALUE;
            if (result.status() != expectedStatus || (expectedStatus == ObservationResultStatus.VALUE
                    && (!Arrays.equals(result.value(), ObservationFixedPoint.parse("0.501000", 6).encode())
                        || result.sourceCount() != 3))) throw new IllegalStateException("Unexpected fault outcome");
            String stateRoot = verifyResult(clients, result, members, genesis, consensus, profile);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", number);
            row.put("scenario", selected.name());
            row.put("status", result.status().name());
            row.put("height", result.finalizedHeight());
            row.put("resultId", HEX.formatHex(result.resultId()));
            row.put("stateRoot", stateRoot);
            row.put("certifiedProofs", 5);
            row.put("observationResources", clients.stream()
                    .map(client -> client.status().path("genericObservations")).toList());
            String line = JSON.writeValueAsString(row);
            Files.writeString(evidence, line + "\n", StandardOpenOption.APPEND);
            System.out.println(line);
            previous = result;
        }
    }

    private static String verifyResult(List<AppChainClient> clients, ObservationResult result, List<String> members,
                                       byte[] genesis, byte[] consensus, ObservationProfileV1 profile) {
        String root = null;
        for (var client : clients) {
            byte[] value = ObservationQualificationBaseline.prove(client, LATEST, result.finalizedHeight(),
                    members, genesis, consensus, profile.digest());
            if (!Arrays.equals(value, result.encode())) throw new IllegalStateException("Certified results differ");
            var block = client.block(result.finalizedHeight()).orElseThrow();
            if (root != null && !root.equals(block.stateRootHex())) throw new IllegalStateException("Roots differ");
            root = block.stateRootHex();
        }
        return root;
    }
}
