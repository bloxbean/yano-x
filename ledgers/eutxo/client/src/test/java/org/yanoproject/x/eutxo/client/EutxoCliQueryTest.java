package org.yanoproject.x.eutxo.client;

import org.yanoproject.x.eutxo.contracts.EutxoOutpoint;
import org.yanoproject.x.eutxo.contracts.EutxoQueryCodec;
import org.yanoproject.x.eutxo.contracts.EutxoReceipt;
import org.yanoproject.x.eutxo.contracts.EutxoRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoCliQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EutxoOutpoint OUTPOINT = new EutxoOutpoint("11".repeat(32), 2);
    private static final EutxoRecord RECORD = new EutxoRecord(
            OUTPOINT, "addr_test1vr0cliquery", new byte[]{(byte) 0x82, 0x01, 0x02},
            EutxoRecord.Origin.L1_DEPOSIT);
    private static final EutxoReceipt RECEIPT = new EutxoReceipt(
            EutxoReceipt.Status.ACCEPTED, "44".repeat(32), HexFormat.of().parseHex("55".repeat(32)),
            2, 0, 0, "", "");

    private HttpServer server;
    private boolean present = true;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readCommandsPrintPlainJson() throws Exception {
        startServer();

        JsonNode utxo = run(0, "utxo", "get", OUTPOINT.toString());
        assertThat(utxo.at("/utxo/outpoint").asText()).isEqualTo(OUTPOINT.toString());
        assertThat(utxo.at("/utxo/outputCbor").asText()).isEqualTo("820102");
        assertThat(utxo.at("/utxo/origin").asText()).isEqualTo("L1_DEPOSIT");
        assertThat(utxo.at("/committedHeight").asLong()).isEqualTo(7);

        JsonNode utxos = run(0, "utxo", "list", RECORD.address());
        assertThat(utxos.at("/utxos/0/outpoint").asText()).isEqualTo(OUTPOINT.toString());

        JsonNode receipt = run(0, "transaction", "status", RECEIPT.transactionId());
        assertThat(receipt.at("/receipt/status").asText()).isEqualTo("ACCEPTED");
        assertThat(receipt.at("/receipt/appMessageId").asText()).isEqualTo("55".repeat(32));
        assertThat(receipt.at("/receipt/appHeight").asLong()).isEqualTo(2);
    }

    @Test
    void absentValuesPrintNull() throws Exception {
        present = false;
        startServer();

        assertThat(run(0, "utxo", "get", OUTPOINT.toString()).get("utxo").isNull()).isTrue();
        assertThat(run(0, "transaction", "status", RECEIPT.transactionId()).get("receipt").isNull())
                .isTrue();
    }

    private JsonNode run(int expectedExit, String... command) throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        String[] arguments = new String[command.length + 4];
        System.arraycopy(command, 0, arguments, 0, command.length);
        arguments[command.length] = "--url";
        arguments[command.length + 1] = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        arguments[command.length + 2] = "--chain";
        arguments[command.length + 3] = "eutxo";
        int exit = EutxoCli.run(arguments, new PrintWriter(out), new PrintWriter(err));
        assertThat(err.toString()).isEmpty();
        assertThat(exit).isEqualTo(expectedExit);
        return JSON.readTree(out.toString());
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                byte[] payload;
                if (path.endsWith("/query/" + EutxoQueryCodec.OUTPOINT_PATH)) {
                    payload = EutxoQueryCodec.optionalRecord(present ? RECORD : null);
                } else if (path.endsWith("/query/" + EutxoQueryCodec.ADDRESS_PATH)) {
                    payload = EutxoQueryCodec.records(List.of(RECORD));
                } else {
                    payload = EutxoQueryCodec.optionalReceipt(present ? RECEIPT : null);
                }
                respond(exchange, """
                        {"chainId":"eutxo","stateMachineId":"eutxo-ledger",
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
}
