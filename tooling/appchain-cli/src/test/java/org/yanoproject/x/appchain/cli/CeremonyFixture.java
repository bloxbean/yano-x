package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs CLI verbs in-process against a minimal distribution directory, as one coordinator and its operators. */
final class CeremonyFixture {
    record Result(int exit, String out, String err) {
    }

    final Path root;
    final Path coordinator;
    final Distribution distribution;

    CeremonyFixture(Path root) throws IOException {
        this.root = root;
        this.coordinator = Files.createDirectories(root.resolve("coordinator"));
        Path home = Files.createDirectories(root.resolve("distribution"));
        Files.createDirectories(home.resolve("plugins"));
        Files.writeString(home.resolve("yano-distribution-v1.json"), "{\"version\":\"0.1.0-test\"}");
        Files.writeString(home.resolve("yano-x-distribution-v1.json"), "{\"version\":\"0.1.0-x-test\"}");
        this.distribution = new Distribution(home);
    }

    Result run(String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = AppChainCli.run(arguments, new Terminal(null, new PrintStream(out, true, StandardCharsets.UTF_8),
                false), new PrintStream(err, true, StandardCharsets.UTF_8), distribution);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    Result ok(String... arguments) {
        Result result = run(arguments);
        if (result.exit() != 0) {
            throw new AssertionError(String.join(" ", arguments) + " failed (" + result.exit() + "): " + result.err()
                    + result.out());
        }
        return result;
    }

    Path draft(String app) {
        return coordinator.resolve(app + ".draft.yaml");
    }

    Path sealedFile(String app) {
        return coordinator.resolve(app + ".chain");
    }

    SealedApp sealed(String app) {
        return SealedApp.parse(NodeDir.read(sealedFile(app)));
    }

    Path node(String operator) {
        return root.resolve(operator);
    }

    /** Drafts an application and makes one card per operator, with ports starting at the given offset. */
    void draftAndCards(String app, String network, List<String> operators, String... chains) {
        List<String> create = new ArrayList<>(List.of("new", app, "--network", network,
                "--operators", String.join(",", operators), "--output", coordinator.toString(), "--yes"));
        for (String chain : chains) {
            create.add("--chain");
            create.add(chain);
        }
        ok(create.toArray(String[]::new));
        int index = 0;
        for (String operator : operators) {
            card(draft(app), operator, index++);
        }
    }

    Result card(Path source, String operator, int index) {
        return ok("request", source.toString(), "--name", operator, "--host", operator + ".example",
                "--node-port", Integer.toString(13337 + index), "--http-port", Integer.toString(8080 + index),
                "--node-dir", node(operator).toString(), "--output", coordinator.toString(), "--yes");
    }

    String[] join(String app, String operator, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("join", sealedFile(app).toString(), "--expect-fingerprint",
                sealed(app).displayFingerprint(), "--node-dir", node(operator).toString()));
        arguments.addAll(Arrays.asList(extra));
        return arguments.toArray(String[]::new);
    }
}
