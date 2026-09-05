package com.bloxbean.cardano.yano.appchain.deployment;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class DeploymentDocument {
    static final Set<String> PROVIDER_TYPES = Set.of(
            "contabo", "hetzner-cloud", "digitalocean", "existing");
    static final String EPOCH_PARAMS_OBSERVER = "l1-epoch-params-v1";
    static final String EPOCH_STAKE_OBSERVER = "l1-epoch-stake-v1";
    static final String EPOCH_GOVERNANCE_OBSERVER = "l1-epoch-governance-v1";
    static final Set<String> CARDANO_HISTORY_OBSERVERS = Set.of(
            EPOCH_PARAMS_OBSERVER, EPOCH_STAKE_OBSERVER, EPOCH_GOVERNANCE_OBSERVER);
    static final Set<String> NODE_ROLES = Set.of(
            "validator", "l1-bootstrap", "api-gateway", "monitoring");
    private static final DeploymentSchemaValidator SCHEMA = new DeploymentSchemaValidator();
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern PROFILE_LIST = Pattern.compile(
            "[a-z][a-z0-9-]{0,62}(,[a-z][a-z0-9-]{0,62})*");
    private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}");
    private static final Pattern RETENTION = Pattern.compile("([1-9][0-9]*)(h|d)");
    private static final Pattern RETENTION_SIZE = Pattern.compile("([1-9][0-9]*)(MB|GB)");

    private final Path directory;
    private final JsonNode root;

    DeploymentDocument(Path directory, JsonNode root) {
        this.directory = directory;
        this.root = root;
    }

    Path directory() {
        return directory;
    }

    JsonNode root() {
        return root;
    }

    String name() {
        return text("/metadata/name");
    }

    String clusterId() {
        return text("/spec/identity/clusterId");
    }

    String network() {
        return text("/spec/l1/network");
    }

    String launchProfile() {
        String configured = text("/spec/l1/profile");
        return configured.isBlank() ? network() : configured;
    }

    int p2pPort() {
        return integer("/spec/network/p2pPort", 13337);
    }

    int httpPort() {
        return integer("/spec/network/httpPort", 7070);
    }

    int threshold() {
        return integer("/spec/consensus/threshold", validators().size() * 2 / 3 + 1);
    }

    String sequencerMode() {
        return text("/spec/consensus/sequencer/mode");
    }

    String sequencerProposerNode() {
        return text("/spec/consensus/sequencer/proposerNode");
    }

    String sequencerProposerPublicKey() {
        return validators().stream()
                .filter(node -> node.name().equals(sequencerProposerNode()))
                .map(Node::memberPublicKey)
                .findFirst()
                .orElse("");
    }

    int sequencerWindowSlots() {
        return integer("/spec/consensus/sequencer/windowSlots", 60);
    }

    boolean destructionProtection() {
        return root.at("/spec/destructionProtection").asBoolean(true);
    }

    String openTofuVersion() {
        return text("/spec/infrastructure/openTofuVersion");
    }

    String stateBackendType() {
        return text("/spec/infrastructure/state/backendType");
    }

    String stateBackendConfigFile() {
        return text("/spec/infrastructure/state/backendConfigFile");
    }

    String artifactFile() {
        return text("/spec/runtime/artifact/file");
    }

    String artifactSha256() {
        return text("/spec/runtime/artifact/sha256").toLowerCase(Locale.ROOT);
    }

    Path artifactPath() {
        Path configured = Path.of(artifactFile());
        return configured.isAbsolute() ? configured.normalize() : directory.resolve(configured).normalize();
    }

    String apiExposure() {
        String value = text("/spec/access/api/exposure");
        return value.isBlank() ? "disabled" : value;
    }

    String sshAccessMode() {
        String value = text("/spec/access/ssh/mode");
        return value.isBlank() ? "allowlist" : value;
    }

    List<String> sshCidrs() {
        return strings("/spec/access/ssh/sourceCidrs");
    }

    List<String> apiCidrs() {
        return strings("/spec/access/api/sourceCidrs");
    }

    String apiProxy() {
        String value = text("/spec/access/api/proxy");
        return value.isBlank() ? "direct" : value;
    }

    int apiRateLimitAverage() {
        return integer("/spec/access/api/rateLimit/average", 20);
    }

    int apiRateLimitBurst() {
        return integer("/spec/access/api/rateLimit/burst", 40);
    }

    List<String> apiCorsAllowedOrigins() {
        return strings("/spec/access/api/cors/allowedOrigins");
    }

    String apiCertificateFile() {
        return text("/spec/access/api/tls/certificateFile");
    }

    String apiPrivateKeyFile() {
        return text("/spec/access/api/tls/privateKeyFile");
    }

    String apiKeyFile() {
        return text("/spec/access/api/apiKeyFile");
    }

    String monitoringMode() {
        String value = text("/spec/monitoring/mode");
        return value.isBlank() ? "none" : value;
    }

    boolean monitoringEnabled() {
        return "central-prometheus".equals(monitoringMode());
    }

    String monitoringNode() {
        return text("/spec/monitoring/node");
    }

    String monitoringHostname() {
        return text("/spec/monitoring/hostname");
    }

    String monitoringProxy() {
        String value = text("/spec/monitoring/proxy");
        return value.isBlank() ? "direct" : value;
    }

    String monitoringExposure() {
        String value = text("/spec/monitoring/exposure");
        return value.isBlank() ? "disabled" : value;
    }

    String monitoringRetention() {
        String value = text("/spec/monitoring/retention");
        return value.isBlank() ? "15d" : value;
    }

    String monitoringRetentionSize() {
        String value = text("/spec/monitoring/retentionSize");
        return value.isBlank() ? "2GB" : value;
    }

    int monitoringScrapePort() {
        return integer("/spec/monitoring/scrapePort", 9091);
    }

    String applicationProfile() {
        return text("/spec/application/profile");
    }

    List<String> cardanoHistoryL1Observations() {
        List<String> configured = strings("/spec/application/cardanoHistory/l1Observations");
        return configured.isEmpty() ? List.of(EPOCH_PARAMS_OBSERVER) : configured;
    }

    String cardanoHistoryPreset() {
        Set<String> observations = Set.copyOf(cardanoHistoryL1Observations());
        if (observations.contains(EPOCH_STAKE_OBSERVER)
                && observations.contains(EPOCH_GOVERNANCE_OBSERVER)) {
            return "full-v1";
        }
        if (observations.contains(EPOCH_STAKE_OBSERVER)) {
            return "params-stake-v1";
        }
        if (observations.contains(EPOCH_GOVERNANCE_OBSERVER)) {
            return "params-governance-v1";
        }
        return "params-only-v1";
    }

    String cardanoHistoryGenesisId() {
        return text("/spec/application/cardanoHistory/genesisId").toLowerCase(Locale.ROOT);
    }

    int cardanoHistorySourceSnapshotRetentionEpochs() {
        return integer("/spec/application/cardanoHistory/sourceSnapshotRetentionEpochs", 50);
    }

    String anchoringMode() {
        return text("/spec/application/anchoring/mode");
    }

    String anchoredChains() {
        return text("/spec/application/anchoring/chains");
    }

    String anchorLeaderNode() {
        return text("/spec/application/anchoring/leaderNode");
    }

    String anchorSeedFile() {
        return text("/spec/application/anchoring/seedFile");
    }

    Map<String, String> anchorChainSeedFiles() {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode configured = root.at("/spec/application/anchoring/chainSeedFiles");
        if (configured.isObject()) {
            configured.fields().forEachRemaining(entry ->
                    result.put(entry.getKey().trim(), entry.getValue().asText("").trim()));
        }
        return Map.copyOf(result);
    }

    int anchorEveryBlocks() {
        return integer("/spec/application/anchoring/everyBlocks", 30);
    }

    int anchorMaxIntervalMinutes() {
        return integer("/spec/application/anchoring/maxIntervalMinutes", 60);
    }

    String settlementMode() {
        return text("/spec/application/settlement/mode");
    }

    String settlementChainId() {
        return text("/spec/application/settlement/chainId");
    }

    String settlementOwnerNode() {
        return text("/spec/application/settlement/ownerNode");
    }

    String settlementOperatorSeedFile() {
        return text("/spec/application/settlement/operatorSeedFile");
    }

    String settlementDeploymentRecordFile() {
        return text("/spec/application/settlement/deploymentRecordFile");
    }

    Path anchorSeedPath() {
        return resolve(anchorSeedFile());
    }

    Map<String, Path> anchorChainSeedPaths() {
        Map<String, Path> result = new LinkedHashMap<>();
        anchorChainSeedFiles().forEach((chainId, reference) -> result.put(chainId, resolve(reference)));
        return Map.copyOf(result);
    }

    Path settlementOperatorSeedPath() {
        return resolve(settlementOperatorSeedFile());
    }

    Path settlementDeploymentRecordPath() {
        return resolve(settlementDeploymentRecordFile());
    }

    List<Provider> providers() {
        List<Provider> result = new ArrayList<>();
        for (JsonNode provider : array("/spec/providers")) {
            result.add(new Provider(required(provider, "name"), required(provider, "type"), provider));
        }
        return List.copyOf(result);
    }

    Map<String, Provider> providersByName() {
        Map<String, Provider> result = new LinkedHashMap<>();
        providers().forEach(provider -> result.put(provider.name(), provider));
        return result;
    }

    List<Node> nodes() {
        List<Node> result = new ArrayList<>();
        for (JsonNode node : array("/spec/nodes")) {
            List<String> roles = new ArrayList<>();
            node.path("roles").forEach(role -> roles.add(role.asText()));
            result.add(new Node(
                    required(node, "name"),
                    node.path("index").asInt(-1),
                    required(node, "providerRef"),
                    optional(node, "region"),
                    optional(node, "address"),
                    optional(node, "advertisedAddress"),
                    optional(node, "hostname"),
                    optional(node, "sshUser"),
                    node.path("sshPort").asInt(22),
                    optional(node, "instanceType"),
                    optional(node, "image"),
                    optional(node, "memberPublicKey"),
                    optional(node, "memberPrivateKeyFile"),
                    List.copyOf(roles),
                    node));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(Node::index)).toList();
    }

    List<Node> validators() {
        return nodes().stream().filter(node -> node.roles().contains("validator")).toList();
    }

    List<String> validate(boolean requireArtifact) {
        List<String> errors = new ArrayList<>();
        errors.addAll(SCHEMA.validate(root));
        if (!"yano.bloxbean.com/v1alpha1".equals(text("/apiVersion"))) {
            errors.add("apiVersion must be yano.bloxbean.com/v1alpha1");
        }
        if (!"YanoClusterDeployment".equals(text("/kind"))) {
            errors.add("kind must be YanoClusterDeployment");
        }
        if (!NAME.matcher(name()).matches()) {
            errors.add("metadata.name must be a lower-case DNS label");
        }
        try {
            UUID.fromString(clusterId());
        } catch (IllegalArgumentException failure) {
            errors.add("spec.identity.clusterId must be a UUID");
        }
        if (!NAME.matcher(network()).matches()) {
            errors.add("spec.l1.network must be an explicit profile name");
        }
        if (!PROFILE_LIST.matcher(launchProfile()).matches()) {
            errors.add("spec.l1.profile must be a comma-separated list of profile names");
        } else {
            List<String> profiles = List.of(launchProfile().split(","));
            if (!profiles.contains(network())) {
                errors.add("spec.l1.profile must include spec.l1.network");
            }
            if (new HashSet<>(profiles).size() != profiles.size()) {
                errors.add("spec.l1.profile must not contain duplicate profiles");
            }
        }
        Map<String, Provider> providerMap = providersByName();
        if (providerMap.size() != providers().size() || providerMap.isEmpty()) {
            errors.add("provider names must be unique and at least one provider is required");
        }
        providers().forEach(provider -> validateProvider(provider, errors));
        validateNodes(providerMap, errors);
        validateApplication(errors);
        validateMonitoring(errors);
        if (p2pPort() < 1024 || p2pPort() > 65535 || httpPort() < 1024 || httpPort() > 65535
                || p2pPort() == httpPort()) {
            errors.add("P2P and HTTP ports must be distinct values in 1024..65535");
        }
        if (!Set.of("disabled", "allowlist", "public-https").contains(apiExposure())) {
            errors.add("API exposure must be disabled, allowlist, or public-https");
        }
        if (!Set.of("direct", "cloudflare").contains(apiProxy())) {
            errors.add("API proxy must be direct or cloudflare");
        }
        if (apiRateLimitAverage() < 1 || apiRateLimitAverage() > 10_000) {
            errors.add("API rate-limit average must be in 1..10000 requests per second");
        }
        if (apiRateLimitBurst() < apiRateLimitAverage() || apiRateLimitBurst() > 20_000) {
            errors.add("API rate-limit burst must be at least average and no more than 20000");
        }
        if (apiCorsAllowedOrigins().size() > 16) {
            errors.add("API CORS supports no more than 16 allowed origins");
        }
        if (new HashSet<>(apiCorsAllowedOrigins()).size() != apiCorsAllowedOrigins().size()) {
            errors.add("API CORS allowed origins must be unique");
        }
        apiCorsAllowedOrigins().stream()
                .filter(origin -> !validCorsOrigin(origin))
                .forEach(origin -> errors.add("invalid API CORS allowed origin: " + origin));
        if ("disabled".equals(apiExposure()) && !apiCorsAllowedOrigins().isEmpty()) {
            errors.add("API CORS allowed origins require API exposure to be enabled");
        }
        if (!Set.of("allowlist", "public-key-only").contains(sshAccessMode())) {
            errors.add("SSH access mode must be allowlist or public-key-only");
        }
        if ("allowlist".equals(sshAccessMode())
                && (sshCidrs().isEmpty() || sshCidrs().stream().anyMatch(DeploymentDocument::wildcardCidr))) {
            errors.add("allowlisted SSH requires non-wildcard source CIDRs");
        }
        sshCidrs().stream().filter(cidr -> !validCidr(cidr))
                .forEach(cidr -> errors.add("invalid SSH source CIDR: " + cidr));
        if ("public-key-only".equals(sshAccessMode()) && !sshCidrs().isEmpty()) {
            errors.add("public-key-only SSH requires an empty sourceCidrs list");
        }
        if ("allowlist".equals(apiExposure())
                && (apiCidrs().isEmpty() || apiCidrs().stream().anyMatch(DeploymentDocument::wildcardCidr))) {
            errors.add("allowlisted API exposure requires non-wildcard source CIDRs");
        }
        if ("public-https".equals(apiExposure()) && !apiCidrs().isEmpty()) {
            errors.add("public-https API exposure derives ingress policy and requires empty sourceCidrs");
        }
        apiCidrs().stream().filter(cidr -> !validCidr(cidr))
                .forEach(cidr -> errors.add("invalid API source CIDR: " + cidr));
        List<Node> gateways = nodes().stream().filter(node -> node.roles().contains("api-gateway")).toList();
        if (!"disabled".equals(apiExposure())
                && (gateways.isEmpty() || gateways.stream().anyMatch(node -> node.hostname().isBlank()))) {
            errors.add("each API gateway node requires its own hostname");
        }
        if ("allowlist".equals(apiExposure()) && (apiCertificateFile().isBlank()
                || apiPrivateKeyFile().isBlank() || apiKeyFile().isBlank())) {
            errors.add("allowlisted API exposure requires TLS file references and an API-key file");
        }
        if ("disabled".equals(apiExposure())
                && nodes().stream().anyMatch(node -> !node.hostname().isBlank())) {
            errors.add("node hostnames require API exposure to be enabled");
        }
        Map<String, Long> providerTypeCounts = providers().stream().collect(java.util.stream.Collectors.groupingBy(
                Provider::type, java.util.stream.Collectors.counting()));
        providerTypeCounts.forEach((type, count) -> {
            if (!"existing".equals(type) && count > 1) {
                errors.add("version one supports one credential context per cloud provider type: " + type);
            }
        });
        boolean hasCloud = providers().stream().anyMatch(provider -> !"existing".equals(provider.type()));
        if (hasCloud && (openTofuVersion().isBlank() || stateBackendType().isBlank()
                || "local".equals(stateBackendType()) || stateBackendConfigFile().isBlank())) {
            errors.add("cloud deployments require an exact OpenTofu version and non-local backend config reference");
        }
        if (requireArtifact) {
            if (!SHA256.matcher(artifactSha256()).matches()) {
                errors.add("runtime artifact sha256 must contain 64 lower-case hexadecimal characters");
            }
            if (artifactFile().isBlank()) {
                errors.add("runtime artifact file is required; run artifact import");
            }
        }
        return List.copyOf(errors);
    }

    private void validateApplication(List<String> errors) {
        if (!"distributed-showcase-preprod-anchored-settlement-v1".equals(applicationProfile())) {
            errors.add("application profile must be distributed-showcase-preprod-anchored-settlement-v1");
        }
        if (!"preprod".equals(network())) {
            errors.add("the anchored-settlement profile initially supports only preprod");
        }
        List<String> historyObservers = cardanoHistoryL1Observations();
        if (historyObservers.stream().anyMatch(observer -> !CARDANO_HISTORY_OBSERVERS.contains(observer))) {
            errors.add("Cardano History l1Observations contains an unsupported observer");
        }
        if (new HashSet<>(historyObservers).size() != historyObservers.size()) {
            errors.add("Cardano History l1Observations must be unique");
        }
        if (!historyObservers.contains(EPOCH_PARAMS_OBSERVER)) {
            errors.add("Cardano History l1Observations must include l1-epoch-params-v1");
        }
        if (!cardanoHistoryGenesisId().isBlank() && !SHA256.matcher(cardanoHistoryGenesisId()).matches()) {
            errors.add("Cardano History genesisId must contain 64 lower-case hexadecimal characters");
        }
        int sourceRetention = cardanoHistorySourceSnapshotRetentionEpochs();
        if (sourceRetention < 2 || sourceRetention > 1_000) {
            errors.add("Cardano History sourceSnapshotRetentionEpochs must be in 2..1000");
        }
        if (!"script".equals(anchoringMode()) || !"all".equals(anchoredChains())) {
            errors.add("the anchored-settlement profile requires SCRIPT anchoring for all chains");
        }
        if (!validatorNames().contains(anchorLeaderNode())) {
            errors.add("anchor leaderNode must name a validator");
        }
        if (anchorSeedFile().isBlank() || anchorEveryBlocks() < 1 || anchorMaxIntervalMinutes() < 1) {
            errors.add("SCRIPT anchoring requires a seed-file reference and positive cadence values");
        }
        anchorChainSeedFiles().forEach((chainId, reference) -> {
            if (!NAME.matcher(chainId).matches() || reference.isBlank()) {
                errors.add("anchoring chainSeedFiles requires valid chain IDs and non-empty file references");
            }
        });
        if (!"preprod".equals(settlementMode())
                || !"payment-chain-settlement".equals(settlementChainId())) {
            errors.add("the profile requires the preprod payment-chain-settlement chain");
        }
        if (!validatorNames().contains(settlementOwnerNode())) {
            errors.add("settlement ownerNode must name a validator");
        }
        if (settlementOperatorSeedFile().isBlank() || settlementDeploymentRecordFile().isBlank()) {
            errors.add("preprod settlement requires operator-seed and deployment-record references");
        }
        List<String> nonAnchorSecrets = new ArrayList<>();
        validators().forEach(node -> nonAnchorSecrets.add(node.memberPrivateKeyFile()));
        nonAnchorSecrets.add(settlementOperatorSeedFile());
        long distinct = nonAnchorSecrets.stream().map(this::normalizedReference).distinct().count();
        if (distinct != nonAnchorSecrets.size()) {
            errors.add("member and settlement roles require distinct secret files");
        }
        Set<String> reserved = nonAnchorSecrets.stream().map(this::normalizedReference)
                .collect(java.util.stream.Collectors.toSet());
        List<String> anchorSecrets = new ArrayList<>();
        anchorSecrets.add(anchorSeedFile());
        anchorSecrets.addAll(anchorChainSeedFiles().values());
        if (anchorSecrets.stream().map(this::normalizedReference).anyMatch(reserved::contains)) {
            errors.add("anchor seed files must be distinct from member and settlement secret files");
        }
    }

    private void validateMonitoring(List<String> errors) {
        if (!Set.of("none", "central-prometheus").contains(monitoringMode())) {
            errors.add("monitoring mode must be none or central-prometheus");
            return;
        }
        List<Node> monitoringNodes = nodes().stream()
                .filter(node -> node.roles().contains("monitoring")).toList();
        if (!monitoringEnabled()) {
            if (!monitoringNodes.isEmpty()) {
                errors.add("the monitoring role requires monitoring.mode: central-prometheus");
            }
            return;
        }
        if (monitoringNodes.size() != 1 || !monitoringNodes.getFirst().name().equals(monitoringNode())) {
            errors.add("central-prometheus requires exactly one matching monitoring-role node");
        }
        if (validators().stream().noneMatch(node -> node.name().equals(monitoringNode()))) {
            errors.add("version one requires the monitoring node to also be a validator");
        }
        if (!HOSTNAME.matcher(monitoringHostname()).matches()) {
            errors.add("central-prometheus requires a valid monitoring hostname");
        }
        if (nodes().stream().map(Node::hostname).anyMatch(monitoringHostname()::equals)) {
            errors.add("monitoring hostname must be distinct from every node API hostname");
        }
        if (!Set.of("direct", "cloudflare").contains(monitoringProxy())) {
            errors.add("monitoring proxy must be direct or cloudflare");
        }
        if (!"public-https".equals(monitoringExposure())) {
            errors.add("central-prometheus initially supports only public-https exposure");
        }
        var retention = RETENTION.matcher(monitoringRetention());
        if (!retention.matches()) {
            errors.add("monitoring retention must use whole hours or days");
        } else {
            long hours = Long.parseLong(retention.group(1)) * ("d".equals(retention.group(2)) ? 24 : 1);
            if (hours > 2_160) {
                errors.add("monitoring retention must not exceed 90d");
            }
        }
        var retentionSize = RETENTION_SIZE.matcher(monitoringRetentionSize());
        if (!retentionSize.matches()) {
            errors.add("monitoring retentionSize must use MB or GB");
        } else {
            long megabytes = Long.parseLong(retentionSize.group(1))
                    * ("GB".equals(retentionSize.group(2)) ? 1_024 : 1);
            if (megabytes < 256 || megabytes > 20_480) {
                errors.add("monitoring retentionSize must be between 256MB and 20GB");
            }
        }
        int scrapePort = monitoringScrapePort();
        if (scrapePort < 1_024 || scrapePort > 65_535
                || scrapePort == p2pPort() || scrapePort == httpPort()) {
            errors.add("monitoring scrapePort must be distinct and in 1024..65535");
        }
    }

    private Set<String> validatorNames() {
        return validators().stream().map(Node::name).collect(java.util.stream.Collectors.toSet());
    }

    private String normalizedReference(String reference) {
        return resolve(reference).toString();
    }

    private Path resolve(String reference) {
        Path configured = Path.of(reference);
        return (configured.isAbsolute() ? configured : directory.resolve(configured)).normalize();
    }

    private void validateProvider(Provider provider, List<String> errors) {
        if (!NAME.matcher(provider.name()).matches()) {
            errors.add("provider name is invalid: " + provider.name());
        }
        if (!PROVIDER_TYPES.contains(provider.type())) {
            errors.add("unsupported provider type: " + provider.type());
        }
        if (!"existing".equals(provider.type()) && provider.version().isBlank()) {
            errors.add("cloud provider " + provider.name() + " requires an exact providerVersion");
        }
    }

    private void validateNodes(Map<String, Provider> providers, List<String> errors) {
        List<Node> nodes = nodes();
        Set<String> names = new HashSet<>();
        Set<Integer> indexes = new HashSet<>();
        for (Node node : nodes) {
            if (!NAME.matcher(node.name()).matches() || !names.add(node.name())) {
                errors.add("node names must be unique lower-case DNS labels: " + node.name());
            }
            if (node.index() < 0 || !indexes.add(node.index())) {
                errors.add("node indexes must be unique non-negative integers: " + node.name());
            }
            Provider provider = providers.get(node.providerRef());
            if (provider == null) {
                errors.add("node " + node.name() + " references an unknown provider");
                continue;
            }
            if (node.sshPort() < 1 || node.sshPort() > 65535 || node.sshUser().isBlank()) {
                errors.add("node " + node.name() + " requires a valid sshUser and sshPort");
            }
            if (node.roles().isEmpty() || node.roles().stream().anyMatch(role -> !NODE_ROLES.contains(role))) {
                errors.add("node " + node.name() + " contains an unsupported or empty role set");
            }
            if (!node.roles().contains("validator")) {
                errors.add("version one requires every managed node to have the validator role");
            }
            if ("existing".equals(provider.type()) && node.address().isBlank()) {
                errors.add("existing node " + node.name() + " requires a stable address");
            }
            if (!node.address().isBlank() && !validIpAddress(node.address())) {
                errors.add("node " + node.name() + " address must be a numeric IPv4 or IPv6 address");
            }
            if (!node.advertisedAddress().isBlank()
                    && !validIpAddress(node.advertisedAddress())
                    && !HOSTNAME.matcher(node.advertisedAddress()).matches()) {
                errors.add("node " + node.name() + " advertisedAddress must be an IP address or hostname");
            }
            if (!"existing".equals(provider.type())
                    && (node.region().isBlank() || node.instanceType().isBlank() || node.image().isBlank())) {
                errors.add("cloud node " + node.name() + " requires region, instanceType, and image");
            }
            if (node.roles().contains("validator")
                    && (!PUBLIC_KEY.matcher(node.memberPublicKey()).matches()
                    || node.memberPrivateKeyFile().isBlank())) {
                errors.add("validator " + node.name() + " requires a public key and private-key file reference");
            }
        }
        if (nodes.isEmpty() || !indexes.equals(java.util.stream.IntStream.range(0, nodes.size())
                .boxed().collect(java.util.stream.Collectors.toSet()))) {
            errors.add("node indexes must be contiguous from zero");
        }
        int validators = validators().size();
        if (!Set.of(3, 5, 7).contains(validators) || threshold() < validators / 2 + 1
                || threshold() > validators) {
            errors.add("version one requires 3, 5, or 7 validators and a safe threshold");
        }
        if (!Set.of("fixed", "rotating").contains(sequencerMode())) {
            errors.add("consensus sequencer mode must be fixed or rotating");
        } else if ("fixed".equals(sequencerMode())) {
            if (!validatorNames().contains(sequencerProposerNode())) {
                errors.add("fixed consensus sequencer proposerNode must name a validator");
            }
            if (!root.at("/spec/consensus/sequencer/windowSlots").isMissingNode()) {
                errors.add("fixed consensus sequencer must not configure windowSlots");
            }
        } else {
            if (!sequencerProposerNode().isBlank()) {
                errors.add("rotating consensus sequencer must not configure proposerNode");
            }
            if (sequencerWindowSlots() < 1 || sequencerWindowSlots() > 1_000_000) {
                errors.add("rotating consensus sequencer windowSlots must be in 1..1000000");
            }
        }
    }

    private String text(String pointer) {
        return root.at(pointer).asText("").trim();
    }

    private int integer(String pointer, int fallback) {
        JsonNode value = root.at(pointer);
        return value.isIntegralNumber() ? value.asInt() : fallback;
    }

    private List<JsonNode> array(String pointer) {
        JsonNode value = root.at(pointer);
        if (!value.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        value.forEach(result::add);
        return result;
    }

    private List<String> strings(String pointer) {
        return array(pointer).stream().map(JsonNode::asText).toList();
    }

    private static String required(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static String optional(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static boolean wildcardCidr(String cidr) {
        return Set.of("0.0.0.0/0", "::/0").contains(cidr);
    }

    private static boolean validCidr(String value) {
        if (value == null || value.isBlank() || value.indexOf('/') <= 0
                || value.indexOf('/') != value.lastIndexOf('/')) {
            return false;
        }
        String[] parts = value.split("/", -1);
        try {
            int prefix = Integer.parseInt(parts[1]);
            if (parts[0].contains(":")) {
                if (!parts[0].matches("[0-9a-fA-F:.]+")) {
                    return false;
                }
                InetAddress parsed = InetAddress.getByName(parts[0]);
                return parsed instanceof Inet6Address && prefix >= 0 && prefix <= 128;
            }
            String[] octets = parts[0].split("\\.", -1);
            if (octets.length != 4 || prefix < 0 || prefix > 32) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty() || !octet.matches("[0-9]{1,3}")
                        || Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException | UnknownHostException ignored) {
            return false;
        }
    }

    private static boolean validIpAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            if (value.contains(":")) {
                return value.matches("[0-9a-fA-F:.]+") && InetAddress.getByName(value) instanceof Inet6Address;
            }
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty() || !octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException | UnknownHostException ignored) {
            return false;
        }
    }

    private static boolean validCorsOrigin(String origin) {
        try {
            URI uri = new URI(origin);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                return false;
            }
            if ("https".equals(uri.getScheme())) {
                return true;
            }
            return "http".equals(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    record Provider(String name, String type, JsonNode source) {
        String version() {
            return source.path("providerVersion").asText("");
        }

        JsonNode settings() {
            return source.path("settings");
        }
    }

    record Node(
            String name,
            int index,
            String providerRef,
            String region,
            String address,
            String advertisedAddress,
            String hostname,
            String sshUser,
            int sshPort,
            String instanceType,
            String image,
            String memberPublicKey,
            String memberPrivateKeyFile,
            List<String> roles,
            JsonNode source) {
        String effectiveAddress() {
            return advertisedAddress.isBlank() ? address : advertisedAddress;
        }
    }
}
