package org.yanoproject.x.deployment;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.eutxo.demo.SettlementBootstrapPlan;
import org.yanoproject.x.eutxo.demo.ShowcaseSettlementPlan;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deploy tool's own showcase profile keeps a static settlement chain block. Its script identities and state
 * genesis id are derived from the compiled settlement validators, so a compiler or plan change must update them
 * together. Regenerate the showcase plan with EmitShowcaseSettlementTest and copy the values printed here.
 */
class ShowcaseProfileSettlementIdentityTest {
    @Test
    void staticSettlementBlockMatchesTheDerivedPlan() throws Exception {
        String profile;
        try (InputStream in = getClass().getResourceAsStream("/showcase-profile/application-appchain.yml")) {
            assertThat(in).isNotNull();
            profile = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        SettlementBootstrapPlan plan = ShowcaseSettlementPlan.PLAN;
        HexFormat hex = HexFormat.of();
        String genesisId = hex.formatHex(
                SettlementProfileCompiler.stateGenesisId(plan, ShowcaseSettlementPlan.CHAIN_ID));

        assertThat(profile)
                .contains("genesis-id: \"" + genesisId + "\"")
                .contains("vault-address: \"" + plan.vaultAddress() + "\"")
                .contains("vault-script-hash: \"" + hex.formatHex(plan.vaultScriptHash()) + "\"")
                .contains("shard-address: \"" + plan.shardAddress() + "\"")
                .contains("root-address: \"" + plan.rootAddress() + "\"")
                .contains("shard-thread-policy-id: \"" + plan.shardThreadPolicyIdHex() + "\"")
                .contains("root-unit: \"" + plan.rootThreadPolicyIdHex());
    }
}
