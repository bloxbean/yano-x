package org.yanoproject.x.deployment;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class DeploymentRenderer {
    static final int RENDER_REVISION = 30;
    private static final String CARDANO_HISTORY_CHAIN = "cardano-history-chain";
    private static final String SETTLEMENT_CHAIN = "payment-chain-settlement";
    private static final String WORKFLOW_CHAIN = "workflow-chain";
    private static final String TRAEFIK_VERSION = "3.7.11";
    private static final String TRAEFIK_AMD64_SHA256 =
            "0bd0b7152805af906a9feb5aec95e96322237d715ae89c4fe3adbbc07a63b631";
    private static final String TRAEFIK_ARM64_SHA256 =
            "7b3c0469a2716974e7ec512c2c4d6071afcb1353531f09cb807ee5041d3d5c9e";
    private static final String PROMETHEUS_IMAGE =
            "prom/prometheus:v3.13.1@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893";
    private static final List<String> CLOUDFLARE_PROXY_CIDRS = List.of(
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22", "2400:cb00::/32",
            "2606:4700::/32", "2803:f800::/32", "2405:b500::/32", "2405:8100::/32",
            "2a06:98c0::/29", "2c0f:f248::/32");
    private static final Set<String> FORBIDDEN_PLUGIN_MARKERS = Set.of(
            "kafka", "objectstore-s3", "ipfs", "evidence-profile", "evidence-registry",
            "effects-cardano", "eutxo-zk");
    private static final String CLOUD_INIT = """
            #cloud-config
            package_update: true
            packages: [python3, unzip, ufw]
            runcmd:
              - [ufw, default, deny, incoming]
              - [ufw, default, allow, outgoing]
              - [ufw, --force, enable]
            """;

    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private final ShowcaseArtifact artifacts = new ShowcaseArtifact();
    private final SettlementProfileCompiler settlementCompiler = new SettlementProfileCompiler();
    private final ProfileGenerator profileCompiler;

    DeploymentRenderer() {
        this(new ShowcaseProfileCompiler()::compile);
    }

    DeploymentRenderer(ProfileGenerator profileCompiler) {
        this.profileCompiler = java.util.Objects.requireNonNull(profileCompiler, "profileCompiler");
    }

    Rendered render(DeploymentDocument document, Path requestedOutput) throws IOException {
        List<String> errors = document.validate(true);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(System.lineSeparator(), errors));
        }
        ShowcaseArtifact.Metadata artifact = artifacts.inspect(document.artifactPath());
        if (!document.artifactSha256().equals(artifact.sha256())) {
            throw new IllegalArgumentException("imported showcase archive does not match its locked SHA-256");
        }
        artifacts.verifyImportLock(document, artifact);
        List<String> unknownAnchorAccounts = document.anchorChainSeedFiles().keySet().stream()
                .filter(chainId -> !artifact.chains().contains(chainId)).sorted().toList();
        if (!unknownAnchorAccounts.isEmpty()) {
            throw new IllegalArgumentException(
                    "anchoring chainSeedFiles contains unknown catalog chains: " + unknownAnchorAccounts);
        }
        Path output = prepareOutput(requestedOutput);
        Path tofu = Files.createDirectories(output.resolve("tofu"));
        Path ansible = Files.createDirectories(output.resolve("ansible"));
        Files.createDirectories(ansible.resolve("files"));
        Files.createDirectories(ansible.resolve("templates"));
        Path settlementScripts = Files.createDirectories(ansible.resolve("files/settlement"));
        SettlementProfileCompiler.Compiled settlement = settlementCompiler.compile(document, settlementScripts);

        write(tofu.resolve("main.tf"), terraform(document));
        write(tofu.resolve(".gitignore"), ".terraform/\n*.tfstate\n*.tfstate.*\n*.tfplan\n");
        write(ansible.resolve("inventory.yml"), inventory(document, Map.of()));
        write(ansible.resolve("deploy.yml"), playbook());
        write(ansible.resolve("gateway.yml"), gatewayPlaybook());
        write(ansible.resolve("monitoring.yml"), monitoringPlaybook());
        write(ansible.resolve("mesh-recover.yml"), meshRecoveryPlaybook());
        write(ansible.resolve("preflight.yml"), preflightPlaybook());
        write(ansible.resolve("bootstrap-anchors.yml"), anchorBootstrapPlaybook());
        write(ansible.resolve("bootstrap-one-anchor.yml"), anchorBootstrapChainTasks());
        write(ansible.resolve("status.yml"), statusPlaybook());
        write(ansible.resolve("reset.yml"), resetPlaybook());
        write(ansible.resolve("files/yano-x.service"), serviceUnit(document));
        write(ansible.resolve("files/yano-x.env"), jvmEnvironment());
        write(ansible.resolve("files/traefik.service"), traefikServiceUnit());
        write(ansible.resolve("files/prometheus-compose.yml"), prometheusCompose());
        write(ansible.resolve("templates/node.properties.j2"), nodeProperties(document, artifact));
        write(ansible.resolve("templates/traefik.yml.j2"), traefikConfiguration());
        write(ansible.resolve("templates/traefik-yano.yml.j2"), traefikYanoConfiguration());
        write(ansible.resolve("templates/prometheus.yml.j2"), prometheusConfiguration());
        write(ansible.resolve("files/application-appchain.yml"),
                sanitizedApplication(document, artifact, settlement));
        write(ansible.resolve("files/active-plugin-policy.txt"), activePluginPolicy());

        Map<String, String> checksums = checksums(output);
        Map<String, Object> lock = new LinkedHashMap<>();
        lock.put("schemaVersion", 1);
        lock.put("rendererRevision", RENDER_REVISION);
        lock.put("kind", "YanoClusterDeploymentLock");
        lock.put("clusterName", document.name());
        lock.put("clusterId", document.clusterId());
        lock.put("network", document.network());
        lock.put("l1LaunchProfile", document.launchProfile());
        Map<String, Object> sequencer = new LinkedHashMap<>();
        sequencer.put("mode", document.sequencerMode());
        if ("fixed".equals(document.sequencerMode())) {
            sequencer.put("proposerNode", document.sequencerProposerNode());
            sequencer.put("proposerPublicKey", document.sequencerProposerPublicKey());
        } else {
            sequencer.put("windowSlots", document.sequencerWindowSlots());
        }
        lock.put("consensus", Map.of(
                "threshold", document.threshold(),
                "sequencer", sequencer));
        lock.put("appChainTransport", Map.of(
                "connectionsPerPeer", appConnectionsPerPeer(artifact),
                "maxInboundConnections", maxInboundConnections(document, artifact)));
        lock.put("meshRecovery", Map.of(
                "strategy", "coordinated-clean-restart",
                "reason", "pinned-yano-dedicated-peer-reconnect",
                "verification", "all-chains-all-peers"));
        lock.put("manifestSha256", ShowcaseArtifact.digest(document.directory().resolve("deployment.yaml")));
        lock.put("artifactSha256", artifact.sha256());
        lock.put("yanoIdentitySha256", artifact.yanoIdentitySha256());
        lock.put("yanoXIdentitySha256", artifact.yanoXIdentitySha256());
        lock.put("applicationProfile", document.applicationProfile());
        lock.put("cardanoHistory", Map.of(
                "l1Observations", document.cardanoHistoryL1Observations(),
                "preset", document.cardanoHistoryPreset(),
                "genesisId", document.cardanoHistoryGenesisId(),
                "sourceSnapshotRetentionEpochs", document.cardanoHistorySourceSnapshotRetentionEpochs()));
        lock.put("activeChainIds", artifact.chains());
        lock.put("excludedChainIds", List.of());
        lock.put("excludedIntegrations", List.of("kafka", "s3-effects", "ipfs"));
        lock.put("anchoring", Map.of(
                "mode", document.anchoringMode(), "chains", document.anchoredChains(),
                "leaderNode", document.anchorLeaderNode(), "everyBlocks", document.anchorEveryBlocks(),
                "maxIntervalMinutes", document.anchorMaxIntervalMinutes(),
                "accountStrategy", "shared-default-with-per-chain-overrides",
                "perChainAccountOverrides", document.anchorChainSeedFiles().keySet().stream().sorted().toList()));
        lock.put("settlement", Map.of(
                "mode", document.settlementMode(), "chainId", document.settlementChainId(),
                "ownerNode", document.settlementOwnerNode(), "rootAddress", settlement.rootAddress(),
                "vaultAddress", settlement.vaultAddress()));
        lock.put("sshAccessMode", document.sshAccessMode());
        lock.put("apiGateway", Map.of(
                "exposure", document.apiExposure(), "proxy", document.apiProxy(),
                "implementation", "traefik", "version", TRAEFIK_VERSION,
                "rateLimitAverage", document.apiRateLimitAverage(),
                "rateLimitBurst", document.apiRateLimitBurst(),
                "corsAllowedOrigins", document.apiCorsAllowedOrigins(),
                "hostnames", document.nodes().stream().filter(node -> !node.hostname().isBlank())
                        .map(DeploymentDocument.Node::hostname).toList()));
        lock.put("monitoring", Map.of(
                "mode", document.monitoringMode(),
                "node", document.monitoringNode(),
                "hostname", document.monitoringHostname(),
                "proxy", document.monitoringProxy(),
                "exposure", document.monitoringExposure(),
                "retention", document.monitoringRetention(),
                "retentionSize", document.monitoringRetentionSize(),
                "scrapePort", document.monitoringScrapePort(),
                "prometheusImage", PROMETHEUS_IMAGE));
        lock.put("openTofuVersion", document.openTofuVersion());
        lock.put("stateBackendType", document.stateBackendType());
        lock.put("providers", document.providers().stream().map(provider -> Map.of(
                "name", provider.name(), "type", provider.type(), "version", provider.version())).toList());
        lock.put("files", checksums);
        byte[] lockBytes = canonicalJson(lock);
        writeBytes(output.resolve("deployment.lock.json"), lockBytes);
        String lockDigest = ShowcaseArtifact.digest(output.resolve("deployment.lock.json"));
        return new Rendered(output, lockDigest, document.nodes().size(), cloudNodes(document).size());
    }

    void resolveInventory(DeploymentDocument document, Path output, Map<String, String> addresses)
            throws IOException {
        replace(output.resolve("ansible/inventory.yml"), inventory(document, addresses));
        refreshLockedFile(output, "ansible/inventory.yml");
        verifyRendered(document, output);
    }

    void verifyRendered(DeploymentDocument document, Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        Path lockPath = normalized.resolve("deployment.lock.json");
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("deployment render lock is missing or is not a regular file");
        }
        JsonNode lock = json.readTree(lockPath.toFile());
        if (lock.path("schemaVersion").asInt() != 1
                || !"YanoClusterDeploymentLock".equals(lock.path("kind").asText())
                || lock.path("rendererRevision").asInt() != RENDER_REVISION
                || !document.clusterId().equals(lock.path("clusterId").asText())
                || !document.artifactSha256().equals(lock.path("artifactSha256").asText())
                || !ShowcaseArtifact.digest(document.directory().resolve("deployment.yaml"))
                        .equals(lock.path("manifestSha256").asText())) {
            throw new IOException("deployment render lock does not match the current manifest, artifact, or renderer");
        }
        JsonNode files = lock.path("files");
        if (!files.isObject() || files.isEmpty()) {
            throw new IOException("deployment render lock contains no generated-file checksums");
        }
        var entries = files.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            Path file = normalized.resolve(entry.getKey()).normalize();
            if (!file.startsWith(normalized) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !entry.getValue().asText().equals(ShowcaseArtifact.digest(file))) {
                throw new IOException("generated deployment file differs from its lock: " + entry.getKey());
            }
        }
    }

    private void refreshLockedFile(Path output, String relative) throws IOException {
        Path lockPath = output.resolve("deployment.lock.json");
        ObjectNode lock = (ObjectNode) json.readTree(lockPath.toFile());
        ObjectNode files = (ObjectNode) lock.path("files");
        files.put(relative, ShowcaseArtifact.digest(output.resolve(relative)));
        replaceBytes(lockPath, canonicalJson(lock));
    }

    private Path prepareOutput(Path requested) throws IOException {
        Path output = requested.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(output)) {
                throw new IOException("render output must be a real directory");
            }
            try (var entries = Files.list(output)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException("render output must not exist or must be empty: " + output);
                }
            }
        } else {
            Files.createDirectories(output);
        }
        return output;
    }

    private String terraform(DeploymentDocument document) {
        String requiredVersion = document.openTofuVersion().isBlank()
                ? ">= 1.8.0, < 2.0.0" : "= " + document.openTofuVersion();
        StringBuilder hcl = new StringBuilder("terraform {\n  required_version = \"")
                .append(escape(requiredVersion)).append("\"\n");
        if (!document.stateBackendType().isBlank()) {
            hcl.append("  backend \"").append(escape(document.stateBackendType())).append("\" {}\n");
        }
        hcl.append("  required_providers {\n");
        document.providers().stream().filter(provider -> !"existing".equals(provider.type()))
                .sorted(Comparator.comparing(DeploymentDocument.Provider::type)).forEach(provider -> {
                    String local = providerLocal(provider.type());
                    hcl.append("    ").append(local).append(" = { source = \"")
                            .append(providerSource(provider.type())).append("\", version = \"=")
                            .append(escape(provider.version())).append("\" }\n");
                });
        hcl.append("  }\n}\n\n");
        Map<String, DeploymentDocument.Provider> providers = document.providersByName();
        for (DeploymentDocument.Node node : cloudNodes(document)) {
            DeploymentDocument.Provider provider = providers.get(node.providerRef());
            appendCloudNode(hcl, document, provider, node);
        }
        return hcl.toString();
    }

    private void appendCloudNode(
            StringBuilder hcl,
            DeploymentDocument document,
            DeploymentDocument.Provider provider,
            DeploymentDocument.Node node) {
        String resourceName = node.name().replace('-', '_');
        switch (provider.type()) {
            case "contabo" -> appendContabo(hcl, document, provider, node, resourceName);
            case "hetzner-cloud" -> appendHetzner(hcl, document, provider, node, resourceName);
            case "digitalocean" -> appendDigitalOcean(hcl, document, provider, node, resourceName);
            default -> throw new IllegalArgumentException("unsupported cloud provider: " + provider.type());
        }
    }

    private void appendContabo(StringBuilder hcl, DeploymentDocument document,
            DeploymentDocument.Provider provider, DeploymentDocument.Node node, String resource) {
        hcl.append("resource \"contabo_instance\" \"").append(resource).append("\" {\n")
                .append(attribute("display_name", document.name() + "-" + node.name()))
                .append(attribute("product_id", node.instanceType()))
                .append(attribute("region", node.region()))
                .append(attribute("image_id", node.image()))
                .append(attribute("default_user", node.sshUser()))
                .append(numberList("ssh_keys", provider.settings().path("sshKeyIds")))
                .append(attribute("user_data", CLOUD_INIT))
                .append(lifecycle(document)).append("}\n\n");
        hcl.append("resource \"contabo_firewall\" \"").append(resource).append("\" {\n")
                .append(attribute("name", document.name() + "-" + node.name()))
                .append("  status = \"enabled\"\n  instance_ids = [contabo_instance.")
                .append(resource).append(".id]\n")
                .append(contaboRules(document, node)).append(lifecycle(document)).append("}\n\n")
                .append(output(node, "contabo_instance." + resource + ".ip_config[0].v4[0].ip"));
    }

    private void appendHetzner(StringBuilder hcl, DeploymentDocument document,
            DeploymentDocument.Provider provider, DeploymentDocument.Node node, String resource) {
        hcl.append("resource \"hcloud_firewall\" \"").append(resource).append("\" {\n")
                .append(attribute("name", document.name() + "-" + node.name()))
                .append(hcloudRules(document, node))
                .append("  apply_to { server = hcloud_server.").append(resource).append(".id }\n")
                .append("}\n\n")
                .append("resource \"hcloud_server\" \"").append(resource).append("\" {\n")
                .append(attribute("name", document.name() + "-" + node.name()))
                .append(attribute("server_type", node.instanceType()))
                .append(attribute("image", node.image()))
                .append(attribute("location", node.region()))
                .append(stringList("ssh_keys", provider.settings().path("sshKeys")))
                .append(attribute("user_data", CLOUD_INIT))
                .append("  delete_protection = true\n  rebuild_protection = true\n")
                .append(lifecycle(document)).append("}\n\n")
                .append(output(node, "hcloud_server." + resource + ".ipv4_address"));
    }

    private void appendDigitalOcean(StringBuilder hcl, DeploymentDocument document,
            DeploymentDocument.Provider provider, DeploymentDocument.Node node, String resource) {
        hcl.append("resource \"digitalocean_droplet\" \"").append(resource).append("\" {\n")
                .append(attribute("name", document.name() + "-" + node.name()))
                .append(attribute("size", node.instanceType()))
                .append(attribute("image", node.image()))
                .append(attribute("region", node.region()))
                .append(stringList("ssh_keys", provider.settings().path("sshKeys")))
                .append("  monitoring = true\n")
                .append(attribute("user_data", CLOUD_INIT)).append(lifecycle(document)).append("}\n\n")
                .append("resource \"digitalocean_firewall\" \"").append(resource).append("\" {\n")
                .append(attribute("name", document.name() + "-" + node.name()))
                .append("  droplet_ids = [digitalocean_droplet.").append(resource).append(".id]\n")
                .append(digitalOceanRules(document, node)).append("}\n\n")
                .append(output(node, "digitalocean_droplet." + resource + ".ipv4_address"));
    }

    private String contaboRules(DeploymentDocument document, DeploymentDocument.Node node) {
        StringBuilder rules = new StringBuilder("  rules {\n");
        for (String cidr : effectiveSshCidrs(document)) {
            rules.append(contaboInbound("TCP", Integer.toString(node.sshPort()), cidr));
        }
        p2pSources(document, node).forEach(cidr -> rules.append(
                contaboInbound("TCP", Integer.toString(document.p2pPort()), cidr)));
        if (apiGateway(document, node)) {
            effectiveApiCidrs(document).forEach(cidr -> {
                rules.append(contaboInbound("TCP", "80", cidr));
                rules.append(contaboInbound("TCP", "443", cidr));
            });
        }
        return rules.append("  }\n").toString();
    }

    private String contaboInbound(String protocol, String port, String cidr) {
        String family = cidr.contains(":") ? "ipv6" : "ipv4";
        return "    inbound {\n"
                + "      protocol = \"" + protocol + "\"\n"
                + "      action = \"allow\"\n"
                + "      status = \"enabled\"\n"
                + "      dest_ports = [\"" + port + "\"]\n"
                + "      src_cidr {\n"
                + "        " + family + " = [\"" + escape(cidr) + "\"]\n"
                + "      }\n"
                + "    }\n";
    }

    private String hcloudRules(DeploymentDocument document, DeploymentDocument.Node node) {
        StringBuilder rules = new StringBuilder();
        effectiveSshCidrs(document).forEach(cidr -> rules.append(hcloudRule(node.sshPort(), cidr)));
        p2pSources(document, node).forEach(cidr -> rules.append(hcloudRule(document.p2pPort(), cidr)));
        if (apiGateway(document, node)) {
            effectiveApiCidrs(document).forEach(cidr -> {
                rules.append(hcloudRule(80, cidr));
                rules.append(hcloudRule(443, cidr));
            });
        }
        return rules.toString();
    }

    private String hcloudRule(int port, String cidr) {
        return "  rule {\n"
                + "    direction = \"in\"\n"
                + "    protocol = \"tcp\"\n"
                + "    port = \"" + port + "\"\n"
                + "    source_ips = [\"" + escape(cidr) + "\"]\n"
                + "  }\n";
    }

    private String digitalOceanRules(DeploymentDocument document, DeploymentDocument.Node node) {
        StringBuilder rules = new StringBuilder();
        effectiveSshCidrs(document).forEach(cidr -> rules.append(doInbound(node.sshPort(), cidr)));
        p2pSources(document, node).forEach(cidr -> rules.append(doInbound(document.p2pPort(), cidr)));
        if (apiGateway(document, node)) {
            effectiveApiCidrs(document).forEach(cidr -> {
                rules.append(doInbound(80, cidr));
                rules.append(doInbound(443, cidr));
            });
        }
        rules.append(doOutbound("tcp")).append(doOutbound("udp"));
        return rules.toString();
    }

    private String doInbound(int port, String cidr) {
        return "  inbound_rule {\n"
                + "    protocol = \"tcp\"\n"
                + "    port_range = \"" + port + "\"\n"
                + "    source_addresses = [\"" + escape(cidr) + "\"]\n"
                + "  }\n";
    }

    private String doOutbound(String protocol) {
        return "  outbound_rule {\n"
                + "    protocol = \"" + protocol + "\"\n"
                + "    port_range = \"1-65535\"\n"
                + "    destination_addresses = [\"0.0.0.0/0\", \"::/0\"]\n"
                + "  }\n";
    }

    private List<String> p2pSources(DeploymentDocument document, DeploymentDocument.Node target) {
        if (target.roles().contains("l1-bootstrap")) {
            return List.of("0.0.0.0/0", "::/0");
        }
        Map<String, DeploymentDocument.Provider> providers = document.providersByName();
        return document.nodes().stream().filter(node -> !node.name().equals(target.name())).map(node -> {
            if (!node.address().isBlank()) {
                return node.address() + (node.address().contains(":") ? "/128" : "/32");
            }
            String resource = node.name().replace('-', '_');
            return switch (providers.get(node.providerRef()).type()) {
                case "contabo" -> "${contabo_instance." + resource + ".ip_config[0].v4[0].ip}/32";
                case "hetzner-cloud" -> "${hcloud_server." + resource + ".ipv4_address}/32";
                case "digitalocean" -> "${digitalocean_droplet." + resource + ".ipv4_address}/32";
                default -> throw new IllegalArgumentException("P2P peer address is unresolved for " + node.name());
            };
        }).toList();
    }

    private List<String> effectiveSshCidrs(DeploymentDocument document) {
        return "public-key-only".equals(document.sshAccessMode())
                ? List.of("0.0.0.0/0", "::/0")
                : document.sshCidrs();
    }

    private List<String> effectiveApiCidrs(DeploymentDocument document) {
        if ("disabled".equals(document.apiExposure())) {
            return List.of();
        }
        if ("cloudflare".equals(document.apiProxy())) {
            return CLOUDFLARE_PROXY_CIDRS;
        }
        if ("public-https".equals(document.apiExposure())) {
            return List.of("0.0.0.0/0", "::/0");
        }
        return document.apiCidrs();
    }

    private List<String> effectiveMonitoringCidrs(DeploymentDocument document) {
        if (!document.monitoringEnabled()) {
            return List.of();
        }
        if ("cloudflare".equals(document.monitoringProxy())) {
            return CLOUDFLARE_PROXY_CIDRS;
        }
        return List.of("0.0.0.0/0", "::/0");
    }

    private boolean apiGateway(DeploymentDocument document, DeploymentDocument.Node node) {
        return !"disabled".equals(document.apiExposure())
                && node.roles().contains("api-gateway")
                && !node.hostname().isBlank();
    }

    private int appConnectionsPerPeer(ShowcaseArtifact.Metadata artifact) {
        return Math.max(16, artifact.chains().size() + 4);
    }

    private int maxInboundConnections(DeploymentDocument document, ShowcaseArtifact.Metadata artifact) {
        return Math.max(100, (document.validators().size() - 1) * appConnectionsPerPeer(artifact) + 32);
    }

    private String inventory(DeploymentDocument document, Map<String, String> resolved) throws IOException {
        ShowcaseArtifact.Metadata artifact = artifacts.inspect(document.artifactPath());
        String monitoringAddress = document.nodes().stream()
                .filter(node -> node.name().equals(document.monitoringNode()))
                .map(node -> resolved.getOrDefault(node.name(), node.address()))
                .findFirst().orElse("");
        StringBuilder result = new StringBuilder("all:\n  vars:\n")
                .append("    ansible_python_interpreter: /usr/bin/python3\n")
                .append("    cluster_name: ").append(yamlQuote(document.name())).append('\n')
                .append("    cluster_id: ").append(yamlQuote(document.clusterId())).append('\n')
                .append("    network_profile: ").append(yamlQuote(document.network())).append('\n')
                .append("    l1_launch_profile: ").append(yamlQuote(document.launchProfile())).append('\n')
                .append("    p2p_port: ").append(document.p2pPort()).append('\n')
                .append("    http_port: ").append(document.httpPort()).append('\n')
                .append("    consensus_threshold: ").append(document.threshold()).append('\n')
                .append("    sequencer_mode: ").append(yamlQuote(document.sequencerMode())).append('\n')
                .append("    sequencer_proposer_node: ")
                .append(yamlQuote(document.sequencerProposerNode())).append('\n')
                .append("    sequencer_proposer_public_key: ")
                .append(yamlQuote(document.sequencerProposerPublicKey())).append('\n')
                .append("    sequencer_window_slots: ").append(document.sequencerWindowSlots()).append('\n')
                .append("    validator_count: ").append(document.validators().size()).append('\n')
                .append("    locked_chain_count: ").append(artifact.chains().size())
                .append('\n')
                .append("    plugin_bundle_sha256:\n");
        new TreeMap<>(artifact.pluginBundleSha256()).forEach((path, digest) -> result
                .append("      ").append(yamlQuote(path)).append(": ").append(yamlQuote(digest)).append('\n'));
        result
                .append("    yano_jar_sha256: ")
                .append(yamlQuote(artifacts.digestEntry(artifact, "yano.jar"))).append('\n')
                .append("    showcase_archive: ").append(yamlQuote(document.artifactPath().toString())).append('\n')
                .append("    showcase_sha256: ").append(yamlQuote(document.artifactSha256())).append('\n')
                .append("    api_exposure: ").append(yamlQuote(document.apiExposure())).append('\n')
                .append("    api_proxy: ").append(yamlQuote(document.apiProxy())).append('\n')
                .append("    api_rate_limit_average: ").append(document.apiRateLimitAverage()).append('\n')
                .append("    api_rate_limit_burst: ").append(document.apiRateLimitBurst()).append('\n')
                .append("    api_cors_allowed_origins:");
        appendYamlList(result, document.apiCorsAllowedOrigins());
        result
                .append("    ssh_access_mode: ").append(yamlQuote(document.sshAccessMode())).append('\n')
                .append("    traefik_version: ").append(yamlQuote(TRAEFIK_VERSION)).append('\n')
                .append("    traefik_amd64_sha256: ").append(yamlQuote(TRAEFIK_AMD64_SHA256)).append('\n')
                .append("    traefik_arm64_sha256: ").append(yamlQuote(TRAEFIK_ARM64_SHA256)).append('\n')
                .append("    monitoring_enabled: ").append(document.monitoringEnabled()).append('\n')
                .append("    monitoring_node_name: ").append(yamlQuote(document.monitoringNode())).append('\n')
                .append("    monitoring_owner_address: ")
                .append(yamlQuote(document.monitoringEnabled() && monitoringAddress.isBlank()
                        ? "TOFU_PENDING" : monitoringAddress)).append('\n')
                .append("    monitoring_owner_cidr: ")
                .append(yamlQuote(monitoringAddress.isBlank() ? "" : monitoringAddress
                        + (monitoringAddress.contains(":") ? "/128" : "/32"))).append('\n')
                .append("    monitoring_hostname: ").append(yamlQuote(document.monitoringHostname())).append('\n')
                .append("    monitoring_url_encoded: ")
                .append(yamlQuote(document.monitoringEnabled()
                        ? "https%3A%2F%2F" + document.monitoringHostname() : "")).append('\n')
                .append("    monitoring_proxy: ").append(yamlQuote(document.monitoringProxy())).append('\n')
                .append("    monitoring_exposure: ")
                .append(yamlQuote(document.monitoringExposure())).append('\n')
                .append("    monitoring_retention: ")
                .append(yamlQuote(document.monitoringRetention())).append('\n')
                .append("    monitoring_retention_size: ")
                .append(yamlQuote(document.monitoringRetentionSize())).append('\n')
                .append("    monitoring_scrape_port: ").append(document.monitoringScrapePort()).append('\n')
                .append("    monitoring_prometheus_image: ").append(yamlQuote(PROMETHEUS_IMAGE)).append('\n')
                .append("    monitoring_cors_origin: ")
                .append(yamlQuote(prometheusCorsOrigin(document))).append('\n')
                .append("    anchor_leader_name: ").append(yamlQuote(document.anchorLeaderNode())).append('\n')
                .append("    anchor_default_key_file: ")
                .append(yamlQuote(document.anchorSeedPath().toString())).append('\n')
                .append("    anchor_chain_key_files:");
        if (document.anchorChainSeedPaths().isEmpty()) {
            result.append(" {}\n");
        } else {
            result.append('\n');
            new TreeMap<>(document.anchorChainSeedPaths()).forEach((chainId, path) -> result
                    .append("      ").append(yamlQuote(chainId)).append(": ")
                    .append(yamlQuote(path.toString())).append('\n'));
        }
        result
                .append("    settlement_operator_seed_file: ")
                .append(yamlQuote(document.settlementOperatorSeedPath().toString())).append('\n')
                .append("    api_certificate_file: ")
                .append(yamlQuote(resolvePath(document, document.apiCertificateFile()))).append('\n')
                .append("    api_private_key_file: ")
                .append(yamlQuote(resolvePath(document, document.apiPrivateKeyFile()))).append('\n')
                .append("    api_key_file: ")
                .append(yamlQuote(resolvePath(document, document.apiKeyFile()))).append('\n')
                .append("    ssh_source_cidrs:");
        appendYamlList(result, document.sshCidrs());
        result.append("    api_source_cidrs:");
        appendYamlList(result, document.apiCidrs());
        result.append("    api_ingress_cidrs:");
        appendYamlList(result, effectiveApiCidrs(document));
        result.append("    monitoring_ingress_cidrs:");
        appendYamlList(result, effectiveMonitoringCidrs(document));
        result
                .append("  hosts:\n");
        for (DeploymentDocument.Node node : document.nodes()) {
            String address = resolved.getOrDefault(node.name(), node.address());
            String advertised = node.advertisedAddress().isBlank() ? address : node.advertisedAddress();
            String p2pSourceCidr = address.isBlank() ? "TOFU_PENDING"
                    : address + (address.contains(":") ? "/128" : "/32");
            result.append("    ").append(node.name()).append(":\n")
                    .append("      ansible_host: ")
                    .append(yamlQuote(address.isBlank() ? "TOFU_PENDING" : address)).append('\n')
                    .append("      ansible_user: ").append(yamlQuote(node.sshUser())).append('\n')
                    .append("      ansible_port: ").append(node.sshPort()).append('\n')
                    .append("      yano_advertised_address: ")
                    .append(yamlQuote(advertised.isBlank() ? "TOFU_PENDING" : advertised)).append('\n')
                    .append("      p2p_source_cidr: ").append(yamlQuote(p2pSourceCidr)).append('\n')
                    .append("      l1_bootstrap: ").append(node.roles().contains("l1-bootstrap")).append('\n')
                    .append("      node_index: ").append(node.index()).append('\n')
                    .append("      provider_ref: ").append(yamlQuote(node.providerRef())).append('\n')
                    .append("      api_hostname: ").append(yamlQuote(node.hostname())).append('\n')
                    .append("      member_key_file: ")
                    .append(yamlQuote(resolveSecretReference(document, node))).append('\n')
                    .append("      anchor_leader: ")
                    .append(node.name().equals(document.anchorLeaderNode())).append('\n')
                    .append("      settlement_owner: ")
                    .append(node.name().equals(document.settlementOwnerNode())).append('\n')
                    .append("      showcase_effect_owner: ")
                    .append(node.equals(document.validators().getFirst())).append('\n')
                    .append("      api_gateway: ").append(apiGateway(document, node)).append('\n')
                    .append("      monitoring_owner: ")
                    .append(document.monitoringEnabled() && node.name().equals(document.monitoringNode()))
                    .append('\n')
                    .append("      monitoring_target: ")
                    .append(document.monitoringEnabled() && node.roles().contains("validator")).append('\n')
                    .append("      traefik_enabled: ")
                    .append(apiGateway(document, node) || (document.monitoringEnabled()
                            && node.roles().contains("validator")))
                    .append('\n');
        }
        return result.toString();
    }

    private String prometheusCorsOrigin(DeploymentDocument document) {
        List<String> origins = document.nodes().stream()
                .map(DeploymentDocument.Node::hostname)
                .filter(hostname -> !hostname.isBlank())
                .map(hostname -> "https://" + hostname.replace(".", "\\."))
                .toList();
        return origins.isEmpty() ? "^$" : "^(" + String.join("|", origins) + ")$";
    }

    private void appendYamlList(StringBuilder result, List<String> values) {
        if (values.isEmpty()) {
            result.append(" []\n");
            return;
        }
        result.append('\n');
        values.forEach(value -> result.append("      - ").append(yamlQuote(value)).append('\n'));
    }

    private String resolveSecretReference(DeploymentDocument document, DeploymentDocument.Node node) {
        return resolvePath(document, node.memberPrivateKeyFile());
    }

    private String resolvePath(DeploymentDocument document, String value) {
        if (value.isBlank()) {
            return "";
        }
        Path configured = Path.of(value);
        return configured.isAbsolute() ? configured.normalize().toString()
                : document.directory().resolve(configured).normalize().toString();
    }

    private String sanitizedApplication(
            DeploymentDocument document,
            ShowcaseArtifact.Metadata artifact,
            SettlementProfileCompiler.Compiled settlement) throws IOException {
        JsonNode parsed = yaml.readTree(artifacts.readEntry(artifact, ShowcaseArtifact.SHOWCASE_APPLICATION));
        ObjectNode yano = (ObjectNode) parsed.path("yano");
        ArrayNode allowList = (ArrayNode) yano.path("plugins").path("allow-list");
        List<String> allowed = new ArrayList<>();
        allowList.forEach(item -> {
            String id = item.asText();
            if (FORBIDDEN_PLUGIN_MARKERS.stream().noneMatch(id::contains)) {
                allowed.add(id);
            }
        });
        allowList.removeAll();
        allowed.forEach(allowList::add);
        ObjectNode appChain = (ObjectNode) yano.path("app-chain");
        Map<String, JsonNode> packaged = new LinkedHashMap<>();
        appChain.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("chains[")) {
                packaged.put(entry.getValue().path("chain-id").asText(), entry.getValue());
            }
        });
        JsonNode generated = yaml.readTree(settlement.yamlBlock()).path("chains[10]");
        if (generated.isMissingNode() || !SETTLEMENT_CHAIN.equals(generated.path("chain-id").asText())) {
            throw new IOException("generated settlement profile contains no canonical settlement chain");
        }
        List<String> oldKeys = new ArrayList<>();
        appChain.fieldNames().forEachRemaining(key -> {
            if (key.startsWith("chains[")) {
                oldKeys.add(key);
            }
        });
        oldKeys.forEach(appChain::remove);
        for (int index = 0; index < artifact.chains().size(); index++) {
            String chain = artifact.chains().get(index);
            JsonNode config = SETTLEMENT_CHAIN.equals(chain) ? generated : packaged.get(chain);
            if (config == null) {
                throw new IOException("showcase config is missing catalog chain: " + chain);
            }
            if ("cardano-history-chain".equals(chain)) {
                configureCardanoHistory((ObjectNode) config, document);
            }
            appChain.set("chains[" + index + "]", config);
        }
        return yaml.writeValueAsString(parsed);
    }

    private void configureCardanoHistory(ObjectNode chain, DeploymentDocument document) {
        ObjectNode state = chain.withObject("state");
        if (!document.cardanoHistoryGenesisId().isBlank()) {
            state.put("genesis-id", document.cardanoHistoryGenesisId());
        }
        chain.put("max-message-bytes", "6291456");
        chain.withObject("block").put("max-bytes", "8388608");

        ObjectNode machines = chain.withObject("machines");
        machines.withObject("cardano-history").put("preset", document.cardanoHistoryPreset());
        machines.remove("epoch-stake");
        machines.remove("epoch-governance");

        ObjectNode observers = chain.withObject("observers");
        observers.remove("epoch-stake");
        observers.remove("epoch-governance");
        ObjectNode capabilities = chain.withObject("capabilities");
        capabilities.remove("authenticated-snapshots");
        List<String> snapshotSeries = new ArrayList<>();
        if (document.cardanoHistoryL1Observations().contains(DeploymentDocument.EPOCH_STAKE_OBSERVER)) {
            machines.withObject("epoch-stake")
                    .put("chunk-entries", "25000")
                    .put("snapshot-profile", "mpf-blake2b256-v1");
            observers.withObject("epoch-stake")
                    .put("type", DeploymentDocument.EPOCH_STAKE_OBSERVER)
                    .put("chain-id", "cardano-history-chain")
                    .put("chunk-entries", "25000");
            snapshotSeries.add("l1-epoch-stake-v1.distribution");
        }
        if (document.cardanoHistoryL1Observations().contains(DeploymentDocument.EPOCH_GOVERNANCE_OBSERVER)) {
            machines.withObject("epoch-governance")
                    .put("drep-chunk-entries", "25000")
                    .put("drep-snapshot-profile", "mpf-blake2b256-v1");
            observers.withObject("epoch-governance")
                    .put("type", DeploymentDocument.EPOCH_GOVERNANCE_OBSERVER)
                    .put("chain-id", "cardano-history-chain")
                    .put("include-proposals", "true")
                    .put("include-drep-distribution", "true")
                    .put("drep-chunk-entries", "25000");
            snapshotSeries.add("l1-epoch-governance-v1.drep-distribution");
        }
        if (!snapshotSeries.isEmpty()) {
            capabilities.withObject("authenticated-snapshots")
                    .put("enabled", "true")
                    .put("series", String.join(",", snapshotSeries))
                    .put("archive-directory", "/var/lib/yano/appchain-snapshot-archives");
        }
    }

    private String nodeProperties(DeploymentDocument document, ShowcaseArtifact.Metadata artifact)
            throws IOException {
        List<DeploymentDocument.Node> validators = document.validators();
        String members = validators.stream().map(DeploymentDocument.Node::memberPublicKey)
                .collect(java.util.stream.Collectors.joining(","));
        List<String> activeChains = artifact.chains();
        Set<String> observerChains = observerChains(artifact);
        String l1NetworkGenesisId = artifacts.digestEntry(artifact,
                "config/network/" + document.network() + "/shelley-genesis.json");
        StringBuilder properties = new StringBuilder()
                .append("quarkus.http.host=127.0.0.1\n")
                .append("quarkus.http.port=").append(document.httpPort()).append('\n')
                .append("yano.server.enabled=true\n")
                .append("yano.server.port=").append(document.p2pPort()).append('\n')
                .append("yano.relay.connection.max-connections-per-ip=")
                .append(appConnectionsPerPeer(artifact)).append('\n')
                .append("yano.relay.connection.max-inbound-connections=")
                .append(maxInboundConnections(document, artifact)).append('\n')
                .append("yano.storage.path=/var/lib/yano/chainstate\n")
                .append("yano.app-chain.storage.path=/var/lib/yano/appchain-chainstate\n")
                .append("yano.account-state.snapshot-retention-epochs=")
                .append(document.cardanoHistorySourceSnapshotRetentionEpochs()).append('\n')
                .append("yano.plugins.directory=/opt/yano/active/{{ showcase_sha256 }}-r30/plugins\n")
                .append("yano.plugins.bundle.\"org.yanoproject.x.eutxo.indexer\".storage-path=")
                .append("/var/lib/yano/appchain-indexers\n");
        if (!document.apiKeyFile().isBlank()) {
            properties.append("yano.app-chain.api.keys={{ lookup('file', api_key_file) | trim }}\n");
        }
        for (int index = 0; index < activeChains.size(); index++) {
            String prefix = "yano.app-chain.chains[" + index + "].";
            properties.append(prefix).append("signing-key={{ lookup('file', member_key_file) | trim }}\n")
                    .append(prefix).append("members=").append(members).append('\n')
                    .append(prefix).append("threshold=").append(document.threshold()).append('\n')
                    .append(prefix).append("peers={{ yano_peer_csv }}\n")
                    .append(prefix).append("sequencer.mode=").append(document.sequencerMode()).append('\n');
            if ("fixed".equals(document.sequencerMode())) {
                properties.append(prefix).append("sequencer.proposer=")
                        .append(document.sequencerProposerPublicKey()).append('\n');
            } else {
                properties.append(prefix).append("sequencer.window-slots=")
                        .append(document.sequencerWindowSlots()).append('\n');
            }
            if (observerChains.contains(activeChains.get(index))) {
                properties.append(prefix).append("observation.l1-network-genesis-id=")
                        .append(l1NetworkGenesisId).append('\n');
            }
            if (SETTLEMENT_CHAIN.equals(activeChains.get(index))) {
                properties.append(prefix)
                        .append("effects.executor.enabled={{ settlement_owner | lower }}\n")
                        .append(prefix)
                        .append("effects.executors.eutxo-settlement.owner={{ settlement_owner | lower }}\n");
            }
            if (WORKFLOW_CHAIN.equals(activeChains.get(index))) {
                properties.append(prefix)
                        .append("effects.executor.enabled={{ showcase_effect_owner | lower }}\n")
                        .append(prefix)
                        .append("effects.executors.showcase-outbox.enabled={{ showcase_effect_owner | lower }}\n")
                        .append("{% if showcase_effect_owner %}\n")
                        .append(prefix)
                        .append("effects.executors.showcase-outbox.directory=")
                        .append("/var/lib/yano/appchain-effects/showcase-outbox\n")
                        .append("{% endif %}\n");
            }
            properties.append("{% if anchor_leader %}\n")
                    .append(prefix).append("anchor.enabled=true\n")
                    .append(prefix).append("anchor.mode=script\n")
                    .append(prefix)
                    .append("anchor.signing-key={{ lookup('file', anchor_chain_key_files.get(")
                    .append(yamlQuote(activeChains.get(index)))
                    .append(", anchor_default_key_file)) | trim }}\n")
                    .append(prefix).append("anchor.every-blocks=")
                    .append(document.anchorEveryBlocks()).append('\n')
                    .append(prefix).append("anchor.max-interval-minutes=")
                    .append(document.anchorMaxIntervalMinutes()).append('\n')
                    .append("{% endif %}\n");
        }
        properties.append(profileCompiler.compile(artifact,
                validators.stream().map(DeploymentDocument.Node::memberPublicKey).toList(), document.threshold()));
        if (!document.cardanoHistoryGenesisId().isBlank()) {
            int historyIndex = activeChains.indexOf(CARDANO_HISTORY_CHAIN);
            if (historyIndex < 0) {
                throw new IllegalStateException("Cardano History chain is unavailable in the selected artifact");
            }
            // The profile compiler emits a generated genesis for every authenticated chain. Keep
            // the operator-pinned Cardano History identity last so it wins property resolution.
            properties.append("yano.app-chain.chains[").append(historyIndex)
                    .append("].state.genesis-id=").append(document.cardanoHistoryGenesisId()).append('\n');
        }
        return properties.toString();
    }

    private Set<String> observerChains(ShowcaseArtifact.Metadata artifact) throws IOException {
        JsonNode parsed = yaml.readTree(artifacts.readEntry(artifact, ShowcaseArtifact.SHOWCASE_APPLICATION));
        Set<String> result = new java.util.LinkedHashSet<>();
        parsed.path("yano").path("app-chain").fields().forEachRemaining(entry -> {
            JsonNode chain = entry.getValue();
            if (entry.getKey().startsWith("chains[") && chain.path("observers").size() > 0) {
                result.add(chain.path("chain-id").asText());
            }
        });
        return Set.copyOf(result);
    }

    private String playbook() {
        return """
                ---
                - name: Close public gateways before changing any cluster member
                  hosts: all
                  become: true
                  gather_facts: false
                  tasks:
                    - name: Stop a previously installed Traefik gateway
                      ansible.builtin.systemd_service:
                        name: traefik
                        state: stopped
                      when: api_gateway
                      failed_when: false
                    - name: Stop a legacy Nginx gateway
                      ansible.builtin.systemd_service:
                        name: nginx
                        state: stopped
                      when: api_gateway
                      failed_when: false

                - name: Deploy Yano X app-chain members
                  hosts: all
                  become: true
                  serial: 1
                  vars:
                    yano_release: "/opt/yano/releases/{{ showcase_sha256 }}"
                    yano_active_plugins: "/opt/yano/active/{{ showcase_sha256 }}-r30"
                    yano_peer_csv: >-
                      {{ groups['all'] | reject('equalto', inventory_hostname)
                         | map('extract', hostvars, 'yano_advertised_address')
                         | map('regex_replace', '$', ':' ~ p2p_port) | join(',') }}
                    yano_peer_cidrs: >-
                      {{ groups['all'] | reject('equalto', inventory_hostname)
                         | map('extract', hostvars, 'p2p_source_cidr') | list }}
                    ssh_client_address: >-
                      {{ (ansible_facts.env.SSH_CONNECTION | default('0.0.0.0')).split() | first }}
                  pre_tasks:
                    - name: Reject unresolved cloud inventory
                      ansible.builtin.assert:
                        that: ansible_host != 'TOFU_PENDING'
                        fail_msg: Run apply so OpenTofu outputs can resolve the inventory.
                    - name: Verify member key exists on controller
                      ansible.builtin.stat:
                        path: "{{ member_key_file }}"
                      register: member_key
                      delegate_to: localhost
                      become: false
                    - name: Require an owner-controlled member key file
                      ansible.builtin.assert:
                        that:
                          - member_key.stat.exists and member_key.stat.isreg
                          - member_key.stat.mode in ['0400', '0600']
                      no_log: true
                    - name: Verify anchor account seeds exist on controller
                      ansible.builtin.stat:
                        path: "{{ item }}"
                      loop: >-
                        {{ ([anchor_default_key_file]
                        + (anchor_chain_key_files.values() | list)) | unique }}
                      loop_control:
                        label: "configured-anchor-account-{{ ansible_loop.index }}"
                        extended: true
                      register: anchor_keys
                      delegate_to: localhost
                      become: false
                      when: anchor_leader
                      no_log: true
                    - name: Require owner-controlled anchor account seeds
                      ansible.builtin.assert:
                        that:
                          - item.stat.exists and item.stat.isreg
                          - item.stat.mode in ['0400', '0600']
                      loop: "{{ anchor_keys.results }}"
                      no_log: true
                      when: anchor_leader
                    - name: Verify settlement seed exists on controller
                      ansible.builtin.stat:
                        path: "{{ settlement_operator_seed_file }}"
                      register: settlement_key
                      delegate_to: localhost
                      become: false
                      when: settlement_owner
                    - name: Require an owner-controlled settlement seed
                      ansible.builtin.assert:
                        that:
                          - settlement_key.stat.exists and settlement_key.stat.isreg
                          - settlement_key.stat.mode in ['0400', '0600']
                      no_log: true
                      when: settlement_owner
                    - name: Read effective SSH configuration for public SSH mode
                      ansible.builtin.command:
                        argv:
                          - sshd
                          - -T
                          - -C
                          - "user={{ ansible_user }},host={{ inventory_hostname }},addr={{ ssh_client_address }}"
                      register: effective_sshd
                      changed_when: false
                      when: ssh_access_mode == 'public-key-only'
                    - name: Read effective root SSH configuration for public SSH mode
                      ansible.builtin.command:
                        argv:
                          - sshd
                          - -T
                          - -C
                          - "user=root,host={{ inventory_hostname }},addr={{ ssh_client_address }}"
                      register: effective_root_sshd
                      changed_when: false
                      when: ssh_access_mode == 'public-key-only'
                    - name: Require hardened SSH before allowing public ingress
                      ansible.builtin.assert:
                        that:
                          - "'pubkeyauthentication yes' in effective_sshd.stdout_lines"
                          - "'passwordauthentication no' in effective_sshd.stdout_lines"
                          - "'kbdinteractiveauthentication no' in effective_sshd.stdout_lines"
                          - "'permitrootlogin no' in effective_root_sshd.stdout_lines"
                        fail_msg: Public SSH ingress requires keys only, no passwords, and no direct root login.
                      when: ssh_access_mode == 'public-key-only'
                  tasks:
                    - name: Install host packages
                      ansible.builtin.package:
                        name: [openjdk-25-jre-headless, unzip, tar, ca-certificates, ufw]
                        state: present
                    - name: Create runtime account
                      ansible.builtin.user:
                        name: yano
                        system: true
                        home: /var/lib/yano
                        shell: /usr/sbin/nologin
                    - name: Create Traefik gateway account
                      ansible.builtin.user:
                        name: traefik
                        system: true
                        home: /var/lib/traefik
                        shell: /usr/sbin/nologin
                      when: api_gateway
                    - name: Create retained and configuration directories
                      ansible.builtin.file:
                        path: "{{ item.path }}"
                        state: directory
                        owner: "{{ item.owner }}"
                        group: yano
                        mode: "{{ item.mode }}"
                      loop:
                        - {path: /opt/yano/releases, owner: root, mode: '0755'}
                        - {path: /opt/yano/active, owner: root, mode: '0755'}
                        - {path: /etc/yano, owner: root, mode: '0750'}
                        - {path: /etc/yano/credentials, owner: root, mode: '0750'}
                        - {path: /etc/yano/settlement, owner: yano, mode: '0700'}
                        - {path: /var/lib/yano/chainstate, owner: yano, mode: '0700'}
                        - {path: /var/lib/yano/appchain-chainstate, owner: yano, mode: '0700'}
                        - {path: /var/lib/yano/appchain-indexers, owner: yano, mode: '0700'}
                        - {path: /var/lib/yano/appchain-snapshot-archives, owner: yano, mode: '0700'}
                        - {path: /var/lib/yano/appchain-effects/showcase-outbox, owner: yano, mode: '0700'}
                    - name: Create Traefik gateway directories
                      ansible.builtin.file:
                        path: "{{ item.path }}"
                        state: directory
                        owner: "{{ item.owner }}"
                        group: traefik
                        mode: "{{ item.mode }}"
                      loop:
                        - {path: "/opt/traefik/{{ traefik_version }}", owner: root, mode: '0755'}
                        - {path: /etc/traefik, owner: root, mode: '0750'}
                        - {path: /etc/traefik/dynamic, owner: root, mode: '0750'}
                        - {path: /var/lib/traefik, owner: traefik, mode: '0700'}
                      when: api_gateway
                    - name: Select supported Traefik architecture
                      ansible.builtin.set_fact:
                        traefik_arch: >-
                          {{ {'x86_64': 'amd64', 'aarch64': 'arm64'}.get(ansible_facts.architecture, '') }}
                      when: api_gateway
                    - name: Require a supported Traefik architecture
                      ansible.builtin.assert:
                        that: traefik_arch in ['amd64', 'arm64']
                      when: api_gateway
                    - name: Select pinned Traefik checksum
                      ansible.builtin.set_fact:
                        traefik_sha256: >-
                          {{ {'amd64': traefik_amd64_sha256,
                              'arm64': traefik_arm64_sha256}[traefik_arch] }}
                      when: api_gateway
                    - name: Download exact Traefik release
                      ansible.builtin.get_url:
                        url: >-
                          {{ 'https://github.com/traefik/traefik/releases/download/v' ~ traefik_version
                             ~ '/traefik_v' ~ traefik_version ~ '_linux_' ~ traefik_arch ~ '.tar.gz' }}
                        dest: "/opt/traefik/traefik_v{{ traefik_version }}_linux_{{ traefik_arch }}.tar.gz"
                        checksum: "sha256:{{ traefik_sha256 }}"
                        owner: root
                        mode: '0644'
                      when: api_gateway
                    - name: Extract exact Traefik release
                      ansible.builtin.unarchive:
                        src: "/opt/traefik/traefik_v{{ traefik_version }}_linux_{{ traefik_arch }}.tar.gz"
                        dest: "/opt/traefik/{{ traefik_version }}"
                        remote_src: true
                        creates: "/opt/traefik/{{ traefik_version }}/traefik"
                      when: api_gateway
                    - name: Activate exact Traefik release
                      ansible.builtin.file:
                        src: "/opt/traefik/{{ traefik_version }}/traefik"
                        dest: /usr/local/bin/traefik
                        state: link
                      when: api_gateway
                    - name: Retain ACME account and certificate state
                      ansible.builtin.file:
                        path: /var/lib/traefik/acme.json
                        state: touch
                        owner: traefik
                        group: traefik
                        mode: '0600'
                        access_time: preserve
                        modification_time: preserve
                      when: api_gateway and api_exposure == 'public-https'
                    - name: Copy exact showcase archive
                      ansible.builtin.copy:
                        src: "{{ showcase_archive }}"
                        dest: "/opt/yano/{{ showcase_sha256 }}.zip"
                        owner: root
                        mode: '0644'
                    - name: Verify remote showcase SHA-256
                      ansible.builtin.stat:
                        path: "/opt/yano/{{ showcase_sha256 }}.zip"
                        checksum_algorithm: sha256
                      register: remote_showcase
                    - name: Require the exact imported showcase bytes
                      ansible.builtin.assert:
                        that: remote_showcase.stat.checksum == showcase_sha256
                    - name: Inspect immutable release marker
                      ansible.builtin.stat:
                        path: "{{ yano_release }}/.yano-artifact-sha256"
                      register: yano_release_marker
                    - name: Inspect an existing digest release path
                      ansible.builtin.stat:
                        path: "{{ yano_release }}"
                        follow: false
                      register: yano_release_path
                    - name: Reject an unmarked release directory
                      ansible.builtin.assert:
                        that: >-
                          not yano_release_path.stat.exists
                          or yano_release_path.stat.islnk | default(false)
                          or yano_release_marker.stat.exists
                        fail_msg: >-
                          Refusing to reuse an unmarked release directory at {{ yano_release }}.
                    - name: Remove the legacy mutable release symlink
                      ansible.builtin.file:
                        path: "{{ yano_release }}"
                        state: absent
                      when: >-
                        not yano_release_marker.stat.exists
                        and yano_release_path.stat.islnk | default(false)
                    - name: Recreate clean release staging directory
                      when: not yano_release_marker.stat.exists
                      block:
                        - name: Remove incomplete release staging directory
                          ansible.builtin.file:
                            path: "/opt/yano/releases/.{{ showcase_sha256 }}.staging"
                            state: absent
                        - name: Create release staging directory
                          ansible.builtin.file:
                            path: "/opt/yano/releases/.{{ showcase_sha256 }}.staging"
                            state: directory
                            owner: root
                            group: root
                            mode: '0755'
                        - name: Extract archive into release staging directory
                          ansible.builtin.unarchive:
                            src: "/opt/yano/{{ showcase_sha256 }}.zip"
                            dest: "/opt/yano/releases/.{{ showcase_sha256 }}.staging"
                            remote_src: true
                        - name: Locate the single staged showcase root
                          ansible.builtin.find:
                            paths: "/opt/yano/releases/.{{ showcase_sha256 }}.staging"
                            patterns: 'yano-x-jvm-*'
                            file_type: directory
                            recurse: false
                          register: staged_showcase_roots
                        - name: Require exactly one staged showcase root
                          ansible.builtin.assert:
                            that: staged_showcase_roots.matched == 1
                        - name: Mark staged release with its archive identity
                          ansible.builtin.copy:
                            content: "{{ showcase_sha256 }}\n"
                            dest: >-
                              {{ staged_showcase_roots.files[0].path }}/.yano-artifact-sha256
                            owner: root
                            group: root
                            mode: '0444'
                        - name: Atomically install immutable digest release
                          ansible.builtin.command:
                            argv:
                              - mv
                              - -T
                              - "{{ staged_showcase_roots.files[0].path }}"
                              - "{{ yano_release }}"
                          args:
                            creates: "{{ yano_release }}/.yano-artifact-sha256"
                        - name: Remove empty release staging directory
                          ansible.builtin.file:
                            path: "/opt/yano/releases/.{{ showcase_sha256 }}.staging"
                            state: absent
                    - name: Read immutable release marker
                      ansible.builtin.slurp:
                        src: "{{ yano_release }}/.yano-artifact-sha256"
                      register: installed_release_marker
                    - name: Require immutable release identity to match the archive
                      ansible.builtin.assert:
                        that: >-
                          (installed_release_marker.content | b64decode | trim) == showcase_sha256
                    - name: Verify every immutable plugin bundle checksum
                      ansible.builtin.stat:
                        path: "{{ yano_release }}/{{ item.key }}"
                        checksum_algorithm: sha256
                      loop: "{{ plugin_bundle_sha256 | dict2items }}"
                      loop_control:
                        label: "{{ item.key }}"
                      register: installed_plugin_bundles
                    - name: Verify immutable Yano runtime checksum
                      ansible.builtin.stat:
                        path: "{{ yano_release }}/yano.jar"
                        checksum_algorithm: sha256
                      register: installed_yano_runtime
                    - name: Reject a missing or modified Yano runtime
                      ansible.builtin.assert:
                        that:
                          - installed_yano_runtime.stat.exists
                          - installed_yano_runtime.stat.isreg
                          - installed_yano_runtime.stat.checksum == yano_jar_sha256
                    - name: Reject missing or modified immutable plugin bundles
                      ansible.builtin.assert:
                        that:
                          - item.stat.exists
                          - item.stat.isreg
                          - item.stat.checksum == item.item.value
                        fail_msg: "Immutable plugin bundle differs from the imported artifact: {{ item.item.key }}"
                      loop: "{{ installed_plugin_bundles.results }}"
                      loop_control:
                        label: "{{ item.item.key }}"
                    - name: Remove the previous filtered plugin view
                      ansible.builtin.file:
                        path: "{{ yano_active_plugins }}"
                        state: absent
                    - name: Create filtered active plugin directory
                      ansible.builtin.file:
                        path: "{{ yano_active_plugins }}/plugins"
                        state: directory
                        owner: root
                        group: yano
                        mode: '0755'
                    - name: Locate permitted runtime bundles in the immutable release
                      ansible.builtin.find:
                        paths: "{{ yano_release }}/plugins"
                        patterns: '*.jar'
                        excludes:
                          - '*kafka*'
                          - '*objectstore-s3*'
                          - '*ipfs*'
                          - '*evidence-profile*'
                          - '*evidence-registry*'
                          - '*effects-cardano*'
                          - '*eutxo-zk*'
                        file_type: file
                      register: permitted_plugins
                    - name: Copy permitted bundles into the active plugin view
                      ansible.builtin.copy:
                        src: "{{ item.path }}"
                        dest: "{{ yano_active_plugins }}/plugins/{{ item.path | basename }}"
                        remote_src: true
                        owner: root
                        group: yano
                        mode: '0444'
                      loop: "{{ permitted_plugins.files }}"
                    - name: Mark the complete active plugin view
                      ansible.builtin.copy:
                        content: "{{ showcase_sha256 }}\n"
                        dest: "{{ yano_active_plugins }}/.yano-artifact-sha256"
                        owner: root
                        group: root
                        mode: '0444'
                    - name: Install shared app-chain configuration
                      ansible.builtin.copy:
                        src: files/application-appchain.yml
                        dest: /etc/yano/application-appchain.yml
                        owner: root
                        group: yano
                        mode: '0640'
                    - name: Install private node configuration
                      ansible.builtin.template:
                        src: templates/node.properties.j2
                        dest: /etc/yano/node.properties
                        owner: root
                        group: yano
                        mode: '0640'
                      no_log: true
                    - name: Install settlement operator seed on its sole owner
                      ansible.builtin.copy:
                        src: "{{ settlement_operator_seed_file }}"
                        dest: /etc/yano/credentials/settlement-operator.seed
                        owner: yano
                        group: yano
                        mode: '0400'
                      no_log: true
                      when: settlement_owner
                    - name: Install parameterized settlement validators
                      ansible.builtin.copy:
                        src: "files/settlement/{{ item }}"
                        dest: "/etc/yano/settlement/{{ item }}"
                        owner: yano
                        group: yano
                        mode: '0400'
                      loop:
                        - settlement-vault.script
                        - settlement-shard.script
                    - name: Activate exact release
                      ansible.builtin.file:
                        src: "{{ yano_release }}"
                        dest: /opt/yano/current
                        state: link
                    - name: Create the showcase Yano home
                      ansible.builtin.file:
                        path: /opt/yano/home
                        state: directory
                        owner: root
                        group: yano
                        mode: '0755'
                    # yano.sh runs from the directory it sits in, and the node reads
                    # config/ from there. The home puts the showcase configuration in
                    # front of the release runtime instead of the distribution's own.
                    - name: Link the showcase Yano home to the active release runtime
                      ansible.builtin.file:
                        src: "/opt/yano/current/{{ item }}"
                        dest: "/opt/yano/home/{{ item }}"
                        state: link
                        force: true
                      loop:
                        - yano.sh
                        - yano.jar
                    - name: Remove the previous showcase configuration
                      ansible.builtin.file:
                        path: /opt/yano/home/config
                        state: absent
                    - name: Install the showcase configuration from the active release
                      ansible.builtin.copy:
                        src: "{{ yano_release }}/examples/showcase/yano/config/"
                        dest: /opt/yano/home/config/
                        remote_src: true
                        owner: root
                        group: yano
                        directory_mode: '0755'
                        mode: '0644'
                    - name: Install systemd service
                      ansible.builtin.copy:
                        src: files/yano-x.service
                        dest: /etc/systemd/system/yano-x.service
                        owner: root
                        mode: '0644'
                    - name: Install JVM environment
                      ansible.builtin.copy:
                        src: files/yano-x.env
                        dest: /etc/yano/yano-x.env
                        owner: root
                        group: yano
                        mode: '0640'
                    - name: Install supplied TLS certificate
                      ansible.builtin.copy:
                        src: "{{ api_certificate_file }}"
                        dest: /etc/yano/api.crt
                        owner: root
                        group: traefik
                        mode: '0644'
                      when: api_gateway and api_exposure == 'allowlist'
                    - name: Install supplied TLS private key
                      ansible.builtin.copy:
                        src: "{{ api_private_key_file }}"
                        dest: /etc/yano/api.key
                        owner: root
                        group: traefik
                        mode: '0640'
                      no_log: true
                      when: api_gateway and api_exposure == 'allowlist'
                    - name: Install Traefik static configuration
                      ansible.builtin.template:
                        src: templates/traefik.yml.j2
                        dest: /etc/traefik/traefik.yml
                        owner: root
                        group: traefik
                        mode: '0640'
                      when: api_gateway
                    - name: Install Traefik Yano route
                      ansible.builtin.template:
                        src: templates/traefik-yano.yml.j2
                        dest: /etc/traefik/dynamic/yano.yml
                        owner: root
                        group: traefik
                        mode: '0640'
                      when: api_gateway
                    - name: Install Traefik systemd service
                      ansible.builtin.copy:
                        src: files/traefik.service
                        dest: /etc/systemd/system/traefik.service
                        owner: root
                        mode: '0644'
                      when: api_gateway
                    - name: Discover existing host services before gateway migration
                      ansible.builtin.service_facts:
                      when: api_gateway
                    - name: Disable the legacy Nginx gateway before starting Traefik
                      ansible.builtin.systemd_service:
                        name: nginx
                        enabled: false
                        state: stopped
                      when: api_gateway and 'nginx.service' in ansible_facts.services
                    - name: Open SSH and P2P host firewall policy
                      ansible.builtin.shell: |
                        set -eu
                        ufw default deny incoming
                        ufw default allow outgoing
                        ufw delete allow {{ ansible_port }}/tcp 2>/dev/null || true
                        ufw delete limit {{ ansible_port }}/tcp 2>/dev/null || true
                        {% if ssh_access_mode == 'public-key-only' %}
                        ufw allow {{ ansible_port }}/tcp
                        {% else %}
                        {% for cidr in ssh_source_cidrs %}
                        ufw allow from {{ cidr }} to any port {{ ansible_port }} proto tcp
                        {% endfor %}
                        {% endif %}
                        ufw delete allow {{ p2p_port }}/tcp 2>/dev/null || true
                        {% if l1_bootstrap %}
                        ufw allow {{ p2p_port }}/tcp
                        {% else %}
                        {% for cidr in yano_peer_cidrs %}
                        ufw allow from {{ cidr }} to any port {{ p2p_port }} proto tcp
                        {% endfor %}
                        {% endif %}
                        ufw delete allow 80/tcp 2>/dev/null || true
                        ufw delete allow 443/tcp 2>/dev/null || true
                        ufw --force enable
                      args:
                        executable: /bin/sh
                      changed_when: false
                    - name: Ensure Yano X is running without activating pending configuration
                      ansible.builtin.systemd_service:
                        name: yano-x
                        enabled: true
                        state: started
                        daemon_reload: true
                    - name: Keep API reverse proxy stopped until cluster health is validated
                      ansible.builtin.systemd_service:
                        name: traefik
                        enabled: false
                        state: stopped
                        daemon_reload: true
                      when: api_gateway
                    - name: Wait for local readiness
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/q/health/ready"
                        status_code: 200
                      register: ready
                      retries: 90
                      delay: 2
                      until: ready.status == 200
                """;
    }

    private String traefikConfiguration() {
        return """
                entryPoints:
                  web:
                    address: ":80"
                    http:
                      redirections:
                        entryPoint:
                          to: websecure
                          scheme: https
                          permanent: true
                  websecure:
                    address: ":443"
                {% if monitoring_target %}
                  metrics:
                    address: ":{{ monitoring_scrape_port }}"
                {% endif %}
                providers:
                  file:
                    directory: /etc/traefik/dynamic
                    watch: true
                api:
                  dashboard: false
                log:
                  level: INFO
                accessLog: {}
                {% if api_exposure == 'public-https' or monitoring_owner %}
                certificatesResolvers:
                  letsencrypt:
                    acme:
                      storage: /var/lib/traefik/acme.json
                      httpChallenge:
                        entryPoint: web
                {% endif %}
                """;
    }

    private String traefikYanoConfiguration() {
        return """
                http:
                  routers:
                {% if api_gateway %}
                    yano:
                      rule: "Host(`{{ api_hostname }}`)"
                      entryPoints: [websecure]
                      service: yano
                      middlewares: [yano-cors, yano-security, yano-rate-limit]
                {% if api_exposure == 'public-https' %}
                      tls:
                        certResolver: letsencrypt
                {% else %}
                      tls: {}
                {% endif %}
                {% endif %}
                {% if monitoring_enabled and api_gateway %}
                    observability-default:
                      rule: "Host(`{{ api_hostname }}`) && Path(`/ui/observability/`) && !Query(`metrics`)"
                      priority: 100
                      entryPoints: [websecure]
                      service: yano
                      middlewares: [observability-default, yano-cors, yano-security, yano-rate-limit]
                {% if api_exposure == 'public-https' %}
                      tls:
                        certResolver: letsencrypt
                {% else %}
                      tls: {}
                {% endif %}
                {% endif %}
                {% if monitoring_target %}
                    yano-metrics:
                      rule: "Path(`/q/metrics`)"
                      entryPoints: [metrics]
                      service: yano
                      middlewares: [monitoring-source]
                {% endif %}
                {% if monitoring_owner %}
                    prometheus:
                      rule: "Host(`{{ monitoring_hostname }}`)"
                      entryPoints: [websecure]
                      service: prometheus
                      middlewares: [prometheus-security, prometheus-rate-limit]
                      tls:
                        certResolver: letsencrypt
                {% endif %}
                  services:
                {% if api_gateway or monitoring_target %}
                    yano:
                      loadBalancer:
                        servers:
                          - url: "http://127.0.0.1:{{ http_port }}"
                {% endif %}
                {% if monitoring_owner %}
                    prometheus:
                      loadBalancer:
                        servers:
                          - url: "http://127.0.0.1:9090"
                {% endif %}
                  middlewares:
                {% if api_gateway %}
                    yano-cors:
                      headers:
                        accessControlAllowMethods: [GET, HEAD, POST, OPTIONS]
                        accessControlAllowHeaders: [Accept, Content-Type, X-API-Key]
                        accessControlMaxAge: 600
                        addVaryHeader: true
                {% if api_cors_allowed_origins | length > 0 %}
                        accessControlAllowOriginList:
                {% for origin in api_cors_allowed_origins %}
                          - "{{ origin }}"
                {% endfor %}
                {% endif %}
                {% if monitoring_enabled %}
                    observability-default:
                      redirectRegex:
                        regex: "^https://([^/]+)/ui/observability/$"
                        replacement: "https://${1}/ui/observability/?metrics={{ monitoring_url_encoded }}"
                        permanent: false
                {% endif %}
                    yano-security:
                      headers:
                        contentTypeNosniff: true
                        frameDeny: true
                        referrerPolicy: no-referrer
                        customResponseHeaders:
                          Cache-Control: "no-store"
                {% if monitoring_enabled %}
                          Content-Security-Policy: >-
                            connect-src 'self' https://{{ monitoring_hostname }};
                            img-src 'self' data:; object-src 'none'; base-uri 'none';
                            frame-ancestors 'none'; form-action 'self'
                {% endif %}
                    yano-rate-limit:
                      rateLimit:
                        average: {{ api_rate_limit_average }}
                        burst: {{ api_rate_limit_burst }}
                {% if api_proxy == 'cloudflare' %}
                        sourceCriterion:
                          requestHeaderName: CF-Connecting-IP
                {% endif %}
                {% endif %}
                {% if monitoring_target %}
                    monitoring-source:
                      ipAllowList:
                        sourceRange:
                          - "{{ monitoring_owner_cidr }}"
                {% endif %}
                {% if monitoring_owner %}
                    prometheus-security:
                      headers:
                        contentTypeNosniff: true
                        frameDeny: true
                        referrerPolicy: no-referrer
                        customResponseHeaders:
                          Cache-Control: "no-store"
                    prometheus-rate-limit:
                      rateLimit:
                        average: 50
                        burst: 100
                {% if monitoring_proxy == 'cloudflare' %}
                        sourceCriterion:
                          requestHeaderName: CF-Connecting-IP
                {% endif %}
                {% endif %}
                {% if api_exposure == 'allowlist' %}
                tls:
                  certificates:
                    - certFile: /etc/yano/api.crt
                      keyFile: /etc/yano/api.key
                {% endif %}
                """;
    }

    private String gatewayPlaybook() {
        return """
                ---
                - name: Reconcile Yano X API gateways
                  hosts: all
                  become: true
                  any_errors_fatal: true
                  pre_tasks:
                    - name: Require declaratively enabled API exposure
                      ansible.builtin.assert:
                        that:
                          - api_exposure != 'disabled'
                          - api_rate_limit_average | int >= 1
                          - (api_rate_limit_burst | int) >= (api_rate_limit_average | int)
                        fail_msg: Enable and validate API exposure before reconciling its gateway.
                      run_once: true
                  tasks:
                    - name: Install generated Traefik API routes
                      ansible.builtin.template:
                        src: templates/traefik-yano.yml.j2
                        dest: /etc/traefik/dynamic/yano.yml
                        owner: root
                        group: traefik
                        mode: '0640'
                      when: api_gateway
                    - name: Require Traefik to remain active
                      ansible.builtin.systemd_service:
                        name: traefik
                        enabled: true
                        state: started
                      when: api_gateway
                    - name: Wait for the reconciled local HTTPS gateway route
                      ansible.builtin.uri:
                        url: https://127.0.0.1/q/health/ready
                        headers:
                          Host: "{{ api_hostname }}"
                        validate_certs: false
                        status_code: 200
                      register: gateway_ready
                      retries: 30
                      delay: 2
                      until: gateway_ready.status == 200
                      when: api_gateway
                    - name: Open API ingress only after cluster validation
                      ansible.builtin.shell: |
                        set -eu
                        ufw delete allow 80/tcp 2>/dev/null || true
                        ufw delete allow 443/tcp 2>/dev/null || true
                        {% for cidr in api_ingress_cidrs %}
                        ufw allow from {{ cidr }} to any port 80 proto tcp
                        ufw allow from {{ cidr }} to any port 443 proto tcp
                        {% endfor %}
                        ufw --force enable
                      args:
                        executable: /bin/sh
                      changed_when: false
                      when: api_gateway
                """;
    }

    private String prometheusConfiguration() {
        return """
                global:
                  scrape_interval: 5s
                  evaluation_interval: 5s
                scrape_configs:
                {% for node in groups['all'] %}
                {% if hostvars[node].monitoring_target %}
                  - job_name: "yano-{{ node }}"
                    metrics_path: /q/metrics
                    static_configs:
                      - targets:
                {% if hostvars[node].monitoring_owner %}
                          - "127.0.0.1:{{ http_port }}"
                {% else %}
                          - "{{ hostvars[node].ansible_host }}:{{ monitoring_scrape_port }}"
                {% endif %}
                        labels:
                          yano_node: "{{ node }}"
                          yano_cluster: "{{ cluster_name }}"
                {% endif %}
                {% endfor %}
                """;
    }

    private String prometheusCompose() {
        return """
                services:
                  prometheus:
                    image: ${YANO_PROMETHEUS_IMAGE}
                    command:
                      - --config.file=/etc/prometheus/prometheus.yml
                      - --storage.tsdb.path=/prometheus
                      - --storage.tsdb.retention.time=${YANO_PROMETHEUS_RETENTION}
                      - --storage.tsdb.retention.size=${YANO_PROMETHEUS_RETENTION_SIZE}
                      - --web.listen-address=127.0.0.1:9090
                      - --web.cors.origin=${YANO_PROMETHEUS_CORS_ORIGIN}
                    network_mode: host
                    volumes:
                      - /etc/yano/observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
                      - prometheus-data:/prometheus
                    restart: unless-stopped
                    mem_limit: 1g
                    cpus: 1.0
                volumes:
                  prometheus-data:
                    name: yano-prometheus-data
                    labels:
                      com.bloxbean.yano.owner: observability
                      com.bloxbean.yano.cluster: ${YANO_CLUSTER_NAME}
                """;
    }

    private String monitoringPlaybook() {
        return """
                ---
                - name: Configure central Yano X monitoring
                  hosts: all
                  become: true
                  any_errors_fatal: true
                  vars:
                    monitoring_compose_dir: /etc/yano/observability
                  pre_tasks:
                    - name: Require monitoring to be enabled declaratively
                      ansible.builtin.assert:
                        that:
                          - monitoring_enabled | bool
                          - monitoring_owner_address != 'TOFU_PENDING'
                          - monitoring_owner_cidr | length > 0
                        fail_msg: Enable a resolved central-prometheus configuration before applying monitoring.
                      run_once: true
                  tasks:
                    - name: Install monitoring gateway prerequisites
                      ansible.builtin.package:
                        name: [ca-certificates, ufw]
                        state: present
                    - name: Create Traefik gateway account for metrics ingress
                      ansible.builtin.user:
                        name: traefik
                        system: true
                        home: /var/lib/traefik
                        shell: /usr/sbin/nologin
                    - name: Create metrics gateway directories
                      ansible.builtin.file:
                        path: "{{ item.path }}"
                        state: directory
                        owner: "{{ item.owner }}"
                        group: traefik
                        mode: "{{ item.mode }}"
                      loop:
                        - {path: "/opt/traefik/{{ traefik_version }}", owner: root, mode: '0755'}
                        - {path: /etc/traefik, owner: root, mode: '0750'}
                        - {path: /etc/traefik/dynamic, owner: root, mode: '0750'}
                        - {path: /var/lib/traefik, owner: traefik, mode: '0700'}
                    - name: Select supported Traefik architecture for metrics ingress
                      ansible.builtin.set_fact:
                        traefik_arch: >-
                          {{ {'x86_64': 'amd64', 'aarch64': 'arm64'}.get(ansible_facts.architecture, '') }}
                    - name: Require a supported Traefik architecture for metrics ingress
                      ansible.builtin.assert:
                        that: traefik_arch in ['amd64', 'arm64']
                    - name: Select pinned Traefik checksum for metrics ingress
                      ansible.builtin.set_fact:
                        traefik_sha256: >-
                          {{ {'amd64': traefik_amd64_sha256,
                              'arm64': traefik_arm64_sha256}[traefik_arch] }}
                    - name: Download exact Traefik release for metrics ingress
                      ansible.builtin.get_url:
                        url: >-
                          {{ 'https://github.com/traefik/traefik/releases/download/v' ~ traefik_version
                             ~ '/traefik_v' ~ traefik_version ~ '_linux_' ~ traefik_arch ~ '.tar.gz' }}
                        dest: "/opt/traefik/traefik_v{{ traefik_version }}_linux_{{ traefik_arch }}.tar.gz"
                        checksum: "sha256:{{ traefik_sha256 }}"
                        owner: root
                        mode: '0644'
                    - name: Extract exact Traefik release for metrics ingress
                      ansible.builtin.unarchive:
                        src: "/opt/traefik/traefik_v{{ traefik_version }}_linux_{{ traefik_arch }}.tar.gz"
                        dest: "/opt/traefik/{{ traefik_version }}"
                        remote_src: true
                        creates: "/opt/traefik/{{ traefik_version }}/traefik"
                    - name: Activate exact Traefik release for metrics ingress
                      ansible.builtin.file:
                        src: "/opt/traefik/{{ traefik_version }}/traefik"
                        dest: /usr/local/bin/traefik
                        state: link
                    - name: Retain ACME state on the monitoring owner
                      ansible.builtin.file:
                        path: /var/lib/traefik/acme.json
                        state: touch
                        owner: traefik
                        group: traefik
                        mode: '0600'
                        access_time: preserve
                        modification_time: preserve
                      when: monitoring_owner
                    - name: Install monitoring-aware Traefik static configuration
                      ansible.builtin.template:
                        src: templates/traefik.yml.j2
                        dest: /etc/traefik/traefik.yml
                        owner: root
                        group: traefik
                        mode: '0640'
                      tags: [monitoring_gateway]
                    - name: Install monitoring-aware Traefik routes
                      ansible.builtin.template:
                        src: templates/traefik-yano.yml.j2
                        dest: /etc/traefik/dynamic/yano.yml
                        owner: root
                        group: traefik
                        mode: '0640'
                      tags: [monitoring_gateway]
                    - name: Install Traefik systemd service for metrics ingress
                      ansible.builtin.copy:
                        src: files/traefik.service
                        dest: /etc/systemd/system/traefik.service
                        owner: root
                        mode: '0644'
                    - name: Restrict direct metrics ingress to the monitoring owner
                      ansible.builtin.shell: |
                        set -eu
                        ufw delete allow {{ monitoring_scrape_port }}/tcp 2>/dev/null || true
                        ufw allow from {{ monitoring_owner_address }} to any port {{ monitoring_scrape_port }} proto tcp
                        {% if monitoring_owner %}
                        {% for cidr in monitoring_ingress_cidrs %}
                        ufw allow from {{ cidr }} to any port 80 proto tcp
                        ufw allow from {{ cidr }} to any port 443 proto tcp
                        {% endfor %}
                        {% endif %}
                        ufw --force enable
                      args:
                        executable: /bin/sh
                      changed_when: false
                    - name: Install Docker and Compose on the monitoring owner
                      ansible.builtin.package:
                        name: [docker.io, docker-compose-v2]
                        state: present
                      when: monitoring_owner
                    - name: Start Docker on the monitoring owner
                      ansible.builtin.systemd_service:
                        name: docker
                        enabled: true
                        state: started
                      when: monitoring_owner
                    - name: Create persistent Prometheus configuration directory
                      ansible.builtin.file:
                        path: "{{ monitoring_compose_dir }}"
                        state: directory
                        owner: root
                        group: root
                        mode: '0750'
                      when: monitoring_owner
                    - name: Install generated Prometheus scrape configuration
                      ansible.builtin.template:
                        src: templates/prometheus.yml.j2
                        dest: "{{ monitoring_compose_dir }}/prometheus.yml"
                        owner: root
                        group: root
                        mode: '0644'
                      when: monitoring_owner
                    - name: Install pinned Prometheus Compose definition
                      ansible.builtin.copy:
                        src: files/prometheus-compose.yml
                        dest: "{{ monitoring_compose_dir }}/compose.yml"
                        owner: root
                        group: root
                        mode: '0644'
                      when: monitoring_owner
                    - name: Install non-secret Prometheus environment
                      ansible.builtin.copy:
                        dest: "{{ monitoring_compose_dir }}/compose.env"
                        owner: root
                        group: root
                        mode: '0644'
                        content: |
                          YANO_PROMETHEUS_IMAGE={{ monitoring_prometheus_image }}
                          YANO_PROMETHEUS_RETENTION={{ monitoring_retention }}
                          YANO_PROMETHEUS_RETENTION_SIZE={{ monitoring_retention_size }}
                          YANO_PROMETHEUS_CORS_ORIGIN={{ monitoring_cors_origin }}
                          YANO_CLUSTER_NAME={{ cluster_name }}
                      when: monitoring_owner
                    - name: Validate the Prometheus Compose definition
                      ansible.builtin.command:
                        argv:
                          - docker
                          - compose
                          - --env-file
                          - "{{ monitoring_compose_dir }}/compose.env"
                          - -f
                          - "{{ monitoring_compose_dir }}/compose.yml"
                          - config
                          - --quiet
                      changed_when: false
                      when: monitoring_owner
                    - name: Pull the pinned Prometheus image
                      ansible.builtin.command:
                        argv:
                          - docker
                          - compose
                          - --env-file
                          - "{{ monitoring_compose_dir }}/compose.env"
                          - -f
                          - "{{ monitoring_compose_dir }}/compose.yml"
                          - pull
                      register: prometheus_pull
                      changed_when: "'Pulled' in prometheus_pull.stdout or 'Downloaded' in prometheus_pull.stderr"
                      when: monitoring_owner
                    - name: Start central Prometheus
                      ansible.builtin.command:
                        argv:
                          - docker
                          - compose
                          - --env-file
                          - "{{ monitoring_compose_dir }}/compose.env"
                          - -f
                          - "{{ monitoring_compose_dir }}/compose.yml"
                          - up
                          - -d
                          - --remove-orphans
                      register: prometheus_up
                      changed_when: "'Started' in prometheus_up.stdout or 'Created' in prometheus_up.stdout"
                      when: monitoring_owner
                    - name: Restart Traefik with monitoring routes
                      ansible.builtin.systemd_service:
                        name: traefik
                        enabled: true
                        state: restarted
                        daemon_reload: true
                      tags: [monitoring_gateway]
                    - name: Wait for central Prometheus readiness
                      ansible.builtin.uri:
                        url: http://127.0.0.1:9090/-/ready
                        status_code: 200
                      register: prometheus_ready
                      retries: 30
                      delay: 2
                      until: prometheus_ready.status == 200
                      when: monitoring_owner
                    - name: Wait for all Yano scrape targets
                      ansible.builtin.uri:
                        url: http://127.0.0.1:9090/api/v1/targets
                        status_code: 200
                      register: prometheus_targets
                      retries: 30
                      delay: 2
                      until: >-
                        prometheus_targets.json.status == 'success'
                        and prometheus_targets.json.data.activeTargets | length == validator_count
                        and prometheus_targets.json.data.activeTargets
                          | selectattr('health', 'equalto', 'up') | list | length == validator_count
                      when: monitoring_owner
                    - name: Verify the local Prometheus HTTPS route
                      ansible.builtin.uri:
                        url: https://127.0.0.1/-/ready
                        headers:
                          Host: "{{ monitoring_hostname }}"
                        validate_certs: false
                        status_code: 200
                      register: monitoring_gateway_ready
                      retries: 30
                      delay: 2
                      until: monitoring_gateway_ready.status == 200
                      when: monitoring_owner
                """;
    }

    private String anchorBootstrapPlaybook() {
        return """
                ---
                - name: Preflight Yano X anchor bootstrap
                  hosts: all
                  gather_facts: false
                  any_errors_fatal: true
                  vars:
                    yano_l1_tip_lag_tolerance_blocks: 5
                  tasks:
                    - name: Require explicit public-network spend authorization
                      ansible.builtin.assert:
                        that:
                          - yano_confirm_public_anchor is defined
                          - yano_confirm_public_anchor == network_profile
                        fail_msg: >-
                          Pass yano_confirm_public_anchor={{ network_profile }} only after reviewing
                          anchor-wallet funding and public-network transaction fees.
                      run_once: true
                    - name: Inspect the local privileged API-key file
                      ansible.builtin.stat:
                        path: "{{ api_key_file }}"
                      delegate_to: localhost
                      register: yano_anchor_api_key
                      run_once: true
                    - name: Require a private full API key for anchor administration
                      ansible.builtin.assert:
                        that:
                          - (api_key_file | length) > 0
                          - yano_anchor_api_key.stat.exists
                          - yano_anchor_api_key.stat.isreg
                          - not yano_anchor_api_key.stat.roth
                          - not yano_anchor_api_key.stat.woth
                        fail_msg: >-
                          Configure spec.access.api.apiKeyFile as a private local file before bootstrap.
                      run_once: true
                    - name: Read local readiness
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/q/health/ready"
                        status_code: 200
                      register: yano_anchor_ready
                    - name: Read local L1 synchronization status
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/node/status"
                        return_content: true
                        status_code: 200
                      register: yano_l1_status
                    - name: Classify this member for the L1-current signing quorum
                      ansible.builtin.set_fact:
                        yano_anchor_l1_current: >-
                          {{ (yano_l1_status.json.running | bool)
                          and (yano_l1_status.json.inSync | default(false) | bool)
                          and not (yano_l1_status.json.runtimeDegraded | default(false) | bool)
                          and ((yano_l1_status.json.localTipBlockNumber | default(0) | int) > 0)
                          and ((yano_l1_status.json.remoteTipBlockNumber | default(0) | int) > 0)
                          and (((yano_l1_status.json.remoteTipBlockNumber | int)
                          - (yano_l1_status.json.localTipBlockNumber | int)) | abs)
                          <= (yano_l1_tip_lag_tolerance_blocks | int) }}
                    - name: Require an L1-current signing quorum including the anchor leader
                      ansible.builtin.assert:
                        that:
                          - hostvars[anchor_leader_name].yano_anchor_l1_current | bool
                          - >-
                            (groups['all'] | map('extract', hostvars, 'yano_anchor_l1_current')
                            | select('equalto', true) | list | length)
                            >= (consensus_threshold | int)
                        fail_msg: >-
                          Fewer than {{ consensus_threshold }} members, or the anchor leader, are L1-current.
                          No transaction was submitted; wait for a signing quorum to synchronize.
                      run_once: true
                    - name: Read hosted chain catalog
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains"
                        return_content: true
                        status_code: 200
                      register: yano_anchor_catalog_preflight
                    - name: Read every hosted chain status
                      ansible.builtin.uri:
                        url: >-
                          http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains/{{ item.chainId }}/status
                        return_content: true
                        status_code: 200
                      loop: "{{ yano_anchor_catalog_preflight.json }}"
                      loop_control:
                        label: "{{ item.chainId }}"
                      register: yano_anchor_mesh_preflight
                    - name: Require a fully connected app-chain mesh
                      ansible.builtin.assert:
                        that:
                          - item.json.running | bool
                          - (item.json.peers | length) == ((groups['all'] | length) - 1)
                          - >-
                            (item.json.peers | dict2items
                            | selectattr('value', 'equalto', true) | list | length)
                            == ((groups['all'] | length) - 1)
                        fail_msg: >-
                          {{ inventory_hostname }} chain {{ item.json.chainId }} is not fully connected.
                          No anchor bootstrap transaction was submitted.
                      no_log: true
                      loop: "{{ yano_anchor_mesh_preflight.results }}"
                      loop_control:
                        label: "{{ item.json.chainId }}"

                - name: Bootstrap configured SCRIPT anchors sequentially
                  hosts: all
                  gather_facts: false
                  any_errors_fatal: true
                  vars:
                    yano_anchor_confirmation_delay_seconds: 15
                    yano_anchor_confirmation_retries: 60
                  tasks:
                    - name: Bootstrap anchors on the declared leader
                      when: anchor_leader | bool
                      block:
                        - name: Read leader chain catalog
                          ansible.builtin.uri:
                            url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains"
                            return_content: true
                            status_code: 200
                          register: yano_anchor_catalog
                        - name: Require the optional chain selector to name a hosted chain
                          ansible.builtin.assert:
                            that:
                              - >-
                                (yano_anchor_chain | default('all')) == 'all'
                                or (yano_anchor_chain | default('all'))
                                in (yano_anchor_catalog.json | map(attribute='chainId') | list)
                            fail_msg: >-
                              yano_anchor_chain must be 'all' or one chain from the locked catalog.
                        - name: Reconcile every selected anchor in catalog order
                          ansible.builtin.include_tasks: bootstrap-one-anchor.yml
                          loop: "{{ yano_anchor_catalog.json }}"
                          loop_control:
                            loop_var: yano_anchor_catalog_entry
                            label: "{{ yano_anchor_catalog_entry.chainId }}"
                          when: >-
                            (yano_anchor_chain | default('all')) == 'all'
                            or (yano_anchor_chain | default('all')) == yano_anchor_catalog_entry.chainId
                        - name: Read final anchor status for every selected chain
                          ansible.builtin.uri:
                            url: >-
                              http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains/{{ item.chainId }}/status
                            return_content: true
                            status_code: 200
                          loop: "{{ yano_anchor_catalog.json }}"
                          loop_control:
                            label: "{{ item.chainId }}"
                          when: >-
                            (yano_anchor_chain | default('all')) == 'all'
                            or (yano_anchor_chain | default('all')) == item.chainId
                          register: yano_anchor_final_status
                        - name: Require every selected chain to be bootstrapped
                          ansible.builtin.assert:
                            that:
                              - item.json.anchor.enabled | bool
                              - item.json.anchor.mode == 'script'
                              - item.json.anchor.bootstrapped | bool
                            fail_msg: "SCRIPT anchor bootstrap did not complete for {{ item.json.chainId }}."
                          no_log: true
                          loop: >-
                            {{ yano_anchor_final_status.results
                            | rejectattr('skipped', 'defined') | list }}
                          loop_control:
                            label: "{{ item.json.chainId }}"
                        - name: Report completed anchor bootstrap reconciliation
                          ansible.builtin.debug:
                            msg: >-
                              Anchor bootstrap is complete for selector
                              {{ yano_anchor_chain | default('all') }} on {{ inventory_hostname }}.
                """;
    }

    private String anchorBootstrapChainTasks() {
        return """
                ---
                - name: Read current SCRIPT anchor status
                  ansible.builtin.uri:
                    url: >-
                      {{ 'http://127.0.0.1:' ~ http_port ~ '/api/v1/app-chain/chains/'
                      ~ yano_anchor_catalog_entry.chainId ~ '/status' }}
                    return_content: true
                    status_code: 200
                  register: yano_anchor_before

                - name: Require SCRIPT anchoring on the declared leader
                  ansible.builtin.assert:
                    that:
                      - yano_anchor_before.json.anchor.enabled | bool
                      - yano_anchor_before.json.anchor.mode == 'script'
                      - yano_anchor_before.json.anchor.leader | bool
                    fail_msg: >-
                      {{ yano_anchor_catalog_entry.chainId }} is not configured for SCRIPT anchoring
                      on {{ inventory_hostname }}.

                - name: Read the anchor wallet UTxOs before a new bootstrap
                  ansible.builtin.uri:
                    url: >-
                      {{ 'http://127.0.0.1:' ~ http_port ~ '/api/v1/addresses/'
                      ~ yano_anchor_before.json.anchor.walletAddress ~ '/utxos?page=1&count=50' }}
                    return_content: true
                    status_code: 200
                  register: yano_anchor_wallet_utxos
                  when:
                    - not (yano_anchor_before.json.anchor.bootstrapped | bool)
                    - yano_anchor_before.json.anchor.pendingBootstrapTx is not defined

                - name: Reset usable pure-ADA anchor inputs
                  ansible.builtin.set_fact:
                    yano_usable_anchor_utxos: []
                  when:
                    - not (yano_anchor_before.json.anchor.bootstrapped | bool)
                    - yano_anchor_before.json.anchor.pendingBootstrapTx is not defined

                - name: Select usable pure-ADA anchor inputs
                  ansible.builtin.set_fact:
                    yano_usable_anchor_utxos: "{{ yano_usable_anchor_utxos + [item] }}"
                  loop: "{{ yano_anchor_wallet_utxos.json | default([]) }}"
                  loop_control:
                    label: "{{ item.tx_hash }}#{{ item.output_index }}"
                  when:
                    - not (yano_anchor_before.json.anchor.bootstrapped | bool)
                    - yano_anchor_before.json.anchor.pendingBootstrapTx is not defined
                    - (item.amount | length) == 1
                    - item.amount[0].unit == 'lovelace'
                    - (item.amount[0].quantity | int) >= 1000000

                - name: Require a usable funded anchor account
                  ansible.builtin.assert:
                    that: (yano_usable_anchor_utxos | length) > 0
                    fail_msg: >-
                      {{ yano_anchor_catalog_entry.chainId }} anchor account has no visible pure-ADA
                      UTxO of at least 1000000 lovelace. No transaction was submitted.
                  when:
                    - not (yano_anchor_before.json.anchor.bootstrapped | bool)
                    - yano_anchor_before.json.anchor.pendingBootstrapTx is not defined

                - name: Submit the one-time SCRIPT anchor bootstrap transaction
                  ansible.builtin.uri:
                    url: >-
                      {{ 'http://127.0.0.1:' ~ http_port ~ '/api/v1/app-chain/chains/'
                      ~ yano_anchor_catalog_entry.chainId ~ '/admin/anchor/bootstrap' }}
                    method: POST
                    headers:
                      X-API-Key: "{{ lookup('file', api_key_file) | trim }}"
                    return_content: true
                    status_code: 202
                  register: yano_anchor_bootstrap_submit
                  no_log: true
                  when:
                    - not (yano_anchor_before.json.anchor.bootstrapped | bool)
                    - yano_anchor_before.json.anchor.pendingBootstrapTx is not defined

                - name: Wait for the bootstrap identity to become L1-confirmed
                  ansible.builtin.uri:
                    url: >-
                      {{ 'http://127.0.0.1:' ~ http_port ~ '/api/v1/app-chain/chains/'
                      ~ yano_anchor_catalog_entry.chainId ~ '/status' }}
                    return_content: true
                    status_code: 200
                  register: yano_anchor_confirmed
                  retries: "{{ yano_anchor_confirmation_retries }}"
                  delay: "{{ yano_anchor_confirmation_delay_seconds }}"
                  until: yano_anchor_confirmed.json.anchor.bootstrapped | default(false) | bool
                  when: not (yano_anchor_before.json.anchor.bootstrapped | bool)

                - name: Wait for the first threshold-signed advancement when the chain has progress
                  ansible.builtin.uri:
                    url: >-
                      {{ 'http://127.0.0.1:' ~ http_port ~ '/api/v1/app-chain/chains/'
                      ~ yano_anchor_catalog_entry.chainId ~ '/status' }}
                    return_content: true
                    status_code: 200
                  register: yano_anchor_advanced
                  retries: "{{ yano_anchor_confirmation_retries }}"
                  delay: "{{ yano_anchor_confirmation_delay_seconds }}"
                  until: (yano_anchor_advanced.json.anchor.lastAnchoredHeight | default(0) | int) > 0
                  when: (yano_anchor_before.json.tipHeight | int) > 0

                - name: Report this chain without exposing signing material
                  ansible.builtin.debug:
                    msg:
                      chain: "{{ yano_anchor_catalog_entry.chainId }}"
                      already_bootstrapped: "{{ yano_anchor_before.json.anchor.bootstrapped | bool }}"
                      bootstrapped: true
                """;
    }

    private String statusPlaybook() {
        return """
                ---
                - name: Validate every Yano X member and hosted chain
                  hosts: all
                  gather_facts: false
                  any_errors_fatal: true
                  vars:
                    yano_status_retries: "{{ yano_status_retries_override | default(30) }}"
                    yano_status_delay: "{{ yano_status_delay_override | default(2) }}"
                  tasks:
                    - name: Read readiness
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/q/health/ready"
                        return_content: true
                        status_code: 200
                      register: ready
                      retries: "{{ yano_status_retries | int }}"
                      delay: "{{ yano_status_delay | int }}"
                      until: ready.status == 200
                    - name: Read L1 node status
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/node/status"
                        return_content: true
                        status_code: 200
                      register: yano_node_status
                      retries: "{{ yano_status_retries | int }}"
                      delay: "{{ yano_status_delay | int }}"
                      until: >-
                        (yano_node_status.json.running | default(false) | bool)
                        and not (yano_node_status.json.runtimeDegraded | default(false) | bool)
                        and ((yano_node_status.json.localTipBlockNumber | default(0) | int) > 0)
                        and (not (yano_require_l1_tip | default(false) | bool)
                        or ((yano_node_status.json.inSync | default(false) | bool)
                        and ((yano_node_status.json.localTipBlockNumber | default(0) | int) > 0)
                        and (((yano_node_status.json.remoteTipBlockNumber | default(0) | int)
                        - (yano_node_status.json.localTipBlockNumber | default(0) | int)) | abs)
                        <= (yano_l1_tip_lag_tolerance_blocks | default(5) | int)))
                    - name: Read chain catalog
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains"
                        return_content: true
                        status_code: 200
                      register: chains
                    - name: Require the locked chain count
                      ansible.builtin.assert:
                        that: (chains.json | length) == (locked_chain_count | int)
                        fail_msg: >-
                          {{ inventory_hostname }} does not host the artifact-locked chain catalog.
                    - name: Read every hosted chain status
                      ansible.builtin.uri:
                        url: >-
                          http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains/{{ item.chainId }}/status
                        return_content: true
                        status_code: 200
                      loop: "{{ chains.json }}"
                      loop_control:
                        label: "{{ item.chainId }}"
                      register: yano_chain_statuses
                    - name: Require healthy fully connected chains and observers
                      ansible.builtin.assert:
                        that:
                          - item.json.running | bool
                          - (item.json.tipHeight | default(0) | int) > 0
                          - (item.json.peers | length) == ((groups['all'] | length) - 1)
                          - >-
                            (item.json.peers | dict2items
                            | selectattr('value', 'equalto', true) | list | length)
                            == ((groups['all'] | length) - 1)
                          - item.json.observers.healthy | default(true) | bool
                          - item.json.epochObservers.healthy | default(true) | bool
                          - item.json.consensusProfile.digest | default('') | length > 0
                          - item.json.capabilityManifest.manifestDigest | default('') | length > 0
                        fail_msg: >-
                          {{ inventory_hostname }} chain {{ item.json.chainId }} is unhealthy,
                          disconnected, or has an unhealthy L1 observer.
                      loop: "{{ yano_chain_statuses.results }}"
                      loop_control:
                        label: "{{ item.json.chainId }}"
                    - name: Record consensus identities for cross-member comparison
                      ansible.builtin.set_fact:
                        yano_chain_ids: >-
                          {{ yano_chain_statuses.results | map(attribute='json.chainId') | list }}
                        yano_genesis_ids: >-
                          {{ yano_chain_statuses.results
                          | map(attribute='json.stateCommitment.genesisId') | list }}
                        yano_format_fingerprints: >-
                          {{ yano_chain_statuses.results
                          | map(attribute='json.stateCommitment.formatFingerprint') | list }}
                        yano_consensus_digests: >-
                          {{ yano_chain_statuses.results
                          | map(attribute='json.consensusProfile.digest') | list }}
                        yano_capability_digests: >-
                          {{ yano_chain_statuses.results
                          | map(attribute='json.capabilityManifest.manifestDigest') | list }}
                        yano_tip_heights: >-
                          {{ yano_chain_statuses.results | map(attribute='json.tipHeight') | list }}
                        yano_state_roots: >-
                          {{ yano_chain_statuses.results | map(attribute='json.stateRoot') | list }}
                    - name: Report sanitized member status
                      ansible.builtin.debug:
                        msg:
                          node: "{{ inventory_hostname }}"
                          ready: "{{ ready.status == 200 }}"
                          l1_running: "{{ yano_node_status.json.running | default(false) }}"
                          l1_in_sync: "{{ yano_node_status.json.inSync | default(false) }}"
                          l1_local_tip: "{{ yano_node_status.json.localTipBlockNumber | default(0) }}"
                          l1_remote_tip: "{{ yano_node_status.json.remoteTipBlockNumber | default(0) }}"
                          chains: "{{ yano_chain_ids }}"

                - name: Require one consensus identity across the cluster
                  hosts: all
                  gather_facts: false
                  any_errors_fatal: true
                  tasks:
                    - name: Compare every member with the first inventory member
                      ansible.builtin.assert:
                        that:
                          - hostvars[item].yano_chain_ids == hostvars[groups['all'][0]].yano_chain_ids
                          - hostvars[item].yano_genesis_ids == hostvars[groups['all'][0]].yano_genesis_ids
                          - >-
                            hostvars[item].yano_format_fingerprints
                            == hostvars[groups['all'][0]].yano_format_fingerprints
                          - >-
                            hostvars[item].yano_consensus_digests
                            == hostvars[groups['all'][0]].yano_consensus_digests
                          - >-
                            hostvars[item].yano_capability_digests
                            == hostvars[groups['all'][0]].yano_capability_digests
                          - >-
                            hostvars[item].yano_tip_heights
                            != hostvars[groups['all'][0]].yano_tip_heights
                            or hostvars[item].yano_state_roots
                            == hostvars[groups['all'][0]].yano_state_roots
                        fail_msg: Consensus identity differs on {{ item }}.
                      loop: "{{ groups['all'] }}"
                      run_once: true
                """;
    }

    private String preflightPlaybook() {
        return """
                ---
                - name: Validate target hosts before deployment
                  hosts: all
                  become: true
                  gather_facts: true
                  any_errors_fatal: true
                  tasks:
                    - name: Require a supported systemd Linux host
                      ansible.builtin.assert:
                        that:
                          - ansible_facts.service_mgr == 'systemd'
                          - ansible_facts.os_family == 'Debian'
                          - ansible_facts.architecture in ['x86_64', 'aarch64']
                          - (ansible_facts.memtotal_mb | int) >= 2048
                        fail_msg: >-
                          Yano X requires Debian-family systemd Linux, amd64/arm64, and at least 2 GiB RAM.
                    - name: Read root filesystem capacity
                      ansible.builtin.command:
                        argv: [df, -Pk, /var/lib]
                      register: yano_disk_capacity
                      changed_when: false
                    - name: Require at least 20 GiB free for initial operation
                      ansible.builtin.assert:
                        that: (yano_disk_capacity.stdout_lines[-1].split()[3] | int) >= 20971520
                        fail_msg: /var/lib has less than 20 GiB free.
                    - name: Read network time synchronization state
                      ansible.builtin.command:
                        argv: [timedatectl, show, --property=NTPSynchronized, --value]
                      register: yano_ntp_state
                      changed_when: false
                    - name: Require synchronized host clocks
                      ansible.builtin.assert:
                        that: (yano_ntp_state.stdout | trim | lower) == 'yes'
                        fail_msg: Host clock is not synchronized.
                    - name: Report sanitized host capacity
                      ansible.builtin.debug:
                        msg:
                          node: "{{ inventory_hostname }}"
                          distribution: "{{ ansible_facts.distribution }} {{ ansible_facts.distribution_version }}"
                          architecture: "{{ ansible_facts.architecture }}"
                          memory_mb: "{{ ansible_facts.memtotal_mb }}"
                          ntp_synchronized: "{{ yano_ntp_state.stdout | trim }}"
                """;
    }

    private String resetPlaybook() {
        return """
                ---
                - name: Reset explicitly selected Yano retained stores
                  hosts: all
                  become: true
                  gather_facts: false
                  any_errors_fatal: true
                  vars:
                    yano_appchain_reset_paths:
                      - /var/lib/yano/appchain-chainstate
                      - /var/lib/yano/appchain-indexers
                      - /var/lib/yano/appchain-effects
                      - /var/lib/yano/appchain-snapshot-archives
                    yano_l1_reset_paths:
                      - /var/lib/yano/chainstate
                  pre_tasks:
                    - name: Require immutable cluster identity and explicit reset scope
                      ansible.builtin.assert:
                        that:
                          - (yano_confirm_reset_cluster | default('')) == cluster_id
                          - (yano_reset_scope | default('')) in ['appchain', 'all']
                        fail_msg: >-
                          Set yano_confirm_reset_cluster to the inventory cluster_id and
                          yano_reset_scope to appchain or all. This operation deletes retained state.
                    - name: Select exact retained store paths
                      ansible.builtin.set_fact:
                        yano_selected_reset_paths: >-
                          {{ yano_appchain_reset_paths
                             + (yano_l1_reset_paths
                                if (yano_reset_scope == 'all') else []) }}
                    - name: Report exact reset targets
                      ansible.builtin.debug:
                        msg:
                          cluster: "{{ cluster_id }}"
                          scope: "{{ yano_reset_scope }}"
                          paths: "{{ yano_selected_reset_paths }}"
                          restart_after_reset: "{{ yano_start_after_reset | default(false) | bool }}"
                  tasks:
                    - name: Stop public gateway before destructive state reset
                      ansible.builtin.systemd_service:
                        name: traefik
                        state: stopped
                        enabled: false
                      when: api_gateway
                      failed_when: false
                    - name: Close API firewall ports before destructive state reset
                      ansible.builtin.shell: |
                        ufw delete allow 80/tcp 2>/dev/null || true
                        ufw delete allow 443/tcp 2>/dev/null || true
                      args:
                        executable: /bin/sh
                      changed_when: false
                      when: api_gateway
                    - name: Stop Yano X before deleting retained state
                      ansible.builtin.systemd_service:
                        name: yano-x
                        state: stopped
                    - name: Delete only the selected retained stores
                      ansible.builtin.file:
                        path: "{{ item }}"
                        state: absent
                      loop: "{{ yano_selected_reset_paths }}"
                    - name: Recreate selected retained store roots
                      ansible.builtin.file:
                        path: "{{ item }}"
                        state: directory
                        owner: yano
                        group: yano
                        mode: '0700'
                      loop: "{{ yano_selected_reset_paths }}"
                    - name: Recreate the showcase outbox after an app-chain reset
                      ansible.builtin.file:
                        path: /var/lib/yano/appchain-effects/showcase-outbox
                        state: directory
                        owner: yano
                        group: yano
                        mode: '0700'

                - name: Optionally restart reset members and verify the full mesh
                  hosts: all
                  become: true
                  gather_facts: false
                  any_errors_fatal: true
                  tasks:
                    - name: Start every reset member together
                      ansible.builtin.systemd_service:
                        name: yano-x
                        enabled: true
                        state: started
                      when: yano_start_after_reset | default(false) | bool
                    - name: Wait for local readiness after reset
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/q/health/ready"
                        status_code: 200
                      register: reset_ready
                      retries: 90
                      delay: 2
                      until: reset_ready.status == 200
                      when: yano_start_after_reset | default(false) | bool
                    - name: Read hosted chains after reset
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains"
                        return_content: true
                        status_code: 200
                      register: reset_hosted_chains
                      when: yano_start_after_reset | default(false) | bool
                    - name: Require every reset chain to reconnect to every other member
                      ansible.builtin.uri:
                        url: >-
                          http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains/{{ item.chainId }}/status
                        return_content: true
                        status_code: 200
                      loop: "{{ reset_hosted_chains.json | default([]) }}"
                      loop_control:
                        label: "{{ item.chainId }}"
                      register: reset_mesh_status
                      retries: 60
                      delay: 2
                      until:
                        - reset_mesh_status.json.running | bool
                        - (reset_mesh_status.json.peers | length) == ((groups['all'] | length) - 1)
                        - >-
                          (reset_mesh_status.json.peers | dict2items
                           | selectattr('value', 'equalto', true) | list | length)
                          == ((groups['all'] | length) - 1)
                      when: yano_start_after_reset | default(false) | bool
                """;
    }

    private String meshRecoveryPlaybook() {
        return """
                ---
                - name: Stop all Yano X members before compatibility restart
                  hosts: all
                  become: true
                  gather_facts: false
                  tasks:
                    - name: Cleanly stop every member
                      ansible.builtin.systemd_service:
                        name: yano-x
                        state: stopped

                - name: Start all Yano X members together and verify the full mesh
                  hosts: all
                  become: true
                  gather_facts: false
                  tasks:
                    - name: Start every member in parallel
                      ansible.builtin.systemd_service:
                        name: yano-x
                        enabled: true
                        state: started
                    - name: Wait for local readiness after coordinated start
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/q/health/ready"
                        status_code: 200
                      register: mesh_ready
                      retries: 90
                      delay: 2
                      until: mesh_ready.status == 200
                    - name: Read the hosted chain catalog
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains"
                        return_content: true
                        status_code: 200
                      register: hosted_chains
                    - name: Require every chain to connect to every other member
                      ansible.builtin.uri:
                        url: "http://127.0.0.1:{{ http_port }}/api/v1/app-chain/chains/{{ item.chainId }}/status"
                        return_content: true
                        status_code: 200
                      loop: "{{ hosted_chains.json }}"
                      loop_control:
                        label: "{{ item.chainId }}"
                      register: mesh_status
                      retries: 60
                      delay: 2
                      until:
                        - mesh_status.json.running | bool
                        - (mesh_status.json.peers | length) == ((groups['all'] | length) - 1)
                        - >-
                          (mesh_status.json.peers | dict2items
                           | selectattr('value', 'equalto', true) | list | length)
                          == ((groups['all'] | length) - 1)
                """;
    }

    private String serviceUnit(DeploymentDocument document) {
        return """
                [Unit]
                Description=Yano X app-chain member
                Wants=network-online.target
                After=network-online.target

                [Service]
                Type=simple
                User=yano
                Group=yano
                WorkingDirectory=/opt/yano/home
                EnvironmentFile=/etc/yano/yano-x.env
                ExecStart=/opt/yano/home/yano.sh start:%s
                Restart=on-failure
                RestartSec=10
                TimeoutStopSec=120
                NoNewPrivileges=true
                PrivateTmp=true
                ProtectSystem=strict
                ProtectHome=true
                ReadWritePaths=/var/lib/yano
                UMask=0027

                [Install]
                WantedBy=multi-user.target
                """.formatted(document.launchProfile());
    }

    private String traefikServiceUnit() {
        return """
                [Unit]
                Description=Traefik gateway for Yano X
                Wants=network-online.target
                After=network-online.target yano-x.service

                [Service]
                Type=simple
                User=traefik
                Group=traefik
                WorkingDirectory=/var/lib/traefik
                ExecStart=/usr/local/bin/traefik --configFile=/etc/traefik/traefik.yml
                Restart=on-failure
                RestartSec=5
                NoNewPrivileges=true
                AmbientCapabilities=CAP_NET_BIND_SERVICE
                CapabilityBoundingSet=CAP_NET_BIND_SERVICE
                PrivateTmp=true
                ProtectSystem=strict
                ProtectHome=true
                ReadWritePaths=/var/lib/traefik
                UMask=0027

                [Install]
                WantedBy=multi-user.target
                """;
    }

    private String jvmEnvironment() {
        return "JAVA_OPTS=-Xms1g -Xmx4g "
                + "-Dquarkus.log.file.enabled=false "
                + "-Dquarkus.config.locations=file:/etc/yano/application-appchain.yml,"
                + "file:/etc/yano/node.properties\n";
    }

    private String activePluginPolicy() {
        return """
                Active profile includes the production Preprod payment-chain-settlement identity.
                Forbidden bundle markers: kafka, objectstore-s3, ipfs, evidence-profile,
                evidence-registry, effects-cardano, eutxo-zk.
                The EUTxO Cardano bridge bundle is retained for settlement.
                """;
    }

    private Map<String, String> checksums(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), ShowcaseArtifact.digest(path));
            }
        }
        return result;
    }

    private byte[] canonicalJson(Object value) throws IOException {
        ObjectMapper canonical = json.copy()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return (canonical.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private List<DeploymentDocument.Node> cloudNodes(DeploymentDocument document) {
        Map<String, DeploymentDocument.Provider> providers = document.providersByName();
        return document.nodes().stream()
                .filter(node -> !"existing".equals(providers.get(node.providerRef()).type())).toList();
    }

    private String lifecycle(DeploymentDocument document) {
        return document.destructionProtection() ? "  lifecycle { prevent_destroy = true }\n" : "";
    }

    private String output(DeploymentDocument.Node node, String expression) {
        return "output \"" + node.name().replace('-', '_') + "_address\" { value = " + expression + " }\n\n";
    }

    private String attribute(String name, String value) {
        return "  " + name + " = \"" + escape(value) + "\"\n";
    }

    private String stringList(String name, JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        values.forEach(value -> items.add("\"" + escape(value.asText()) + "\""));
        return "  " + name + " = [" + String.join(", ", items) + "]\n";
    }

    private String numberList(String name, JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "";
        }
        List<String> items = new ArrayList<>();
        values.forEach(value -> items.add(Long.toString(value.asLong())));
        return "  " + name + " = [" + String.join(", ", items) + "]\n";
    }

    private String providerLocal(String type) {
        return switch (type) {
            case "contabo" -> "contabo";
            case "hetzner-cloud" -> "hcloud";
            case "digitalocean" -> "digitalocean";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private String providerSource(String type) {
        return switch (type) {
            case "contabo" -> "contabo/contabo";
            case "hetzner-cloud" -> "hetznercloud/hcloud";
            case "digitalocean" -> "digitalocean/digitalocean";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static String yamlQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void write(Path path, String value) throws IOException {
        writeBytes(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(Path path, byte[] value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void replace(Path path, String value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".inventory-", ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replaceBytes(Path path, byte[] value) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".lock-", ".tmp");
        try {
            Files.write(temporary, value, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record Rendered(Path output, String lockSha256, int nodes, int cloudNodes) {
    }

    @FunctionalInterface
    interface ProfileGenerator {
        String compile(ShowcaseArtifact.Metadata artifact, List<String> memberPublicKeys, int threshold)
                throws IOException;
    }
}
