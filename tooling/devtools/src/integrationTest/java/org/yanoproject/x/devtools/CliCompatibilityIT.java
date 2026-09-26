package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Historical CLI compatibility for ADR-031.2 (existing consumers must remain qualified).
 *
 * <p>The expected files under {@code cli-compat/expected} were captured by running this same matrix with the
 * packaged {@code appchain} launcher and bundles built from the commit before ADR-031.2 tooling changes
 * ({@link #main}). The current packaged launcher must reproduce every standard output, standard error and exit
 * status byte for byte when {@code --report} is not used. Relative paths keep outputs location-independent.
 *
 * <p>The single intentional difference is the usage text, which gained lines for {@code catalog} and
 * {@code --report}. The gate requires the current usage to contain every historical usage line in order, then
 * substitutes the historical usage back before the byte comparison, so no other text may change.
 */
class CliCompatibilityIT {
    @TempDir Path temporary;

    /** Captures expected outputs: launcher, resources directory, bundle paths (path-separated), output directory. */
    public static void main(String[] args) throws Exception {
        Path launcher = Path.of(args[0]);
        Path resources = Path.of(args[1]);
        List<Path> bundles = Stream.of(args[2].split(File.pathSeparator)).map(Path::of).toList();
        Path expected = Path.of(args[3]);
        Files.createDirectories(expected);
        Path work = Files.createTempDirectory("cli-compat");
        for (var entry : run(launcher, resources, bundles, work).entrySet()) {
            Files.writeString(expected.resolve(entry.getKey() + ".out"), entry.getValue().out());
            Files.writeString(expected.resolve(entry.getKey() + ".err"), entry.getValue().err());
            Files.writeString(expected.resolve(entry.getKey() + ".exit"), entry.getValue().exit() + "\n");
        }
    }

    @Test
    void currentLauncherReproducesHistoricalOutputsExactly() throws Exception {
        Path resources = Path.of(System.getProperty("yano.test.repo-root"))
                .resolve("tooling/devtools/src/integrationTest/resources/cli-compat");
        List<Path> bundles = Stream.of(System.getProperty("yano.test.binding-bundles").split(File.pathSeparator))
                .map(Path::of).toList();
        var results = run(Path.of(System.getProperty("yano.test.appchain-cli")), resources, bundles, temporary);
        Path expected = resources.resolve("expected");
        String historicalUsage = Files.readString(expected.resolve("help.out"));
        String currentUsage = results.get("help").out();
        assertThat(historicalUsage).startsWith("Usage: appchain bindings");
        assertThat(isOrderedSubsequence(historicalUsage.lines().toList(), currentUsage.lines().toList()))
                .as("current usage keeps every historical line in order").isTrue();
        List<String> differences = new ArrayList<>();
        for (var entry : results.entrySet()) {
            String name = entry.getKey();
            String out = entry.getValue().out().replace(currentUsage, historicalUsage);
            String err = entry.getValue().err().replace(currentUsage, historicalUsage);
            if (!out.equals(Files.readString(expected.resolve(name + ".out")))) {
                differences.add(name + " stdout");
            }
            if (!err.equals(Files.readString(expected.resolve(name + ".err")))) {
                differences.add(name + " stderr");
            }
            if (!(entry.getValue().exit() + "\n").equals(Files.readString(expected.resolve(name + ".exit")))) {
                differences.add(name + " exit");
            }
        }
        assertThat(results).hasSizeGreaterThanOrEqualTo(30);
        assertThat(differences).isEmpty();
    }

    record Result(String out, String err, int exit) { }

    private static boolean isOrderedSubsequence(List<String> historical, List<String> current) {
        int position = 0;
        for (String line : current) {
            if (position < historical.size() && historical.get(position).equals(line)) position++;
        }
        return position == historical.size();
    }

    static Map<String, Result> run(Path launcher, Path resources, List<Path> bundles, Path work) throws Exception {
        Path inputs = Files.createDirectories(work.resolve("inputs"));
        try (Stream<Path> files = Files.list(resources.resolve("inputs"))) {
            for (Path file : files.toList()) Files.copy(file, inputs.resolve(file.getFileName()));
        }
        Path plugins = Files.createDirectories(work.resolve("plugins"));
        for (Path bundle : bundles) Files.copy(bundle, plugins.resolve(bundle.getFileName()));
        Path outputs = Files.createDirectories(work.resolve("outputs"));
        Map<String, Result> results = new LinkedHashMap<>();
        for (String line : Files.readAllLines(resources.resolve("matrix.tsv"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] fields = line.split("\t");
            List<String> command = new ArrayList<>();
            command.add(launcher.toString());
            for (int index = 1; index < fields.length; index++) command.add(fields[index]);
            Path out = outputs.resolve(fields[0] + ".out");
            Path err = outputs.resolve(fields[0] + ".err");
            Process process = new ProcessBuilder(command).directory(work.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            int exit = process.waitFor();
            results.put(fields[0], new Result(read(out), read(err), exit));
        }
        return results;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
