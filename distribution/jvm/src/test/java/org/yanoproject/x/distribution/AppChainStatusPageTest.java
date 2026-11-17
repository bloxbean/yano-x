package org.yanoproject.x.distribution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-064 D9: framework/rest serves the app-chain console at /ui/app-chain/, next to Yano's
 * console at /ui/, and links back to it. Moved from Yano's AppChainStatusPageTest,
 * ConsoleSecurityHeadersTest and PluginOperationsDashboardPageTest.
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class AppChainStatusPageTest {

    @Test
    void appChainStatusPage_isServed() {
        String page = given()
                .when().get("/ui/app-chain/index.html")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · App Ledgers"));
        assertTrue(page.contains("data-console-route=\"app-chain\""));
        for (String panel : new String[]{"App ledger operations", "Consensus &amp; traffic",
                "L1 anchor", "Effect executors", "Profile governance", "Live messages",
                "Recent blocks", "Finalized message", "Authenticated fetch SSE"}) {
            assertTrue(page.contains(panel), panel);
        }
        for (String link : new String[]{"href=\"/ui/\"", "href=\"/ui/status/\"", "href=\"/ui/plugins/\""}) {
            assertTrue(page.contains(link), link);
        }
    }

    @Test
    void eutxoExplorerIsARealPackagedStaticPage() {
        String page = given()
                .when().get("/ui/app-chain/eutxo/index.html")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · EUTxO Explorer"));
        assertTrue(page.contains("data-console-route=\"eutxo\""));
        assertTrue(page.contains("Lifecycle explorer"));
        assertTrue(page.contains(
                "transaction, message, claim, outpoint, or address"));
    }

    @Test
    void authenticatedMapConsoleIsARealPackagedStaticPage() {
        String page = given()
                .when().get("/ui/app-chain/authenticated-map/index.html")
                .then()
                .statusCode(200)
                .extract().asString();

        assertTrue(page.contains("Yano · Authenticated Map"));
        assertTrue(page.contains("data-console-route=\"authenticated-map\""));
        assertTrue(page.contains("Committed state console"));
        assertTrue(page.contains(
                "Exact records, receipts, approvals, and proofs from the committed authenticated map."));
    }

    @Test
    void consolePagesCarryStrictSecurityHeaders() {
        for (String page : new String[] {
                "/ui/app-chain/index.html",
                "/ui/app-chain/eutxo/index.html"}) {
            given().when().get(page).then().statusCode(200)
                    .header("X-Frame-Options", "DENY")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", Matchers.allOf(
                            Matchers.containsString("connect-src 'self'"),
                            Matchers.not(Matchers.containsString("connect-src 'self' http:")),
                            Matchers.containsString("frame-ancestors 'none'")))
                    .header("Referrer-Policy", "no-referrer")
                    .header("Cache-Control", "no-store");
        }
    }

    @Test
    void yanoConsoleStillOwnsTheRestOfUi() {
        given().when().get("/ui/index.html").then().statusCode(200)
                .body(Matchers.containsString("Yano Console"));
    }
}
