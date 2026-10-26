package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        } finally {
            server.stop(0);
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
