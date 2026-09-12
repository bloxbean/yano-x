package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.yanoproject.api.appchain.observation.ObservationFixedPoint;
import org.yanoproject.api.appchain.observation.ObservationKeys;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.observation.ObservationReport;
import org.yanoproject.api.appchain.observation.ObservationResult;
import org.yanoproject.api.appchain.observation.ObservationResultStatus;
import org.yanoproject.api.appchain.observation.ObservationRound;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ObservationReporterJournal;
import org.yanoproject.x.stdlib.AdaUsdReferenceStateMachine;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.stream.IntStream;

/** Operator-controlled TCP omission drill. No node code, keys, or retained state are replaced. */
public final class ObservationQualificationWithholding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] LATEST = "ada-usd/latest-result".getBytes(StandardCharsets.UTF_8);

    private ObservationQualificationWithholding() { }

    static boolean linksMatch(List<JsonNode> states, int withheld, int base) {
        if (states.size() != 5 || withheld < -1 || withheld >= 5 || base < 1024 || base > 65511) return false;
        for (int node = 0; node < 5; node++) {
            JsonNode peers = states.get(node).path("peers");
            if (!peers.isObject() || peers.size() != 4) return false;
            for (int target = 0; target < 5; target++) {
                if (target == node) continue;
                JsonNode value = peers.path("127.0.0.1:" + (base + 5 * node + target));
                if (!value.isBoolean() || value.asBoolean() != (node != withheld && target != withheld)) return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected directory, next round number and proxy base port");
        }
        Path root = Path.of(args[0]).toRealPath();
        long number = Long.parseLong(args[1]);
        int proxyBase = Integer.parseInt(args[2]);
        if (number < 10 || number > 99) throw new IllegalArgumentException("Expected post-membership round 10..99");
        long opening = 2 + 10 * number;
        JsonNode manifest = JSON.readTree(root.resolve("qualification.json").toFile());
        Map<String, String> settings = new LinkedHashMap<>();
        manifest.path("chainSettings").properties().forEach(e -> settings.put(e.getKey(), e.getValue().asText()));
        if (!ObservationQualificationConfig.CHAIN_ID.equals(settings.get("chain-id"))
                || !"false".equals(settings.get("effects.enabled"))
                || !"false".equals(settings.get("anchor.enabled"))) {
            throw new IllegalArgumentException("Requires the non-spending qualification fixture");
        }
        byte[] genesis = ObservationQualificationConfig.effectiveIdentity(settings).genesisId();
        byte[] consensus = HEX.parseHex(manifest.path("consensusProfileDigest").asText());
        var profile = ObservationProfileV1.decode(HEX.parseHex(settings.get("observations.profile-cbor-hex")));
        var definition = profile.definitions().getFirst();
        List<String> original = new ArrayList<>();
        manifest.path("validators").forEach(key -> original.add(key.asText()));
        List<String> members = ObservationQualificationMembership.membersAt(root, original, opening);
        if (original.size() != 5 || !members.equals(original.stream().sorted().toList())) {
            throw new IllegalStateException("Finish the membership removal before this drill");
        }
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
        if (!linksMatch(clients.stream().map(AppChainClient::status).toList(), -1, proxyBase)) {
            throw new IllegalStateException("Requires the fully connected directed proxy topology");
        }
        long initial = clients.getFirst().status().path("tipHeight").asLong();
        ObservationResult previous = null;
        for (int node = 0; node < 5; node++) {
            var status = clients.get(node).status();
            if (initial >= opening || !status.path("running").asBoolean()
                    || !status.path("genericObservations").path("ready").asBoolean()
                    || status.path("tipHeight").asLong() != initial
                    || status.path("genericObservations").path("openRounds").asLong() != 0
                    || !original.get(node).equals(status.path("memberKey").asText())) {
                throw new IllegalStateException("Requires five ready, converged pinned nodes before opening");
            }
            var result = ObservationResult.decode(ObservationQualificationBaseline.prove(clients.get(node), LATEST,
                    initial, members, genesis, consensus, profile.digest()));
            if (result.roundNumber() != number - 1
                    || previous != null && !Arrays.equals(result.encode(), previous.encode())) {
                throw new IllegalStateException("Wrong certified prior result");
            }
            previous = result;
        }
        Path attempt = root.resolve("withholding-round-" + number);
        Files.createDirectory(attempt); // Never silently resume a partly signed or partitioned attempt.
        for (long height = initial + 1; height <= opening; height++) {
            ObservationQualificationBaseline.advance(clients, height);
        }
        ObservationRound round = null;
        for (var client : clients) {
            var candidate = ObservationRound.decode(ObservationQualificationBaseline.prove(client,
                    ObservationKeys.round(previous.subscriptionId(), number), opening, members,
                    genesis, consensus, profile.digest()));
            if (round != null && !Arrays.equals(round.encode(), candidate.encode())) {
                throw new IllegalStateException("Certified opening rounds differ");
            }
            round = candidate;
        }
        if (round.memberCount() != 5 || round.finalityQuorum() != 4 || round.maxByzantineMembers() != 1
                || round.roundNumber() != number || round.openingHeight() != opening) {
            throw new IllegalStateException("Wrong opening quorum");
        }
        var leaderState = clients.getFirst().status().path("sequencer");
        String leader = leaderState.path("leader").asText();
        long view = leaderState.path("currentView").asLong(-1);
        int withheld = original.indexOf(leader);
        if (withheld < 0 || clients.stream().anyMatch(client ->
                !leader.equals(client.status().path("sequencer").path("leader").asText())
                || view != client.status().path("sequencer").path("currentView").asLong(-1))) {
            throw new IllegalStateException("Certified-consensus leaders/views have not converged");
        }
        write(attempt.resolve("plan.json"), Map.of("round", number, "opening", opening,
                "withheldNode", withheld, "leader", leader, "view", view));
        System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "partition-required",
                "round", number, "node", withheld, "view", view, "leader", leader)));
        ObservationQualificationBaseline.await(() ->
                linksMatch(clients.stream().map(AppChainClient::status).toList(), withheld, proxyBase));
        var partitionSequencer = clients.get(withheld).status().path("sequencer");
        long partitionView = partitionSequencer.path("currentView").asLong(-1);
        if (!leader.equals(partitionSequencer.path("leader").asText()) || partitionView < view) {
            throw new IllegalStateException("Selected node ceased being proposer before isolation; preserve attempt");
        }
        // Operator response may span a full leader cycle. Pin the view actually isolated,
        // not only the earlier advertised selection view, before signing any reports.
        write(attempt.resolve("partition-view.json"), Map.of("leader", leader, "view", partitionView));
        long readyBefore = clients.get(withheld).status().path("genericObservations")
                .path("certificatesReady").asLong();
        List<ObservationReport> signed = new ArrayList<>();
        for (var claim : ObservationQualificationCadence.claims(ObservationQualificationCadence.Scenario.COMPLETE)) {
            byte[] seed = HEX.parseHex(secrets.getProperty("reporter." + claim.reporter()));
            byte[] key = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            if (!HEX.formatHex(key).equals(manifest.path("reporters").get(claim.reporter()).asText())) {
                throw new IllegalStateException("Reporter differs from pinned key");
            }
            var unsigned = new ObservationReport(1, genesis, ObservationQualificationConfig.CHAIN_ID, consensus,
                    profile.digest(), definition.digest(), round.subscriptionId(), number, round.membershipDigest(),
                    round.reporterSetDigest(), key,
                    AdaUsdReferenceStateMachine.parameters().sources().get(claim.source()).id(),
                    ObservationFixedPoint.parse(claim.value(), 6).encode(), new byte[0], new byte[]{1},
                    0, round.dueAnchor(), new byte[64]);
            try (var journal = new ObservationReporterJournal(root.resolve("reporter-journal-" + claim.reporter()),
                    unsigned, 1000, 4_000_000)) {
                signed.add(journal.sign(round, unsigned, opening, opening,
                        digest -> CryptoConfiguration.INSTANCE.getSigningProvider().sign(digest, seed)));
            }
        }
        write(attempt.resolve("signed-reports.json"),
                signed.stream().map(report -> HEX.formatHex(report.encode())).toList());
        for (var report : signed) clients.get(withheld).submitObservationReport(report);
        ObservationQualificationBaseline.await(() -> clients.get(withheld).status().path("genericObservations")
                .path("certificatesReady").asLong() > readyBefore);
        if (!linksMatch(clients.stream().map(AppChainClient::status).toList(), withheld, proxyBase)
                || clients.stream().anyMatch(client -> client.status().path("tipHeight").asLong() != opening)) {
            throw new IllegalStateException("Partition or opening checkpoint changed before certificate release");
        }
        write(attempt.resolve("withholder-ready.json"), clients.get(withheld).status().path("genericObservations"));
        System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "certificate-retained-behind-partition",
                "round", number, "node", withheld)));
        List<AppChainClient> honest = IntStream.range(0, 5).filter(node -> node != withheld)
                .mapToObj(clients::get).toList();
        // Exact retained wires, no re-signing.
        for (var report : signed) honest.getFirst().submitObservationReport(report);
        ObservationQualificationBaseline.await(() -> honest.stream().allMatch(client ->
                ObservationResult.decode(client.query("latest", new byte[0]).payload()).roundNumber() == number));
        var result = ObservationResult.decode(honest.getFirst().query("latest", new byte[0]).payload());
        if (result.status() != ObservationResultStatus.VALUE || result.sourceCount() != 3
                || !Arrays.equals(result.value(), ObservationFixedPoint.parse("0.501000", 6).encode())) {
            throw new IllegalStateException("Unexpected honest result");
        }
        List<AppChainClient.Proof> proofs = prove(honest, result, members, genesis, consensus, profile);
        if (proofs.stream().anyMatch(proof ->
                leader.equals(proof.block().proposerHex()) || proof.block().view() <= partitionView)
                || clients.get(withheld).status().path("tipHeight").asLong() != opening
                || !linksMatch(clients.stream().map(AppChainClient::status).toList(), withheld, proxyBase)) {
            throw new IllegalStateException("Honest finality did not precede healing the withholding proposer");
        }
        write(attempt.resolve("honest-certified-before-heal.json"), proofs);
        System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "heal-required",
                "height", result.finalizedHeight(),
                "resultId", HEX.formatHex(result.resultId()), "certifiedHonestProofs", 4)));
        ObservationQualificationBaseline.await(() -> clients.stream().allMatch(client ->
                ObservationResult.decode(client.query("latest", new byte[0]).payload()).roundNumber() == number));
        write(attempt.resolve("all-five-certified-after-heal.json"),
                prove(clients, result, members, genesis, consensus, profile));
        System.out.println(JSON.writeValueAsString(Map.of("checkpoint", "withholding-complete", "round", number,
                "resultId", HEX.formatHex(result.resultId()), "certifiedProofs", 5)));
    }

    private static List<AppChainClient.Proof> prove(List<AppChainClient> clients, ObservationResult result,
                                                   List<String> members, byte[] genesis, byte[] consensus,
                                                   ObservationProfileV1 profile) {
        List<AppChainClient.Proof> proofs = new ArrayList<>();
        for (var client : clients) {
            var proof = ObservationQualificationBaseline.certifiedProof(client, LATEST, result.finalizedHeight(),
                    members, genesis, consensus, profile.digest());
            if (!Arrays.equals(HEX.parseHex(proof.valueHex()), result.encode())
                    || !proofs.isEmpty() && !proofs.getFirst().stateRootHex().equals(proof.stateRootHex())) {
                throw new IllegalStateException("Certified result/root mismatch");
            }
            proofs.add(proof);
        }
        return proofs;
    }

    private static void write(Path file, Object value) throws Exception {
        Files.writeString(file, JSON.writeValueAsString(value), StandardOpenOption.CREATE_NEW);
    }
}
