package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Real host execution and persisted restart; no external network or effect delivery is used. */
@Timeout(60)
class DeclarativeBindingsRuntimeTest {
    @TempDir Path directory;

    @Test
    void registryApprovalAndAuditCascadeSurvivesRestartWithAuthenticatedReceipts() throws Exception {
        byte[] seed = new byte[32];
        seed[0] = 94;
        String member = HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
        var ir = document();
        var config = AppChainConfig.builder("binding-runtime")
                .signingKeyHex(HexFormat.of().formatHex(seed))
                .stateCommitmentIdentity(StdlibTestStateCommitments.mpf("binding-runtime"))
                .memberKeysHex(Set.of(member)).proposerKeyHex(member).threshold(1).blockIntervalMs(100)
                .stateMachineId(DeclarativeCompositeProvider.ID)
                .pluginSettings(Map.of(DeclarativeCompositeProvider.IR_SETTING, HexFormat.of().formatHex(ir.encode())))
                .build();
        byte[] approvalKey = CompositeStateKeys.componentKey("reviews", ApprovalsStateMachine.itemKey("order-1"));
        byte[] auditKey = CompositeStateKeys.componentKey("audit", DocTrailContract.entityKey("order-1"));
        byte[] root;
        long height;
        String voteId;
        try (AppChainSubsystem node = node(config)) {
            node.start();
            String orderId = node.submit("orders.v1", KvRegistryStateMachine.put(new byte[]{1}, new byte[]{42}));
            await(() -> node.stateValue(approvalKey).isPresent());
            assertThat(ApprovalsStateMachine.decodeItem(node.stateValue(approvalKey).orElseThrow()).status())
                    .isEqualTo(ApprovalsStateMachine.STATUS_PENDING);
            assertThat(receipt(node, orderId).accepted()).isTrue();

            voteId = node.submit("reviews.v1", ApprovalsStateMachine.approve("order-1"));
            await(() -> node.stateValue(auditKey).isPresent());
            assertThat(ApprovalsStateMachine.decodeItem(node.stateValue(approvalKey).orElseThrow()).status())
                    .isEqualTo(ApprovalsStateMachine.STATUS_APPROVED);
            assertThat(DocTrailStateMachine.decodeEntry(node.stateValue(auditKey).orElseThrow()).count()).isEqualTo(1);
            assertThat(receipt(node, voteId).accepted()).isTrue();
            byte[] receiptKey = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID,
                    HexFormat.of().parseHex(voteId));
            assertThat(node.stateProof(receiptKey)).isPresent();
            root = node.stateRoot();
            height = node.tipHeight();
        }
        try (AppChainSubsystem restarted = node(config)) {
            restarted.start();
            assertThat(restarted.tipHeight()).isEqualTo(height);
            assertThat(restarted.stateRoot()).containsExactly(root);
            assertThat(receipt(restarted, voteId).accepted()).isTrue();
            assertThat(DocTrailStateMachine.decodeEntry(restarted.stateValue(auditKey).orElseThrow()).count())
                    .isEqualTo(1);
        }
    }

    private AppChainSubsystem node(AppChainConfig config) {
        return new AppChainSubsystem(config, 42, null, null, directory.resolve("ledger").toString(), null,
                StdlibTestPluginProviders.registry(), LoggerFactory.getLogger(DeclarativeBindingsRuntimeTest.class));
    }

    private static BindingReceiptV1 receipt(AppChainSubsystem node, String id) {
        return BindingReceiptV1.decode(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload());
    }

    private static BindingIrV1 document() {
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("orders", "kv-registry", "orders.v1",
                        Map.of("value-format", new BindingSourceV1.Literal("raw")), 0),
                new BindingIrV1.Component("reviews", "approvals", "reviews.v1", Map.of(), 0),
                new BindingIrV1.Component("audit", "doc-trail", "audit.v1", Map.of(), 0)), List.of(
                new BindingIrV1.Binding("propose-order", "orders", "kv-registry.entry-put.v1", List.of(),
                        new BindingIrV1.CommandTarget("reviews", "propose", BindingIrV1.Mapping.fields(List.of(
                                literal("itemId", "order-1"), field("payload", "value"), literal("required", 1L),
                                literal("deadlineMillis", 0L))))),
                new BindingIrV1.Binding("audit-approval", "reviews", "approvals.item-approved.v1", List.of(),
                        new BindingIrV1.CommandTarget("audit", "append", BindingIrV1.Mapping.fields(List.of(
                                field("entityId", "itemId"), field("entryHash", "payloadHash"),
                                literal("reference", "approved")))))), BindingIrV1.Limits.DEFAULT);
    }
    private static BindingIrV1.Assignment field(String target, String source) {
        return new BindingIrV1.Assignment(target, new BindingSourceV1.Field(source));
    }
    private static BindingIrV1.Assignment literal(String target, Object value) {
        return new BindingIrV1.Assignment(target, new BindingSourceV1.Literal(value));
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("timed out waiting for finalized cascade");
            Thread.sleep(25);
        }
    }
}
