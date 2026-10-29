package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.Names;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A {@code yano://host:port/token#invite} link from {@code invite --serve}, and the calls an operator makes. */
record InviteLink(String host, int port, String token, String invite) {
    private static final Pattern LINK = Pattern.compile("yano://([A-Za-z0-9.-]+):([0-9]{1,5})/([0-9a-f]{24})#(.+)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static boolean looksLike(String value) {
        return value != null && value.startsWith(InviteCommand.SCHEME);
    }

    static InviteLink parse(String value) {
        Matcher match = LINK.matcher(value);
        if (!match.matches()) {
            throw CliException.usage("Not an invitation link: expected yano://host:port/<token>#<invite code>");
        }
        return new InviteLink(match.group(1), Integer.parseInt(match.group(2)), match.group(3),
                Names.invite(match.group(4)));
    }

    /** One response: status, body and the operator name the coordinator assigned to this link. */
    record Reply(int status, String body, String operator) {
    }

    Reply get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    Reply post(String path, String json) {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private URI uri(String path) {
        return URI.create("http://" + host + ":" + port + path);
    }

    private Reply send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = HTTP.send(builder.header("X-Invite-Token", token)
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(response.statusCode(), response.body(),
                    response.headers().firstValue("X-Operator-Name").orElse(null));
        } catch (IOException error) {
            throw CliException.notReady("Cannot reach the coordinator at " + host + ":" + port + " (" + error.getMessage()
                    + "); check the link and that the coordinator is still running invite --serve");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw CliException.failure("Interrupted");
        }
    }
}
