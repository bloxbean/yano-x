package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Studio language-construct corpus (ADR-031.2 M0) is valid against the real first-party bundles, so later
 * round-trip tests compare meaningful documents rather than documents that fail for unrelated reasons.
 */
class StudioCorpusIT {
    @TempDir Path temporary;

    @Test
    void everyCorpusDocumentValidatesThroughTheRealCatalog() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path corpus = repository.resolve("tooling/studio/src/test/fixtures/corpus");
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        Path tutorialContext = repository.resolve("tooling/studio/src/main/web/binding-authoring-context.json");
        List<Path> documents = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.list(corpus)) {
            documents.addAll(files.filter(path -> path.toString().endsWith(".yaml")).sorted().toList());
        }
        // Studio starters are first-party examples and must validate with the shipped tutorial context.
        documents.add(repository.resolve("examples/bindings/registry-to-audit.yaml"));
        documents.add(repository.resolve("examples/bindings/approval-to-audit.yaml"));
        assertThat(documents).hasSizeGreaterThanOrEqualTo(4);
        for (Path document : documents) {
            Path context = document.getFileName().toString().startsWith("effects")
                    ? corpus.resolve("context-effects.json") : tutorialContext;
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            int exit = new AppChainDevtoolsCli().run(new String[] {"bindings", "validate", document.toString(),
                    "--plugins-directory", plugins.toString(), "--context", context.toString()},
                    new PrintWriter(out), new PrintWriter(err));
            assertThat(exit).as(document.getFileName() + ": " + err).isZero();
        }
    }
}
