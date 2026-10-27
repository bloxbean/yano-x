package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path temporary;

    @Test
    void thePageRunsTheSameCommandsAndWritesTheSameFilesAsTheCli() throws Exception {
        CeremonyFixture fixture = new CeremonyFixture(temporary);
        fixture.draftAndCards("demo", "devnet", List.of("alice", "bob"), "orders=audit-log");
        Path cli = Files.createDirectories(temporary.resolve("cli"));
        for (String file : List.of("demo.draft.yaml", "alice.member", "bob.member")) {
            Files.copy(fixture.coordinator.resolve(file), cli.resolve(file));
        }
        fixture.ok("seal", cli.resolve("demo.draft.yaml").toString());

        UiCommand ui = new UiCommand(new Terminal(null, new PrintStream(new ByteArrayOutputStream()), false),
                fixture.distribution, new PrintStream(new ByteArrayOutputStream()));
        var server = ui.start(fixture.coordinator, fixture.node("alice"), 0, TOKEN);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertThat(get(base + "/", null).statusCode()).isEqualTo(200);
            assertThat(get(base + "/app.mjs", null).body()).contains("X-UI-Token");
            assertThat(get(base + "/api/state", null).statusCode()).isEqualTo(403);
            assertThat(get(base + "/api/state", "wrong").statusCode()).isEqualTo(403);

            JsonNode sealed = post(base + "/api/run", Map.of("args", List.of("seal", "demo.draft.yaml")));
            assertThat(sealed.path("exit").asInt()).isZero();
            assertThat(sealed.path("command").asText()).isEqualTo("yano.sh appchain seal demo.draft.yaml");
            assertThat(Files.readString(fixture.sealedFile("demo")))
                    .isEqualTo(Files.readString(cli.resolve("demo.chain")));

            JsonNode state = JSON.readTree(get(base + "/api/state", TOKEN).body());
            assertThat(state.path("sealed").get(0).path("fingerprint").asText())
                    .isEqualTo(fixture.sealed("demo").displayFingerprint());
            assertThat(state.path("node").path("name").asText()).isEqualTo("alice");
            assertThat(state.toString()).doesNotContain(Files.readString(
                    fixture.node("alice").resolve("secrets/member.key")).strip());

            assertThat(post(base + "/api/run", Map.of("args", List.of("try"))).path("error").asText())
                    .contains("not an allowed command");
            assertThat(post(base + "/api/upload", Map.of("name", "../escape.member", "content", "{}"))
                    .path("error").asText()).contains("only .member");
            assertThat(get(base + "/api/file?name=../yano-node/secrets/member.key", TOKEN).statusCode())
                    .isEqualTo(404);

            // The page may vote on a topics plan, never plan, sign or submit one.
            for (String action : List.of("plan", "sign", "submit", "delegate")) {
                assertThat(post(base + "/api/run", Map.of("args", List.of("topics", action, "x.plan.json")))
                        .path("error").asText()).contains("not an allowed command");
            }
            assertThat(post(base + "/api/run", Map.of("args", List.of("topics"))).path("error").asText())
                    .contains("not an allowed command");
            // Only a member-vote plan is kept; anything else never lands in the work directory.
            assertThat(post(base + "/api/upload", Map.of("name", "notes.plan.json", "content", "{\"kind\":\"x\"}"))
                    .path("error").asText()).contains("is not a member-vote topics plan");
            assertThat(fixture.coordinator.resolve("notes.plan.json")).doesNotExist();
            // A file already here is never replaced by an upload of other contents.
            String draft = Files.readString(fixture.coordinator.resolve("demo.draft.yaml"));
            assertThat(post(base + "/api/upload", Map.of("name", "demo.draft.yaml", "content", draft))
                    .path("saved").asText()).isEqualTo("demo.draft.yaml");
            assertThat(post(base + "/api/upload", Map.of("name", "demo.draft.yaml", "content", draft + "# edit\n"))
                    .path("error").asText()).contains("already here with other contents");
            assertThat(Files.readString(fixture.coordinator.resolve("demo.draft.yaml"))).isEqualTo(draft);
            // A plan copied in by hand that does not check is listed as invalid, with its digest, not voted on.
            Files.writeString(fixture.coordinator.resolve("vote.plan.json"), "{\"kind\":\"TopicsPlan\"}");
            JsonNode listed = JSON.readTree(get(base + "/api/state", TOKEN).body()).path("plans");
            assertThat(listed.get(0).path("file").asText()).isEqualTo("vote.plan.json");
            assertThat(listed.get(0).path("kind").asText()).isEqualTo("invalid");
            assertThat(listed.get(0).path("sha256").asText()).hasSize(64);
            assertThat(listed.get(0).has("votes")).isFalse();
            // Only the files operators send each other can be downloaded.
            assertThat(get(base + "/api/file?name=demo.draft.yaml", TOKEN).body()).isEqualTo(draft);
            Files.writeString(fixture.coordinator.resolve("notes.txt"), "private");
            assertThat(get(base + "/api/file?name=notes.txt", TOKEN).statusCode()).isEqualTo(404);
            // A name that resolves elsewhere (DNS rebinding) is refused even with the token.
            assertThat(rawStatus(server.getAddress().getPort(), "rebound.example.com")).isEqualTo(403);
            assertThat(rawStatus(server.getAddress().getPort(), "localhost:" + server.getAddress().getPort()))
                    .isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    /** A state request with a chosen Host header, which the JDK client will not send. */
    private static int rawStatus(int port, String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(("GET /api/state HTTP/1.1\r\nHost: " + host + "\r\nX-UI-Token: " + TOKEN
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String status = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);
            return Integer.parseInt(status.substring(9, 12));
        }
    }

    private static HttpResponse<String> get(String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
        if (token != null) request.header("X-UI-Token", token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode post(String url, Object body) throws Exception {
        return JSON.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("X-UI-Token", TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString()).body());
    }
}
