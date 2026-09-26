package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeProfileCodec;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingCliIT {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DOCUMENT = """
            composite:
              components:
                - {id: records, machine: kv-registry}
                - {id: audit, machine: doc-trail}
              bindings:
                - id: audit-record
                  from: {component: records, event: kv-registry.entry-put.v1}
                  when: [{expr: 'event.valueLength < 100'}]
                  to:
                    component: audit
                    command: append
                    map:
                      entityId: {fn: hex, args: [{field: key}]}
                      entryHash: {field: valueHash}
                      reference: {literal: published}
            """;

    @Test
    void emptyGenesisBlockInitializesCatalogStateAndContinuesWithFreshCommand() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        var commandFixture = JSON.readValue(inputs.fixture().toFile(), BindingDryRun.Fixture.class);
        JSON.writeValue(inputs.fixture().toFile(), new BindingDryRun.Fixture(1, 100, "00".repeat(32),
                0, List.of(), List.of()));
        Output empty = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString()));
        assertThat(empty.exit()).as(empty.err()).isZero();
        JsonNode initialized = JSON.readTree(empty.out());
        assertThat(initialized.path("receipts").size()).isZero();
        assertThat(initialized.path("postState").size()).isPositive();
        Path prior = temporary.resolve("empty-genesis.json");
        Files.writeString(prior, empty.out());
        JSON.writeValue(inputs.fixture().toFile(), new BindingDryRun.Fixture(2, 200, "00".repeat(32),
                0, List.of(), commandFixture.messages()));
        Output next = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--prior-result", prior.toString()));
        assertThat(next.exit()).as(next.err()).isZero();
        var receipt = BindingReceiptV1.decode(HexFormat.of().parseHex(JSON.readTree(next.out())
                .path("receipts").get(0).path("receiptHex").textValue()));
        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.steps()).hasSize(2);
    }

    @Test
    void compilesValidatesGraphsAndExecutesThroughRealManifestedCatalog() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Output compiled = run("compile", inputs, List.of());
        assertThat(compiled.exit()).withFailMessage(compiled.err()).isZero();
        BindingIrV1 ir = BindingIrV1.decode(HexFormat.of().parseHex(compiled.out().strip()));
        assertThat(ir.components().getFirst().configuration().get("value-format").value()).isEqualTo("raw");

        Output validation = run("validate", inputs, List.of());
        assertThat(validation.exit()).withFailMessage(validation.err()).isZero();
        assertThat(JSON.readTree(validation.out()).path("valid").booleanValue()).isTrue();
        assertThat(run("graph", inputs, List.of()).out()).contains("\"records\" -> \"audit\"");

        Output rehearsal = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString()));
        assertThat(rehearsal.exit()).withFailMessage(rehearsal.err()).isZero();
        JsonNode result = JSON.readTree(rehearsal.out());
        byte[] receipt = HexFormat.of().parseHex(result.path("receipts").get(0).path("receiptHex").textValue());
        assertThat(BindingReceiptV1.decode(receipt).accepted()).isTrue();
        assertThat(BindingReceiptV1.decode(receipt).steps()).hasSize(2);
        assertThat(result.path("stateChanges").size()).isGreaterThan(3);
        assertThat(result.path("assurance").textValue()).contains("not authenticated", "no post-state root");
        assertThat(run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString())).out())
                .isEqualTo(rehearsal.out());
    }

    @Test
    void priorResultCarriesCompleteStateAndPreservesExactReplayAcrossFreshCatalogInstances() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Output first = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString()));
        assertThat(first.exit()).as(first.err()).isZero();
        Path prior = temporary.resolve("prior.json");
        Output compact = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--continuation-only"));
        assertThat(compact.exit()).as(compact.err()).isZero();
        assertThat(JSON.readTree(compact.out()).has("receipts")).isFalse();
        assertThat(JSON.readTree(compact.out()).path("postState"))
                .isEqualTo(JSON.readTree(first.out()).path("postState"));
        Files.writeString(prior, compact.out());
        var next = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(inputs.fixture().toFile());
        next.put("height", 2);
        next.put("timestamp", 124);
        JSON.writeValue(inputs.fixture().toFile(), next);
        Output replay = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--prior-result", prior.toString()));
        assertThat(replay.exit()).as(replay.err()).isZero();
        var original = JSON.readTree(first.out());
        var result = JSON.readTree(replay.out());
        assertThat(result.path("receipts")).isEqualTo(original.path("receipts"));
        assertThat(result.path("postState")).isEqualTo(original.path("postState"));
        assertThat(result.path("height").longValue()).isEqualTo(2);
        var changed = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(inputs.context().toFile());
        changed.put("chainId", "other-chain");
        JSON.writeValue(inputs.context().toFile(), changed);
        Output mismatch = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString(),
                "--prior-result", prior.toString()));
        assertThat(mismatch.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(mismatch.err()).contains("IR or chain context differs");
    }

    @Test
    void callerSpecificGovernedRecipesCompileAndValidateAgainstActualBundles() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        for (String name : List.of("dpp", "feed")) {
            String chain = "caller-" + name;
            var descriptor = name.equals("dpp")
                    ? org.yanoproject.x.dpp.profile.DppGenesis.demo(chain)
                    : org.yanoproject.x.feed.profile.FeedGenesis.demo(chain);
            var authored = new BindingRecipe.Descriptor(1, chain, descriptor.organizations(), descriptor.actors(),
                    descriptor.authority(), List.of(), List.of());
            var recipe = BindingRecipe.generate(name, authored, List.of("22".repeat(32), "33".repeat(32)), 2);
            JSON.writeValue(inputs.document().toFile(), recipe.document());
            JSON.writeValue(inputs.context().toFile(), recipe.context());
            Output result = run("validate", inputs, List.of());
            assertThat(result.exit()).as(result.err()).isZero();
            assertThat(JSON.readTree(result.out()).path("valid").booleanValue()).isTrue();
        }
    }

    @Test
    void profileCheckDispatchReconstructsActualCatalogProfileAndRejectsExecutionVersionDrift() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        byte[] canonical;
        try (BindingPluginEnvironment environment = BindingPluginEnvironment.open(inputs.plugins())) {
            var session = new BindingCatalogSession(environment.providers(), context());
            var machine = session.validate(BindingDocumentCompiler.compile(DOCUMENT, session));
            canonical = machine.query("composite/active-profile-v1", new byte[0], new AppQueryContext() {
                @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
                @Override public byte[] stateRoot() { return new byte[32]; }
                @Override public long committedHeight() { return 0; }
            });
        }
        var expected = CompositeProfileCodec.decode(canonical);
        Path profiles = temporary.resolve("profiles.json");
        JSON.writeValue(profiles.toFile(), List.of(HexFormat.of().formatHex(canonical)));
        Output success = profileCheck(inputs, profiles);
        assertThat(success.exit()).as(success.err()).isZero();
        var report = JSON.readTree(success.out());
        assertThat(report.path("reproducesProfiles").booleanValue()).isTrue();
        assertThat(report.at("/profiles/0/expectedDigest").textValue())
                .isEqualTo(HexFormat.of().formatHex(expected.digest()));
        assertThat(report.at("/profiles/0/reproducesProfile").booleanValue()).isTrue();
        assertThat(report.path("assurance").textValue()).contains("not migration", "semantic equivalence");

        var oldExecution = new CompositeProfile(2, expected.profileId(), "1.0.0", expected.components(),
                expected.workflows(), expected.queryAliases(), expected.aggregateQueryLimits(), expected.bindingIr());
        JSON.writeValue(profiles.toFile(), List.of(HexFormat.of().formatHex(oldExecution.canonicalBytes())));
        Output incompatible = profileCheck(inputs, profiles);
        assertThat(incompatible.exit()).as(incompatible.err()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        var rejected = JSON.readTree(incompatible.out());
        assertThat(rejected.path("reproducesProfiles").booleanValue()).isFalse();
        assertThat(rejected.at("/profiles/0/expectedDigest").textValue())
                .isEqualTo(HexFormat.of().formatHex(oldExecution.digest()));
        assertThat(rejected.at("/profiles/0/diagnostic").textValue()).contains("absent or incompatible");
    }

    private static Output profileCheck(Inputs inputs, Path profiles) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(new String[]{"bindings", "profile-check", "--profiles",
                profiles.toString(), "--context", inputs.context().toString(), "--plugins-directory",
                inputs.plugins().toString()}, new PrintWriter(out), new PrintWriter(err));
        return new Output(exit, out.toString(), err.toString());
    }

    @Test
    void actualCatalogKeepsKernelSpiIdentitySharedAndRejectsUnknownProvider() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        try (BindingPluginEnvironment environment = BindingPluginEnvironment.open(inputs.plugins())) {
            assertThat(environment.providers().names(AppStateMachineProvider.class))
                    .contains("kv-registry", "doc-trail");
            BindingCatalogSession session = new BindingCatalogSession(environment.providers(), context());
            assertThat(session.configuration("kv-registry").getClass().getClassLoader())
                    .isSameAs(TransitionKernel.class.getClassLoader());
        }
        Files.writeString(inputs.document(), DOCUMENT.replace("machine: kv-registry", "machine: missing"));
        Output failure = run("compile", inputs, List.of());
        assertThat(failure.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(failure.err()).contains("missing");
    }

    @Test
    void hostBuiltinOrderedLogBaselineEventUsesRealCompositeCatalogAndReceipts() throws Exception {
        Inputs inputs = inputs(DOCUMENT.replace("machine: kv-registry", "machine: ordered-log")
                .replace("kv-registry.entry-put.v1", "composite.command-accepted.v1")
                .replace("event.valueLength", "event.bodyLength")
                .replace("{field: key}", "{field: messageId}")
                .replace("{field: valueHash}", "{field: bodyHash}"));
        Output compiled = run("compile", inputs, List.of());
        assertThat(compiled.exit()).withFailMessage(compiled.err()).isZero();
        Output rehearsal = run("dry-run", inputs, List.of("--fixture", inputs.fixture().toString()));
        assertThat(rehearsal.exit()).withFailMessage(rehearsal.err()).isZero();
        byte[] receipt = HexFormat.of().parseHex(JSON.readTree(rehearsal.out())
                .path("receipts").get(0).path("receiptHex").textValue());
        assertThat(BindingReceiptV1.decode(receipt).accepted()).isTrue();
        assertThat(BindingReceiptV1.decode(receipt).steps()).hasSize(2);
    }

    @Test
    void rejectsGraphCyclesThroughRealConstructor() throws Exception {
        Inputs inputs = inputs(DOCUMENT.replace("component: audit\n", "component: records\n")
                .replace("command: append", "command: put")
                .replace("entityId: {fn: hex, args: [{field: key}]}", "key: {field: key}")
                .replace("entryHash: {field: valueHash}", "value: {field: valueHash}")
                .replace("          reference: {literal: published}\n", ""));
        Output result = run("validate", inputs, List.of());
        assertThat(result.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(result.err()).containsIgnoringCase("cyclic");
    }

    @Test
    void committedIrTypeMismatchRetainsAuthoredBindingPathAndProviderDiagnostic() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Output compiled = run("compile", inputs, List.of());
        assertThat(compiled.exit()).as(compiled.err()).isZero();
        var original = BindingIrV1.decode(HexFormat.of().parseHex(compiled.out().strip()));
        var binding = original.bindings().getFirst();
        var target = (BindingIrV1.CommandTarget) binding.target();
        var fields = target.mapping().fields().stream().map(assignment -> assignment.field().equals("entityId")
                ? new BindingIrV1.Assignment("entityId", new BindingSourceV1.Field("key")) : assignment).toList();
        var invalid = new BindingIrV1(original.components(), List.of(new BindingIrV1.Binding(binding.id(),
                binding.sourceComponent(), binding.eventId(), binding.conditions(), new BindingIrV1.CommandTarget(
                        target.component(), target.command(), BindingIrV1.Mapping.fields(fields)))),
                original.limits(), original.workflowFromHeight());
        Files.writeString(inputs.document(), HexFormat.of().formatHex(invalid.encode()));
        Output failure = run("validate", inputs, List.of("--ir"));
        assertThat(failure.exit()).isEqualTo(AppChainDevtoolsCli.EXIT_INVALID_CONFIG);
        assertThat(failure.err()).contains("$.bindings[0]", "binding 'audit-record'",
                "records/kv-registry.entry-put.v1", "binding type mismatch");
    }

    @Test
    void blueprintPinsCatalogIrAndProfileWithoutLeakingAuthoringPathIntoConsensus() throws Exception {
        inputs(DOCUMENT);
        var properties = AppChainPropertyRegistry.framework();
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        var renderer = new AppChainProjectRenderer(catalog, resolver);
        var composite = new ObjectMapper(new YAMLFactory()).readTree(DOCUMENT).get("composite");
        var topology = new AppChainProjectModel.Topology(3,
                List.of("22".repeat(32), "33".repeat(32), "44".repeat(32)), List.of(),
                "two-thirds", "fixed", "static", null, null);
        var chain = new AppChainProjectModel.ChainIntent("workflow", "declarative-composite", List.of(),
                Map.of(), topology, null, composite);
        var blueprint = new AppChainProjectModel.Blueprint(AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("binding-project"),
                new AppChainProjectModel.Spec("0.1.0-test", "devnet",
                        new AppChainProjectModel.RuntimeSelection("jvm", "../plugins"),
                        new AppChainProjectModel.DeploymentSelection("host"), List.of(chain)));
        Path project = temporary.resolve("project");
        var lock = renderer.initialize(project, blueprint);
        assertThat(lock.catalogDigests()).containsKeys("binding.workflow.profile", "binding.workflow.ir",
                "binding.workflow.catalog");
        assertThat(lock.catalogDigests().get("binding.workflow.profile")).matches("[0-9a-f]{64}");
        assertThat(resolver.resolve(blueprint, project).consensusProperties().values())
                .noneMatch(value -> value.contains("../plugins") || value.contains(temporary.toString()));
        renderer.validate(project);
        Path lockFile = project.resolve(AppChainProjectRenderer.LOCK_FILE);
        var tampered = JSON.readTree(lockFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.get("catalogDigests"))
                .put("binding.workflow.profile", "00".repeat(32));
        JSON.writeValue(lockFile.toFile(), tampered);
        assertThatThrownBy(() -> renderer.validate(project)).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("catalog digests");
    }

    @Test
    void initImportsStrictBindingsAndStoresProjectRelativeAuthoringLocation() throws Exception {
        Inputs inputs = inputs(DOCUMENT);
        Path project = temporary.resolve("initialized");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int result = new AppChainDevtoolsCli().run(new String[]{"init", "--non-interactive",
                "--recipe", "declarative-composite", "--network", "devnet", "--members", "3",
                "--member-key", "22".repeat(32), "--member-key", "33".repeat(32),
                "--member-key", "44".repeat(32), "--bindings", inputs.document().toString(),
                "--plugins-directory", inputs.plugins().toString(), "--output", project.toString()},
                new PrintWriter(out), new PrintWriter(err));
        assertThat(result).as(err.toString()).isZero();
        var blueprint = new ObjectMapper(new YAMLFactory()).readTree(project.resolve("appchain.yaml").toFile());
        assertThat(blueprint.at("/spec/runtime/pluginsDirectory").textValue()).isEqualTo("../plugins");
        assertThat(blueprint.at("/spec/chains/0/composite/bindings/0/id").textValue()).isEqualTo("audit-record");
        assertThat(Files.readString(project.resolve("appchain.lock"))).doesNotContain(temporary.toString());
    }

    @Test
    void independentlyPackagedKernelComposesThroughCatalogAndCannotEscapeClosedLifetime() throws Exception {
        Inputs inputs = inputs(DOCUMENT.replace("machine: kv-registry", "machine: external-log")
                .replace("kv-registry.entry-put.v1", "composite.command-accepted.v1")
                .replace("event.valueLength", "event.bodyLength")
                .replace("{field: key}", "{field: messageId}")
                .replace("{field: valueHash}", "{field: bodyHash}"));
        packageThirdPartyKernel(inputs.plugins());
        TransitionKernel<?, ?> retained;
        ClassLoader caller = Thread.currentThread().getContextClassLoader();
        try (BindingPluginEnvironment environment = BindingPluginEnvironment.open(inputs.plugins())) {
            var machine = environment.providers().require(AppStateMachineProvider.class, "external-log").create();
            retained = machine.transitionKernel().orElseThrow();
            assertThat(retained.configuration().getClass().getClassLoader())
                    .isSameAs(TransitionKernel.class.getClassLoader());
            assertThat(retained.codec().type()).isEqualTo(byte[].class);
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
            var catalog = new BindingCatalogSession(environment.providers(), context());
            var ir = BindingDocumentCompiler.compile(Files.readString(inputs.document()), catalog);
            var fixture = JSON.readValue(inputs.fixture().toFile(), BindingDryRun.Fixture.class);
            var rehearsal = BindingDryRun.execute(catalog.validate(ir), context(), fixture);
            var receipt = BindingReceiptV1.decode(HexFormat.of()
                    .parseHex((String) rehearsal.receipts().getFirst().get("receiptHex")));
            assertThat(receipt.accepted()).isTrue();
            assertThat(receipt.steps()).hasSize(2);
        }
        assertThatThrownBy(retained::configuration).isInstanceOf(IllegalStateException.class);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(caller);
    }

    /** Compile an independent implementation; its classes never enter the test/application classpath. */
    private void packageThirdPartyKernel(Path plugins) throws Exception {
        Path source = temporary.resolve("ExternalProvider.java");
        Files.writeString(source, """
                package thirdparty;
                import java.util.*;
                import org.yanoproject.api.appchain.*;
                import org.yanoproject.api.appchain.codec.MessageCodec;
                import org.yanoproject.api.appchain.effects.AppEffectEmitter;
                import org.yanoproject.api.appchain.transition.*;
                public final class ExternalProvider implements AppStateMachineProvider {
                  static void check() {
                    if (Thread.currentThread().getContextClassLoader() != ExternalProvider.class.getClassLoader())
                      throw new AssertionError("kernel callback escaped its plugin loader");
                  }
                  public String id() { check(); return "external-log"; }
                  public AppStateMachine create() {
                    check();
                    return new AppStateMachine() {
                      public String id() { check(); return "external-log"; }
                      public Optional<TransitionKernel<?, ?>> transitionKernel() {
                        check(); return Optional.of(new Kernel());
                      }
                      public void apply(AppBlockExecutionContext c, AppStateWriter w, AppEffectEmitter e) {
                        check();
                        if (!c.messages().isEmpty()) throw new AssertionError("composition bypassed kernel");
                      }
                    };
                  }
                  static final class Kernel implements TransitionKernel<byte[], Boolean> {
                    final OrderedLogKernel delegate = new OrderedLogKernel();
                    public MessageCodec<byte[]> codec() {
                      check();
                      return new MessageCodec<>() {
                        public byte[] encode(byte[] value) { check(); return value.clone(); }
                        public byte[] decode(byte[] value) { check(); return value.clone(); }
                        public Class<byte[]> type() { check(); return byte[].class; }
                      };
                    }
                    public Boolean facts(byte[] command, TransitionContext c, AppStateReader state) {
                      check(); return true;
                    }
                    public TransitionDecision decide(byte[] command, TransitionContext c, Boolean facts) {
                      check(); return delegate.decide(command, c, facts);
                    }
                    public List<CommandDescriptor> commands() { check(); return delegate.commands(); }
                    public List<EventDescriptor> events() { check(); return delegate.events(); }
                    public ConfigurationDescriptor configuration() { check(); return delegate.configuration(); }
                  }
                }
                """);
        Path classes = Files.createDirectory(temporary.resolve("external-classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        String compileClasspath = System.getProperty("yano.test.binding-classpath");
        assertThat(compiler).as("fixture compilation requires the Java 25 JDK compiler").isNotNull();
        assertThat(compileClasspath)
                .as("Gradle must supply yano.test.binding-classpath; rerun with fresh configuration")
                .isNotBlank();
        int result = compiler.run(null, null, null,
                "-classpath", compileClasspath,
                "-d", classes.toString(), source.toString());
        assertThat(result).as("independent third-party plugin compilation").isZero();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("external.jar")))) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
            jar.putNextEntry(new JarEntry("META-INF/yano/plugins/thirdparty.external.json"));
            jar.write("""
                    {"schemaVersion":1,"id":"thirdparty.external","version":"1.0.0",
                     "yanoApi":{"min":3,"max":3,"minLevel":10},"dependencies":[],
                     "contributions":[{"kind":"app-state-machine","name":"external-log",
                       "provider":"thirdparty.ExternalProvider"}]}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/services/org.yanoproject.api.appchain.AppStateMachineProvider"));
            jar.write("thirdparty.ExternalProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private Inputs inputs(String document) throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        Path authored = temporary.resolve("bindings.yml");
        Files.writeString(authored, document);
        Path context = temporary.resolve("context.json");
        JSON.writeValue(context.toFile(), context());
        Path fixture = temporary.resolve("fixture.json");
        byte[] command = KvRegistryContract.put(new byte[]{1, 2}, new byte[]{3, 4});
        JSON.writeValue(fixture.toFile(), new BindingDryRun.Fixture(1, 123, "00".repeat(32), 0, List.of(),
                List.of(new BindingDryRun.Message("11".repeat(32), "22".repeat(32), 1, Long.MAX_VALUE,
                        "records.command.v1", HexFormat.of().formatHex(command), "00"))));
        return new Inputs(authored, context, fixture, plugins);
    }

    private static BindingCatalogSession.ContextInput context() {
        var profile = new AppChainConsensusProfile(2, 65536, 100, 1_048_576, 0, 0, false, false,
                0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT, true, List.of());
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]);
        return new BindingCatalogSession.ContextInput("offline-test", identity.settings(), profile,
                new AppChainMembershipEpoch(0, List.of("22".repeat(32)), 1));
    }

    private static Output run(String command, Inputs inputs, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("bindings", command, inputs.document().toString(),
                "--plugins-directory", inputs.plugins().toString(), "--context", inputs.context().toString()));
        args.addAll(extra);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(args.toArray(String[]::new),
                new PrintWriter(out), new PrintWriter(err));
        return new Output(exit, out.toString(), err.toString());
    }

    private record Inputs(Path document, Path context, Path fixture, Path plugins) { }
    private record Output(int exit, String out, String err) { }
}
