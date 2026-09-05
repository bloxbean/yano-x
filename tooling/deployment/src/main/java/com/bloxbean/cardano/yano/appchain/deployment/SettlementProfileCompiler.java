package com.bloxbean.cardano.yano.appchain.deployment;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementBootstrapPlan;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementDeploymentRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementOperatorIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.ShowcaseSettlementPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                        EutxoProfile.V3.fallbackDelayMinSlots()));
        if (!record.vaultAddress().equals(plan.vaultAddress())
                || !record.shardAddress().equals(plan.shardAddress())
                || !record.rootAddress().equals(plan.rootAddress())) {
            throw new IllegalArgumentException(
                    "settlement deployment record does not match the configured federation");
        }
        ShowcaseSettlementPlan.writeScripts(plan, scriptOutput);
        String yaml = ShowcaseSettlementPlan.yamlBlock(10, document.settlementChainId(),
                ShowcaseSettlementPlan.configProperties(
                        plan, document.settlementChainId(),
                        com.bloxbean.cardano.yano.appchain.eutxo.demo.SettlementDeployment
                                .withdrawalAddress(operator),
                        operator.operatorAddress(), null, REMOTE_OPERATOR_SEED,
                        document.network(), REMOTE_SCRIPT_DIRECTORY));
        return new Compiled(yaml, record.rootAddress(), record.vaultAddress());
    }

    record Compiled(String yamlBlock, String rootAddress, String vaultAddress) {
    }
}
