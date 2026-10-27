package org.yanoproject.x.eutxo.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regenerates the showcase's packaged settlement application: its template, the executor's node-local settings
 * and the parameterized validators. It writes only when YANO_EMIT_SHOWCASE_SETTLEMENT=true, so an ordinary test
 * run never rewrites them and the showcase module's ShowcaseSettlementChainConfigTest catches drift. Regenerate
 * with {@code YANO_EMIT_SHOWCASE_SETTLEMENT=true ./gradlew :ledgers:eutxo:demo:test --tests
 * '*EmitShowcaseSettlementTest'} whenever the plan, the profile or the wiring keys change.
 */
class EmitShowcaseSettlementTest {
    @Test
    void emitPackagedSettlementApplication() throws Exception {
        Map<String, String> properties = ShowcaseSettlementPlan.packagedProperties();
        String template = ShowcaseSettlementPlan.template(ShowcaseSettlementPlan.CHAIN_ID, properties,
                ShowcaseSettlementPlan.PACKAGED_TEMPLATE_COMMENT);
        Map<String, String> executor = ShowcaseSettlementPlan.executorSettings(properties);

        // The template stays small enough to read: the 22 KB of parameterized validators live in files.
        assertThat(template.length()).isLessThan(8_000);
        assertThat(template).contains("yano-eutxo-v3-bridge-settlement-devnet",
                "eutxo-batch-withdrawal-confirmation-v1").doesNotContain("effects.executor", "state.");
        assertThat(executor).containsKey("effects.executors.eutxo-settlement.vault-script-file")
                .doesNotContainKey("effects.executors.eutxo-settlement.owner");

        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("YANO_EMIT_SHOWCASE_SETTLEMENT")),
                "set YANO_EMIT_SHOWCASE_SETTLEMENT=true to rewrite the packaged settlement files");
        Path config = Path.of("../../../examples/showcase/src/main/showcase/config");
        Files.writeString(config.resolve("settlement.template.yaml"), template);
        Files.writeString(config.resolve("settlement/executor.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(executor) + "\n");
        // A node must be able to start its settlement chain before the L1 bootstrap has run, so the validators,
        // a pure function of the same plan, ship with the distribution.
        ShowcaseSettlementPlan.writeScripts(ShowcaseSettlementPlan.PLAN, config.resolve("settlement"));
        System.out.println("[emit] " + config.toAbsolutePath());
    }
}
