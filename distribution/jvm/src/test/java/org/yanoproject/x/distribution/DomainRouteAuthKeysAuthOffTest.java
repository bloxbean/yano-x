package org.yanoproject.x.distribution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keys configured, broad authentication disabled (key states: absent, wrong, full, scoped, snapshot). */
@QuarkusTest
@TestProfile(DomainRouteAuthKeysAuthOffTest.Profile.class)
class DomainRouteAuthKeysAuthOffTest extends DomainRouteAuthMatrixTest {

    public static class Profile extends ConformanceChainProfile {
        @Override
        Map<String, String> keyConfiguration() {
            return keyed(false);
        }
    }

    @Override
    Map<String, List<Integer>> expectedByKey() {
        Map<String, List<Integer>> expected = new LinkedHashMap<>();
        expected.put("GET /api/v1/app-chain/status", all(200));
        expected.put("POST /api/v1/app-chain/messages", all(400));
        expected.put("POST /api/v1/app-chain/admin/pause", codes(401, 401, 200, 403, 401));
        expected.put("GET /api/v1/app-chain/chains/conformance-chain/admin/snapshots/jobs", codes(401, 401, 401, 401, 200));
        expected.put("GET /api/v1/plugin-operations", codes(401, 401, 200, 403, 401));
        expected.put("GET " + DOMAIN + "status", all(200));
        expected.put("POST " + DOMAIN + "query/echo#body", all(200));
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
