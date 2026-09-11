package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfileCommitment;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationDefinition;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProfileV1;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationReporterMode;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedBlockMessageRootIndexedStateMachine;
import com.bloxbean.cardano.yano.api.appchain.transition.FinalizedMessageIndexedStateMachine;
import com.bloxbean.cardano.yano.appchain.config.AppChainConfigParser;
import com.bloxbean.cardano.yano.appchain.config.AppChainEffectsConfig;
import org.yanoproject.x.stdlib.AdaUsdReferenceStateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Fresh, non-spending five-validator fixture. Does not launch nodes or touch retained stores. */
public final class ObservationQualificationConfig {
    public static final String CHAIN_ID = "adr037-qualification";
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObservationQualificationConfig() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Expected: new-directory exact-host-directory plugin-directory host-version http-base n2n-base");
        }
        prepare(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3],
                Integer.parseInt(args[4]), Integer.parseInt(args[5]));
    }

    public static void prepare(Path target, Path host, Path plugins, String expectedHostVersion,
                               int httpBase, int n2nBase) throws IOException {
        target = target.toAbsolutePath().normalize();
        host = host.toRealPath();
        plugins = plugins.toRealPath();
        if (httpBase < 1024 || n2nBase < 1024 || httpBase > 65531 || n2nBase > 65531
                || Math.abs(httpBase - n2nBase) < 5) {
            throw new IllegalArgumentException("Require disjoint unprivileged five-port ranges");
        }
        var distribution = JSON.readTree(host.resolve("yano-distribution-v1.json").toFile());
        if (!"core-jvm".equals(distribution.path("distribution").asText())
                || !expectedHostVersion.equals(distribution.path("version").asText())
                || !Files.isRegularFile(host.resolve("yano.jar")) || !Files.isDirectory(plugins)) {
            throw new IllegalArgumentException("Exact ordinary JVM host and plugin directory required");
        }
        String allowList = pluginClosure(plugins, "org.yanoproject.x.stdlib");
        // Atomic create refuses existing deployments, including symlinks. Fail closed on non-POSIX storage.
        Files.createDirectory(target, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        SecureRandom random = new SecureRandom();
        List<String> validatorSeeds = secrets(random, 5);
        List<String> reporterSeeds = secrets(random, 5);
        List<String> validators = validatorSeeds.stream().map(ObservationQualificationConfig::publicKey).toList();
        List<String> reporters = reporterSeeds.stream().map(ObservationQualificationConfig::publicKey).toList();
        String token = secrets(random, 1).getFirst();
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                HEX.parseHex(secrets(random, 1).getFirst()));
        var parameters = AdaUsdReferenceStateMachine.parameters();
        var definition = definition(reporters);
        var profile = new ObservationProfileV1(1, true, 1, 2, 1, 1, 2, 1, 1,
                List.of(definition), 100, 100, 100, 10, 100, 15, 3,
                4096, 1024, 16_384, 10, 32_768, 1, 20, 3);
        Map<String, String> shared = new LinkedHashMap<>(identity.settings());
        shared.put("chain-id", CHAIN_ID);
        shared.put("members", String.join(",", validators));
        shared.put("threshold", "4");
        shared.put("consensus.max-byzantine-members", "1");
        shared.put("consensus.round-timeout-ms", "5000");
        shared.put("sequencer.mode", "rotating");
        shared.put("membership.mode", "governed");
        shared.put("block.interval-ms", "500");
        shared.put("l1.stability-depth", "10");
        shared.put("state-machine", AdaUsdReferenceStateMachine.ID);
        shared.put("anchor.enabled", "false");
        shared.put("effects.enabled", "false");
        shared.put("observations.profile-cbor-hex", HEX.formatHex(profile.encode()));
        shared.put("observations.reporters.ada-usd", String.join(",", reporters));
        shared.put("observations.policy.ada-usd", HEX.formatHex(parameters.encode()));
        for (int index = 0; index < 5; index++) {
            Path node = Files.createDirectory(target.resolve("node-" + index));
            Map<String, String> chain = new LinkedHashMap<>(shared);
            chain.put("signing-key", validatorSeeds.get(index));
            int ownPort = n2nBase + index;
            chain.put("peers", IntStream.range(n2nBase, n2nBase + 5).filter(port -> port != ownPort)
                    .mapToObj(port -> "127.0.0.1:" + port).collect(Collectors.joining(",")));
            var parsed = AppChainConfigParser.parse(chain);
            if (!HEX.formatHex(profile.encode()).equals(parsed.pluginSettings()
                    .get("observations.profile-cbor-hex"))
                    || !"1".equals(parsed.pluginSettings().get("consensus.max-byzantine-members"))) {
                throw new IllegalStateException("Host parser dropped required observation/consensus settings");
            }
            Properties properties = new Properties();
            chain.forEach((key, value) -> properties.setProperty("yano.app-chain.chains[0]." + key, value));
            properties.setProperty("quarkus.http.host", "127.0.0.1");
            properties.setProperty("quarkus.http.port", Integer.toString(httpBase + index));
            properties.setProperty("yano.server.port", Integer.toString(ownPort));
            properties.setProperty("yano.storage.path", node.resolve("chainstate").toString());
            properties.setProperty("yano.app-chain.storage.path", node.resolve("appchain-chainstate").toString());
            properties.setProperty("yano.app-chain.enabled", "true");
            properties.setProperty("yano.app-chain.api.keys", token);
            properties.setProperty("yano.app-chain.api.auth.enabled", "true");
            properties.setProperty("yano.plugins.directory", plugins.toString());
            properties.setProperty("yano.plugins.allow-list", allowList);
            properties.setProperty("yano.relay.connection.source-port-reuse", "false");
            properties.setProperty("yano.relay.connection.max-connections-per-ip", "100");
            properties.setProperty("yano.network", "preprod");
            properties.setProperty("yano.remote.host", "preprod-node.world.dev.cardano.org");
            properties.setProperty("yano.remote.port", "30000");
            properties.setProperty("yano.remote.protocol-magic", "1");
            for (String era : List.of("shelley", "byron", "alonzo", "conway")) {
                properties.setProperty("yano.genesis." + era + "-genesis-file",
                        host.resolve("config/network/preprod/" + era + "-genesis.json").toString());
            }
            properties.setProperty("yano.genesis.protocol-parameters-file",
                    host.resolve("config/network/preprod/protocol-param.json").toString());
            writeProperties(node.resolve("node.properties"), properties);
        }
        Properties privateReporter = new Properties();
        privateReporter.setProperty("api-key", token);
        for (int index = 0; index < 5; index++) {
            privateReporter.setProperty("reporter." + index, reporterSeeds.get(index));
        }
        writeProperties(target.resolve("reporters.private.properties"), privateReporter);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("purpose", "synthetic-source non-spending Preprod observation qualification");
        manifest.put("hostVersion", expectedHostVersion);
        manifest.put("hostDirectory", host.toString());
        manifest.put("pluginDirectory", plugins.toString());
        manifest.put("pluginAllowList", allowList);
        manifest.put("httpBase", httpBase);
        manifest.put("n2nBase", n2nBase);
        manifest.put("validators", validators);
        manifest.put("reporters", reporters);
        manifest.put("chainSettings", shared);
        manifest.put("effectiveGenesisId", HEX.formatHex(effectiveIdentity(shared).genesisId()));
        manifest.put("observationProfileDigest", HEX.formatHex(profile.digest()));
        var parsed = AppChainConfigParser.parse(shared);
        manifest.put("consensusProfileDigest", HEX.formatHex(AppChainConsensusProfileCommitment.digest(
                AppChainEffectsConfig.from(parsed).consensusProfile(parsed))));
        Files.writeString(target.resolve("qualification.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest), StandardOpenOption.CREATE_NEW);
    }

    private static ObservationDefinition definition(List<String> reporters) {
        var parameters = AdaUsdReferenceStateMachine.parameters();
        return new ObservationDefinition(1, "ada-usd", 1, digest("pair-utf8-v1"), digest("fixed-point-v1"),
                digest("fixed-point-v1"), digest("source-id-v1"), ObservationReporterMode.EXTERNAL_REPORTERS,
                ObservationHashes.reporterSetDigest(reporters.stream().map(HEX::parseHex).toList()),
                1, 4, 3, true, "external-reporters-v1", parameters.sourceSetDigest(), "fixed-point-v1",
                "external-reporter-claim-v1", "complete-source-median-v1", parameters.digest(), digest("round-v2"),
                "pinned-groups-v1", "round-anchor-v1", "inline-v1", 1, 128, 18, 0, 15, 3);
    }

    static StateCommitmentIdentity effectiveIdentity(Map<String, String> settings) {
        if (Boolean.parseBoolean(settings.getOrDefault("capabilities.authenticated-snapshots.enabled", "false"))) {
            throw new IllegalArgumentException("Qualification fixture does not select authenticated snapshots");
        }
        var config = AppChainConfigParser.parse(settings);
        int maxMessages = AppChainEffectsConfig.from(config).consensusProfile(config).maxBlockMessages();
        var identity = StateCommitmentIdentity.fromSettings(settings).withApplicationProfile(
                FinalizedBlockMessageRootIndexedStateMachine.configuration(settings, maxMessages).digest());
        return FinalizedMessageIndexedStateMachine.configuration(settings, maxMessages)
                .map(index -> identity.withApplicationProfile(index.digest())).orElse(identity);
    }

    /** Read declarative dependencies without loading or activating any plugin implementation. */
    static String pluginClosure(Path directory, String root) throws IOException {
        Map<String, List<String>> dependencies = new TreeMap<>();
        List<Path> jars;
        try (var entries = Files.list(directory)) {
            jars = entries.filter(path -> path.getFileName().toString().endsWith(".jar")).limit(65).toList();
        }
        if (jars.size() > 64) throw new IllegalArgumentException("Qualification plugin directory exceeds 64 JARs");
        for (Path path : jars) {
            try (JarFile jar = new JarFile(path.toFile())) {
                var manifests = jar.stream().filter(entry -> entry.getName().startsWith("META-INF/yano/plugins/")
                        && entry.getName().endsWith(".json")).limit(65).toList();
                if (manifests.size() > 64) throw new IllegalArgumentException("Too many plugin manifests");
                for (var entry : manifests) {
                    byte[] encoded;
                    try (var input = jar.getInputStream(entry)) { encoded = input.readNBytes(65_537); }
                    if (encoded.length > 65_536) throw new IllegalArgumentException("Oversized plugin manifest");
                    var metadata = JSON.readTree(encoded);
                    String id = metadata.path("id").asText();
                    if (!id.matches("[A-Za-z0-9_.-]{1,256}")) {
                        throw new IllegalArgumentException("Invalid plugin identity");
                    }
                    List<String> required = new ArrayList<>();
                    for (var dependency : metadata.path("dependencies")) {
                        required.add(dependency.path("id").asText());
                    }
                    if (dependencies.putIfAbsent(id, required) != null) {
                        throw new IllegalArgumentException("Duplicate plugin identity: " + id);
                    }
                }
            }
        }
        var selected = new TreeSet<String>();
        var pending = new ArrayDeque<String>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!selected.add(id)) continue;
            var required = dependencies.get(id);
            if (required == null) throw new IllegalArgumentException("Missing required plugin: " + id);
            pending.addAll(required);
        }
        return String.join(",", selected);
    }

    private static byte[] digest(String text) {
        return ObservationHashes.digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> secrets(SecureRandom random, int count) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] value = new byte[32];
            random.nextBytes(value);
            values.add(HEX.formatHex(value));
        }
        return values;
    }

    private static String publicKey(String seed) {
        return HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(HEX.parseHex(seed)));
    }

    private static void writeProperties(Path file, Properties values) throws IOException {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
            values.store(writer, "Private qualification configuration; never publish signing material");
        }
    }
}
