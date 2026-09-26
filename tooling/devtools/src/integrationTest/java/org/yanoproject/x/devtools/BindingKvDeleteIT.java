package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual-catalog regression for the frozen three-slot KV delete wire contract. */
class BindingKvDeleteIT {
    @TempDir Path temporary;
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] KEY = {42};
    private static final String DOCUMENT = """
            composite:
              components:
                - {id: requests, machine: kv-registry}
                - {id: records, machine: kv-registry}
              bindings:
                - id: delete-record
                  from: {component: requests, event: kv-registry.entry-put.v1}
                  to:
                    component: records
                    command: delete
                    map:
                      key: {field: key}
                      value: {literal: {bytesHex: '%s'}}
            """;

    @Test
    void emptyValueDeletesButNonemptyValueRejectsAndRollsBackWholeCascade() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        var profile = new AppChainConsensusProfile(2, 65536, 100, 1_048_576, 0, 0, false, false,
                0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT, true, List.of());
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF, new byte[32]);
        var context = new BindingCatalogSession.ContextInput("delete-rehearsal", identity.settings(), profile,
                new AppChainMembershipEpoch(0, List.of("22".repeat(32)), 1));
        try (var environment = BindingPluginEnvironment.open(plugins)) {
            var session = new BindingCatalogSession(environment.providers(), context);
            for (String valueHex : List.of("", "01")) {
                var ir = BindingDocumentCompiler.compile(DOCUMENT.formatted(valueHex), session);
                // Seed the target through its genuine ingress route, using the same
                // originating member that subsequently triggers the derived delete.
                var seeded = BindingDryRun.execute(session.validate(ir), context,
                        fixture(1, "records.command.v1", List.of()));
                String targetKey = HEX.formatHex(CompositeStateKeys.componentKey("records", KEY));
                var targetBefore = seeded.postState().stream().filter(entry -> entry.keyHex().equals(targetKey))
                        .findFirst().orElseThrow();
                var result = BindingDryRun.execute(session.validate(ir), context,
                        fixture(2, "requests.command.v1", seeded.postState()));
                var receipt = BindingReceiptV1.decode(HEX.parseHex(
                        (String) result.receipts().getFirst().get("receiptHex")));
                if (valueHex.isEmpty()) {
                    assertThat(receipt.accepted()).isTrue();
                    assertThat(receipt.steps()).hasSize(2);
                    assertThat(result.postState()).noneMatch(entry -> entry.keyHex().equals(targetKey));
                    assertThat(result.stateChanges()).contains(new BindingDryRun.Entry(targetKey, null));
                } else {
                    assertThat(receipt.accepted()).isFalse();
                    assertThat(receipt.code()).isEqualTo("MALFORMED_DERIVED_COMMAND");
                    assertThat(result.postState()).contains(targetBefore);
                    String sourceKey = HEX.formatHex(CompositeStateKeys.componentKey("requests", KEY));
                    assertThat(result.postState()).noneMatch(entry -> entry.keyHex().equals(sourceKey));
                }
            }
        }
    }

    private static BindingDryRun.Fixture fixture(long height, String topic, List<BindingDryRun.Entry> state) {
        return new BindingDryRun.Fixture(height, 100 + height, "00".repeat(32), 0, state,
                List.of(new BindingDryRun.Message((height == 1 ? "11" : "33").repeat(32), "22".repeat(32),
                        height, Long.MAX_VALUE, topic,
                        HEX.formatHex(KvRegistryContract.put(KEY, new byte[]{1})), null)));
    }
}
