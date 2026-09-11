package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationFixedPoint;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationKeys;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReport;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResult;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationResultStatus;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRound;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ObservationReporterJournal;
import org.yanoproject.x.stdlib.AdaUsdReferenceStateMachine;
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

    static boolean validStartingBoundary(long height, long round, long openRounds, boolean recover) {
        long opening = Math.addExact(2, Math.multiplyExact(10, round));
        return recover ? height == opening && openRounds == 1 : height < opening && openRounds == 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Expected directory, round count, optional explicit fault/recovery mode");
        }
        int count = Integer.parseInt(args[1]);
        if (count < 1 || count > 99) throw new IllegalArgumentException("Round count must be 1..99");
        boolean equivocating = args.length == 3 && "equivocating-reporter".equals(args[2]);
        boolean recovering = args.length == 3 && "recover-open-round".equals(args[2]);
        if (args.length == 3 && ((!equivocating && !recovering) || count != 1)) {
            throw new IllegalArgumentException("Explicit fault/recovery mode requires exactly one round");
        }
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
        verifyResult(root, clients, previous, members, genesis, consensus, profile);
        long firstRound = previous.roundNumber() + 1;
        long lastRound = Math.addExact(firstRound, count - 1L);
        scenario(lastRound);
        long initialHeight = clients.getFirst().status().path("tipHeight").asLong();
        for (var client : clients) {
            var status = client.status();
            if (!status.path("running").asBoolean() || !status.path("genericObservations").path("ready").asBoolean()
                    || status.path("tipHeight").asLong() != initialHeight
                    || !validStartingBoundary(initialHeight, firstRound,
                            status.path("genericObservations").path("openRounds").asLong(), recovering)) {
                throw new IllegalStateException("Requires converged, ready nodes at the explicit starting boundary");
            }
        }
        Path evidence = root.resolve("cadence-rounds-" + firstRound + "-" + lastRound + ".jsonl");
        Files.writeString(evidence, "", StandardOpenOption.CREATE_NEW);
        for (long number = firstRound; number <= lastRound; number++) {
            long opening = 2 + 10 * number;
            for (long height = clients.getFirst().status().path("tipHeight").asLong() + 1;
                 height <= opening; height++) ObservationQualificationBaseline.advance(clients, height);
            byte[] subscription = previous.subscriptionId();
            List<String> roundMembers = ObservationQualificationMembership.membersAt(root, members, opening);
            var round = ObservationRound.decode(ObservationQualificationBaseline.prove(clients.getFirst(),
                    ObservationKeys.round(subscription, number), opening, roundMembers, genesis, consensus, profile.digest()));
            for (var client : clients) {
                byte[] proofValue = ObservationQualificationBaseline.prove(client,
                        ObservationKeys.round(subscription, number), opening, roundMembers, genesis, consensus, profile.digest());
                if (!Arrays.equals(proofValue, round.encode())) {
                    throw new IllegalStateException("Certified opening rounds differ");
                }
            }
            if (round.memberCount() != roundMembers.size() || round.finalityQuorum() != 4 || round.maxByzantineMembers() != 1
                    || round.roundNumber() != number || round.openingHeight() != opening) {
                throw new IllegalStateException("Authenticated round changed the pinned quorum/schedule");
            }
            Scenario selected = equivocating ? Scenario.COMPLETE : scenario(number);
            String scenarioName = equivocating ? "EQUIVOCATING_REPORTER" : selected.name();
            System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "authenticated-round",
                    "round", number, "height", opening, "scenario", scenarioName)));
            if (equivocating) {
                byte[] seed = HEX.parseHex(secrets.getProperty("reporter.4"));
                byte[] key = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
                if (!HEX.formatHex(key).equals(manifest.path("reporters").get(4).asText())) {
                    throw new IllegalStateException("Adversarial reporter differs from pinned fifth fixture key");
                }
                var template = new ObservationReport(1, genesis, ObservationQualificationConfig.CHAIN_ID,
                        consensus, profile.digest(), definition.digest(), subscription, number, round.membershipDigest(),
                        round.reporterSetDigest(), key, AdaUsdReferenceStateMachine.parameters().sources().get(2).id(),
                        ObservationFixedPoint.parse("0.503000", 6).encode(), new byte[0], new byte[]{1},
                        0, round.dueAnchor(), new byte[64]);
                var faults = adversarialReports(template, seed);
                // This deliberate Byzantine signer is separate from all four honest signing journals.
                // Retain both signed wires before any submission; never silently recreate a partial attempt.
                Files.writeString(root.resolve("equivocation-round-" + number + ".json"),
                        JSON.writeValueAsString(Map.of("round", number, "reporter", HEX.formatHex(key),
                                "reports", faults.stream().map(report -> HEX.formatHex(report.encode())).toList())),
                        StandardOpenOption.CREATE_NEW);
                clients.get(0).submitObservationReport(faults.get(0));
                clients.get(4).submitObservationReport(faults.get(1));
            }
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
            String stateRoot = verifyResult(root, clients, result, members, genesis, consensus, profile);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", number);
            row.put("scenario", scenarioName);
            row.put("explicitOpeningRecovery", recovering);
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

    static List<ObservationReport> adversarialReports(ObservationReport template, byte[] seed) {
        if (!Arrays.equals(template.reporterPublicKey(), KeyGenUtil.getPublicKeyFromPrivateKey(seed))) {
            throw new IllegalArgumentException("Adversarial signing seed does not match report");
        }
        List<ObservationReport> reports = new ArrayList<>();
        for (String value : List.of("0.503000", "0.504000")) {
            var unsigned = copyClaim(template, ObservationFixedPoint.parse(value, 6).encode(), new byte[64]);
            byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(unsigned.signingDigest(), seed);
            reports.add(copyClaim(unsigned, unsigned.value(), signature));
        }
        return List.copyOf(reports);
    }

    private static ObservationReport copyClaim(ObservationReport report, byte[] value, byte[] signature) {
        return new ObservationReport(report.version(), report.chainGenesisId(), report.chainId(),
                report.consensusProfileDigest(), report.observationProfileDigest(), report.definitionDigest(),
                report.subscriptionId(), report.roundNumber(), report.membershipDigest(), report.reporterSetDigest(),
                report.reporterPublicKey(), report.sourceId(), value, report.evidence(), report.sourceVersion(),
                report.freshnessAnchorType(), report.freshnessAnchor(), signature);
    }

    private static String verifyResult(Path directory, List<AppChainClient> clients,
                                       ObservationResult result, List<String> members,
                                       byte[] genesis, byte[] consensus, ObservationProfileV1 profile) {
        String root = null;
        for (var client : clients) {
            byte[] value = ObservationQualificationBaseline.prove(client, LATEST, result.finalizedHeight(),
                    ObservationQualificationMembership.membersAt(directory, members, result.finalizedHeight()),
                    genesis, consensus, profile.digest());
            if (!Arrays.equals(value, result.encode())) throw new IllegalStateException("Certified results differ");
            var block = client.block(result.finalizedHeight()).orElseThrow();
            if (root != null && !root.equals(block.stateRootHex())) throw new IllegalStateException("Roots differ");
            root = block.stateRootHex();
        }
        return root;
    }
}
