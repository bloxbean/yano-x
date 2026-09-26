package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.2 M1 gate: documents exported by Studio's draft model compile to exactly the IR and profile of the
 * original under the same catalog and context. A layout change keeps them identical; a binding reorder or a
 * component rename changes them. The real compiler and catalog-selected provider decide every case.
 */
class StudioRoundTripIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void studioExportsCompileToIdenticalIrAndProfileAndSemanticEditsChangeIdentity() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path cases = Path.of(System.getProperty("yano.test.studio-roundtrip"));
        JsonNode manifest = JSON.readTree(cases.resolve("manifest.json").toFile());
        assertThat(manifest.path("schema").textValue()).isEqualTo("yano-x-studio-roundtrip-v1");
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        ObjectMapper contextReader = new ObjectMapper();
        var tutorial = contextReader.readValue(repository.resolve(
                "tooling/studio/src/main/web/binding-authoring-context.json").toFile(),
                BindingCatalogSession.ContextInput.class);
        var effects = contextReader.readValue(repository.resolve(
                "tooling/studio/src/test/fixtures/corpus/context-effects.json").toFile(),
                BindingCatalogSession.ContextInput.class);
        List<String> checked = new ArrayList<>();
        int reordered = 0;
        try (var environment = BindingPluginEnvironment.open(plugins)) {
            for (JsonNode item : manifest.path("cases")) {
                String name = item.path("name").textValue();
                JsonNode files = item.path("files");
                String original = Files.readString(cases.resolve(files.path("original").textValue()));
                String context = item.path("context").textValue();
                var input = context.equals("effects") ? effects : context.startsWith("recipe:")
                        ? BindingRecipesIT.recipeContext(context.substring("recipe:".length()), original) : tutorial;
                var session = new BindingCatalogSession(environment.providers(), input);
                BindingIrV1 expected = BindingDocumentCompiler.compile(original, session);
                String expectedProfile = profile(session, expected);
                for (String same : List.of("emitted", "layoutMoved")) {
                    String text = Files.readString(cases.resolve(files.path(same).textValue()));
                    BindingIrV1 actual = BindingDocumentCompiler.compile(text, session);
                    assertThat(Arrays.equals(actual.encode(), expected.encode())).as(name + " " + same).isTrue();
                    assertThat(profile(session, actual)).as(name + " " + same + " profile").isEqualTo(expectedProfile);
                }
                assertThat(Files.readString(cases.resolve(files.path("layoutMoved").textValue())))
                        .as(name + ": a layout change must not change the exported document")
                        .isEqualTo(Files.readString(cases.resolve(files.path("emitted").textValue())));
                for (String different : List.of("reordered", "renamed")) {
                    if (!files.has(different)) continue;
                    String text = Files.readString(cases.resolve(files.path(different).textValue()));
                    BindingIrV1 changed;
                    try {
                        changed = BindingDocumentCompiler.compile(text, session);
                    } catch (IllegalArgumentException invalid) {
                        // Only a rename can make a governed leaf's committed participant setting invalid.
                        assertThat(different).as(name + ": a reordered document must compile").isEqualTo("renamed");
                        continue;
                    }
                    assertThat(Arrays.equals(changed.encode(), expected.encode())).as(name + " " + different).isFalse();
                    String changedProfile;
                    try {
                        changedProfile = profile(session, changed);
                    } catch (RuntimeException invalid) {
                        // As above, only a rename may leave a committed participant setting that no longer resolves.
                        assertThat(different).as(name + ": a reordered profile must construct").isEqualTo("renamed");
                        continue;
                    }
                    assertThat(changedProfile).as(name + " " + different + " profile").isNotEqualTo(expectedProfile);
                    if (different.equals("reordered")) reordered++;
                }
                checked.add(name);
            }
        }
        assertThat(checked).hasSizeGreaterThanOrEqualTo(10);
        assertThat(reordered).as("reordering must be exercised").isPositive();
        // The project model reads a Studio-spliced blueprint to the same chains and the same composite IR.
        var mapper = AppChainProjectRenderer.configured(new ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()));
        try (var environment = BindingPluginEnvironment.open(plugins)) {
            var session = new BindingCatalogSession(environment.providers(), tutorial);
            for (JsonNode item : manifest.path("blueprints")) {
                var original = mapper.readValue(cases.resolve(item.path("original").textValue()).toFile(),
                        AppChainProjectModel.Blueprint.class);
                var spliced = mapper.readValue(cases.resolve(item.path("spliced").textValue()).toFile(),
                        AppChainProjectModel.Blueprint.class);
                assertThat(spliced.spec().chains()).hasSameSizeAs(original.spec().chains());
                for (int index = 0; index < original.spec().chains().size(); index++) {
                    var before = original.spec().chains().get(index);
                    var after = spliced.spec().chains().get(index);
                    assertThat(after.chainId()).isEqualTo(before.chainId());
                    if (before.composite() == null) {
                        assertThat(after).isEqualTo(before);
                        continue;
                    }
                    assertThat(BindingDocumentCompiler.compile(after.composite(), session).encode())
                            .isEqualTo(BindingDocumentCompiler.compile(before.composite(), session).encode());
                }
                assertThat(spliced.metadata()).isEqualTo(original.metadata());
            }
            assertThat(manifest.path("blueprints").size()).isPositive();
        }
    }

    private static String profile(BindingCatalogSession session, BindingIrV1 ir) {
        Object digest = session.validate(ir).operationalStatus().get("activeProfileDigest");
        assertThat(digest).isInstanceOf(String.class);
        return (String) digest;
    }
}
