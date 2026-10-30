package org.yanoproject.x.appchain.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.x.appchain.formats.SealedApp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCommandTest {
    @TempDir
    Path temporary;

    @Test
    void linksCarryTheDraftCardsAndSealedFileAndRefuseStrangers() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.ok("new", "linked", "--network", "devnet", "--operators", "alice,bob", "--chain", "orders=audit-log",
                "--output", fixture.coordinator.toString(), "--yes");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        ByteArrayOutputStream served = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(served, true, StandardCharsets.UTF_8);
        String[] serve = {"invite", fixture.draft("linked").toString(), "--serve", "--host", "127.0.0.1", "--port",
                Integer.toString(port), "--seal"};
        CompletableFuture<Integer> coordinator = CompletableFuture.supplyAsync(() -> AppChainCli.run(serve,
                new Terminal(null, out, false), out, fixture.distribution));

        Map<String, String> links = new LinkedHashMap<>();
        Pattern line = Pattern.compile("^\\s+(\\w+)\\s+yano\\.sh appchain request (yano://\\S+)$", Pattern.MULTILINE);
        for (int attempt = 0; attempt < 100 && links.size() < 2; attempt++) {
            Matcher match = line.matcher(served.toString(StandardCharsets.UTF_8));
            while (match.find()) links.put(match.group(1), match.group(2));
            Thread.sleep(100);
        }
        assertThat(links).containsOnlyKeys("alice", "bob");

        // A link only accepts its own operator's card; an unknown token is refused outright.
        InviteLink alice = InviteLink.parse(links.get("alice"));
        InviteLink stranger = new InviteLink(alice.host(), alice.port(), "0".repeat(24), alice.invite());
        assertThat(stranger.get("/v1/draft").status()).isEqualTo(403);

        // alice sends her card and comes back later; bob's card completes the set, so bob waits for the seal.
        var early = fixture.ok("request", links.get("alice"), "--host", "127.0.0.1", "--node-port", "14000",
                "--http-port", "9400", "--node-dir", fixture.node("alice").toString(), "--output",
                fixture.node("alice").toString(), "--no-wait", "--yes");
        assertThat(early.out()).contains("Sent your card", "run the same request command again later");
        var bob = fixture.ok("request", links.get("bob"), "--host", "127.0.0.1", "--node-port", "14001",
                "--http-port", "9401", "--node-dir", fixture.node("bob").toString(), "--output",
                fixture.node("bob").toString(), "--yes");
        assertThat(bob.out()).contains("Fetched linked.draft.yaml", "you are bob", "Received", "--expect-fingerprint");
        // Coming back with --no-wait again still collects the sealed file the coordinator now serves.
        var resumed = fixture.ok("request", links.get("alice"), "--node-dir", fixture.node("alice").toString(),
                "--output", fixture.node("alice").toString(), "--no-wait", "--yes");
        assertThat(resumed.out()).contains("Received").doesNotContain("Sent your card");
        assertThat(coordinator.get(30, TimeUnit.SECONDS)).isZero();
        assertThat(served.toString(StandardCharsets.UTF_8)).contains("Card from alice", "Card from bob",
                "Every operator has the sealed file").doesNotContain("send linked.chain to every operator");

        // Everyone received byte-identical files, and the founding join works from them.
        SealedApp sealed = fixture.sealed("linked");
        for (String operator : List.of("alice", "bob")) {
            assertThat(Files.readString(fixture.node(operator).resolve("linked.chain")))
                    .isEqualTo(Files.readString(fixture.sealedFile("linked")));
        }
        fixture.ok("join", fixture.node("bob").resolve("linked.chain").toString(), "--expect-fingerprint",
                sealed.displayFingerprint(), "--node-dir", fixture.node("bob").toString());
        assertThat(sealed.genesis().member("bob").publicKey()).isEqualTo(
                fixture.ok("inspect", fixture.coordinator.resolve("bob.member").toString(), "--json").out()
                        .replaceAll("(?s).*\"publicKey\" : \"([0-9a-f]{64})\".*", "$1"));
    }
}
