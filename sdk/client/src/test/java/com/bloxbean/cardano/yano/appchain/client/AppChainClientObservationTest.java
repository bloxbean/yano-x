package com.bloxbean.cardano.yano.appchain.client;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationHashes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainClientObservationTest {
    @Test
    void submitsCanonicalBytesAndChecksBoundedQueueReceipt() throws Exception {
        var report = ObservationReporterJournalTest.report(501000, 1);
        String digest = Hex.encode(ObservationHashes.digest(report.encode()));
        AtomicInteger mode = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/app-chain/chains/chain/observations/reports", exchange -> {
            try (exchange) {
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                        .isEqualTo("application/octet-stream");
                assertThat(exchange.getRequestBody().readAllBytes()).isEqualTo(report.encode());
                String receipt = mode.get() == 2 ? "x".repeat(4096)
                        : "{\"status\":\"QUEUED\",\"chainId\":\"chain\",\"reportDigest\":\""
                        + (mode.get() == 1 ? "wrong" : digest) + "\"}";
                if (mode.get() == 4) receipt = receipt.replace("{", "{\"status\":\"REJECTED\",");
                if (mode.get() == 5) receipt += " {}";
                byte[] body = receipt.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(mode.get() == 3 ? 429 : 202, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            AppChainClient client = AppChainClient.builder("http://localhost:"
                    + server.getAddress().getPort() + "/api/v1").chainId("chain").build();
            assertThat(client.submitObservationReport(report)).isEqualTo(digest);
            for (int failure = 1; failure <= 5; failure++) {
                mode.set(failure);
                assertThatThrownBy(() -> client.submitObservationReport(report))
                        .isInstanceOf(AppChainClient.AppChainClientException.class);
            }
        } finally {
            server.stop(0);
        }
    }
}
