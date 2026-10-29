package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.yanoproject.x.client.Hex;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainProjectTest {
    @TempDir
    Path temporary;

    @Test
    void embeddedDescriptorsResolveRecipesImplicationsArtifactsAndConsensusDefaults()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("evidence-ledger", "rotating", List.of()));

        assertThat(catalog.recipes()).extracting(AppChainProjectModel.Recipe::id)
                .containsExactly("declarative-composite", "audit-log", "owned-registry", "document-trail", "authenticated-map",
                        "approval-workflow",
                        "role-approval", "evidence-ledger", "eutxo-ledger",
                        "eutxo-cardano-bridge", "custom-plugin");
        assertThat(catalog.recipes()).allSatisfy(recipe -> {
            assertThat(recipe.primaryOutcome()).isNotBlank();
            assertThat(recipe.firstCommand()).isNotBlank();
            assertThat(recipe.verificationQuery()).isNotBlank();
            assertThat(recipe.acceptanceScenario()).isNotBlank();
        });
        assertThat(resolution.selectedCapabilities()).contains(
                "state:role-evidence", "sequencer:rotating", "membership:static",
                "l1:slot-feed");
        assertThat(resolution.impliedCapabilities()).containsExactly("l1:slot-feed");
        assertThat(resolution.artifacts()).contains("yano-runtime", "yano-x-stdlib",
                "yano-x-evidence-profile", "yano-x-role-workflow");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].effects.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].sequencer.mode", "rotating")
                .containsEntry("yano.app-chain.chains[0].block.max-bytes", "4194304")
                .containsEntry("yano.app-chain.chains[0].threshold", "2");
        assertThat(resolution.bootstrapRequired()).isTrue();
        assertThat(catalog.digests().values()).allMatch(value -> value.matches("[0-9a-f]{64}"));
        assertThat(catalog.digests()).containsEntry("capabilities", golden("appchain-capability-catalog.json"))
                .containsEntry("recipes", golden("appchain-recipe-catalog.json"))
                .containsEntry("firstPartyMetadata",
                        golden("appchain-first-party-metadata.json"))
                .containsEntry("releaseIndex",
                        packagedResourceDigest(
                                "appchain-release-capability-index.json"))
                .containsEntry("releaseAcceptanceIndex",
                        golden("appchain-release-acceptance-index.json"))
                .containsEntry("metadataTrustSchema",
                        golden("appchain-metadata-trust.schema.json"))
                .containsEntry("componentCatalogSchema",
                        golden("appchain-component-catalog-schema.json"))
                .containsEntry("componentCatalogSnapshotSchema",
                        golden("appchain-component-catalog-snapshot-schema.json"));
        assertThat(catalog.releaseIndex().schemaStatus()).isEqualTo("alpha");
        assertThat(catalog.releaseIndex().stabilizationDecision())
                .isEqualTo("RETAIN_V1ALPHA1");
    }

    @Test
    void publicMemberKeysArePinnedAndCapabilityConflictsFailResolution() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        List<String> keys = List.of("a".repeat(64), "b".repeat(64), "c".repeat(64));

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("audit-log", "fixed", keys));

        assertThat(resolution.bootstrapRequired()).isFalse();
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].members", String.join(",", keys))
                .containsEntry("yano.app-chain.chains[0].sequencer.proposer", keys.getFirst());

        AppChainProjectModel.Blueprint conflicting = withCapabilities(
                blueprint("audit-log", "fixed", keys), List.of("state:kv-registry"));
        assertThatThrownBy(() -> resolver.resolve(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both provide exclusive contract state-machine");
    }

    @Test
    void genericOnApprovedEffectCapabilityUsesCustomRoutingTypeAndSharedValidation()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint selected = withAnswers(withCapabilities(
                        blueprint("approval-workflow", "fixed", List.of()),
                        List.of("effects:on-approved")),
                Map.of("effectType", "com.acme.erp.create-order"));

        AppChainProjectModel.Resolution resolution = resolver.resolve(selected);

        assertThat(resolution.selectedCapabilities()).contains(
                "state:approval-workflow", "effects:on-approved");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].effects.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "on-approved-effect.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "on-approved-effect.type", "com.acme.erp.create-order")
                .containsEntry("yano.app-chain.chains[0].machines.approvals."
                        + "activations.on-approved-effect", "1");

        assertThatThrownBy(() -> resolver.resolve(withCapabilities(
                blueprint("approval-workflow", "fixed", List.of()),
                List.of("effects:on-approved"))))
                .hasMessageContaining("require non-secret answers")
                .hasMessageContaining("effectType");
    }

    @Test
    void eutxoAnswersRejectLabeledAndMalformedPublicValuesBeforeRendering() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        Map<String, String> malformedGenesis = Map.of(
                "eutxoGenesisAddress",
                "addr_test1vr8nlm7example",
                "eutxoGenesisLovelace",
                "not-a-number");
        assertThatThrownBy(() -> resolver.resolve(withAnswers(
                blueprint("eutxo-ledger", "fixed", List.of()), malformedGenesis)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eutxoGenesisLovelace")
                .hasMessageContaining("bounded decimal integer");
    }

    @Test
    void eutxoBridgeRecipeUsesProjectChainIdAndExcludesVirtualGenesis() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint blueprint = withAnswers(
                blueprint("eutxo-cardano-bridge", "fixed",
                        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                bridgeAnswers());

        AppChainProjectModel.Resolution resolution = resolver.resolve(blueprint);

        assertThat(resolution.selectedCapabilities())
                .contains("state:eutxo-ledger", "profile:eutxo-plutus-v3",
                        "bridge:cardano-federated", "l1:slot-feed")
                .doesNotContain("funding:eutxo-genesis");
        assertThat(resolution.consensusProperties())
                .containsEntry(
                        "yano.app-chain.chains[0].l1.stability-depth",
                        "2")
                .containsEntry(
                        "yano.app-chain.chains[0].observers.bridge-deposits.chain-id",
                        "product-evidence")
                .doesNotContainKeys(
                        "yano.app-chain.chains[0].machines.eutxo.genesis.address",
                        "yano.app-chain.chains[0].machines.eutxo.genesis.lovelace");

        Map<String, String> unsafeAnswers =
                new java.util.LinkedHashMap<>(bridgeAnswers());
        unsafeAnswers.put("eutxoGenesisAddress", "addr_test1vr8nlm7example");
        unsafeAnswers.put("eutxoGenesisLovelace", "100000000");
        AppChainProjectModel.Blueprint unsafe = withAnswers(withCapabilities(
                        blueprint("eutxo-ledger", "fixed", List.of()),
                        List.of("bridge:cardano-federated")),
                unsafeAnswers);
        assertThatThrownBy(() -> resolver.resolve(unsafe))
                .hasMessageContaining("Conflicting capabilities")
                .hasMessageContaining("funding:eutxo-genesis");
    }

    private static boolean composeAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "compose", "version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException unavailable) {
            return false;
        }
    }

    private record ProcessResult(int exit, String output) {
    }

    private static ProcessResult compose(Path project, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("docker", "compose"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        builder.environment().put("YANO_IMAGE", "example/yano:test");
        // Loading compose.yaml is local; a dead socket proves no daemon is reached.
        builder.environment().put("DOCKER_HOST", "unix:///nonexistent/yano-x-test/docker.sock");
        builder.environment().remove("YANO_APPCHAIN_GENESIS_TIMESTAMP");
        builder.environment().remove(L1NetworkGenesis.DEVNET_ENVIRONMENT);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    /** A recording {@code docker} stand-in whose volumes appear on {@code compose up} and go on {@code down --volumes}. */
    private record DockerStub(Path root) {
        static DockerStub create(Path root) throws IOException {
            Files.createDirectories(root.resolve("bin"));
            Files.writeString(root.resolve("source-genesis.json"),
                    "{\"systemStart\":\"2020-01-01T00:00:00Z\",\"epochLength\":432000}");
            Path docker = root.resolve("bin/docker");
            Files.writeString(docker, """
                    #!/usr/bin/env bash
                    state="$DOCKER_STUB"
                    echo "$*" >>"$state/log"
                    # Like Compose, any command that loads compose.yaml, except an uninterpolated config,
                    # needs the genesis variables the start script exports.
                    if [ "$1" = compose ] && [ "$2 $3" != "config --no-interpolate" ]; then
                      if [ -z "${YANO_APPCHAIN_GENESIS_TIMESTAMP:-}" ] || [ -z "${%s:-}" ]; then
                        echo "required variable is missing a value: Run scripts/start" >&2
                        exit 1
                      fi
                    fi
                    case "$*" in
                      "compose config --no-interpolate --format json")
                        [ -f "$state/fail" ] && exit 1
                        echo '{"name": "compose-start"}' ;;
                      "ps --all --quiet --filter label=com.docker.compose.project=compose-start")
                        [ -f "$state/fail" ] && exit 1; true ;;
                      "volume ls --quiet --filter label=com.docker.compose.project=compose-start")
                        cat "$state/volumes" 2>/dev/null || true ;;
                      "run --rm --entrypoint cat example/yano:test /app/default-config/network/devnet/shelley-genesis.json")
                        cat "$state/source-genesis.json" ;;
                      "compose up -d --wait")
                        echo compose-start_node0-data >"$state/volumes"
                        [ "$DOCKER_UP" = ready ] ;;
                      "compose down --volumes") rm -f "$state/volumes" ;;
                      *) echo "unexpected docker $*" >&2; exit 2 ;;
                    esac
                    """.formatted(L1NetworkGenesis.DEVNET_ENVIRONMENT));
            docker.toFile().setExecutable(true);
            return new DockerStub(root);
        }

        int start(Path project, boolean ready) throws Exception {
            Files.deleteIfExists(root.resolve("log"));
            ProcessBuilder builder = new ProcessBuilder("bash", project.resolve("scripts/start").toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PATH", root.resolve("bin") + ":" + System.getenv("PATH"));
            builder.environment().put("DOCKER_STUB", root.toString());
            builder.environment().put("DOCKER_UP", ready ? "ready" : "unhealthy");
            builder.environment().put("YANO_IMAGE", "example/yano:test");
            builder.environment().remove("YANO_APPCHAIN_GENESIS_TIMESTAMP");
            builder.environment().remove(L1NetworkGenesis.DEVNET_ENVIRONMENT);
            return builder.start().waitFor();
        }

        String log() throws IOException {
            Path log = root.resolve("log");
            return Files.exists(log) ? Files.readString(log) : "";
        }

        boolean volumesExist() {
            return Files.exists(root.resolve("volumes"));
        }

        void failQueries() throws IOException {
            Files.writeString(root.resolve("fail"), "");
        }
    }

    private static int prepareDevnet(Path project, Path source) throws Exception {
        Process process = new ProcessBuilder("bash", project.resolve("scripts/prepare-devnet").toString(),
                source.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        return process.waitFor();
    }

    private static String nodeConfig(Path project) throws IOException {
        return Files.readString(project.resolve("config/nodes/node0.yaml"));
    }

    private static Map<String, String> bridgeAnswers() {
        return Map.of(
                "bridgeVaultAddress", "addr_test1wzvault",
                "bridgeVaultScriptHash", "1".repeat(56),
                "bridgeMaxDepositLovelace", "100000000",
                "bridgeWithdrawalAddress", "addr_test1vwithdrawals",
                "bridgeEpoch", "1",
                "bridgeMaxWithdrawalLovelace", "50000000",
                "bridgeMaxPendingWithdrawals", "100");
    }

    private static void assertSecretsOnlyInMemberFiles(Path project, List<String> secrets) throws IOException {
        try (var files = Files.walk(project)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = project.relativize(file).toString();
                if (relative.matches("secrets/node[0-9]+\\.env")) continue;
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String secret : secrets) {
                    assertThat(content).as(relative).doesNotContain(secret);
                }
            }
        }
    }

    private static void assertShellSyntax(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", "-n", script.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", script, diagnostics).isZero();
    }

    private static void assertScriptSucceeds(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", script.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", script, diagnostics).isZero();
    }

    @Test
    void customPluginRecipeRequiresAnAnswerAndRejectsNativeRuntime() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint custom = blueprint(
                "custom-plugin", "fixed", List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        assertThatThrownBy(() -> resolver.resolve(custom))
                .hasMessageContaining("require non-secret answers")
                .hasMessageContaining("stateMachine");
        AppChainProjectModel.Resolution resolved = resolver.resolve(withAnswers(
                custom, Map.of("stateMachine", "com.example.reviewed")));
        assertThat(resolved.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].state-machine",
                        "com.example.reviewed");
        assertThatThrownBy(() -> resolver.resolve(withTarget(withAnswers(custom,
                Map.of("stateMachine", "com.example.reviewed")), "native", "host")))
                .hasMessageContaining("does not support runtime native");
    }

    @Test
    void releaseIndexAdvertisesOnlyTheYanoXJvmDistribution() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        assertThat(catalog.releaseIndex().runtimeTypes()).containsExactly("jvm");
        assertThat(catalog.releaseIndex().distributions()).singleElement().satisfies(flavor -> {
            assertThat(flavor.id()).isEqualTo("yano-x-jvm");
            assertThat(flavor.runtimeType()).isEqualTo("jvm");
            assertThat(flavor.archivePattern()).isEqualTo("yano-x-jvm-{version}.zip");
            assertThat(flavor.tooling()).isEqualTo("bundled");
        });
    }

    @Test
    void completeCatalogIsTruthfulAndDistributionCapabilitiesCannotBeSelected()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);

        assertThat(catalog.capabilities()).hasSize(39)
                .allSatisfy(capability -> {
                    assertThat(capability.availability()).isIn(
                            "BUNDLED", "FIRST_PARTY_OPTIONAL", "REFERENCE", "EXPERIMENTAL");
                    assertThat(capability.effectiveScope()).isIn(
                            "chain", "node", "distribution");
                    assertThat(capability.trustStatement()).isNotBlank();
                    assertThat(capability.documentation()).isNotBlank();
                    assertThat(capability.acceptanceScenario()).isNotBlank();
                });
        assertThat(catalog.capabilities())
                .filteredOn(capability -> "ui:console".equals(capability.id()))
                .singleElement()
                .satisfies(capability -> {
                    assertThat(capability.effectiveScope()).isEqualTo("node");
                    assertThat(capability.effectiveSelectable()).isFalse();
                });
        assertThatThrownBy(() -> resolver.resolve(withCapabilities(
                blueprint("audit-log", "fixed", List.of()), List.of("ui:console"))))
                .hasMessageContaining("not selectable")
                .hasMessageContaining("ui:console");
        assertThat(catalog.capabilities())
                .filteredOn(capability -> "observability:prometheus".equals(capability.id()))
                .singleElement()
                .satisfies(capability -> {
                    assertThat(capability.effectiveScope()).isEqualTo("distribution");
                    assertThat(capability.effectiveSelectable()).isFalse();
                });

        AppChainProjectModel.Resolution governed = resolver.resolve(withMembership(
                blueprint("audit-log", "fixed", List.of()), "governed"));
        assertThat(governed.selectedCapabilities()).contains("membership:governed");
        assertThat(governed.consensusProperties()).containsEntry(
                "yano.app-chain.chains[0].membership.mode", "governed");
    }

    private static AppChainProjectModel.Blueprint blueprint(
            String recipe,
            String sequencing,
            List<String> memberKeys) {
        return new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("product-evidence"),
                new AppChainProjectModel.Spec(
                        "0.1.0-test",
                        "preprod",
                        new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"),
                        List.of(new AppChainProjectModel.ChainIntent(
                                "product-evidence", recipe, List.of(), Map.of(),
                                new AppChainProjectModel.Topology(
                                        3, memberKeys, List.of(),
                                        "two-thirds", sequencing, "static", null, null)))));
    }

    private static AppChainProjectModel.Blueprint authenticatedMapBlueprint(
            String valueEncoding,
            List<String> memberKeys
    ) {
        AppChainProjectModel.Blueprint base = blueprint(
                "authenticated-map", "fixed", memberKeys);
        AppChainProjectModel.ChainIntent chain = base.spec().chains().getFirst();
        AppChainProjectModel.AuthenticatedMapIntent authenticatedMap =
                new AppChainProjectModel.AuthenticatedMapIntent(
                        "mpf-blake2b256-v1",
                        "00".repeat(32),
                        16,
                        65_536,
                        List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                                "products", "owner", false, 64, 1024,
                                valueEncoding, "product-v1")),
                        List.of(new AppChainProjectModel.AuthenticatedMapSchemaIntent(
                                "product-v1", "product",
                                "product = { sku: tstr .size (1..32), qty: uint .le 1000 }")));
        return replaceChain(base, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                chain.topology(), authenticatedMap));
    }

    private static AppChainProjectModel.Blueprint governedAuthenticatedMapBlueprint(
            boolean includePolicy
    ) {
        List<String> memberKeys = List.of(
                "a".repeat(64), "b".repeat(64), "c".repeat(64));
        AppChainProjectModel.Blueprint base = blueprint(
                "authenticated-map", "fixed", memberKeys);
        AppChainProjectModel.ChainIntent chain = base.spec().chains().getFirst();
        byte[] seed = Hex.decode("01".repeat(32));
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        ActorKeyEpochV1 key = new ActorKeyEpochV1(
                "admin-a-key", publicKey, 1, 0, RecordStatus.ACTIVE);
        ActorKeyProofV1 proof = ActorKeyProofV1.sign(
                chain.chainId(), "admin-a", 1, key, seed);
        var actorKey = new AppChainProjectModel.AuthenticatedMapActorKeyIntent(
                key.keyId(), "ed25519", Hex.encode(publicKey),
                Hex.encode(proof.signature()), 1L, 0L, "active");
        var records = new AppChainProjectModel.AuthenticatedMapGenesisRecordsIntent(
                List.of(new AppChainProjectModel.AuthenticatedMapOrganizationIntent(
                        "operator-a", 1L, "active", null)),
                List.of(new AppChainProjectModel.AuthenticatedMapActorIntent(
                        "admin-a", 1L, "operator-a", "active",
                        List.of("registry-admin", "issuer"), List.of(actorKey), null)),
                includePolicy ? List.of(
                        new AppChainProjectModel.AuthenticatedMapDirectPolicyIntent(
                                "issuer-write", 1L, "active", "issuer", 100L))
                        : List.of(),
                List.of());
        var authenticatedMap = new AppChainProjectModel.AuthenticatedMapIntent(
                "mpf-blake2b256-v1", "00".repeat(32), 16, 65_536,
                List.of(new AppChainProjectModel.AuthenticatedMapCollectionIntent(
                        "records", "governed-role", "issuer-write", false,
                        64, 1024, "canonical-cbor", null)),
                List.of(),
                new AppChainProjectModel.AuthenticatedMapGovernanceIntent(
                        "registry-admins", 1L, List.of("admin-a"), 1, 1000L),
                records, null,
                includePolicy ? List.of() : List.of(
                new AppChainProjectModel.AuthenticatedMapOnboardingIntent(
                        "direct-policy", "issuer-write", "activate before writes")));
        return replaceChain(base, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                chain.topology(), authenticatedMap));
    }

    private static AppChainProjectModel.Blueprint withMembership(
            AppChainProjectModel.Blueprint blueprint,
            String membership) {
        AppChainProjectModel.ChainIntent chain = blueprint.spec().chains().getFirst();
        AppChainProjectModel.Topology topology = chain.topology();
        return replaceChain(blueprint, new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(),
                new AppChainProjectModel.Topology(
                        topology.members(), topology.memberKeys(), topology.nodeHosts(),
                        topology.finality(), topology.sequencing(), membership,
                        topology.httpPortBase(), topology.serverPortBase())));
    }

    private static AppChainProjectModel.Blueprint withCapabilities(
            AppChainProjectModel.Blueprint blueprint,
            List<String> capabilities) {
        AppChainProjectModel.ChainIntent chain = blueprint.spec().chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), capabilities, chain.answers(), chain.topology());
        return replaceChain(blueprint, changed);
    }

    private static AppChainProjectModel.Blueprint replaceChain(
            AppChainProjectModel.Blueprint blueprint,
            AppChainProjectModel.ChainIntent changed) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static AppChainProjectModel.Blueprint withTarget(
            AppChainProjectModel.Blueprint blueprint,
            String runtime,
            String deployment) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(
                        spec.yanoVersion(), spec.network(),
                        new AppChainProjectModel.RuntimeSelection(runtime),
                        new AppChainProjectModel.DeploymentSelection(deployment),
                        spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withNetwork(
            AppChainProjectModel.Blueprint blueprint,
            String network) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), network, spec.runtime(),
                        spec.deployment(), spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withHosts(
            AppChainProjectModel.Blueprint blueprint,
            List<String> hosts) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.Topology topology = chain.topology();
        AppChainProjectModel.Topology changedTopology = new AppChainProjectModel.Topology(
                topology.members(), topology.memberKeys(), hosts, topology.finality(),
                topology.sequencing(), topology.membership(), topology.httpPortBase(),
                topology.serverPortBase());
        AppChainProjectModel.ChainIntent changedChain = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(), changedTopology);
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changedChain)));
    }

    private static AppChainProjectModel.Blueprint withAnswers(
            AppChainProjectModel.Blueprint blueprint,
            Map<String, String> answers) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), answers, chain.topology());
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static Map<String, String> yamlValues(Path path) throws IOException {
        Map<String, String> values = new TreeMap<>();
        new AppChainConfigFileLoader().load(path).forEach((key, value) ->
                values.put(key, String.valueOf(value)));
        return values;
    }

    private static Map<String, String> fileDigests(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(),
                        AppChainProjectCatalog.sha256(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private static String allText(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private static String golden(String name) throws IOException {
        java.util.Properties hashes = new java.util.Properties();
        try (var input = AppChainProjectTest.class.getClassLoader().getResourceAsStream(
                "appchain-dx/v1alpha1/metadata.sha256")) {
            if (input == null) throw new IOException("missing project metadata golden hashes");
            hashes.load(input);
        }
        return hashes.getProperty(name);
    }

    private static String packagedResourceDigest(String name) throws IOException {
        try (var input = AppChainProjectTest.class.getClassLoader().getResourceAsStream(
                "appchain-dx/v1alpha1/" + name)) {
            if (input == null) throw new IOException("missing packaged metadata " + name);
            return AppChainProjectCatalog.sha256(input.readAllBytes());
        }
    }
}
