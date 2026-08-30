package com.bloxbean.cardano.yano.appchain.deployment;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Provider-neutral command line entry point for Yano X deployments. */
public final class YanoDeploymentCli {
    static final int EXIT_OK = 0;
    static final int EXIT_INVALID = 2;
    static final int EXIT_USAGE = 64;
    static final int EXIT_IO = 74;

    private static final String USAGE = """
            Usage: yano-x-deploy init <cluster-directory>
               or: yano-x-deploy artifact import <cluster-directory> --file <showcase.zip>
               or: yano-x-deploy validate <cluster-directory>
               or: yano-x-deploy render <cluster-directory> [--output <empty-directory>]
               or: yano-x-deploy plan <cluster-directory>
               or: yano-x-deploy apply <cluster-directory> --confirm <cluster-id>
               or: yano-x-deploy bootstrap-anchors <cluster-directory> --confirm-network <network>
                       [--chain <chain-id|all>]
               or: yano-x-deploy gateway <cluster-directory>
               or: yano-x-deploy monitoring <cluster-directory>
               or: yano-x-deploy status <cluster-directory>

            Provider credentials are accepted only through the providers' environment variables
            (CNTB_OAUTH2_* for Contabo). This CLI never accepts secret values as arguments.
            """.stripTrailing();

    private final DeploymentLoader loader = new DeploymentLoader();
    private final ShowcaseArtifact artifacts = new ShowcaseArtifact();
    private final DeploymentRenderer renderer = new DeploymentRenderer();
    private final DeploymentLifecycle lifecycle = new DeploymentLifecycle();

    public static void main(String[] args) {
        int exit = new YanoDeploymentCli().run(args, new PrintWriter(System.out, true),
                new PrintWriter(System.err, true));
        if (exit != 0) {
            System.exit(exit);
        }
    }

    int run(String[] args, PrintWriter out, PrintWriter err) {
        if (args.length == 0 || List.of("help", "-h", "--help").contains(args[0])) {
            out.println(USAGE);
            return EXIT_OK;
        }
        try {
            return dispatch(args, out);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            err.println("Deployment is invalid: " + safe(failure.getMessage()));
            return EXIT_INVALID;
        } catch (IOException failure) {
            err.println("Deployment operation failed: " + safe(failure.getMessage()));
            return EXIT_IO;
        }
    }

    private int dispatch(String[] args, PrintWriter out) throws IOException {
        return switch (args[0]) {
            case "init" -> init(requireDirectory(args, 1), out);
            case "artifact" -> artifact(args, out);
            case "validate" -> validate(loader.load(requireDirectory(args, 1)), out);
            case "render" -> render(args, out);
            case "plan" -> plan(loader.load(requireDirectory(args, 1)), out);
            case "apply" -> apply(args, out);
            case "bootstrap-anchors" -> bootstrapAnchors(args, out);
            case "gateway" -> gateway(loader.load(requireDirectory(args, 1)), out);
            case "monitoring" -> monitoring(loader.load(requireDirectory(args, 1)), out);
            case "status" -> status(loader.load(requireDirectory(args, 1)), out);
            default -> throw new IllegalArgumentException("unknown command; use --help");
        };
    }

    private int init(Path directory, PrintWriter out) throws IOException {
        Path target = directory.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IOException("cluster target must be a real directory");
            }
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("cluster target must be empty");
                }
            }
        } else {
            Files.createDirectories(target);
        }
        String manifest = template(UUID.randomUUID().toString());
        Files.writeString(target.resolve("deployment.yaml"), manifest,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.writeString(target.resolve(".gitignore"), "artifacts/\n.yano-deploy/\nsecrets/\n",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        out.println("Initialized " + target);
        out.println("Edit deployment.yaml, place member seeds outside version control, then import the showcase ZIP.");
        return EXIT_OK;
    }

    private int artifact(String[] args, PrintWriter out) throws IOException {
        if (args.length < 5 || !"import".equals(args[1])) {
            throw new IllegalArgumentException("usage: artifact import <directory> --file <showcase.zip>");
        }
        DeploymentDocument document = loader.load(Path.of(args[2]));
        Path archive = option(args, "--file");
        List<String> errors = document.validate(false);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }
        ShowcaseArtifact.Metadata metadata = artifacts.importInto(document, archive);
        loader.write(document);
        out.println("Imported showcase archive sha256:" + metadata.sha256());
        out.println("Locked 13 active demonstration chains with preprod anchoring and settlement; "
                + "Kafka, S3 effects, and IPFS are excluded.");
        return EXIT_OK;
    }

    private int validate(DeploymentDocument document, PrintWriter out) throws IOException {
        List<String> errors = document.validate(true);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }
        ShowcaseArtifact.Metadata metadata = artifacts.inspect(document.artifactPath());
        if (!document.artifactSha256().equals(metadata.sha256())) {
            throw new IllegalArgumentException("runtime artifact checksum differs from deployment.yaml");
        }
        out.printf("Valid: %s (%s), %d nodes, %d validators, providers=%s%n",
                document.name(), document.clusterId(), document.nodes().size(), document.validators().size(),
                document.providers().stream().map(DeploymentDocument.Provider::type).distinct().toList());
        return EXIT_OK;
    }

    private int render(String[] args, PrintWriter out) throws IOException {
        DeploymentDocument document = loader.load(requireDirectory(args, 1));
        Path output = hasOption(args, "--output") ? option(args, "--output")
                : document.directory().resolve("rendered");
        DeploymentRenderer.Rendered rendered = renderer.render(document, output);
        out.printf("Rendered %d nodes (%d cloud) to %s%nlock sha256:%s%n",
                rendered.nodes(), rendered.cloudNodes(), rendered.output(), rendered.lockSha256());
        return EXIT_OK;
    }

    private int plan(DeploymentDocument document, PrintWriter out) throws IOException {
        Path output = lifecycle.plan(document);
        out.println("Plan completed in " + output);
        return EXIT_OK;
    }

    private int apply(String[] args, PrintWriter out) throws IOException {
        DeploymentDocument document = loader.load(requireDirectory(args, 1));
        String confirmation = option(args, "--confirm").toString();
        Path output = lifecycle.apply(document, confirmation);
        out.println("Deployment converged from " + output);
        return EXIT_OK;
    }

    private int status(DeploymentDocument document, PrintWriter out) throws IOException {
        lifecycle.status(document);
        out.println("All queried members returned status.");
        return EXIT_OK;
    }

    private int bootstrapAnchors(String[] args, PrintWriter out) throws IOException {
        DeploymentDocument document = loader.load(requireDirectory(args, 1));
        String confirmedNetwork = option(args, "--confirm-network").toString();
        String chain = hasOption(args, "--chain") ? option(args, "--chain").toString() : "all";
        Path output = lifecycle.bootstrapAnchors(document, confirmedNetwork, chain);
        out.printf("Anchor bootstrap reconciliation completed for %s from %s%n", chain, output);
        return EXIT_OK;
    }

    private int monitoring(DeploymentDocument document, PrintWriter out) throws IOException {
        Path output = lifecycle.configureMonitoring(document);
        out.println("Central Prometheus monitoring converged from " + output);
        return EXIT_OK;
    }

    private int gateway(DeploymentDocument document, PrintWriter out) throws IOException {
        Path output = lifecycle.configureGateway(document);
        out.println("API gateways converged from " + output);
        return EXIT_OK;
    }

    private Path requireDirectory(String[] args, int index) {
        if (args.length <= index || args[index].startsWith("--")) {
            throw new IllegalArgumentException("cluster directory is required");
        }
        return Path.of(args[index]);
    }

    private Path option(String[] args, String name) {
        for (int index = 0; index < args.length; index++) {
            if (name.equals(args[index]) && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                return Path.of(args[index + 1]);
            }
        }
        throw new IllegalArgumentException(name + " is required");
    }

    private boolean hasOption(String[] args, String name) {
        return Arrays.asList(args).contains(name);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "no diagnostic";
        }
        String sanitized = value.lines().findFirst().orElse("no diagnostic")
                .replaceAll("[\\p{Cntrl}]", "?");
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }

    private String template(String clusterId) {
        return """
                apiVersion: yano.bloxbean.com/v1alpha1
                kind: YanoClusterDeployment
                metadata:
                  name: yano-preprod
                spec:
                  identity:
                    clusterId: "%s"
                  l1:
                    network: preprod
                  providers:
                    - name: internal
                      type: existing
                  nodes:
                    - name: node-0
                      index: 0
                      providerRef: internal
                      address: "192.0.2.10"
                      sshUser: yano-admin
                      roles: [validator, api-gateway]
                      memberPublicKey: "<64-hex>"
                      memberPrivateKeyFile: secrets/node-0.seed
                    - name: node-1
                      index: 1
                      providerRef: internal
                      address: "192.0.2.11"
                      sshUser: yano-admin
                      roles: [validator, api-gateway]
                      memberPublicKey: "<64-hex>"
                      memberPrivateKeyFile: secrets/node-1.seed
                    - name: node-2
                      index: 2
                      providerRef: internal
                      address: "192.0.2.12"
                      sshUser: yano-admin
                      roles: [validator]
                      memberPublicKey: "<64-hex>"
                      memberPrivateKeyFile: secrets/node-2.seed
                    - name: node-3
                      index: 3
                      providerRef: internal
                      address: "192.0.2.13"
                      sshUser: yano-admin
                      roles: [validator]
                      memberPublicKey: "<64-hex>"
                      memberPrivateKeyFile: secrets/node-3.seed
                    - name: node-4
                      index: 4
                      providerRef: internal
                      address: "192.0.2.14"
                      sshUser: yano-admin
                      roles: [validator]
                      memberPublicKey: "<64-hex>"
                      memberPrivateKeyFile: secrets/node-4.seed
                  consensus:
                    threshold: 4
                    sequencer: {mode: fixed, proposerNode: node-0}
                  network:
                    p2pPort: 13337
                    httpPort: 7070
                  runtime:
                    artifact:
                      kind: local-showcase-zip
                      file: ""
                      sha256: ""
                  access:
                    ssh:
                      mode: allowlist
                      sourceCidrs: ["192.0.2.0/24"]
                    api:
                      exposure: disabled
                      sourceCidrs: []
                      rateLimit: {average: 20, burst: 40}
                      cors: {allowedOrigins: []}
                  destructionProtection: true
                """.formatted(clusterId);
    }
}
