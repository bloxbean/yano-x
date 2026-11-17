package org.yanoproject.x.distribution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** No keys configured and authentication disabled: privileged routes fail closed. */
@QuarkusTest
@TestProfile(DomainRouteAuthNoKeysTest.Profile.class)
class DomainRouteAuthNoKeysTest extends DomainRouteAuthMatrixTest {

    public static class Profile extends ConformanceChainProfile {
        @Override
        Map<String, String> keyConfiguration() {
            return Map.of();
        }
    }

    @Override
    Map<String, List<Integer>> expectedByKey() {
        Map<String, List<Integer>> expected = new LinkedHashMap<>();
        expected.put("GET /api/v1/app-chain/status", all(200));
        expected.put("POST /api/v1/app-chain/messages", all(400));
        expected.put("POST /api/v1/app-chain/admin/pause", all(503));
        expected.put("GET /api/v1/app-chain/chains/conformance-chain/admin/snapshots/jobs", all(503));
        expected.put("GET /api/v1/plugin-operations", all(503));
        expected.put("GET " + DOMAIN + "status", all(200));
        expected.put("POST " + DOMAIN + "query/echo#body", all(200));
        expected.put("GET " + DOMAIN + "operator", all(404));
        expected.put("GET " + DOMAIN + "internal", all(404));
        expected.put("GET " + DOMAIN + "unregistered", all(404));
        expected.put("GET /api/v1/plugins/org.example.none/x", all(404));
        return expected;
    }

    @Override
    Map<String, Integer> expectedMalformed() {
        return malformed();
    }
}
