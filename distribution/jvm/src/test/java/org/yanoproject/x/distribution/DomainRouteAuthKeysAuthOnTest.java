package org.yanoproject.x.distribution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keys configured, broad authentication enabled (key states: absent, wrong, full, scoped, snapshot). */
@QuarkusTest
@TestProfile(DomainRouteAuthKeysAuthOnTest.Profile.class)
class DomainRouteAuthKeysAuthOnTest extends DomainRouteAuthMatrixTest {

    public static class Profile extends ConformanceChainProfile {
        @Override
        Map<String, String> keyConfiguration() {
            return keyed(true);
        }
    }

    @Override
    Map<String, List<Integer>> expectedByKey() {
        Map<String, List<Integer>> expected = new LinkedHashMap<>();
        expected.put("GET /api/v1/app-chain/status", codes(401, 401, 200, 200, 401));
        expected.put("POST /api/v1/app-chain/messages", codes(401, 401, 400, 400, 401));
        expected.put("POST /api/v1/app-chain/admin/pause", codes(401, 401, 200, 403, 401));
        expected.put("GET /api/v1/app-chain/chains/conformance-chain/admin/snapshots/jobs", codes(401, 401, 401, 401, 200));
        expected.put("GET /api/v1/plugin-operations", codes(401, 401, 200, 403, 401));
        expected.put("GET " + DOMAIN + "status", codes(401, 401, 200, 200, 401));
        expected.put("POST " + DOMAIN + "query/echo#body", codes(401, 401, 200, 200, 401));
        expected.put("GET " + DOMAIN + "operator", codes(401, 401, 200, 403, 401));
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
