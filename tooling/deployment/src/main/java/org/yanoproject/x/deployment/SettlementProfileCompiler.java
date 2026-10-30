package org.yanoproject.x.deployment;

import com.bloxbean.cardano.client.common.model.Networks;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.eutxo.contracts.EutxoProfile;
import org.yanoproject.x.eutxo.demo.SettlementBootstrapPlan;
import org.yanoproject.x.eutxo.demo.SettlementDeploymentRecord;
import org.yanoproject.x.eutxo.demo.SettlementOperatorIdentity;
import org.yanoproject.x.eutxo.demo.ShowcaseSettlementPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SettlementProfileCompiler {
    private static final String REMOTE_OPERATOR_SEED =
            "/etc/yano/credentials/settlement-operator.seed";
    private static final String REMOTE_SCRIPT_DIRECTORY = "/etc/yano/settlement";

    Compiled compile(DeploymentDocument document, Path scriptOutput) throws IOException {
        Path recordPath = document.settlementDeploymentRecordPath();
        Path expected = SettlementDeploymentRecord.path(
                recordPath.getParent(), document.settlementChainId()).normalize();
        if (!recordPath.equals(expected)) {
            throw new IllegalArgumentException("settlement deployment record must use the canonical filename: "
                    + expected.getFileName());
        }
        SettlementDeploymentRecord record = SettlementDeploymentRecord
                .load(recordPath.getParent(), document.settlementChainId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "settlement deployment record is missing: " + recordPath));
        record.requireMatches(document.settlementChainId(), document.network());
        if (!EutxoProfile.V3.id().equals(record.profileId())) {
            throw new IllegalArgumentException("settlement deployment record must use the production v3 profile");
        }
        SettlementOperatorIdentity operator = SettlementOperatorIdentity.fromKeyFile(
                document.settlementOperatorSeedPath());
        if (!record.operatorAddress().equals(operator.operatorAddress())) {
            throw new IllegalArgumentException(
                    "settlement deployment record does not belong to the configured operator seed");
        }
        List<String> members = document.validators().stream()
                .map(DeploymentDocument.Node::memberPublicKey).toList();
        SettlementBootstrapPlan plan = SettlementBootstrapPlan.plan(
                record.rootSeed(), record.shardSeed(),
                new SettlementBootstrapPlan.Config(
                        document.settlementChainId(), 0, Networks.testnet(),
                        ShowcaseSettlementPlan.ROOT_TOKEN.getBytes(StandardCharsets.UTF_8),
                        members, document.threshold(), 0,
                        EutxoProfile.V3.fallbackDelayMinSlots(),
                        SettlementBootstrapPlan.PUBLIC_SLOT_LENGTH_MILLIS));
        if (!record.vaultAddress().equals(plan.vaultAddress())
                || !record.shardAddress().equals(plan.shardAddress())
                || !record.rootAddress().equals(plan.rootAddress())) {
            throw new IllegalArgumentException(
                    "settlement deployment record does not match the configured federation");
        }
        ShowcaseSettlementPlan.writeScripts(plan, scriptOutput);
        Map<String, String> properties = new LinkedHashMap<>(new TreeMap<>(StateCommitmentIdentity.explicit(
                StateCommitmentProfiles.MPF, stateGenesisId(plan, document.settlementChainId())).settings()));
        properties.putAll(ShowcaseSettlementPlan.configProperties(
                plan, document.settlementChainId(),
                org.yanoproject.x.eutxo.demo.SettlementDeployment.withdrawalAddress(operator),
                operator.operatorAddress(), null, REMOTE_OPERATOR_SEED,
                document.network(), REMOTE_SCRIPT_DIRECTORY));
        String yaml = yamlBlock(10, document.settlementChainId(), properties);
        return new Compiled(yaml, record.rootAddress(), record.vaultAddress());
    }

    static byte[] stateGenesisId(SettlementBootstrapPlan plan, String chainId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("yano-showcase-settlement-state-genesis-v1\0"
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update(chainId.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.profile().digestHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.rootThreadPolicyIdHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.shardThreadPolicyIdHex().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(plan.vaultScriptHash());
            return digest.digest();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Render the properties as a chains[index] YAML block, NESTED on the
     * dots so it reads like every other chain in the packaged config (and
     * so it cannot depend on how the config layer treats dotted keys).
     */
    static String yamlBlock(int chainIndex, String chainId,
                                   Map<String, String> properties) {
        Map<String, Object> tree = new java.util.LinkedHashMap<>();
        tree.put("chain-id", chainId);
        tree.put("state-machine", "eutxo-ledger");
        tree.put("membership", new java.util.LinkedHashMap<>(
                Map.of("mode", "governed")));
        tree.put("block", new java.util.LinkedHashMap<>(
                Map.of("interval-ms", 1000)));
        tree.put("l1", new java.util.LinkedHashMap<>(
                Map.of("stability-depth", 2)));
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            insert(tree, entry.getKey().split("\\."), 0, entry.getValue());
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("    chains[").append(chainIndex).append("]:\n");
        render(yaml, tree, "      ");
        return yaml.toString();
    }

    @SuppressWarnings("unchecked")
    private static void insert(Map<String, Object> node, String[] path,
                               int index, String value) {
        String key = path[index];
        if (index == path.length - 1) {
            node.put(key, value);
            return;
        }
        Object child = node.computeIfAbsent(key,
                ignored -> new java.util.LinkedHashMap<String, Object>());
        if (!(child instanceof Map)) {
            throw new IllegalStateException(
                    "settlement config key collides with a scalar: " + key);
        }
        insert((Map<String, Object>) child, path, index + 1, value);
    }

    @SuppressWarnings("unchecked")
    private static void render(StringBuilder yaml, Map<String, Object> node,
                               String indent) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            // Keys with a '[' (none today) or a leading '~' would need
            // quoting; plain config keys do not.
            if (entry.getValue() instanceof Map<?, ?> child) {
                yaml.append(indent).append(key).append(":\n");
                render(yaml, (Map<String, Object>) child, indent + "  ");
            } else {
                yaml.append(indent).append(key).append(": \"")
                        .append(entry.getValue()).append("\"\n");
            }
        }
    }

    record Compiled(String yamlBlock, String rootAddress, String vaultAddress) {
    }
}
