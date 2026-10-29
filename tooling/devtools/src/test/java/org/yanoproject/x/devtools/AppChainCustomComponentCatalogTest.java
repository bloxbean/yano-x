package org.yanoproject.x.devtools;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.effects.AppEffectExecutorFactory;
import org.yanoproject.api.appchain.sink.FinalizedStreamSinkFactory;
import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.yanoproject.appchain.config.AppChainMetadataDescriptor;
import org.yanoproject.appchain.config.AppChainPropertyDefinition;
import org.yanoproject.appchain.config.ChangePolicy;
import org.yanoproject.appchain.config.ConstraintProvenance;
import org.yanoproject.appchain.config.PropertyScope;
import org.yanoproject.appchain.config.PropertyType;
import org.yanoproject.appchain.config.ValidationCoverage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainCustomComponentCatalogTest {
    private static final String SEED = "11".repeat(32);

    @TempDir
    Path temporary;

    @Test
    void customCapabilityCanDeclareAndEnforceSupportedNetworks() throws Exception {
        Fixture fixture = fixture("devnet-only", catalog -> {
            var capability = (com.fasterxml.jackson.databind.node.ObjectNode)
                    catalog.withArray("capabilities").get(0);
            capability.putArray("supportedNetworks").add("devnet");
        });
        AppChainComponentCatalogLoader.Loaded loaded =
                new AppChainComponentCatalogLoader().loadJar(
                        fixture.jar(), Map.of("test-publisher", fixture.publicKey()));
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog =
                new AppChainProjectCatalog(properties, List.of(loaded));

        assertThat(catalog.capability("state:devnet-only").effectiveSupportedNetworks())
                .containsExactly("devnet");

        AppChainProjectModel.Blueprint blueprint = new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("network-policy"),
                new AppChainProjectModel.Spec(
                        "0.1.0-test",
                        "preprod",
                        new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"),
                        List.of(new AppChainProjectModel.ChainIntent(
                                "network-policy",
                                "custom-plugin",
                                List.of("state:devnet-only"),
                                Map.of(),
                                new AppChainProjectModel.Topology(
                                        1, List.of(), List.of(), "all", "fixed",
                                        "static", null, null)))));

        assertThatThrownBy(() -> new AppChainProjectResolver(properties, catalog)
                .resolve(blueprint))
                .hasMessageContaining("state:devnet-only")
                .hasMessageContaining("does not support network preprod");
    }

    @Test
    void trustTamperingCollisionsAndUnsupportedClaimsFailClosed() throws Exception {
        Fixture valid = fixture("custom-sample", null);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        assertThatThrownBy(() -> loader.loadJar(valid.jar(), Map.of()))
                .hasMessageContaining("trusted");
        assertThatThrownBy(() -> loader.loadJar(valid.jar(),
                Map.of("test-publisher", "22".repeat(32))))
                .hasMessageContaining("verification failed");

        Fixture collision = fixture("collision", catalog -> {
            catalog.withArray("capabilities").get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)
                    catalog.withArray("capabilities").get(0)).put("id", "state:ordered-log");
        });
        AppChainComponentCatalogLoader.Loaded collisionLoaded = loader.loadJar(
                collision.jar(), Map.of("test-publisher", collision.publicKey()));
        assertThatThrownBy(() -> new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework(), List.of(collisionLoaded)))
                .hasMessageContaining("Duplicate capability id");

        Fixture elevated = fixture("elevated", catalog -> {
            var capability = (com.fasterxml.jackson.databind.node.ObjectNode)
                    catalog.withArray("capabilities").get(0);
            capability.put("availability", "BUNDLED");
            capability.put("maturity", "stable");
        });
        AppChainComponentCatalogLoader.Loaded elevatedLoaded = loader.loadJar(
                elevated.jar(), Map.of("test-publisher", elevated.publicKey()));
        assertThatThrownBy(() -> new AppChainProjectCatalog(
                AppChainPropertyRegistry.framework(), List.of(elevatedLoaded)))
                .hasMessageContaining("cannot claim bundled");
    }

    @Test
    void scaffoldModesAreBoundedBuildableStartingPointsAndNeverOverwrite() throws Exception {
        AppChainPluginScaffolder scaffolder = new AppChainPluginScaffolder();
        for (String mode : List.of("state-machine", "composite-role", "effect-executor", "sink")) {
            Path output = temporary.resolve(mode);
            AppChainPluginScaffolder.Result result = scaffolder.scaffold(
                    mode, "sample-" + mode, "example." + mode.replace('-', '_'),
                    "0.1.0-test", output);
            assertThat(result.mode()).isEqualTo(mode);
            assertThat(output.resolve("build.gradle")).isRegularFile();
            assertThat(output.resolve("README.md")).content().contains("plugin sign");
            assertThat(output.resolve("src/main/resources/"
                    + AppChainComponentCatalogLoader.CATALOG_PATH)).isRegularFile();
            assertThat(Files.walk(output.resolve("src/main/resources/META-INF/services"))
                    .filter(Files::isRegularFile).count()).isEqualTo(1);
            assertThatThrownBy(() -> scaffolder.scaffold(
                    mode, "sample-" + mode, "example." + mode.replace('-', '_'),
                    "0.1.0-test", output)).hasMessageContaining("not empty");
        }
    }

    @Test
    void everyScaffoldModeCompilesAgainstThePinnedHostApiAndLoadsThroughItsService()
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("tests must run on a JDK").isNotNull();
        Map<String, Class<?>> services = Map.of(
                "state-machine", AppStateMachineProvider.class,
                "composite-role", AppStateMachineProvider.class,
                "effect-executor", AppEffectExecutorFactory.class,
                "sink", FinalizedStreamSinkFactory.class);
        for (String mode : List.of("state-machine", "composite-role", "effect-executor", "sink")) {
            String id = "compiled-" + mode;
            Path output = temporary.resolve("compile-" + mode);
            new AppChainPluginScaffolder().scaffold(
                    mode, id, "example.compiled." + mode.replace('-', '_'), "0.1.0-test", output);
            List<String> sources;
            try (var paths = Files.walk(output.resolve("src/main/java"))) {
                sources = paths.filter(path -> path.toString().endsWith(".java"))
                        .map(Path::toString).toList();
            }
            Path classes = Files.createDirectories(output.resolve("build/classes"));
            // The test classpath carries the pinned host API, so an SPI change fails this build first.
            List<String> arguments = new ArrayList<>(List.of(
                    "-proc:none", "-Xlint:all,-path,-options", "-Werror", "-implicit:none",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()));
            arguments.addAll(sources);
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            int exit = compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
            assertThat(exit).as("%s scaffold compiles:%n%s", mode,
                    diagnostics.toString(StandardCharsets.UTF_8)).isZero();

            try (URLClassLoader pluginLoader = new URLClassLoader(new URL[]{
                    classes.toUri().toURL(), output.resolve("src/main/resources").toUri().toURL()},
                    getClass().getClassLoader())) {
                List<?> providers = ServiceLoader.load(services.get(mode), pluginLoader).stream()
                        .map(ServiceLoader.Provider::get)
                        .filter(provider -> provider.getClass().getClassLoader() == pluginLoader)
                        .toList();
                assertThat(providers).as("%s service registration", mode).hasSize(1);
                switch (providers.getFirst()) {
                    case AppStateMachineProvider provider -> {
                        assertThat(provider.id()).isEqualTo(id);
                        AppStateMachine machine = provider.create();
                        assertThat(machine.id()).isEqualTo(id);
                        AppStateMachine.AdmissionResult admission = machine.validate(null);
                        assertThat(admission.isAccepted()).isFalse();
                        assertThat(admission.reason()).matches("[A-Z_]{1,32}");
                    }
                    case AppEffectExecutorFactory factory -> {
                        assertThat(factory.scheme()).isEqualTo(id);
                        assertThat(factory.create("chain", Map.of())).isEmpty();
                    }
                    case FinalizedStreamSinkFactory factory -> {
                        assertThat(factory.scheme()).isEqualTo(id);
                        assertThat(factory.create("chain", Map.of())).isEmpty();
                    }
                    default -> throw new AssertionError("unexpected provider " + providers.getFirst());
                }
            }
        }
    }

    @Test
    void reviewedJvmArtifactIsVisibleThroughItsServiceLoaderBoundary() throws Exception {
        Fixture fixture = fixture("custom-sample", null);
        try (URLClassLoader pluginLoader = new URLClassLoader(
                new URL[]{fixture.jar().toUri().toURL()}, getClass().getClassLoader())) {
            assertThat(ServiceLoader.load(AppStateMachineProvider.class, pluginLoader))
                    .extracting(AppStateMachineProvider::id).contains("custom-sample");
        }
    }

    @Test
    void signedConfigurationMetadataExtendsRegistryAndOwnershipCollisionsFailClosed()
            throws Exception {
        String owner = "plugin-bundle.metadata";
        byte[] metadata = metadata(owner,
                "yano.app-chain.machines.metadata.limit");
        Fixture fixture = fixture("metadata", null, metadata);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        var loaded = loader.loadJar(fixture.jar(),
                Map.of("test-publisher", fixture.publicKey()));
        AppChainPropertyRegistry extended = loader.extendRegistry(
                AppChainPropertyRegistry.framework(), List.of(loaded));
        assertThat(extended.find("machines.metadata.limit")).isPresent();

        Fixture collision = fixture("metadata-collision", null,
                metadata("plugin-bundle.metadata-collision",
                        "yano.app-chain.state-machine"));
        var collisionLoaded = loader.loadJar(collision.jar(),
                Map.of("test-publisher", collision.publicKey()));
        assertThatThrownBy(() -> loader.extendRegistry(
                AppChainPropertyRegistry.framework(), List.of(collisionLoaded)))
                .hasMessageContaining("Duplicate property definition");
    }

    @Test
    void publicPluginValidateExportsAReverifiableSnapshot() throws Exception {
        Fixture fixture = fixture("cli-sample", null);
        Path snapshot = temporary.resolve("cli-sample-catalog.json");
        StringWriter output = new StringWriter();
        AppChainProjectCli cli = new AppChainProjectCli(
                new BufferedReader(new StringReader("")), new PrintWriter(output, true),
                AppChainPropertyRegistry.framework(), new ObjectMapper());

        Path scaffold = temporary.resolve("scaffold-cli-sample");
        Path signedCopy = temporary.resolve("detached-signature.json");
        int signExit = cli.run(new String[]{"appchain", "plugin", "sign", "--catalog",
                scaffold.resolve("src/main/resources/")
                        .resolve(AppChainComponentCatalogLoader.CATALOG_PATH).toString(),
                "--runtime-manifest", scaffold.resolve("src/main/resources/META-INF/yano/plugins/"
                        + "plugin-bundle.cli-sample.json").toString(),
                "--seed-file", scaffold.resolve("publisher.seed").toString(),
                "--key-id", "test-publisher", "--output", signedCopy.toString()});
        assertThat(signExit).isZero();
        assertThat(signedCopy).isRegularFile();

        int exit = cli.run(new String[]{"appchain", "plugin", "validate",
                fixture.jar().toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", snapshot.toString()});

        assertThat(exit).isZero();
        assertThat(output.toString()).contains("PLUGIN_CATALOG_VALID", "SNAPSHOT_WRITTEN");
        var loaded = new AppChainComponentCatalogLoader().loadSnapshot(
                snapshot, Map.of("test-publisher", fixture.publicKey()));
        assertThat(loaded.catalog().catalogId()).isEqualTo("cli-sample-plugin");

    }

    private Path initializeProject(Fixture fixture) throws Exception {
        Path project = temporary.resolve("doctor-project");
        AppChainProjectCli cli = new AppChainProjectCli(
                new BufferedReader(new StringReader("")),
                new PrintWriter(new StringWriter(), true),
                AppChainPropertyRegistry.framework(), new ObjectMapper());
        cli.run(new String[]{"appchain", "init", "--non-interactive",
                "--recipe", "custom-plugin", "--network", "devnet", "--members", "1",
                "--runtime", "jvm", "--capability", "state:custom-sample",
                "--plugin-jar", fixture.jar().toString(), "--trust-key",
                "test-publisher=" + fixture.publicKey(), "--output", project.toString()});
        return project;
    }

    private Path distribution(String runtime, byte[] releaseIndex) throws Exception {
        Path root = temporary.resolve("distribution-" + runtime);
        Files.createDirectories(root.resolve("config/schema"));
        Files.write(root.resolve("config/schema/appchain-release-capability-index.json"),
                releaseIndex);
        Files.write(root.resolve("jvm".equals(runtime) ? "yano.jar" : "yano"),
                new byte[]{1});
        return root;
    }

    private Fixture fixture(
            String id,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutate)
            throws Exception {
        return fixture(id, mutate, null);
    }

    private Fixture fixture(
            String id,
            java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> mutate,
            byte[] configurationMetadata) throws Exception {
        Path scaffold = temporary.resolve("scaffold-" + id);
        new AppChainPluginScaffolder().scaffold(
                "state-machine", id, "example." + id.replace('-', '_'),
                "0.1.0-test", scaffold);
        Path resources = scaffold.resolve("src/main/resources");
        Path catalog = resources.resolve(AppChainComponentCatalogLoader.CATALOG_PATH);
        if (mutate != null) {
            ObjectMapper mapper = new ObjectMapper();
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode)
                    mapper.readTree(Files.readAllBytes(catalog));
            mutate.accept(tree);
            Files.write(catalog, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree));
        }
        Path manifest;
        try (var files = Files.list(resources.resolve("META-INF/yano/plugins"))) {
            manifest = files.findFirst().orElseThrow();
        }
        Path seed = scaffold.resolve("publisher.seed");
        Files.writeString(seed, SEED, StandardCharsets.US_ASCII);
        AppChainComponentCatalogLoader loader = new AppChainComponentCatalogLoader();
        Path metadata = null;
        if (configurationMetadata != null) {
            metadata = resources.resolve(AppChainMetadataDescriptor.RESOURCE_PATH);
            Files.createDirectories(metadata.getParent());
            Files.write(metadata, configurationMetadata);
        }
        AppChainComponentCatalogLoader.SignedCatalog signed = loader.sign(
                catalog, manifest, metadata, "test-publisher", seed);
        Path signature = resources.resolve(AppChainComponentCatalogLoader.SIGNATURE_PATH);
        loader.writeSignature(signature, signed);

        Path service = resources.resolve("META-INF/services/"
                + AppStateMachineProvider.class.getName());
        Files.writeString(service, CustomCatalogFixtureProvider.class.getName() + "\n");
        Path jar = temporary.resolve(id + ".jar");
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(resources)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    add(archive, resources.relativize(path).toString().replace('\\', '/'),
                            Files.readAllBytes(path));
                }
            }
            String classEntry = CustomCatalogFixtureProvider.class.getName()
                    .replace('.', '/') + ".class";
            try (var input = getClass().getClassLoader().getResourceAsStream(classEntry)) {
                add(archive, classEntry, input.readAllBytes());
            }
            add(archive, "untrusted/NotLoaded.class", HexFormat.of().parseHex("cafebabe"));
        }
        return new Fixture(jar, signed.publicKeyHex());
    }

    private static byte[] metadata(String owner, String key) throws Exception {
        AppChainPropertyDefinition property = new AppChainPropertyDefinition(
                key, owner, PropertyType.INTEGER, "10", 1L, 100L,
                null, null, null, Set.of(), PropertyScope.NODE_LOCAL,
                ChangePolicy.RESTART_REQUIRED, false, true,
                ConstraintProvenance.DOCUMENTED_UNVERIFIED,
                ValidationCoverage.PARTIAL, "Custom bounded setting");
        return new ObjectMapper().writeValueAsBytes(new AppChainMetadataDescriptor(
                1, owner, List.of(property), List.of()));
    }

    private static void add(ZipOutputStream archive, String name, byte[] bytes) throws Exception {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }

    private record Fixture(Path jar, String publicKey) {
    }
}
