package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Local preparation and deterministic, additive configuration planning. Never deletes chain state. */
final class AppChainProjectOperations {
    record ChainChange(String chainId, String action) { }
    record Plan(String status, String digest, String beforeDigest, String blueprintDigest,
                List<ChainChange> chains, List<String> blockers, List<String> addedArtifacts,
                String activation) { }

    private final AppChainPropertyRegistry properties;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    AppChainProjectOperations(AppChainPropertyRegistry properties) {
        this.properties = properties;
    }

    private AppChainProjectCatalog catalog(Path project) throws IOException {
        var loader = new AppChainComponentCatalogLoader();
        var external = loader.loadProject(project);
        return new AppChainProjectCatalog(loader.extendRegistry(properties, external), external);
    }

    private AppChainProjectRenderer renderer(Path project) throws IOException {
        var catalog = catalog(project);
        var external = new AppChainComponentCatalogLoader().loadProject(project);
        var registry = new AppChainComponentCatalogLoader().extendRegistry(properties, external);
        return new AppChainProjectRenderer(catalog, new AppChainProjectResolver(registry, catalog));
    }

    Plan plan(Path project) throws IOException {
        project = project.toRealPath();
        var renderer = renderer(project);
        var prior = renderer.readLock(project);
        Path active = project.resolve(".deployment/active.lock");
        if (Files.exists(active) && !java.util.Arrays.equals(Files.readAllBytes(active),
                Files.readAllBytes(project.resolve("appchain.lock")))) {
            throw new IOException("The current lock differs from the recorded deployment; reconcile it first");
        }
        renderer.verifyPriorOutputs(project);
        var blueprint = renderer.readBlueprint(project);
        var catalog = catalog(project);
        var external = new AppChainComponentCatalogLoader().loadProject(project);
        var registry = new AppChainComponentCatalogLoader().extendRegistry(properties, external);
        var next = new AppChainProjectResolver(registry, catalog).resolve(blueprint);
        List<String> blockers = new ArrayList<>();
        if (!prior.yanoVersion().equals(blueprint.spec().yanoVersion())) blockers.add("Release pin changed");
        if (!prior.network().equals(blueprint.spec().network())) blockers.add("L1 network changed");
        if (!prior.runtime().equals(blueprint.spec().runtime().type())) blockers.add("Runtime changed");
        if (!prior.deployment().equals(blueprint.spec().deployment().target())) {
            blockers.add("Deployment target changed");
        }
        if (!prior.catalogDigests().equals(catalog.digests())) blockers.add("Catalog revision changed");
        var oldChains = chainValues(prior.consensusValues());
        var newChains = chainValues(next.consensusProperties());
        List<ChainChange> changes = new ArrayList<>();
        var ids = new TreeSet<>(oldChains.keySet());
        ids.addAll(newChains.keySet());
        for (String id : ids) {
            String action = !oldChains.containsKey(id) ? "ADD"
                    : !newChains.containsKey(id) ? "REMOVE"
                    : oldChains.get(id).equals(newChains.get(id)) ? "UNCHANGED" : "CHANGE";
            changes.add(new ChainChange(id, action));
            if ("REMOVE".equals(action) || "CHANGE".equals(action)) {
                blockers.add(id + ": existing consensus or genesis changes require a separate migration");
            }
        }
        if (oldChains.keySet().stream().allMatch(newChains::containsKey)) {
            int members = blueprint.spec().chains().getFirst().topology().members();
            var layout = "docker-compose".equals(blueprint.spec().deployment().target())
                    ? AppChainProjectRenderer.NodeLayout.COMPOSE : AppChainProjectRenderer.NodeLayout.HOST;
            for (int node = 0; node < members; node++) {
                String name = "config/nodes/node" + node + ".yaml";
                try {
                    verifyNodeDocument(Files.readString(project.resolve(name)),
                            AppChainProjectRenderer.nodeYaml(next, node, members, layout,
                                    "candidate", catalog.digests().get("releaseIndex")),
                            prior.consensusValues(), next.consensusProperties(), name);
                } catch (IOException changed) {
                    blockers.add(changed.getMessage());
                }
            }
        }
        if (!next.artifacts().containsAll(prior.artifacts())) blockers.add("Existing artifact removed");
        List<String> added = next.artifacts().stream().filter(id -> !prior.artifacts().contains(id)).toList();
        if (added.stream().anyMatch(id -> !"BUNDLED".equals(catalog.artifact(id).availability()))) {
            blockers.add("Install and qualify optional/custom artifacts separately before an additive apply");
        }
        String before = AppChainProjectCatalog.sha256(Files.readAllBytes(project.resolve("appchain.lock")));
        String candidate = AppChainProjectCatalog.sha256(Files.readAllBytes(project.resolve("appchain.yaml")));
        String digest = AppChainProjectCatalog.sha256("yano-project-plan-v1\n" + before + "\n" + candidate);
        return new Plan(blockers.isEmpty() ? "PLAN_READY" : "PLAN_BLOCKED", digest, before, candidate,
                List.copyOf(changes), List.copyOf(blockers), added, "STOP_RENDER_RESTART");
    }

    record Transaction(Plan plan, Map<String, String> before, Map<String, String> after) { }

    void apply(Path project, String expectedDigest) throws IOException {
        if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("apply requires --plan followed by the reviewed plan digest");
        }
        Path root = project.toRealPath();
        Path operations = root.resolve(".deployment");
        safeDirectory(operations);
        try (var channel = java.nio.channels.FileChannel.open(operations.resolve("operation.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lease = channel.tryLock()) {
            if (lease == null) throw new IOException("Another deployment operation is running");
            requireStopped(root);
            var renderer = renderer(root);
            var blueprint = renderer.readBlueprint(root);
            if (!"host".equals(blueprint.spec().deployment().target())
                    || blueprint.spec().chains().stream().anyMatch(chain -> chain.topology().nodeHosts() != null
                    && !chain.topology().nodeHosts().isEmpty())) {
                throw new IOException("Automatic additive apply currently supports same-machine host projects; "
                        + "VM operators use the reviewed plan in a coordinated deployment");
            }
            Path pending = operations.resolve("pending.json");
            Path staged = operations.resolve("revisions").resolve(expectedDigest);
            Transaction transaction;
            if (!Files.exists(pending) && Files.isRegularFile(staged.resolve("completed.json"))) {
                var completed = json.readValue(Files.readAllBytes(staged.resolve("completed.json")), Plan.class);
                if (!expectedDigest.equals(completed.digest()) || !completed.blueprintDigest().equals(
                        AppChainProjectCatalog.sha256(Files.readAllBytes(root.resolve("appchain.yaml"))))
                        || !java.util.Arrays.equals(Files.readAllBytes(root.resolve("appchain.lock")),
                        Files.readAllBytes(staged.resolve("appchain.lock")))) {
                    throw new IOException("Completed revision differs from the current project; create a new plan");
                }
                renderer.validate(root);
                return;
            }
            if (Files.exists(pending)) {
                transaction = json.readValue(Files.readAllBytes(pending), Transaction.class);
                if (!transaction.plan().digest().equals(expectedDigest)) {
                    throw new IOException("Resume the pending operation with its original plan digest");
                }
            } else {
                Plan plan = plan(root);
                if (!plan.digest().equals(expectedDigest)) throw new IOException("Plan is stale; run plan again");
                if (!plan.blockers().isEmpty()) throw new IOException("Plan is blocked: " + plan.blockers());
                if (Files.exists(staged)) {
                    throw new IOException("A previous staging attempt exists; inspect the named revision: " + staged);
                }
                safeDirectory(staged);
                Files.copy(root.resolve("appchain.yaml"), staged.resolve("appchain.yaml"));
                for (var reference : blueprint.spec().componentCatalogs() == null
                        ? List.<AppChainProjectModel.ComponentCatalogRef>of() : blueprint.spec().componentCatalogs()) {
                    Path target = AppChainProjectRenderer.resolveGenerated(staged, reference.path());
                    safeDirectory(target.getParent());
                    Files.copy(AppChainProjectRenderer.resolveGenerated(root, reference.path()), target);
                }
                var candidate = renderer.renderPrepared(staged);
                var before = new TreeMap<>(renderer.readLock(root).generatedFiles());
                before.put("appchain.lock", plan.beforeDigest());
                var after = new TreeMap<>(candidate.generatedFiles());
                after.put("appchain.lock", AppChainProjectCatalog.sha256(
                        Files.readAllBytes(staged.resolve("appchain.lock"))));
                // Check all node-local changes too. Adding a chain must not move stores, ports, or old peers.
                verifyNodeChanges(root, staged, renderer.readLock(root), candidate);
                transaction = new Transaction(plan, Map.copyOf(before), Map.copyOf(after));
                atomicWrite(operations.resolve("previous.lock"),
                        Files.readAllBytes(root.resolve("appchain.lock")), false);
                atomicWrite(pending, json.writeValueAsBytes(transaction), false);
            }
            if (!transaction.plan().blueprintDigest().equals(
                    AppChainProjectCatalog.sha256(Files.readAllBytes(root.resolve("appchain.yaml"))))) {
                throw new IOException("Blueprint changed during pending apply; restore the reviewed input to resume");
            }
            // A interrupted write may contain either the old bytes or the staged bytes, never a third value.
            for (var entry : transaction.after().entrySet()) {
                Path source = AppChainProjectRenderer.resolveGenerated(staged, entry.getKey());
                byte[] bytes = Files.readAllBytes(source);
                if (!entry.getValue().equals(AppChainProjectCatalog.sha256(bytes))) {
                    throw new IOException("Staged revision has changed; refusing activation");
                }
                Path target = AppChainProjectRenderer.resolveGenerated(root, entry.getKey());
                if (Files.exists(target)) {
                    String actual = AppChainProjectCatalog.sha256(Files.readAllBytes(target));
                    if (!actual.equals(entry.getValue()) && !actual.equals(transaction.before().get(entry.getKey()))) {
                        throw new IOException("Unreviewed edit during apply: " + entry.getKey());
                    }
                } else if (transaction.before().containsKey(entry.getKey())) {
                    throw new IOException("Previously generated file disappeared during apply: " + entry.getKey());
                }
            }
            // The lock is installed last; startup also refuses any pending journal.
            var names = new ArrayList<>(transaction.after().keySet().stream().sorted().toList());
            names.remove("appchain.lock");
            names.add("appchain.lock");
            for (String name : names) {
                Path target = AppChainProjectRenderer.resolveGenerated(root, name);
                atomicWrite(target, Files.readAllBytes(AppChainProjectRenderer.resolveGenerated(staged, name)), false);
                if (name.startsWith("scripts/") || name.equals("ci/verify")) {
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            }
            renderer.validate(root);
            atomicWrite(operations.resolve("active.lock"), Files.readAllBytes(root.resolve("appchain.lock")), false);
            atomicWrite(staged.resolve("completed.json"), json.writeValueAsBytes(transaction.plan()), false);
            Files.delete(pending);
        }
    }

    private void verifyNodeChanges(Path root, Path staged, AppChainProjectModel.Lock before,
                                   AppChainProjectModel.Lock after) throws IOException {
        for (String name : before.generatedFiles().keySet()) {
            if (!name.startsWith("config/nodes/")) continue;
            verifyNodeDocument(Files.readString(root.resolve(name)), Files.readString(staged.resolve(name)),
                    before.consensusValues(), after.consensusValues(), name);
        }
    }

    private void verifyNodeDocument(String beforeDocument, String afterDocument, Map<String, String> before,
                                    Map<String, String> after, String name) throws IOException {
        var oldNode = yaml.readTree(beforeDocument);
        var newNode = yaml.readTree(afterDocument);
        var oldApp = (com.fasterxml.jackson.databind.node.ObjectNode) oldNode.path("yano").path("app-chain");
        var newApp = (com.fasterxml.jackson.databind.node.ObjectNode) newNode.path("yano").path("app-chain");
        var oldChains = oldApp.remove("chains");
        var newChains = newApp.remove("chains");
        oldApp.remove("dx");
        newApp.remove("dx");
        if (!oldNode.equals(newNode)) throw new IOException("Node placement or local settings changed: " + name);
        for (var entry : before.entrySet()) {
            if (!entry.getKey().matches("yano\\.app-chain\\.chains\\[\\d+].chain-id")) continue;
            String nextKey = after.entrySet().stream()
                    .filter(value -> value.getKey().endsWith(".chain-id") && value.getValue().equals(entry.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            int oldIndex = Integer.parseInt(entry.getKey().split("\\[")[1].split("]")[0]);
            int newIndex = Integer.parseInt(nextKey.split("\\[")[1].split("]")[0]);
            if (!oldChains.get(oldIndex).equals(newChains.get(newIndex))) {
                throw new IOException("Existing chain node settings changed: " + entry.getValue());
            }
        }
    }

    void startCheck(Path project) throws IOException {
        startCheck(project, false);
    }

    void startCheck(Path project, boolean reserveLauncher) throws IOException {
        Path root = project.toRealPath();
        Path operations = root.resolve(".deployment");
        safeDirectory(operations);
        try (var channel = java.nio.channels.FileChannel.open(operations.resolve("operation.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lease = channel.tryLock()) {
            if (lease == null) throw new IOException("Deployment operation in progress");
            if (Files.exists(operations.resolve("pending.json"))) throw new IOException("Resume pending apply first");
            var validated = renderer(root).validate(root);
            if (validated.lock().acknowledgements().contains("PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START")) {
                throw new IOException("Public member identities are missing; run appchain prepare for local devnet");
            }
            Path active = operations.resolve("active.lock");
            byte[] lock = Files.readAllBytes(root.resolve("appchain.lock"));
            if (Files.exists(active) && !java.util.Arrays.equals(Files.readAllBytes(active), lock)) {
                throw new IOException("Deployment lock changed; apply the reviewed plan before starting");
            }
            if (!Files.exists(active)) atomicWrite(active, lock, false);
            if (reserveLauncher) {
                long launcher = ProcessHandle.current().parent().orElseThrow().pid();
                atomicWrite(root.resolve("run/launch" + launcher + ".pid"),
                        Long.toString(launcher).getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            }
        }
    }

    private static void requireStopped(Path root) throws IOException {
        Path run = root.resolve("run");
        if (!Files.exists(run)) return;
        try (var records = Files.list(run)) {
            for (Path record : records.filter(path -> path.getFileName().toString()
                    .matches("(?:node|launch)[0-9]+\\.pid")).toList()) {
                long pid;
                try { pid = Long.parseLong(Files.readString(record).trim()); }
                catch (NumberFormatException failure) { throw new IOException("Invalid node process record", failure); }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    throw new IOException("Stop all project nodes with scripts/stop before applying");
                }
            }
        }
    }

    static Map<String, Map<String, String>> chainValues(Map<String, String> values) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        values.forEach((key, id) -> {
            if (!key.matches("yano\\.app-chain\\.chains\\[\\d+].chain-id")) return;
            String prefix = key.substring(0, key.length() - "chain-id".length());
            Map<String, String> chain = new TreeMap<>();
            values.forEach((property, value) -> {
                if (property.startsWith(prefix)) chain.put(property.substring(prefix.length()), value);
            });
            if (result.put(id, Map.copyOf(chain)) != null) {
                throw new IllegalArgumentException("Duplicate chain ID in lock");
            }
        });
        if (result.isEmpty()) throw new IllegalArgumentException("Lock has no chain identities");
        return result;
    }

    void addChain(Path project, String chainId, String recipe, List<String> capabilities,
                  Map<String, String> answers) throws IOException {
        project = project.toRealPath();
        var renderer = renderer(project);
        renderer.validate(project);
        var blueprint = renderer.readBlueprint(project);
        var spec = blueprint.spec();
        if (spec.chains().stream().anyMatch(chain -> chain.chainId().equals(chainId))) {
            throw new IllegalArgumentException("Chain ID already exists; adding a chain never replaces one");
        }
        var chains = new ArrayList<>(spec.chains());
        var map = "authenticated-map".equals(recipe) ? new AppChainProjectModel.AuthenticatedMapIntent(
                "mpf-blake2b256-v1", "00".repeat(32), 128, 65_536,
                List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                        "records", "open", false, 128, 65_536, "opaque", null)), List.of()) : null;
        chains.add(new AppChainProjectModel.ChainIntent(chainId, recipe, capabilities, answers,
                spec.chains().getFirst().topology(), map));
        var proposed = new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(), spec.deployment(),
                        List.copyOf(chains), spec.componentCatalogs(), spec.acknowledgements()));
        var external = new AppChainComponentCatalogLoader().loadProject(project);
        var registry = new AppChainComponentCatalogLoader().extendRegistry(properties, external);
        new AppChainProjectResolver(registry, catalog(project)).resolve(proposed);
        atomicWrite(project.resolve("appchain.yaml"), yaml.writeValueAsBytes(proposed), false);
    }

    void prepare(Path project) throws IOException {
        project = project.toRealPath();
        var renderer = renderer(project);
        var blueprint = renderer.readBlueprint(project);
        var spec = blueprint.spec();
        if (!"devnet".equals(spec.network()) || !"host".equals(spec.deployment().target())
                || !"jvm".equals(spec.runtime().type())
                || spec.chains().stream().anyMatch(chain -> chain.topology().nodeHosts() != null
                && !chain.topology().nodeHosts().isEmpty())) {
            throw new IllegalArgumentException("prepare generates local JVM devnet identities only; "
                    + "operators supply their own member keys and secret files");
        }
        if (Files.exists(project.resolve("appchain.lock"))) renderer.verifyPriorOutputs(project);
        var external = new AppChainComponentCatalogLoader().loadProject(project);
        var registry = new AppChainComponentCatalogLoader().extendRegistry(properties, external);
        new AppChainProjectResolver(registry, catalog(project)).resolve(blueprint);
        int members = spec.chains().getFirst().topology().members();
        boolean pinned = spec.chains().stream().allMatch(chain -> chain.topology().memberKeys() != null
                && chain.topology().memberKeys().size() == members);
        if (!pinned && (Files.exists(project.resolve("data")) || Files.exists(project.resolve("runtime")))) {
            // An empty generated runtime directory is fine; an existing L1 genesis is not.
            if (Files.exists(project.resolve("data"))
                    || Files.exists(project.resolve("runtime/shelley-genesis.json"))) {
                throw new IOException("Cannot generate member identities beside retained runtime state");
            }
        }
        if (members < 1 || members > 32) throw new IllegalArgumentException("Members must be from 1 to 32");
        var secrets = project.resolve("secrets");
        safeDirectory(secrets);
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rwx------"));
        List<String> publicKeys = new ArrayList<>();
        String apiKey = null;
        var random = new SecureRandom();
        for (int node = 0; node < members; node++) {
            Path file = secrets.resolve("node" + node + ".env");
            String seed;
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                var values = readPrivateEnvironment(file);
                seed = values.get("YANO_APPCHAIN_SIGNING_KEY");
                if (apiKey == null) apiKey = values.get("YANO_APPCHAIN_API_KEYS");
                if (!apiKey.equals(values.get("YANO_APPCHAIN_API_KEYS"))) {
                    throw new IOException("Prepared nodes must use the same local API key");
                }
            } else {
                if (pinned) throw new IOException("Pinned identities require the original private member files");
                seed = HexFormat.of().formatHex(random.generateSeed(32));
                if (apiKey == null) apiKey = HexFormat.of().formatHex(random.generateSeed(32));
                atomicWrite(file, ("YANO_APPCHAIN_SIGNING_KEY=" + seed + "\nYANO_APPCHAIN_API_KEYS="
                        + apiKey + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
            }
            publicKeys.add(HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(
                    HexFormat.of().parseHex(seed))));
        }
        List<AppChainProjectModel.ChainIntent> chains = new ArrayList<>();
        for (var chain : spec.chains()) {
            var topology = chain.topology();
            if (topology.memberKeys() != null && !topology.memberKeys().isEmpty()
                    && !topology.memberKeys().equals(publicKeys)) {
                throw new IOException("Private member files do not match pinned public identities");
            }
            chains.add(new AppChainProjectModel.ChainIntent(chain.chainId(), chain.recipe(), chain.capabilities(),
                    chain.answers(), new AppChainProjectModel.Topology(members, publicKeys, List.of(),
                    topology.finality(), topology.sequencing(), topology.membership(), topology.httpPortBase(),
                    topology.serverPortBase()), chain.authenticatedMap()));
        }
        var prepared = new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(), spec.deployment(),
                        List.copyOf(chains), spec.componentCatalogs(), spec.acknowledgements()));
        // Validate the complete proposal before changing public input.
        var catalog = catalog(project);
        new AppChainProjectResolver(registry, catalog).resolve(prepared);
        if (!pinned) atomicWrite(project.resolve("appchain.yaml"), yaml.writeValueAsBytes(prepared), false);
        if (pinned) renderer.render(project);
        else renderer.renderPrepared(project);
    }

    private static Map<String, String> readPrivateEnvironment(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024) {
            throw new IOException("Member environment must be a small regular file");
        }
        var permissions = Files.getPosixFilePermissions(file);
        if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                || permission.name().startsWith("OTHERS_"))) {
            throw new IOException("Member environment requires owner-only permissions");
        }
        Map<String, String> values = new TreeMap<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.matches("YANO_APPCHAIN_(SIGNING_KEY|API_KEYS)=[0-9a-f]{64}")) {
                throw new IOException("prepare only reuses its own two-field member environment files");
            }
            int equals = line.indexOf('=');
            if (values.put(line.substring(0, equals), line.substring(equals + 1)) != null) {
                throw new IOException("Duplicate field in member environment");
            }
        }
        if (values.size() != 2) throw new IOException("Incomplete prepared member environment");
        return values;
    }

    static void safeDirectory(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor) ) {
                throw new IOException("Operation path must not traverse a symbolic link");
            }
        }
        Files.createDirectories(absolute);
    }

    static void atomicWrite(Path path, byte[] bytes, boolean privateFile) throws IOException {
        safeDirectory(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Operation target must not be a symbolic link");
        Path temporary = Files.createTempFile(path.getParent(), ".yano-write-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            if (privateFile) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
