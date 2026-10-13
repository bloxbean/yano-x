package org.yanoproject.x.eutxo.client;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.yanoproject.x.eutxo.contracts.EutxoDepositClaim;
import org.yanoproject.x.eutxo.contracts.EutxoDepositNotice;
import org.yanoproject.x.eutxo.contracts.EutxoDepositRecord;
import org.yanoproject.x.eutxo.contracts.EutxoIgnoredConfirmations;
import org.yanoproject.x.eutxo.contracts.EutxoOutpoint;
import org.yanoproject.x.eutxo.contracts.EutxoQueryCodec;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalClaim;
import org.yanoproject.x.eutxo.contracts.EutxoWithdrawalRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoCliBridgeQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDRESS = "addr_test1wzn5ee2qaqvly3hx7e0nk3vhm240n5muq3plhjcnvx9ppjgf62u6a";
    private static final EutxoOutpoint ACCEPTED = new EutxoOutpoint("70".repeat(32), 1);

    private final EutxoDepositRecord deposit;
    private final EutxoWithdrawalRecord pending;
    private final EutxoWithdrawalRecord confirmed;
    private final List<String> queries = new ArrayList<>();
    private final List<byte[]> params = new ArrayList<>();
    private HttpServer server;
    private boolean present = true;

    EutxoCliBridgeQueryTest() throws Exception {
        byte[] output = CborSerializationUtil.serialize(TransactionOutput.builder()
                .address(ADDRESS).value(Value.fromCoin(BigInteger.valueOf(25))).build().serialize());
        EutxoDepositClaim claim = new EutxoDepositClaim(EutxoDepositClaim.ABI_VERSION, "payments", ACCEPTED,
                101, fill(1), ADDRESS, "11".repeat(28), output, ADDRESS, output, fill(2),
                new EutxoOutpoint("69".repeat(32), 0), 1_000);
        deposit = new EutxoDepositRecord(claim, claim.mirroredOutpoint(), 4);
        pending = EutxoWithdrawalRecord.pending(new EutxoWithdrawalClaim(EutxoWithdrawalClaim.ABI_VERSION,
                "payments", 1, deposit.mirroredOutpoint(), ADDRESS, BigInteger.valueOf(25), fill(3), 0, 5), 5);
        confirmed = pending.confirm("71".repeat(32), 103, fill(4), 6);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void depositGetShowsTheNodeMirroredRecord() throws Exception {
        startServer();

        JsonNode result = run("deposit", "get", ACCEPTED.toString());

        assertThat(queries).containsExactly(EutxoQueryCodec.DEPOSIT_PATH, EutxoQueryCodec.DEPOSIT_NOTICE_PATH);
        assertThat(result.at("/notice/outcome").asText()).isEqualTo("CREDITED_WITHOUT_KEY_BINDING");
        assertThat(result.at("/notice/reason").asText()).isEqualTo("KEY_BINDING_UNSIGNED");
        assertThat(result.at("/noticeCommittedHeight").asLong()).isEqualTo(7);
        assertThat(EutxoQueryCodec.decodeDepositRequest(params.getFirst())).isEqualTo(ACCEPTED);
        assertThat(result.at("/acceptedOutpoint").asText()).isEqualTo(ACCEPTED.toString());
        assertThat(result.at("/deposit/mirroredOutpoint").asText())
                .isEqualTo(deposit.mirroredOutpoint().toString());
        assertThat(result.at("/deposit/creditedHeight").asLong()).isEqualTo(4);
        assertThat(result.at("/deposit/l1Slot").asLong()).isEqualTo(101);
        assertThat(result.at("/committedHeight").asLong()).isEqualTo(7);
    }

    @Test
    void withdrawalGetAndListShowConfirmationStatus() throws Exception {
        startServer();

        JsonNode one = run("withdrawal", "get", confirmed.claim().claimId().toUpperCase());
        assertThat(one.at("/claimId").asText()).isEqualTo(confirmed.claim().claimId());
        assertThat(one.at("/withdrawal/status").asText()).isEqualTo("CONFIRMED");
        assertThat(one.at("/withdrawal/settlementTransactionId").asText()).isEqualTo("71".repeat(32));
        assertThat(one.at("/withdrawal/confirmedSlot").asLong()).isEqualTo(103);
        assertThat(one.at("/withdrawal/withdrawalOutpoint").asText())
                .isEqualTo(deposit.mirroredOutpoint().toString());

        JsonNode list = run("withdrawal", "list");
        assertThat(list.at("/withdrawals/0/status").asText()).isEqualTo("PENDING");
        assertThat(list.at("/withdrawals/0/settlementTransactionId").isNull()).isTrue();
        assertThat(list.at("/withdrawals/0/confirmedSlot").isNull()).isTrue();
        assertThat(list.at("/withdrawals/0/confirmedBlockHash").isNull()).isTrue();
        assertThat(one.at("/withdrawal/confirmedBlockHash").asText()).isEqualTo("04".repeat(32));
        assertThat(queries).containsExactly(EutxoQueryCodec.WITHDRAWAL_PATH, EutxoQueryCodec.WITHDRAWALS_PATH);
        // The claim id is sent lowercase, and the list asks for the newest 50 records.
        assertThat(EutxoQueryCodec.decodeWithdrawalRequest(params.get(0))).isEqualTo(confirmed.claim().claimId());
        assertThat(EutxoQueryCodec.decodeLifecyclePageRequest(params.get(1)))
                .isEqualTo(new EutxoQueryCodec.LifecyclePage(0, 50));
    }

    @Test
    void withdrawalIgnoredShowsTheCountAndTheLastIgnoredConfirmation() throws Exception {
        startServer();

        JsonNode result = run("withdrawal", "ignored");

        assertThat(queries).containsExactly(EutxoQueryCodec.IGNORED_CONFIRMATIONS_PATH);
        assertThat(params.getFirst()).isEmpty();
        assertThat(result.at("/ignoredConfirmations/count").asLong()).isEqualTo(3);
        assertThat(result.at("/ignoredConfirmations/lastSettlementTransactionId").asText())
                .isEqualTo("9a".repeat(32));
        assertThat(result.at("/ignoredConfirmations/lastReason").asText()).isEqualTo("CUSTODY_UNPROVEN");
    }

    @Test
    void absentRecordsPrintNull() throws Exception {
        present = false;
        startServer();

        JsonNode deposit = run("deposit", "get", ACCEPTED.toString());
        assertThat(deposit.get("deposit").isNull()).isTrue();
        assertThat(deposit.get("notice").isNull()).isTrue();
        assertThat(run("withdrawal", "ignored").get("ignoredConfirmations").isNull()).isTrue();
        assertThat(run("withdrawal", "get", "ab".repeat(32)).get("withdrawal").isNull()).isTrue();
    }

    @Test
    void malformedClaimIdIsAUsageError() {
        StringWriter err = new StringWriter();
        int exit = EutxoCli.run(new String[]{"withdrawal", "get", "abcd"},
                new PrintWriter(new StringWriter()), new PrintWriter(err));

        assertThat(exit).isEqualTo(EutxoCli.EXIT_USAGE);
        assertThat(err.toString()).contains("claim id must be exactly 32 bytes");
    }

    private JsonNode run(String... command) throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        List<String> arguments = new ArrayList<>(Arrays.asList(command));
        arguments.addAll(List.of("--url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "--chain", "payments"));
        int exit = EutxoCli.run(arguments.toArray(String[]::new), new PrintWriter(out), new PrintWriter(err));
        assertThat(err.toString()).isEmpty();
        assertThat(exit).isZero();
        return JSON.readTree(out.toString());
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                String query = path.substring(path.indexOf("/query/") + "/query/".length());
                queries.add(query);
                params.add(HexFormat.of().parseHex(JSON.readTree(exchange.getRequestBody().readAllBytes())
                        .get("paramsHex").asText()));
                byte[] payload = switch (query) {
                    case EutxoQueryCodec.DEPOSIT_PATH -> EutxoQueryCodec.optionalDepositRecord(
                            present ? deposit : null);
                    case EutxoQueryCodec.WITHDRAWAL_PATH -> EutxoQueryCodec.optionalWithdrawalRecord(
                            present ? confirmed : null);
                    case EutxoQueryCodec.WITHDRAWALS_PATH -> EutxoQueryCodec.withdrawalRecords(List.of(pending));
                    case EutxoQueryCodec.DEPOSIT_NOTICE_PATH -> EutxoQueryCodec.optionalDepositNotice(
                            present ? new EutxoDepositNotice(ACCEPTED,
                                    EutxoDepositNotice.Outcome.CREDITED_WITHOUT_KEY_BINDING,
                                    "KEY_BINDING_UNSIGNED", 4) : null);
                    case EutxoQueryCodec.IGNORED_CONFIRMATIONS_PATH -> EutxoQueryCodec.optionalIgnoredConfirmations(
                            present ? new EutxoIgnoredConfirmations(3, "9a".repeat(32), "CUSTODY_UNPROVEN", 6)
                                    : null);
                    default -> throw new IllegalStateException("unexpected query " + query);
                };
                respond(exchange, """
                        {"chainId":"payments","stateMachineId":"eutxo-ledger",
                         "committedHeight":7,"stateRoot":"%s","payloadHex":"%s"}
                        """.formatted("33".repeat(32), HexFormat.of().formatHex(payload)));
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static byte[] fill(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
