package org.yanoproject.x.distribution;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-064 I6: real-HTTP auth outcomes of the integrated application with the conformance fixture
 * bundle loaded from a plugin directory and a one-member app chain running it. Each subclass is one
 * key configuration; every request is sent with each key state (absent, wrong, full, topic-scoped,
 * snapshot-admin). The app-chain rows equal the M0 baseline matrix
 * (evidence/x-integrated/auth/auth-matrix.txt); the domain rows follow the moved filter's order:
 * canonical path, body bound, dynamic domain access, INTERNAL as 404, privileged 404 or 503,
 * then the key check.
 */
abstract class DomainRouteAuthMatrixTest {

    static final String BUNDLE = "org.yanoproject.fixture.plugin-conformance";
    static final String DOMAIN = "/api/v1/plugins/" + BUNDLE + "/";
    static final List<String> KEYS = List.of("-", "bad", "full", "scoped", "snap");
    static final String MESSAGE = "{\"topic\":\"t1\",\"payload\":\"aGVsbG8=\"}";

    /** Expected status per key state, in {@link #KEYS} order. */
    abstract Map<String, List<Integer>> expectedByKey();

    /** Expected status of the malformed and oversized requests (sent with the full key). */
    abstract Map<String, Integer> expectedMalformed();

    @AfterEach
    void reset() {
        RestAssured.reset();
    }

    @Test
    void routeOutcomesMatchForEveryKeyState() {
        Map<String, List<Integer>> actual = new LinkedHashMap<>();
        for (String route : expectedByKey().keySet()) {
            List<Integer> codes = new ArrayList<>();
            for (String key : KEYS) {
                codes.add(send(route, key));
            }
            actual.put(route, codes);
        }
        assertThat(actual).isEqualTo(expectedByKey());
    }

    @Test
    void malformedAndOversizedRequestsAreRejected() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (String route : expectedMalformed().keySet()) {
            actual.put(route, send(route, "full"));
        }
        assertThat(actual).isEqualTo(expectedMalformed());
    }

    private static int send(String route, String key) {
        String[] parts = route.split(" ", 2);
        RequestSpecification request = given().urlEncodingEnabled(false);
        if (!key.equals("-")) {
            request.header("X-API-Key", key);
        }
        String path = parts[1];
        if (path.endsWith("#oversized")) {
            path = path.substring(0, path.length() - "#oversized".length());
            request.contentType("application/octet-stream").body(new byte[64 * 1024 + 1]);
        } else if (path.endsWith("#body")) {
            path = path.substring(0, path.length() - "#body".length());
            request.contentType("application/octet-stream").body(new byte[] {0, (byte) 0xff});
        } else if (path.endsWith("#json")) {
            path = path.substring(0, path.length() - "#json".length());
            request.contentType("application/json").body("{\"paramsHex\":\"00ff\"}");
        } else if (path.endsWith("/messages")) {
            request.contentType("application/json").body(MESSAGE);
        } else if (parts[0].equals("POST")) {
            // A body-less operation: RestAssured would otherwise send a form content type.
            request.contentType("application/json");
        }
        return request.request(parts[0], path).statusCode();
    }

    static List<Integer> all(int code) {
        return List.of(code, code, code, code, code);
    }

    static List<Integer> codes(Integer... codes) {
        return List.of(codes);
    }

    /** Requests every malformed form of a registered READ route; outcomes are key-independent. */
    static Map<String, Integer> malformed() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("GET " + DOMAIN + "status;x=1", 404);
        expected.put("GET " + DOMAIN + "status/", 404);
        expected.put("GET " + DOMAIN + "sta%2Ftus", 404);
        expected.put("GET " + DOMAIN + "x/../status", 404);
        expected.put("GET " + DOMAIN + "status#body", 400);
        expected.put("POST " + DOMAIN + "query/echo#oversized", 413);
        expected.put("POST /api/v1/app-chain/chains/conformance-chain/query/echo;x=1#json", 400);
        expected.put("POST /api/v1/app-chain/chains/conformance-chain/query/echo/#json", 400);
        return expected;
    }

    /** The fixture bundle in a plugin directory and a one-member chain that selects every fixture kind. */
    static class ConformanceChainProfile extends NoAutoStartTestProfile implements QuarkusTestProfile {
        /** One state directory per profile, under the module build directory. */
        private static final Map<String, Path> STATE = new ConcurrentHashMap<>();

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            Path state = STATE.computeIfAbsent(getClass().getName(), name -> createStateDirectory());
            // The node starts (no upstream client), so the app chain publishes its plugin products.
            overrides.put("yano.auto-sync-start", "true");
            overrides.put("yano.plugins.enabled", "true");
            overrides.put("yano.plugins.directory", System.getProperty("yano.x.test.conformance-plugins"));
            overrides.put("yano.plugins.allow-list", BUNDLE);
            overrides.put("yano.app-chain.storage.path", state.resolve("appchain").toString());
            overrides.put("yano.app-chain.enabled", "true");
            overrides.put("yano.app-chain.chain-id", "conformance-chain");
            overrides.put("yano.app-chain.signing-key", "conformance-signer:key");
            overrides.put("yano.app-chain.members", "00".repeat(32));
            overrides.put("yano.app-chain.threshold", "1");
            overrides.put("yano.app-chain.state-machine", "conformance-machine");
            overrides.put("yano.app-chain.state.commitment-profile", "mpf-blake2b256-v1");
            overrides.put("yano.app-chain.state.format-fingerprint",
                    "91ee14091200f1e24659112d640e877e9177779dcc81dd06117f013e9190082b");
            overrides.put("yano.app-chain.state.genesis-id", "11".repeat(32));
            overrides.put("yano.app-chain.sequencer.mode", "conformance-mode");
            overrides.putAll(keyConfiguration());
            return overrides;
        }

        Map<String, String> keyConfiguration() {
            return Map.of();
        }

        private static Path createStateDirectory() {
            try {
                Path root = Files.createDirectories(Path.of(System.getProperty("yano.x.test.state-root")));
                return Files.createTempDirectory(root, "domain-auth-matrix-");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** A full key, a submit-only key scoped to topic t1 and a snapshot-admin key. */
    static Map<String, String> keyed(boolean authEnabled) {
        return Map.of("yano.app-chain.api.keys", "full,scoped=t1",
                "yano.app-chain.api.snapshot-admin-keys", "snap",
                "yano.app-chain.api.auth.enabled", Boolean.toString(authEnabled));
    }
}
