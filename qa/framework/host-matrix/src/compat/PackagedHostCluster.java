package compat;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.api.appchain.AppChainGateway;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.evidence.EvidenceVerifier;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.config.PluginsOptions;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.assembly.YanoAssembly;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** QA-only frozen client of the public embedding API; never a production config parser or launcher. */
public final class PackagedHostCluster {
    private final Path directory;
    private PackagedHostCluster(Path directory) { this.directory = directory; }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("passed", false);
        String profile = args.length > 1 ? args[1] : "standalone";
        require(Set.of("standalone", "cardano-construction").contains(profile), "known qualification profile");
        report.put("profile", profile);
        boolean cardano = profile.equals("cardano-construction");
        Throwable failure = null;
        try {
            Map<String, String> origins = new LinkedHashMap<>();
            List<String> originClasses = new ArrayList<>(List.of("org.yanoproject.runtime.assembly.YanoAssembly",
                    "org.yanoproject.api.host.HostApi", "org.yanoproject.api.config.YanoConfig",
                    "org.yanoproject.runtime.plugins.PluginRuntimeEnvironment",
                    "org.yanoproject.runtime.appchain.AppLedgerRuntime"));
            if (cardano) {
                originClasses.addAll(List.of("org.yanoproject.x.framework.cardano.CardanoAppLedgerPlugin",
                        "org.yanoproject.x.framework.cardano.CardanoRuntimeServices",
                        "org.yanoproject.runtime.appchain.AppChainManager",
                        "org.yanoproject.runtime.appchain.AppChainSubsystem"));
            } else {
                originClasses.add("org.yanoproject.x.framework.standalone.StandaloneAppLedgerPlugin");
            }
            for (String name : originClasses) {
                origins.put(name, Class.forName(name).getProtectionDomain().getCodeSource().getLocation().toString());
            }
            report.put("loadedOrigins", origins);
            List<String> absentClasses = cardano
                    ? List.of("org.yanoproject.x.framework.standalone.StandaloneAppLedgerPlugin")
                    : List.of("org.yanoproject.runtime.appchain.AppChainSubsystem",
                            "org.yanoproject.x.framework.cardano.CardanoAppLedgerPlugin");
            for (String name : absentClasses) {
                try { Class.forName(name); throw new AssertionError("Unexpected activation class: " + name); }
                catch (ClassNotFoundException expected) { }
            }
            PackagedHostCluster fixture = new PackagedHostCluster(directory);
            if (cardano) {
                fixture.constructCardano();
                report.put("cardanoRuntimeServicesLinked", true);
                report.put("cardanoAppLedgerAssembled", true);
                report.put("cardanoManagerLifecycleHooks", true);
                report.put("networkStarted", false);
                report.put("persistentStoresCreated", false);
            } else {
                fixture.run();
                report.put("nodes", 3);
                report.put("transport", "real-tcp-100-103");
                report.put("optionalPluginsEnabled", false);
                report.put("lateJoin", true);
                report.put("restartAndReopen", true);
                report.put("cardanoStateCreated", false);
            }
            report.put("passed", true);
        } catch (Throwable problem) {
            failure = problem;
            report.put("error", problem.toString());
        } finally {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("results.json").toFile(), report);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }

    private void constructCardano() {
        Path l1 = directory.resolve("cardano-chainstate");
        Path app = directory.resolve("cardano-appchain");
        YanoConfig config = YanoConfig.serverOnly(0).toBuilder()
                .enableServer(false).useRocksDB(false).rocksDBPath(l1.toString())
                .appChainStoragePath(app.toString()).build();
        Map<String, Object> globals = settings(0, List.of(1, 2));
        globals.put("yano.app-chain.peers", "");
        globals.put("yano.app-chain.anchor.enabled", false);
        PluginsOptions plugins = new PluginsOptions(true, false, Set.of(), Set.of(), Map.of());
        // Construction wires the real Cardano callback record and the optional
        // framework manager. Never start networking, ledger stores, or anchoring.
        try (Yano node = YanoAssembly.relay(config)
                .runtimeOptions(new RuntimeOptions(null, plugins, globals))
                .pluginLoader(PluginLoaderHandle.packagedClasspath(PackagedHostCluster.class.getClassLoader()))
                .build()) {
            require(node.chain() != null && node.ledger() != null && node.txGateway() != null
                    && node.txEvaluationGateway() != null, "Cardano public handles");
            require(node.appChain().orElseThrow().getClass().getName()
                    .equals("org.yanoproject.runtime.appchain.AppChainSubsystem"), "Cardano chain assembled");
            long managers = node.kernel().orElseThrow().subsystems().stream().filter(subsystem ->
                    subsystem.name().equals("app-chain")).count();
            require(managers == 1, "exactly one Cardano manager lifecycle hook");
        }
        require(!Files.exists(l1) && !Files.exists(app), "construction creates no persistent stores");
    }

    private void run() throws Exception {
        List<ServerSocket> reservations = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                ServerSocket socket = new ServerSocket(0);
                reservations.add(socket);
                ports.add(socket.getLocalPort());
            }
        } finally {
            for (ServerSocket reservation : reservations) reservation.close();
        }
        List<Yano> nodes = new ArrayList<>();
        Throwable failure = null;
        try {
            nodes.add(node(0, ports));
            nodes.add(node(1, ports));
            nodes.forEach(Yano::start);
            AppChainGateway proposer = nodes.getFirst().appChain().orElseThrow();
            AppChainGateway follower = nodes.get(1).appChain().orElseThrow();
            byte[] id = HexFormat.of().parseHex(follower.submit("orders", new byte[]{1, 2, 3}));
            await(() -> proposer.messageHeight(id).isPresent() && follower.messageHeight(id).isPresent());
            long height = proposer.messageHeight(id).orElseThrow();
            nodes.add(node(2, ports));
            nodes.get(2).start();
            await(() -> nodes.get(2).appChain().orElseThrow().tipHeight() >= height);
            Set<String> members = IntStream.range(0, 3).mapToObj(index ->
                    HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(index))))
                    .collect(Collectors.toSet());
            for (Yano node : nodes) {
                AppChainGateway chain = node.appChain().orElseThrow();
                var block = chain.block(height).orElseThrow();
                var expected = proposer.block(height).orElseThrow();
                require(Arrays.equals(AppBlockCodec.blockHash(block), AppBlockCodec.blockHash(expected)), "block agreement");
                require(Arrays.equals(block.stateRoot(), expected.stateRoot()), "state-root agreement");
                require(chain.finalizedCommitment(height).orElseThrow().equals(
                        proposer.finalizedCommitment(height).orElseThrow()), "commitment agreement");
                require(signatures(chain, height).equals(signatures(proposer, height)), "certificate agreement");
                require(EvidenceVerifier.verify(chain.evidence(id).orElseThrow(), "standalone-test", members, 2).valid(),
                        "independent certificate/evidence verification");
                require(block.l1Slot() == 0 && block.l1BlockHash().length == 0, "no synthetic L1 reference");
                require(!node.lifecycle().isSyncing(), "no Cardano syncing");
                require(node.kernel().orElseThrow().subsystems().stream().noneMatch(subsystem ->
                        Set.of("sync", "ledger-state", "utxo", "producer", "chain-storage").contains(subsystem.name())),
                        "no Cardano subsystem startup");
                try { node.chain().getBlock(new byte[32]); throw new AssertionError("L1 access unexpectedly available"); }
                catch (UnsupportedOperationException expectedFailure) {
                    require(expectedFailure.getMessage().contains("requires Cardano L1"), "explicit L1 unavailability");
                }
            }
            require(!Files.exists(directory.resolve("l1")), "no Cardano chainstate");
            nodes.get(2).stop();
            nodes.get(2).start();
            require(nodes.get(2).maintenanceGate().isPresent(), "maintenance gate survives restart");
            nodes.get(2).close();
            nodes.set(2, node(2, ports));
            nodes.get(2).start();
            require(nodes.get(2).appChain().orElseThrow().tipHeight() >= height, "persistent history on fresh owner");
            byte[] next = HexFormat.of().parseHex(follower.submit("orders", new byte[]{4, 5, 6}));
            await(() -> nodes.get(2).appChain().orElseThrow().messageHeight(next).isPresent());
        } catch (Throwable problem) { failure = problem; }
        finally {
            for (Yano node : nodes.reversed()) {
                try { node.close(); }
                catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }

    private Yano node(int index, List<Integer> ports) {
        YanoConfig config = YanoConfig.builder().mode(RuntimeMode.APP_LEDGER).enableServer(true)
                .serverPort(ports.get(index)).rocksDBPath(directory.resolve("l1").toString())
                .appChainStoragePath(directory.resolve("node-" + index).toString()).build();
        PluginsOptions plugins = new PluginsOptions(false, false, Set.of(), Set.of(), Map.of());
        return YanoAssembly.fromConfig(config).runtimeOptions(new RuntimeOptions(null, plugins, settings(index, ports)))
                .pluginLoader(PluginLoaderHandle.packagedClasspath(PackagedHostCluster.class.getClassLoader())).build();
    }

    private static Map<String, Object> settings(int index, List<Integer> ports) {
        Map<String, Object> settings = new LinkedHashMap<>();
        String prefix = "yano.app-chain.";
        settings.put(prefix + "enabled", true);
        settings.put(prefix + "network-magic", 424242);
        settings.put(prefix + "transport.mode", "dedicated");
        settings.put(prefix + "chain-id", "standalone-test");
        settings.put(prefix + "signing-key", HexFormat.of().formatHex(seed(index)));
        List<String> members = IntStream.range(0, 3).mapToObj(member ->
                HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(member)))).toList();
        settings.put(prefix + "members", String.join(",", members));
        settings.put(prefix + "sequencer.proposer", members.getFirst());
        settings.put(prefix + "threshold", 2);
        settings.put(prefix + "block.interval-ms", 300);
        StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, HexFormat.of().parseHex("12".repeat(32)))
                .settings().forEach((key, value) -> settings.put(prefix + key, value));
        // A/B never dial C: late C can obtain prior history only over its protocol-103 session.
        settings.put(prefix + "peers", String.join(",", ports.subList(0, 2).stream()
                .filter(port -> !port.equals(ports.get(index))).map(port -> "127.0.0.1:" + port).toList()));
        return settings;
    }

    private static Set<String> signatures(AppChainGateway chain, long height) {
        return chain.block(height).orElseThrow().cert().signatures().stream().map(signature ->
                HexFormat.of().formatHex(signature.signer()) + ":" + HexFormat.of().formatHex(signature.signature()))
                .collect(Collectors.toSet());
    }
    private static byte[] seed(int index) { byte[] seed = new byte[32]; seed[0] = (byte) (index + 1); return seed; }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        require(condition.getAsBoolean(), "bounded consensus/catch-up wait");
    }
    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
