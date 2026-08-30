package com.bloxbean.cardano.yano.appchain.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DeploymentLifecycle {
    private final ObjectMapper json = new ObjectMapper();
    private final DeploymentRenderer renderer = new DeploymentRenderer();

    Path ensureRendered(DeploymentDocument document) throws IOException {
        List<String> errors = document.validate(true);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }
        String manifest = ShowcaseArtifact.digest(document.directory().resolve("deployment.yaml"));
        String identity = manifest.substring(0, 12) + "-" + document.artifactSha256().substring(0, 12)
                + "-r" + DeploymentRenderer.RENDER_REVISION;
        Path output = document.directory().resolve(".yano-deploy/render-" + identity);
        if (Files.exists(output.resolve("deployment.lock.json"))) {
            return output;
        }
        renderer.render(document, output);
        return output;
    }

    Path plan(DeploymentDocument document) throws IOException {
        Path output = ensureRendered(document);
        if (cloudNodes(document).isEmpty()) {
            System.out.println("No cloud resources: OpenTofu plan is empty; existing hosts remain externally managed.");
            return output;
        }
        Path tofu = output.resolve("tofu");
        verifyTofuVersion(document, tofu);
        Path backend = resolve(document, document.stateBackendConfigFile());
        if (!Files.isRegularFile(backend)) {
            throw new IOException("OpenTofu backend config file is missing");
        }
        run(tofu, List.of("tofu", "init", "-input=false", "-backend-config=" + backend));
        run(tofu, List.of("tofu", "validate"));
        run(tofu, List.of("tofu", "plan", "-input=false", "-out=cluster.tfplan"));
        return output;
    }

    Path apply(DeploymentDocument document, String confirmation) throws IOException {
        if (!document.clusterId().equals(confirmation)) {
            throw new IllegalArgumentException("--confirm must equal the immutable clusterId");
        }
        Path output = plan(document);
        Map<String, String> addresses = new LinkedHashMap<>();
        for (DeploymentDocument.Node node : document.nodes()) {
            if (!node.address().isBlank()) {
                addresses.put(node.name(), node.address());
            }
        }
        if (!cloudNodes(document).isEmpty()) {
            Path tofu = output.resolve("tofu");
            rejectDestructivePlan(tofu);
            run(tofu, List.of("tofu", "apply", "-input=false", "cluster.tfplan"));
            JsonNode outputs = output(tofu, List.of("tofu", "output", "-json"));
            for (DeploymentDocument.Node node : cloudNodes(document)) {
                String key = node.name().replace('-', '_') + "_address";
                String address = outputs.path(key).path("value").asText("");
                if (address.isBlank()) {
                    throw new IOException("OpenTofu did not return an address for " + node.name());
                }
                addresses.put(node.name(), address);
            }
        }
        renderer.resolveInventory(document, output, addresses);
        Path ansible = output.resolve("ansible");
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "deploy.yml", "--syntax-check"));
        run(ansible, List.of(
                "ansible-playbook", "-i", "inventory.yml", "mesh-recover.yml", "--syntax-check"));
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "deploy.yml"));
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "mesh-recover.yml"));
        if (document.monitoringEnabled()) {
            run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "monitoring.yml", "--syntax-check"));
            run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "monitoring.yml"));
        }
        return output;
    }

    Path configureMonitoring(DeploymentDocument document) throws IOException {
        if (!document.monitoringEnabled()) {
            throw new IllegalArgumentException("monitoring.mode must be central-prometheus");
        }
        Path output = ensureRendered(document);
        requireResolvedInventory(output);
        Path ansible = output.resolve("ansible");
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "monitoring.yml", "--syntax-check"));
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "monitoring.yml"));
        return output;
    }

    Path configureGateway(DeploymentDocument document) throws IOException {
        if ("disabled".equals(document.apiExposure())) {
            throw new IllegalArgumentException("access.api.exposure must be enabled");
        }
        Path output = ensureRendered(document);
        requireResolvedInventory(output);
        Path ansible = output.resolve("ansible");
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "gateway.yml", "--syntax-check"));
        run(ansible, List.of("ansible-playbook", "-i", "inventory.yml", "gateway.yml"));
        return output;
    }

    void status(DeploymentDocument document) throws IOException {
        Path output = ensureRendered(document);
        requireResolvedInventory(output);
        run(output.resolve("ansible"),
                List.of("ansible-playbook", "-i", "inventory.yml", "status.yml"));
    }

    Path bootstrapAnchors(DeploymentDocument document, String confirmedNetwork, String chain) throws IOException {
        if (!document.network().equals(confirmedNetwork)) {
            throw new IllegalArgumentException(
                    "--confirm-network must equal the configured public L1 network");
        }
        if (!"all".equals(chain) && !chain.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("--chain must be all or a lower-case chain ID");
        }
        Path output = ensureRendered(document);
        requireResolvedInventory(output);
        Path ansible = output.resolve("ansible");
        run(ansible, List.of(
                "ansible-playbook", "-i", "inventory.yml", "bootstrap-anchors.yml", "--syntax-check"));
        run(ansible, List.of(
                "ansible-playbook", "-i", "inventory.yml", "bootstrap-anchors.yml",
                "-e", "yano_confirm_public_anchor=" + confirmedNetwork,
                "-e", "yano_anchor_chain=" + chain));
        return output;
    }

    private void requireResolvedInventory(Path output) throws IOException {
        Path inventory = output.resolve("ansible/inventory.yml");
        if (Files.readString(inventory).contains("TOFU_PENDING")) {
            throw new IllegalStateException("inventory has unresolved cloud addresses; run apply first");
        }
    }

    private void rejectDestructivePlan(Path tofu) throws IOException {
        JsonNode plan = output(tofu, List.of("tofu", "show", "-json", "cluster.tfplan"));
        for (JsonNode change : plan.path("resource_changes")) {
            for (JsonNode action : change.path("change").path("actions")) {
                if ("delete".equals(action.asText())) {
                    throw new IllegalStateException(
                            "saved plan contains delete/replace actions; node replacement is a separate workflow");
                }
            }
        }
    }

    private void verifyTofuVersion(DeploymentDocument document, Path tofu) throws IOException {
        JsonNode version = output(tofu, List.of("tofu", "version", "-json"));
        if (!document.openTofuVersion().equals(version.path("terraform_version").asText())) {
            throw new IllegalStateException("OpenTofu version does not match spec.infrastructure.openTofuVersion");
        }
    }

    private Path resolve(DeploymentDocument document, String configured) {
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : document.directory().resolve(path).normalize();
    }

    private List<DeploymentDocument.Node> cloudNodes(DeploymentDocument document) {
        Map<String, DeploymentDocument.Provider> providers = document.providersByName();
        return document.nodes().stream()
                .filter(node -> !"existing".equals(providers.get(node.providerRef()).type())).toList();
    }

    private void run(Path directory, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException(command.getFirst() + " exited with status " + exit);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " was interrupted", failure);
        }
    }

    private JsonNode output(Path directory, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false).start();
        byte[] stdout;
        byte[] stderr;
        try {
            stdout = process.getInputStream().readAllBytes();
            stderr = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException(command.getFirst() + " exited with status " + exit + ": "
                        + firstLine(new String(stderr, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(command.getFirst() + " was interrupted", failure);
        }
        return json.readTree(stdout);
    }

    private String firstLine(String value) {
        return value.lines().findFirst().orElse("no diagnostic").replaceAll("[\\p{Cntrl}]", "?");
    }
}
