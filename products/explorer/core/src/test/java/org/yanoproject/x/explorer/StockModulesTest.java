package org.yanoproject.x.explorer;

import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-050 §2.2: the module decoders against the documented command layouts. */
class StockModulesTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final String SENDER = "aa".repeat(32);

    private static IndexedMessage message(String topic, byte[] body) {
        return new IndexedMessage(7, 2, "11".repeat(32), topic, SENDER, 3, 0, HEX.formatHex(body), 0, "bb".repeat(64),
                IndexedMessage.State.FULL);
    }

    @Test
    void docTrailAppendBecomesAnEntityRow() {
        byte[] hash = ContentArchiver.sha256("quality certificate v1".getBytes(StandardCharsets.UTF_8));
        List<SubjectRow> rows = StockModules.decode(message(DocTrailContract.DEFAULT_TOPIC,
                DocTrailContract.append("supplier-42", hash, "https://docs.example/qc-v1.pdf")));
        assertThat(rows).hasSize(1);
        SubjectRow row = rows.getFirst();
        assertThat(row.module()).isEqualTo(StockModules.DOC_TRAIL);
        assertThat(row.kind()).isEqualTo("entity");
        assertThat(row.subject()).isEqualTo("supplier-42");
        assertThat(row.op()).isEqualTo("APPEND");
        assertThat(row.fields()).containsEntry("entryHashHex", HEX.formatHex(hash))
                .containsEntry("reference", "https://docs.example/qc-v1.pdf")
                .containsEntry("authorHex", SENDER);
    }

    @Test
    void registryPutAndDeleteKeepTheKeyAsSubject() {
        byte[] key = "supplier-42".getBytes(StandardCharsets.UTF_8);
        List<SubjectRow> put = StockModules.decode(message(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.put(key, "active".getBytes(StandardCharsets.UTF_8))));
        List<SubjectRow> delete = StockModules.decode(message(KvRegistryContract.DEFAULT_TOPIC,
                KvRegistryContract.delete(key)));
        assertThat(put.getFirst().subject()).isEqualTo(HEX.formatHex(key));
        assertThat(put.getFirst().op()).isEqualTo("PUT");
        assertThat(put.getFirst().fields()).containsEntry("keyText", "supplier-42").containsEntry("valueText", "active");
        assertThat(delete.getFirst().op()).isEqualTo("DELETE");
        assertThat(delete.getFirst().fields()).containsEntry("valueText", "");
    }

    @Test
    void balancesCommandsCarryAmountsAsDecimalStrings() {
        List<SubjectRow> mint = StockModules.decode(message(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.mint("alice", BigInteger.TEN)));
        List<SubjectRow> transfer = StockModules.decode(message(BalancesContract.DEFAULT_TOPIC,
                BalancesContract.transfer("bob", BigInteger.valueOf(3))));
        assertThat(mint.getFirst().op()).isEqualTo("MINT");
        assertThat(mint.getFirst().subject()).isEqualTo("alice");
        assertThat(mint.getFirst().fields()).containsEntry("amount", "10");
        assertThat(transfer.getFirst().op()).isEqualTo("TRANSFER");
        assertThat(transfer.getFirst().subject()).isEqualTo("bob");
    }

    @Test
    void approvalsProposeApproveRejectBecomeItemRows() {
        byte[] payload = "{\"artifact\":\"inventory-service:2.4.0\"}".getBytes(StandardCharsets.UTF_8);
        List<SubjectRow> propose = StockModules.decode(message(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.propose("release-1", payload, 2, 0)));
        List<SubjectRow> approve = StockModules.decode(message(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.approve("release-1")));
        List<SubjectRow> reject = StockModules.decode(message(ApprovalsContract.DEFAULT_TOPIC,
                ApprovalsContract.reject("release-1")));
        assertThat(propose.getFirst().op()).isEqualTo("PROPOSE");
        assertThat(propose.getFirst().subject()).isEqualTo("release-1");
        assertThat(propose.getFirst().fields()).containsEntry("required", 2)
                .containsKey("payloadHashHex").containsEntry("payloadText", new String(payload, StandardCharsets.UTF_8));
        assertThat(approve.getFirst().op()).isEqualTo("APPROVE");
        assertThat(reject.getFirst().op()).isEqualTo("REJECT");
    }

    @Test
    void openMapCommandYieldsOneRowPerMutation() {
        AuthenticatedMapContract.Command command = new AuthenticatedMapContract.Command(true, List.of(
                AuthenticatedMapContract.Mutation.put("products", "sku-1".getBytes(StandardCharsets.UTF_8),
                        "v1".getBytes(StandardCharsets.UTF_8)),
                AuthenticatedMapContract.Mutation.put("products", "sku-2".getBytes(StandardCharsets.UTF_8),
                        "v2".getBytes(StandardCharsets.UTF_8))));
        byte[] body = AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(
                        AuthenticatedMapAuthorizationContract.MapActionV1.open(command), List.of()));
        List<SubjectRow> rows = StockModules.decode(message(AuthenticatedMapContract.DEFAULT_TOPIC, body));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).subject()).isEqualTo("products/" + HEX.formatHex("sku-1".getBytes(StandardCharsets.UTF_8)));
        assertThat(rows.get(0).op()).isEqualTo("PUT");
        assertThat(rows.get(0).fields()).containsEntry("governed", true).containsEntry("authorizationKind", "OPEN")
                .containsEntry("batch", true).containsEntry("mutationIndex", 0).containsEntry("keyText", "sku-1");
        assertThat(rows.get(1).fields()).containsEntry("mutationIndex", 1);
    }

    @Test
    void unknownTopicsAndTombstonesYieldNoRows() {
        assertThat(StockModules.decode(message("custom.topic.v1", new byte[] {1, 2, 3}))).isEmpty();
        IndexedMessage tombstone = new IndexedMessage(7, 0, "11".repeat(32), DocTrailContract.DEFAULT_TOPIC, SENDER,
                1, 0, "", 0, "", IndexedMessage.State.TOMBSTONE);
        assertThat(StockModules.decode(tombstone)).isEmpty();
    }

    @Test
    void malformedCommandsAreRecordedNotDropped() {
        List<SubjectRow> rows = StockModules.decode(message(DocTrailContract.DEFAULT_TOPIC, new byte[] {0x01, 0x02}));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().op()).isEqualTo(StockModules.MALFORMED_OP);
        assertThat(rows.getFirst().subject()).startsWith("malformed:");
    }
}
