package com.bloxbean.cardano.yano.appchain.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ShowcaseProfileCompiler {
    private static final String GENERATOR =
            "com.bloxbean.cardano.yano.appchain.showcase.ShowcaseAuthenticatedMapConfig";
    private static final Pattern PROPERTY = Pattern.compile(
            "yano\\.app-chain\\.chains\\[(8|9)]\\.[a-z0-9.-]+=[^\\r\\n]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXCLUDED = Set.of(
            "kafka", "objectstore-s3", "ipfs", "evidence-profile", "evidence-registry",
            "effects-cardano", "eutxo-bridge-cardano", "eutxo-zk");

    String compile(
            ShowcaseArtifact.Metadata artifact,
            List<String> memberPublicKeys,
            int threshold) throws IOException {
        Path temporary = Files.createTempDirectory("yano-x-deployment-profile-");
        try {
            List<Path> classpath = extractClasspath(artifact, temporary);
            Path validator = classpath.stream()
                    .filter(path -> path.getFileName().toString().contains("authenticated-map-validators"))
                    .findFirst().orElseThrow(() -> new IOException("authenticated-map validator bundle is absent"));
            String members = String.join(",", memberPublicKeys);
            String mpf = generate(classpath, validator, "authenticated-map-chain", "8", members, threshold);
            String jmt = generate(classpath, validator, "authenticated-map-jmt-chain", "9", members, threshold);
            return validate(mpf + jmt);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private List<Path> extractClasspath(ShowcaseArtifact.Metadata artifact, Path target) throws IOException {
        List<Path> result = new ArrayList<>();
        String prefix = artifact.rootDirectory() + "/yano/";
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String relative = entry.getName().startsWith(prefix)
                        ? entry.getName().substring(prefix.length()) : "";
                boolean yanoJar = "yano.jar".equals(relative);
                boolean plugin = relative.startsWith("plugins/") && relative.endsWith(".jar")
                        && EXCLUDED.stream().noneMatch(relative::contains);
                if (!yanoJar && !plugin) {
                    continue;
                }
                Path file = target.resolve(Path.of(relative).getFileName().toString());
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
                }
                result.add(file);
            }
        }
        if (result.stream().noneMatch(path -> "yano.jar".equals(path.getFileName().toString()))) {
            throw new IOException("showcase archive does not contain yano/yano.jar");
        }
        return List.copyOf(result);
    }

    private String generate(List<Path> classpath, Path validator, String chainId, String index,
            String members, int threshold) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(classpath.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(File.pathSeparator)));
        command.add(GENERATOR);
        command.add("--validator-bundle");
        command.add(validator.toString());
        command.add("--chain-id");
        command.add(chainId);
        command.add("--members");
        command.add(members);
        command.add("--threshold");
        command.add(Integer.toString(threshold));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("authenticated-map profile generation timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("authenticated-map profile generation was interrupted", failure);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("release-matched authenticated-map profile generation failed: "
                    + firstLine(output));
        }
        return output.replace("chains[0]", "chains[" + index + "]");
    }

    private String validate(String output) throws IOException {
        StringBuilder result = new StringBuilder();
        int lines = 0;
        for (String line : output.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            if (!PROPERTY.matcher(line).matches() || ++lines > 8) {
                throw new IOException("profile compiler emitted an unexpected property");
            }
            result.append(line).append('\n');
        }
        if (lines != 8) {
            throw new IOException("profile compiler did not emit the expected eight identity properties");
        }
        return result.toString();
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private String firstLine(String text) {
        return text.lines().findFirst().orElse("no diagnostic").replaceAll("[\\p{Cntrl}]", "?");
    }

    private void deleteTemporary(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
