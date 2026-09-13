package org.yanoproject.x.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentRendererTest {
    private static final List<String> PUBLIC_KEYS = List.of(
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1",
            "ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c",
            "6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1");

    @Test
    void importsAndRendersMixedProviders(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"), "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest());
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        assertThat(document.validate(false)).isEmpty();

        Path showcase = fixtureArchive(temporary.resolve("showcase.zip"));
        new ShowcaseArtifact().importInto(document, showcase);
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("rendered");
        DeploymentRenderer renderer = new DeploymentRenderer((artifact, members, threshold) ->
                fakeAuthenticatedMapProperties());
        DeploymentRenderer.Rendered rendered = renderer.render(document, output);
        String fixtureL1GenesisId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("{\"network\":\"preprod\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(rendered.nodes()).isEqualTo(5);
        assertThat(rendered.cloudNodes()).isEqualTo(3);
        assertThat(Files.readString(output.resolve("tofu/main.tf")))
                .contains("contabo/contabo", "hetznercloud/hcloud", "digitalocean/digitalocean")
                .contains("${hcloud_server.node_1.ipv4_address}/32",
                        "${digitalocean_droplet.node_2.ipv4_address}/32", "203.0.113.20/32")
                .contains("prevent_destroy = true")
                .doesNotContain("oauth2_client_secret", "contabo_client_secret");
        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("TOFU_PENDING", "203.0.113.20", "203.0.113.21");
        renderer.resolveInventory(document, output, Map.of(
                "node-0", "203.0.113.10", "node-1", "203.0.113.11", "node-2", "203.0.113.12",
                "node-3", "203.0.113.20", "node-4", "203.0.113.21"));
        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("203.0.113.10", "203.0.113.11", "203.0.113.12")
                .doesNotContain("TOFU_PENDING");
        assertThat(Files.readString(output.resolve("ansible/files/application-appchain.yml")))
                .contains("payment-chain-settlement", "yano-eutxo-v3-bridge-settlement")
                .contains("preset: \"params-only-v1\"")
                .doesNotContain("kafka", "objectstore-s3", "ipfs");
        assertThat(Files.readString(output.resolve("ansible/templates/node.properties.j2")))
                .contains("yano.server.enabled=true")
                .contains("yano.account-state.snapshot-retention-epochs=50")
                .contains("chains[10].observation.l1-network-genesis-id=" + fixtureL1GenesisId)
                .contains("chains[12].observation.l1-network-genesis-id=" + fixtureL1GenesisId)
                .doesNotContain("yano.app-chain.observation.l1-network-genesis-id=")
                .contains("yano.relay.connection.max-connections-per-ip=17")
                .contains("yano.relay.connection.max-inbound-connections=100")
                .contains("chains[8].machines.authenticated-map.genesis-cbor-hex=")
                .contains("chains[9].machines.authenticated-map.genesis-cbor-hex=")
                .contains("sequencer.mode=fixed")
                .contains("sequencer.proposer=" + PUBLIC_KEYS.getFirst())
                .doesNotContain("sequencer.window-slots=")
                .contains("chains[5].effects.executor.enabled={{ showcase_effect_owner | lower }}")
                .contains("chains[5].effects.executors.showcase-outbox.enabled="
                        + "{{ showcase_effect_owner | lower }}")
                .contains("effects.executors.showcase-outbox.directory="
                        + "/var/lib/yano/appchain-effects/showcase-outbox")
                .contains("chains[10].effects.executor.enabled={{ settlement_owner | lower }}")
                .contains("chains[10].effects.executors.eutxo-settlement.owner="
                        + "{{ settlement_owner | lower }}")
                .contains("anchor.signing-key={{ lookup('file', anchor_chain_key_files.get(")
                .contains("'orders-chain', anchor_default_key_file)) | trim }}")
                .doesNotContain(PUBLIC_KEYS.getFirst().substring(0, 16) + "<secret>");
        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("consensus_threshold: 4", "anchor_default_key_file", "anchor_chain_key_files: {}",
                        "settlement_operator_seed_file",
                        "anchor_leader: true", "settlement_owner: true",
                        "showcase_effect_owner: true");
        assertThat(Files.readString(output.resolve("ansible/deploy.yml")))
                .contains("'*effects-cardano*'", "'*eutxo-zk*'")
                .contains("Atomically install immutable digest release",
                        "/.yano-artifact-sha256", "Create filtered active plugin directory")
                .contains("/var/lib/yano/appchain-effects/showcase-outbox")
                .contains("Install settlement operator seed on its sole owner",
                        "Install parameterized settlement validators",
                        "Ensure Yano X is running without activating pending configuration",
                        "state: started")
                .doesNotContain("Delete forbidden integration bundles");
        assertThat(Files.readString(output.resolve("ansible/status.yml")))
                .contains("runtimeDegraded", "epochObservers.healthy", "observers.healthy",
                        "stateCommitment.genesisId", "stateCommitment.formatFingerprint",
                        "consensusProfile.digest", "capabilityManifest.manifestDigest",
                        "Require one consensus identity across the cluster");
        assertThat(Files.readString(output.resolve("ansible/mesh-recover.yml")))
                .contains("Cleanly stop every member", "Start every member in parallel",
                        "Require every chain to connect to every other member")
                .contains("selectattr('value', 'equalto', true)");
        assertThat(Files.readString(output.resolve("ansible/reset.yml")))
                .contains("Require immutable cluster identity and explicit reset scope",
                        "yano_confirm_reset_cluster", "yano_reset_scope",
                        "/var/lib/yano/chainstate", "/var/lib/yano/appchain-chainstate",
                        "/var/lib/yano/appchain-indexers", "/var/lib/yano/appchain-effects",
                        "/var/lib/yano/appchain-snapshot-archives",
                        "yano_start_after_reset | default(false)")
                .doesNotContain("/etc/yano", "/opt/yano");
        assertThat(Files.readString(output.resolve("ansible/bootstrap-anchors.yml")))
                .contains("Require explicit public-network spend authorization",
                        "any_errors_fatal: true",
                        "Require a private full API key for anchor administration",
                        "Require an L1-current signing quorum including the anchor leader",
                        "Reconcile every selected anchor in catalog order",
                        "bootstrap-one-anchor.yml")
                .contains("yano_anchor_chain | default('all')");
        assertThat(Files.readString(output.resolve("ansible/bootstrap-one-anchor.yml")))
                .contains("Require a usable funded anchor account",
                        "Submit the one-time SCRIPT anchor bootstrap transaction",
                        "X-API-Key: \"{{ lookup('file', api_key_file) | trim }}\"",
                        "no_log: true",
                        "Wait for the bootstrap identity to become L1-confirmed",
                        "Wait for the first threshold-signed advancement");
        assertThat(Files.readString(output.resolve("ansible/files/yano-x.env")))
                .contains("-Dquarkus.log.file.enabled=false");
        assertThat(Files.readString(output.resolve("ansible/files/yano-x.service")))
                .contains("WorkingDirectory=/opt/yano/home", "ExecStart=/opt/yano/home/yano.sh start:preprod")
                .doesNotContain("relay", "praos-lite");
        assertThat(Files.readString(output.resolve("ansible/deploy.yml")))
                .contains("patterns: 'yano-x-jvm-*'",
                        "src: \"{{ yano_release }}/examples/showcase/yano/config/\"")
                .doesNotContain("{{ yano_release }}/yano/");
        assertThat(Files.readString(output.resolve("ansible/files/application-appchain.yml")))
                .doesNotContain("standard-only-chain");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("YanoClusterDeploymentLock", "payment-chain-settlement",
                        "\"anchoring\"", "\"settlement\"", "\"l1LaunchProfile\" : \"preprod\"",
                        "\"connectionsPerPeer\" : 17", "\"meshRecovery\"",
                        "\"accountStrategy\" : \"shared-default-with-per-chain-overrides\"",
                        "\"mode\" : \"fixed\"", "\"proposerNode\" : \"node-0\"",
                "\"rendererRevision\" : 30", "\"preset\" : \"params-only-v1\"");
        verifyGeneratedSyntaxIfInstalled(output);
        Files.writeString(output.resolve("ansible/status.yml"), "# tampered\n",
                java.nio.file.StandardOpenOption.APPEND);
        DeploymentDocument lockedDocument = document;
        assertThatThrownBy(() -> renderer.verifyRendered(lockedDocument, output))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("generated deployment file differs from its lock");
    }

    @Test
    void rendersSelectedCardanoHistoryObservers(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        String genesisId = "ab".repeat(32);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest().replace(
                "    profile: distributed-showcase-preprod-anchored-settlement-v1\n",
                "    profile: distributed-showcase-preprod-anchored-settlement-v1\n"
                        + "    cardanoHistory:\n"
                        + "      l1Observations:\n"
                        + "        - l1-epoch-params-v1\n"
                        + "        - l1-epoch-stake-v1\n"
                        + "        - l1-epoch-governance-v1\n"
                        + "      genesisId: " + genesisId + "\n"
                        + "      sourceSnapshotRetentionEpochs: 400\n"));
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties()
                + "yano.app-chain.chains[12].state.genesis-id=" + "ef".repeat(32) + "\n")
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/files/application-appchain.yml")))
                .contains("genesis-id: \"" + genesisId + "\"",
                        "preset: \"full-v1\"",
                        "epoch-stake:", "type: \"l1-epoch-stake-v1\"",
                        "epoch-governance:", "type: \"l1-epoch-governance-v1\"",
                        "include-proposals: \"true\"", "include-drep-distribution: \"true\"",
                        "chunk-entries: \"25000\"", "drep-chunk-entries: \"25000\"",
                        "max-message-bytes: \"6291456\"", "max-bytes: \"8388608\"",
                        "authenticated-snapshots:", "enabled: \"true\"",
                        "series: \"l1-epoch-stake-v1.distribution,"
                                + "l1-epoch-governance-v1.drep-distribution\"",
                        "archive-directory: \"/var/lib/yano/appchain-snapshot-archives\"");
        String nodeProperties = Files.readString(output.resolve("ansible/templates/node.properties.j2"));
        assertThat(nodeProperties)
                .contains("yano.account-state.snapshot-retention-epochs=400")
                .endsWith("yano.app-chain.chains[12].state.genesis-id=" + genesisId + "\n");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"preset\" : \"full-v1\"", "\"genesisId\" : \"" + genesisId + "\"",
                        "l1-epoch-params-v1", "l1-epoch-stake-v1", "l1-epoch-governance-v1",
                        "\"sourceSnapshotRetentionEpochs\" : 400");
    }

    @Test
    void rejectsCardanoHistoryObserversWithoutEpochParams(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "    profile: distributed-showcase-preprod-anchored-settlement-v1\n",
                "    profile: distributed-showcase-preprod-anchored-settlement-v1\n"
                        + "    cardanoHistory:\n"
                        + "      l1Observations: [l1-epoch-stake-v1]\n"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("Cardano History l1Observations must include l1-epoch-params-v1");
    }

    @Test
    void rendersPerChainAnchorAccountOverridesWithSharedDefault(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(secrets.resolve("orders-anchor.seed"), "ee".repeat(32));
        Files.setPosixFilePermissions(secrets.resolve("orders-anchor.seed"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.writeString(cluster.resolve("deployment.yaml"), manifest().replace(
                "seedFile: secrets/anchor.seed\n",
                "seedFile: secrets/anchor.seed\n"
                        + "      chainSeedFiles:\n"
                        + "        orders-chain: secrets/orders-anchor.seed\n"));
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties())
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("anchor_default_key_file:", "anchor_chain_key_files:",
                        "'orders-chain':", "orders-anchor.seed");
        assertThat(Files.readString(output.resolve("ansible/templates/node.properties.j2")))
                .contains("'orders-chain', anchor_default_key_file")
                .contains("'registry-chain', anchor_default_key_file");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"perChainAccountOverrides\" : [ \"orders-chain\" ]")
                .doesNotContain("orders-anchor.seed");
    }

    @Test
    @EnabledIfSystemProperty(named = "yano.test.showcase-zip", matches = ".+")
    void rendersTheRealShowcaseDistribution(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest());
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        new ShowcaseArtifact().importInto(document,
                Path.of(System.getProperty("yano.test.showcase-zip")));
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("real-render");
        new DeploymentRenderer().render(document, output);
        assertThat(Files.readString(output.resolve("ansible/files/application-appchain.yml")))
                .contains("cardano-history-chain", "org.yanoproject.x.eutxo.bridge.cardano",
                        "payment-chain-settlement", "eutxo-settlement", "operator-seed-file")
                .doesNotContain("operator-seed:");
        assertThat(Files.readString(output.resolve("ansible/templates/node.properties.j2")))
                .contains("chains[8].machines.authenticated-map.genesis-cbor-hex=")
                .contains("chains[12].sequencer.mode=fixed")
                .contains("chains[12].sequencer.proposer=" + PUBLIC_KEYS.getFirst());
    }

    @Test
    void rendersExplicitPublicKeyOnlySsh(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest()
                .replace("ssh: {mode: allowlist, sourceCidrs: [\"198.51.100.0/24\"]}",
                        "ssh: {mode: public-key-only, sourceCidrs: []}"));
        DeploymentDocument document = new DeploymentLoader().load(cluster);
        assertThat(document.validate(false)).isEmpty();

        Path showcase = fixtureArchive(temporary.resolve("showcase.zip"));
        new ShowcaseArtifact().importInto(document, showcase);
        DeploymentLoader loader = new DeploymentLoader();
        loader.write(document);
        document = loader.load(cluster);
        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) ->
                fakeAuthenticatedMapProperties()).render(document, output);

        assertThat(Files.readString(output.resolve("tofu/main.tf")))
                .contains("0.0.0.0/0", "::/0");
        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("ssh_access_mode: 'public-key-only'", "ssh_source_cidrs: []");
        assertThat(Files.readString(output.resolve("ansible/deploy.yml")))
                .contains("Require hardened SSH before allowing public ingress")
                .contains("ansible_facts.env.SSH_CONNECTION")
                .contains("ufw allow {{ ansible_port }}/tcp")
                .doesNotContain("ufw limit {{ ansible_port }}/tcp\n");
    }

    @Test
    void rendersOptionalCloudflareProxiedTraefikGateway(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        String configured = manifest()
                .replace("roles: [validator, api-gateway]\n    memberPublicKey:",
                        "roles: [validator, api-gateway]\n"
                                + "    hostname: node0-showcase.example.org\n    memberPublicKey:")
                .replace("api: {exposure: disabled, sourceCidrs: []}",
                        "api: {exposure: public-https, proxy: cloudflare, sourceCidrs: [], "
                                + "rateLimit: {average: 200, burst: 400}, cors: {allowedOrigins: "
                                + "[\"http://127.0.0.1:4173\", \"https://wallet.example.org\"]}}");
        Files.writeString(cluster.resolve("deployment.yaml"), configured);
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties())
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("api_hostname: 'node0-showcase.example.org'", "api_gateway: true",
                        "api_proxy: 'cloudflare'", "api_rate_limit_average: 200",
                        "api_rate_limit_burst: 400", "api_cors_allowed_origins:",
                        "http://127.0.0.1:4173", "https://wallet.example.org",
                        "173.245.48.0/20", "2606:4700::/32");
        assertThat(Files.readString(output.resolve("ansible/deploy.yml")))
                .contains("Download exact Traefik release", "Retain ACME account and certificate state",
                        "name: traefik", "Disable the legacy Nginx gateway", "name: nginx",
                        "Keep API reverse proxy stopped until cluster health is validated")
                .doesNotContain("ufw allow from {{ cidr }} to any port 80",
                        "Wait for the local HTTPS gateway route", "nginx-yano");
        assertThat(Files.readString(output.resolve("ansible/templates/traefik-yano.yml.j2")))
                .contains("Host(`{{ api_hostname }}`)", "certResolver: letsencrypt",
                        "http://127.0.0.1:{{ http_port }}", "Cache-Control: \"no-store\"",
                        "average: {{ api_rate_limit_average }}", "burst: {{ api_rate_limit_burst }}",
                        "middlewares: [yano-cors, yano-security, yano-rate-limit]",
                        "accessControlAllowMethods: [GET, HEAD, POST, OPTIONS]",
                        "accessControlAllowHeaders: [Accept, Content-Type, X-API-Key]",
                        "accessControlAllowOriginList:", "{{ origin }}");
        assertThat(Files.readString(output.resolve("ansible/gateway.yml")))
                .contains("Reconcile Yano X API gateways", "Install generated Traefik API routes",
                        "Open API ingress only after cluster validation", "port 80 proto tcp",
                        "port 443 proto tcp", "state: started",
                        "Wait for the reconciled local HTTPS gateway route")
                .doesNotContain("yano-x.service", "state: restarted");
        assertThat(Files.readString(output.resolve("ansible/files/traefik.service")))
                .contains("CAP_NET_BIND_SERVICE", "ProtectSystem=strict");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"implementation\" : \"traefik\"", "\"version\" : \"3.7.11\"",
                        "\"rateLimitAverage\" : 200", "\"rateLimitBurst\" : 400",
                        "\"corsAllowedOrigins\"", "http://127.0.0.1:4173");
    }

    @Test
    void rendersOptionalCentralPrometheusMonitoring(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        String configured = manifest()
                .replace("roles: [validator, api-gateway]", "roles: [validator, api-gateway, monitoring]")
                .replace("  access:\n", "  monitoring:\n"
                        + "    mode: central-prometheus\n"
                        + "    node: node-0\n"
                        + "    hostname: metrics-showcase.example.org\n"
                        + "    exposure: public-https\n"
                        + "    proxy: cloudflare\n"
                        + "    retention: 15d\n"
                        + "    retentionSize: 2GB\n"
                        + "  access:\n");
        Files.writeString(cluster.resolve("deployment.yaml"), configured);
        DeploymentLoader loader = new DeploymentLoader();
        DeploymentDocument document = loader.load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        loader.write(document);
        document = loader.load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties())
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/inventory.yml")))
                .contains("monitoring_enabled: true", "monitoring_node_name: 'node-0'",
                        "monitoring_hostname: 'metrics-showcase.example.org'",
                        "monitoring_owner: true", "monitoring_target: true",
                        "monitoring_scrape_port: 9091",
                        "prom/prometheus:v3.13.1@sha256:");
        assertThat(Files.readString(output.resolve("ansible/monitoring.yml")))
                .contains("Install Docker and Compose on the monitoring owner",
                        "Restrict direct metrics ingress to the monitoring owner",
                        "Wait for all Yano scrape targets", "validator_count");
        assertThat(Files.readString(output.resolve("ansible/templates/prometheus.yml.j2")))
                .contains("127.0.0.1:{{ http_port }}",
                        "{{ hostvars[node].ansible_host }}:{{ monitoring_scrape_port }}",
                        "yano_node:");
        assertThat(Files.readString(output.resolve("ansible/templates/traefik-yano.yml.j2")))
                .contains("Host(`{{ monitoring_hostname }}`)", "Path(`/q/metrics`)",
                        "sourceRange:", "{{ monitoring_owner_cidr }}",
                        "http://127.0.0.1:9090", "!Query(`metrics`)",
                        "?metrics={{ monitoring_url_encoded }}",
                        "connect-src 'self' https://{{ monitoring_hostname }}");
        assertThat(Files.readString(output.resolve("ansible/files/prometheus-compose.yml")))
                .contains("network_mode: host", "--web.listen-address=127.0.0.1:9090",
                        "restart: unless-stopped",
                        "prometheus-data:/prometheus");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"mode\" : \"central-prometheus\"",
                        "\"hostname\" : \"metrics-showcase.example.org\"",
                        "prom/prometheus:v3.13.1@sha256:");
    }

    @Test
    void rejectsAmbiguousPublicKeyOnlySshCidrs(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest()
                .replace("ssh: {mode: allowlist, sourceCidrs: [\"198.51.100.0/24\"]}",
                        "ssh: {mode: public-key-only, sourceCidrs: [\"198.51.100.0/24\"]}"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("public-key-only SSH requires an empty sourceCidrs list");
    }

    @Test
    void rejectsUnknownApplicationProfile(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "distributed-showcase-preprod-anchored-settlement-v1", "unknown-profile"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("application profile must be distributed-showcase-preprod-anchored-settlement-v1");
    }

    @Test
    void rendersExplicitRotatingSequencer(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest().replace(
                "sequencer: {mode: fixed, proposerNode: node-0}",
                "sequencer: {mode: rotating, windowSlots: 120}"));
        DeploymentDocument document = new DeploymentLoader().load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        new DeploymentLoader().write(document);
        document = new DeploymentLoader().load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties())
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/templates/node.properties.j2")))
                .contains("sequencer.mode=rotating", "sequencer.window-slots=120")
                .doesNotContain("sequencer.proposer=");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"mode\" : \"rotating\"", "\"windowSlots\" : 120");
    }

    @Test
    void rejectsFixedSequencerWhoseProposerIsNotAValidator(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "proposerNode: node-0", "proposerNode: observer-0"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("fixed consensus sequencer proposerNode must name a validator");
    }

    @Test
    void acceptsAnExplicitComposableL1Profile(@TempDir Path temporary) throws Exception {
        Path cluster = Files.createDirectories(temporary.resolve("cluster"));
        Path secrets = Files.createDirectories(cluster.resolve("secrets"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(secrets.resolve("node-" + index + ".seed"),
                    "%02x".formatted(index + 1).repeat(32));
        }
        writeApplicationSecrets(secrets);
        Files.writeString(cluster.resolve("deployment.yaml"), manifest().replace(
                "l1: {network: preprod}", "l1: {network: preprod, profile: 'preprod,relay'}"));
        DeploymentDocument document = new DeploymentLoader().load(cluster);
        assertThat(document.validate(false)).isEmpty();
        new ShowcaseArtifact().importInto(document, fixtureArchive(temporary.resolve("showcase.zip")));
        new DeploymentLoader().write(document);
        document = new DeploymentLoader().load(cluster);

        Path output = temporary.resolve("rendered");
        new DeploymentRenderer((artifact, members, threshold) -> fakeAuthenticatedMapProperties())
                .render(document, output);

        assertThat(Files.readString(output.resolve("ansible/files/yano-x.service")))
                .contains("yano.sh start:preprod,relay")
                .doesNotContain("praos-lite");
        assertThat(Files.readString(output.resolve("deployment.lock.json")))
                .contains("\"l1LaunchProfile\" : \"preprod,relay\"");
    }

    @Test
    void rejectsAnL1ProfileWithoutTheSelectedNetwork(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "l1: {network: preprod}", "l1: {network: preprod, profile: relay}"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("spec.l1.profile must include spec.l1.network");
    }

    @Test
    void rejectsApiRateLimitBurstBelowAverage(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "api: {exposure: disabled, sourceCidrs: []}",
                "api: {exposure: disabled, sourceCidrs: [], rateLimit: {average: 200, burst: 100}}"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("API rate-limit burst must be at least average and no more than 20000");
    }

    @Test
    void rejectsUnsafeApiCorsOrigins(@TempDir Path temporary) throws Exception {
        Files.writeString(temporary.resolve("deployment.yaml"), manifest().replace(
                "api: {exposure: disabled, sourceCidrs: []}",
                "api: {exposure: public-https, sourceCidrs: [], cors: {allowedOrigins: "
                        + "[\"*\", \"http://remote.example.org\", \"https://ui.example.org/path\"]}}"));

        assertThat(new DeploymentLoader().load(temporary).validate(false))
                .contains("invalid API CORS allowed origin: *",
                        "invalid API CORS allowed origin: http://remote.example.org",
                        "invalid API CORS allowed origin: https://ui.example.org/path");
    }

    private void writeApplicationSecrets(Path secrets) throws Exception {
        Files.writeString(secrets.resolve("anchor.seed"), "aa".repeat(32));
        Files.writeString(secrets.resolve("operator.seed"), "bb".repeat(32));
        Files.setPosixFilePermissions(secrets.resolve("anchor.seed"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(secrets.resolve("operator.seed"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var operator = org.yanoproject.x.eutxo.demo.SettlementOperatorIdentity
                .fromKeyFile(secrets.resolve("operator.seed"));
        var root = new org.yanoproject.x.eutxo.contracts.EutxoOutpoint("cc".repeat(32), 0);
        var shard = new org.yanoproject.x.eutxo.contracts.EutxoOutpoint("dd".repeat(32), 1);
        var plan = org.yanoproject.x.eutxo.demo.SettlementBootstrapPlan.plan(
                root, shard,
                new org.yanoproject.x.eutxo.demo.SettlementBootstrapPlan.Config(
                        "payment-chain-settlement", 0,
                        com.bloxbean.cardano.client.common.model.Networks.testnet(),
                        org.yanoproject.x.eutxo.demo.ShowcaseSettlementPlan.ROOT_TOKEN
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        PUBLIC_KEYS, 4, 0,
                        org.yanoproject.x.eutxo.contracts.EutxoProfile.V3
                                .fallbackDelayMinSlots()));
        new org.yanoproject.x.eutxo.demo.SettlementDeploymentRecord(
                "payment-chain-settlement", "preprod",
                org.yanoproject.x.eutxo.contracts.EutxoProfile.V3.id(),
                root, shard, operator.operatorAddress(), plan.vaultAddress(),
                plan.shardAddress(), plan.rootAddress()).save(secrets);
    }

    private Path fixtureArchive(Path path) throws Exception {
        String root = "yano-x-jvm-test/";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("examples/showcase/showcase.sh", "#!/bin/sh\n");
        files.put("yano.sh", "#!/bin/sh\n");
        files.put("yano.jar", "fixture");
        // The distribution's own single-chain config must never reach a showcase node.
        files.put("config/application-appchain.yml", """
                yano:
                  app-chain:
                    chains[0]:
                      chain-id: "standard-only-chain"
                      threshold: 1
                """);
        files.put("yano-distribution-v1.json", """
                {"schemaVersion":1,"product":"yano","distribution":"core-jvm","version":"test",
                 "pluginDirectorySupported":true}
                """);
        String bundle = "fixture-bundle";
        String bundleFile = "fixture-bundle-test.jar";
        files.put("plugins/" + bundleFile, bundle);
        String pluginPack = """
                {"schemaVersion":1,"product":"yano-x","version":"test","bundles":[
                  {"artifactId":"fixture-bundle","bundleId":"fixture","version":"test",
                   "file":"%s","sha256":"%s","installMode":"default",
                   "dependencies":[],"contributions":[]}]}
                """.formatted(bundleFile, sha256(bundle));
        files.put("yano-x-plugin-pack-v1.json", pluginPack);
        files.put("yano-x-distribution-v1.json", """
                {"schemaVersion":1,"product":"yano-x","distribution":"jvm","version":"test",
                 "yanoVersion":"test","baseDistributionSha256":"%s",
                 "pluginPackManifestSha256":"%s","availablePluginBundleCount":1,
                 "installedPluginBundleCount":1,"optionalPluginBundleCount":0,
                 "nativeImageSupported":false}
                """.formatted("00".repeat(32), sha256(pluginPack)));
        files.put("sbom/yano.cdx.json",
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}");
        files.put("sbom/yano-x.cdx.json",
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}");
        files.put("examples/showcase/catalog/showcase-catalog-v1.json", catalog());
        files.put("examples/showcase/yano/config/application-appchain.yml", application());
        files.put("config/network/preprod/shelley-genesis.json", "{\"network\":\"preprod\"}\n");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(root + entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String catalog() throws Exception {
        List<String> chains = List.of("orders-chain", "registry-chain", "approvals-chain", "balances-chain",
                "documents-chain", "workflow-chain", "roles-chain", "payments-chain",
                "authenticated-map-chain", "authenticated-map-jmt-chain", "payment-chain-settlement",
                "document-review-chain", "cardano-history-chain");
        List<Map<String, String>> items = chains.stream().map(chain -> Map.of("chainId", chain)).toList();
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                Map.of("schemaVersion", 1, "profileId", "light-v1", "chains", items));
    }

    private String application() {
        StringBuilder result = new StringBuilder("""
                yano:
                  plugins:
                    allow-list: [stdlib, kafka, objectstore-s3, ipfs, effects-cardano]
                  app-chain:
                """);
        List<String> chains = List.of("orders-chain", "registry-chain", "approvals-chain", "balances-chain",
                "documents-chain", "workflow-chain", "roles-chain", "payments-chain",
                "authenticated-map-chain", "authenticated-map-jmt-chain", "payment-chain-settlement",
                "document-review-chain", "cardano-history-chain");
        for (int index = 0; index < chains.size(); index++) {
            result.append("    chains[").append(index).append("]:\n      chain-id: ")
                    .append(chains.get(index)).append("\n      state-machine: ordered-log\n");
            if (index == 10 || index == 12) {
                result.append("      observers:\n        fixture:\n          type: fixture-v1\n");
            }
        }
        return result.toString();
    }

    private String fakeAuthenticatedMapProperties() {
        StringBuilder result = new StringBuilder();
        for (int index : List.of(8, 9)) {
            String prefix = "yano.app-chain.chains[" + index + "].";
            result.append(prefix).append("state.commitment-profile=mpf-blake2b256-v1\n")
                    .append(prefix).append("state.format-fingerprint=").append("ab".repeat(32)).append('\n')
                    .append(prefix).append("state.genesis-id=").append("cd".repeat(32)).append('\n')
                    .append(prefix).append("machines.authenticated-map.genesis-cbor-hex=00\n");
        }
        return result.toString();
    }

    private void verifyGeneratedSyntaxIfInstalled(Path output) throws Exception {
        if (available("tofu")) {
            run(output.resolve("tofu"), "tofu", "fmt", "-write=false", "main.tf");
        }
        if (available("ansible-playbook")) {
            for (String playbook : List.of("preflight.yml", "deploy.yml", "mesh-recover.yml", "status.yml",
                    "gateway.yml", "monitoring.yml", "reset.yml", "bootstrap-anchors.yml")) {
                run(output.resolve("ansible"), "ansible-playbook", "-i", "inventory.yml",
                        playbook, "--syntax-check");
            }
        }
    }

    private boolean available(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s%n%s", String.join(" ", command), output).isZero();
    }

    private String manifest() {
        StringBuilder nodes = new StringBuilder();
        String[] providers = {"contabo", "hetzner", "digitalocean", "internal", "internal"};
        String[] addresses = {"", "", "", "203.0.113.20", "203.0.113.21"};
        for (int index = 0; index < 5; index++) {
            String cloud = index < 3
                    ? "    region: region-" + index + "\n    instanceType: type-" + index
                    + "\n    image: image-" + index + "\n" : "    address: " + addresses[index] + "\n";
            nodes.append("  - name: node-").append(index).append("\n")
                    .append("    index: ").append(index).append("\n")
                    .append("    providerRef: ").append(providers[index]).append("\n")
                    .append(cloud)
                    .append("    sshUser: admin\n    sshPort: 22\n")
                    .append("    roles: [validator").append(index == 0 ? ", api-gateway" : "").append("]\n")
                    .append("    memberPublicKey: ").append(PUBLIC_KEYS.get(index)).append("\n")
                    .append("    memberPrivateKeyFile: secrets/node-").append(index).append(".seed\n");
        }
        return """
                apiVersion: yano.bloxbean.com/v1alpha1
                kind: YanoClusterDeployment
                metadata: {name: mixed-preprod}
                spec:
                  identity: {clusterId: "11111111-1111-1111-1111-111111111111"}
                  l1: {network: preprod}
                  infrastructure:
                    openTofuVersion: "1.10.6"
                    state: {backendType: s3, backendConfigFile: secrets/backend.hcl}
                  providers:
                    - {name: contabo, type: contabo, providerVersion: "0.1.44", settings: {sshKeyIds: [1]}}
                    - {name: hetzner, type: hetzner-cloud, providerVersion: "1.68.0", settings: {sshKeys: [team]}}
                    - {name: digitalocean, type: digitalocean, providerVersion: "2.100.0", settings: {sshKeys: [team]}}
                    - {name: internal, type: existing}
                  nodes:
                """ + nodes + """
                  consensus:
                    threshold: 4
                    sequencer: {mode: fixed, proposerNode: node-0}
                  network: {p2pPort: 13337, httpPort: 7070}
                  runtime: {artifact: {kind: local-showcase-zip, file: "", sha256: ""}}
                  application:
                    profile: distributed-showcase-preprod-anchored-settlement-v1
                    anchoring:
                      mode: script
                      chains: all
                      leaderNode: node-0
                      seedFile: secrets/anchor.seed
                      everyBlocks: 30
                      maxIntervalMinutes: 60
                    settlement:
                      mode: preprod
                      chainId: payment-chain-settlement
                      ownerNode: node-0
                      operatorSeedFile: secrets/operator.seed
                      deploymentRecordFile: secrets/settlement-deployment-payment-chain-settlement.properties
                  access:
                    ssh: {mode: allowlist, sourceCidrs: ["198.51.100.0/24"]}
                    api: {exposure: disabled, sourceCidrs: []}
                  destructionProtection: true
                """;
    }
}
