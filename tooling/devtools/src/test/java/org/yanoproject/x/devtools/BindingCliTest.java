package org.yanoproject.x.devtools;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.feed.profile.FeedGenesis;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingCliTest {
    @TempDir Path temporary;

    @Test
    void receiptKeyUsesPublicPhysicalNamespaceWithoutCatalogOrManualCbor() throws Exception {
        var output = run("bindings", "receipt-key", "AB".repeat(32));
        assertThat(output.exit()).as(output.err()).isZero();
        var result = new ObjectMapper().readTree(output.out());
        assertThat(result.path("stateKeyHex").textValue()).isEqualTo(HexFormat.of().formatHex(
                CompositeStateKeys.workflowStateKey("event-bindings",
                        HexFormat.of().parseHex("ab".repeat(32)))));
        assertThat(result.path("receiptQueryPath").textValue()).endsWith("ab".repeat(32));
        assertThat(run("bindings", "receipt-key", "aa").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(run("bindings", "receipt-key", "zz".repeat(32)).exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void continuationRequiresMatchingExecutionIdentityConsecutiveHeightAndNoCompetingState() {
        var state = List.of(new BindingDryRun.Entry("01", "02"));
        var message = new BindingDryRun.Message("11".repeat(32), "22".repeat(32), 2, 100,
                "records.v1", "01", null);
        var prior = new BindingCli.Rehearsal("unverified rehearsal", List.of(), List.of(), List.of(), state, 1, "id");
        var next = new BindingDryRun.Fixture(2, 200, "00".repeat(32), 0, List.of(), List.of(message));
        assertThat(BindingCli.continueFixture(next, prior, "id").state()).isEqualTo(state);
        assertThatThrownBy(() -> BindingCli.continueFixture(next, prior, "other"))
                .hasMessageContaining("IR or chain context differs");
        assertThatThrownBy(() -> BindingCli.continueFixture(new BindingDryRun.Fixture(3, 200,
                "00".repeat(32), 0, List.of(), List.of(message)), prior, "id"))
                .hasMessageContaining("consecutive");
        assertThatThrownBy(() -> BindingCli.continueFixture(new BindingDryRun.Fixture(2, 200,
                "00".repeat(32), 0, state, List.of(message)), prior, "id"))
                .hasMessageContaining("must be empty");
        assertThat(run("bindings", "graph", "x", "--ir", "--prior-result", "prior").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "graph", "x", "--ir", "--continuation-only").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
    }

    @Test
    void maximumPhysicalStateContinuationRoundTripsBelowItsBoundedInputLimit() throws Exception {
        // Sixteen entries each consume exactly1MiB physical bytes (4-byte key plus value).
        String value = "ab".repeat(1_048_576 - 4);
        var entries = java.util.stream.IntStream.range(0, 16).mapToObj(index ->
                new BindingDryRun.Entry(String.format("%08x", index), value)).toList();
        var compact = new BindingCli.Continuation("unverified rehearsal", entries, 1, "11".repeat(32));
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(compact);
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(67_108_864);
        Path file = temporary.resolve("continuation.json");
        Files.writeString(file, json);
        var decoded = mapper.readValue(BindingCli.read(file, 67_108_864), BindingCli.Rehearsal.class);
        assertThat(decoded.postState()).isEqualTo(entries);
        assertThat(decoded.height()).isEqualTo(1);
        assertThat(decoded.executionIdentity()).isEqualTo(compact.executionIdentity());
    }

    @Test
    void recipeGeneratesCallerChainAndVerifiedActorProofsWithoutDemoIdentityDefaults() throws Exception {
        for (String recipe : List.of("dpp", "feed")) {
            String chain = "customer-" + recipe;
            var descriptor = recipe.equals("dpp")
                    ? DppGenesis.demo(chain)
                    : FeedGenesis.demo(chain);
            Path actors = temporary.resolve(recipe + "-actors.json");
            Files.writeString(actors, TrustRegistryGenesis.toJson(descriptor));
            Path members = temporary.resolve(recipe + "-members.json");
            Files.writeString(members, "[\"" + "11".repeat(32) + "\",\"" + "22".repeat(32) + "\"]");
            var output = run("bindings", "recipe", recipe, "--descriptor", actors.toString(),
                    "--members", members.toString(), "--threshold", "2");
            assertThat(output.exit()).as(output.err()).isZero();
            var result = new ObjectMapper().readTree(output.out());
            assertThat(result.at("/context/chainId").textValue()).isEqualTo(chain);
            assertThat(result.at("/document/composite/components").size()).isEqualTo(3);
            assertThat(result.at("/context/settings/membership.mode").textValue()).isEqualTo("governed");
            assertThat(run("bindings", "recipe", recipe, "--descriptor", actors.toString(),
                    "--members", members.toString(), "--threshold", "2").out()).isEqualTo(output.out());
            Files.writeString(actors, Files.readString(actors).replace(chain, "different-chain"));
            assertThat(run("bindings", "recipe", recipe, "--descriptor", actors.toString(),
                    "--members", members.toString(), "--threshold", "2").exit())
                    .isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        }
        assertThat(run("bindings", "recipe", "dpp").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
    }

    @Test
    void malformedThresholdRetainsOptionNameBeforeReadingDescriptors() {
        for (String value : List.of("two", "2147483648")) {
            var result = run("bindings", "recipe", "dpp", "--descriptor", "unused.json",
                    "--members", "unused.json", "--threshold", value);
            assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
            assertThat(result.err()).contains("--threshold", "integer");
        }
    }

    @Test
    void starterRecipesRequireEveryDirectRoleProposerAndDistinctOrganizationQuorum() throws Exception {
        var json = new ObjectMapper();
        for (String recipe : List.of("dpp", "feed")) {
            var descriptor = recipe.equals("dpp")
                    ? DppGenesis.demo("public-dpp")
                    : FeedGenesis.demo("public-feed");
            var roles = recipe.equals("dpp")
                    ? List.of("manufacturer", "operator", "claim-issuer", "certifier", "auditor")
                    : List.of("feed-admin", "source", "feed-operator", "publisher");
            Path actors = temporary.resolve(recipe + "-public.json");
            Path members = temporary.resolve(recipe + "-members.json");
            json.writeValue(members.toFile(), List.of("11".repeat(32)));
            for (String role : roles) {
                var changed = descriptor.actors().stream().map(actor ->
                        new TrustRegistryGenesis.Actor(actor.id(),
                                actor.organizationId(), actor.roles().stream()
                                .map(existing -> existing.equals(role) ? "unrelated" : existing).distinct().toList(),
                                actor.keyId(), actor.publicKeyHex(), actor.keyProofHex())).toList();
                json.writeValue(actors.toFile(), new BindingRecipe.Descriptor(1, descriptor.chainId(),
                        descriptor.organizations(), changed, descriptor.authority(), List.of(), List.of()));
                var rejected = run("bindings", "recipe", recipe, "--descriptor", actors.toString(),
                        "--members", members.toString(), "--threshold", "1");
                assertThat(rejected.exit()).as(role).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
                assertThat(rejected.err()).contains("$.actors", "starter policy", role);
            }
            String voterRole = recipe.equals("dpp") ? "auditor" : "publisher";
            String organization = descriptor.actors().stream().filter(actor -> actor.roles().contains(voterRole))
                    .findFirst().orElseThrow().organizationId();
            var sameOrganization = descriptor.actors().stream().map(actor ->
                    new TrustRegistryGenesis.Actor(actor.id(),
                            actor.roles().contains(voterRole) ? organization : actor.organizationId(), actor.roles(),
                            actor.keyId(), actor.publicKeyHex(), actor.keyProofHex())).toList();
            json.writeValue(actors.toFile(), new BindingRecipe.Descriptor(1, descriptor.chainId(),
                    descriptor.organizations(), sameOrganization, descriptor.authority(), List.of(), List.of()));
            var rejected = run("bindings", "recipe", recipe, "--descriptor", actors.toString(),
                    "--members", members.toString(), "--threshold", "1");
            assertThat(rejected.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
            assertThat(rejected.err()).contains(voterRole, "distinct by ORGANIZATION");
        }
    }

    @Test
    void routesThroughMainCliAndPrintsHelpfulUsage() {
        Output output = run("bindings", "--help");
        assertThat(output.exit()).isZero();
        assertThat(output.out()).contains("compile|validate|graph|dry-run", "not verify signatures");
        assertThat(run("bindings", "compile", "file.yml").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "unknown").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "graph", "file.hex", "--ir", "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
        assertThat(run("bindings", "graph", "file.hex", "--ir", "--fixture", "fixture.json").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
    }

    @Test
    void graphFromCommittedIrNeedsNoPluginActivationOrContext() throws Exception {
        BindingIrV1 ir = new BindingIrV1(List.of(new BindingIrV1.Component("records", "custom-machine",
                "records.command.v1", Map.of(), 0)), List.of(), BindingIrV1.Limits.DEFAULT);
        Path document = temporary.resolve("bindings.hex");
        Files.writeString(document, HexFormat.of().formatHex(ir.encode()));
        Output output = run("bindings", "graph", document.toString(), "--ir");
        assertThat(output.exit()).isZero();
        assertThat(output.out()).isEqualTo(BindingGraph.dot(ir));
        Files.writeString(document, "0001");
        assertThat(run("bindings", "graph", document.toString(), "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
    }

    @Test
    void declarativeInitRequiresBothExplicitInputsAndRejectsThemForOtherRecipes() {
        List<String> base = List.of("init", "--non-interactive", "--network", "devnet", "--members", "3",
                "--output", temporary.resolve("project").toString(), "--recipe");
        for (List<String> suffix : List.of(List.of("declarative-composite"),
                List.of("declarative-composite", "--bindings", "missing.yaml"),
                List.of("declarative-composite", "--plugins-directory", "missing"),
                List.of("audit-log", "--bindings", "missing.yaml", "--plugins-directory", "missing"))) {
            var args = new ArrayList<>(base);
            args.addAll(suffix);
            Output result = run(args.toArray(String[]::new));
            assertThat(result.exit()).as(result.err()).isEqualTo(AppChainDevtoolsCli.EXIT_USAGE);
            assertThat(result.err()).contains("--bindings", "--plugins-directory");
            assertThat(temporary.resolve("project")).doesNotExist();
        }
    }

    @Test
    void rejectsMalformedUtf8AndMissingFilesWithoutCreatingOutputFiles() throws Exception {
        Path bad = temporary.resolve("bad.hex");
        Files.write(bad, new byte[]{(byte) 0xc3, 0x28});
        assertThat(run("bindings", "graph", bad.toString(), "--ir").exit()).isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        assertThat(run("bindings", "graph", temporary.resolve("missing").toString(), "--ir").exit())
                .isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        try (var entries = Files.list(temporary)) {
            assertThat(entries.map(Path::getFileName).toList()).containsExactly(Path.of("bad.hex"));
        }
    }

    @Test
    void snapshotProjectionHidesThirdPartyHelpersAndDiscoveryButSharesHostSpi() throws Exception {
        Path bundle = temporary.resolve("fixture.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(bundle))) {
            for (String entry : List.of("META-INF/yano/plugins/fixture.json", "dev/cel/compiler/CelCompiler.class",
                    "org/yanoproject/api/appchain/AppStateMachine.class",
                    "com/bloxbean/cardano/yaci/core/protocol/appmsg/model/AppMessage.class")) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(new byte[]{0});
                jar.closeEntry();
            }
        }
        BindingPluginEnvironment.SnapshotParent parent =
                new BindingPluginEnvironment.SnapshotParent(getClass().getClassLoader());
        parent.initialize(List.of(bundle));
        assertThatThrownBy(() -> parent.loadClass("dev.cel.compiler.CelCompiler"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThat(parent.loadClass(AppStateMachine.class.getName())).isSameAs(AppStateMachine.class);
        assertThat(parent.loadClass(AppMessage.class.getName())).isSameAs(AppMessage.class);
        assertThat(parent.getResource("dev/cel/compiler/CelCompiler.class")).isNull();
        assertThat(parent.getResources("META-INF/services/org.yanoproject.api.appchain.AppStateMachineProvider")
                .hasMoreElements()).isFalse();
        assertThatThrownBy(() -> parent.initialize(List.of(bundle))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unmanifestedAndEmptyPluginDirectoriesFailClosed() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        assertThatThrownBy(() -> BindingPluginEnvironment.open(plugins))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(plugins.resolve("plain.jar")))) {
            // An ordinary library is not an executable runtime bundle.
        }
        assertThatThrownBy(() -> BindingPluginEnvironment.open(plugins))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifested");
    }

    private static Output run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(args, new PrintWriter(out), new PrintWriter(err));
        return new Output(exit, out.toString(), err.toString());
    }

    private record Output(int exit, String out, String err) { }
}
