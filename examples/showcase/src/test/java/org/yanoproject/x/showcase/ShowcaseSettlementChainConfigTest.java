package org.yanoproject.x.showcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.eutxo.demo.ShowcaseSettlementPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009: the packaged settlement application must stay byte-identical to what {@link ShowcaseSettlementPlan}
 * derives. The plan is a pure function of public demo facts, so any drift (a changed profile, a re-derived vault,
 * a renamed wiring key) fails here rather than at node start. Regenerate with
 * {@code ./gradlew :ledgers:eutxo:demo:test --tests '*EmitShowcaseSettlementTest'}.
 */
class ShowcaseSettlementChainConfigTest {
    private static final Path CONFIG = Path.of("src/main/showcase/config");

    @Test
    void packagedTemplateAndExecutorSettingsMatchTheDerivedPlan() throws Exception {
        Map<String, String> properties = ShowcaseSettlementPlan.packagedProperties();
        assertThat(Files.readString(CONFIG.resolve("settlement.template.yaml")))
                .as("regenerate with EmitShowcaseSettlementTest")
                .isEqualTo(ShowcaseSettlementPlan.template(ShowcaseSettlementPlan.CHAIN_ID, properties,
                        ShowcaseSettlementPlan.PACKAGED_TEMPLATE_COMMENT));
        assertThat(Files.readString(CONFIG.resolve("settlement/executor.json")))
                .isEqualTo(new ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(ShowcaseSettlementPlan.executorSettings(properties)) + "\n");

        // Deposits credit L2 owners from the inline datum; a virtual genesis may never coexist with a bridge chain.
        String template = Files.readString(CONFIG.resolve("settlement.template.yaml"));
        assertThat(template).doesNotContain("genesis", "payment-chain-l1bridge", "yano-eutxo-v2-plutus-v3",
                "eutxo-withdrawal-confirmation-v1\"");
    }

    @Test
    void packagedValidatorsMatchThePlanSoNodesStartBeforeBootstrap() throws Exception {
        // A node must start its settlement chain BEFORE the L1 bootstrap has run, so the validators ship with the
        // distribution rather than being written at bootstrap time.
        Path dir = CONFIG.resolve("settlement");
        assertThat(Files.readString(dir.resolve("settlement-vault.script")).trim())
                .isEqualTo(ShowcaseSettlementPlan.PLAN.vaultScript().getCborHex());
        assertThat(Files.readString(dir.resolve("settlement-shard.script")).trim())
                .isEqualTo(ShowcaseSettlementPlan.PLAN.shardScript().getCborHex());
    }

    @Test
    void theDemoProfileIsDevnetOnlyAndCannotHoldRealFunds() {
        assertThat(ShowcaseSettlementPlan.PLAN.profile().devnetOnly()).isTrue();
        assertThat(ShowcaseSettlementPlan.PLAN.profile().fallbackDelayMinSlots())
                .isEqualTo(ShowcaseSettlementPlan.FALLBACK_DELAY_SLOTS);
    }
}
