package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.QueuedRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.Hex;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TopicDoctorTest {
    @TempDir Path temporary;

    @Test
    void cliInspectsPendingSchemaAndProtectionAtOneRootAndRefusesMixedRootResponses() throws Exception {
        var original = TopicPlanFixtures.snapshot();
        var current = original.entries().get("games/");
        var entry = new EntryRecord(current.name(), current.active(), 3, 1, current.subtree());
        var owners = current.active().content().owners();
        var queued = new QueuedRevision(3, 13, "games/", 1,
                new EntryContent(owners, new Policy(new Writers(owners), null, null, "77".repeat(32), null), false),
                "66".repeat(32));
        Path bindings = temporary.resolve("bindings.yaml");
        Files.writeString(bindings, """
                components:
                  - {id: rides, machine: ordered-log, topic: games/rides}
                bindings:
                  - {id: repeat, to: {component: rides, command: append}}
                """);
        AtomicBoolean wrongRoot = new AtomicBoolean();
        AtomicInteger pendingReads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JsonNode response;
            int status = 200;
            if (path.endsWith("/status")) {
                ObjectNode value = TopicPlan.JSON.createObjectNode().put("chainId", original.chainId());
                value.putObject("stateCommitment").put("generationId", original.generationId())
                        .put("version", original.height()).put("stateRoot", original.stateRoot());
                value.putObject("consensusProfile").put("maxMessageBytes", original.maxMessageBytes());
                response = value;
            } else if (path.endsWith("/topics/config")) {
                ObjectNode value = TopicPlan.JSON.createObjectNode().put("enabled", true);
                value.set("config", TopicPlan.JSON.valueToTree(original.config()));
                response = value;
            } else if (path.endsWith("/topics/entries/games/")) {
                response = TopicPlan.JSON.valueToTree(entry);
            } else if (path.contains("/topics/entries/")) {
                response = TopicPlan.JSON.createObjectNode();
                status = 404;
            } else {
                ObjectNode value = TopicPlan.JSON.createObjectNode().put("stateRoot", original.stateRoot())
                        .put("presence", "ABSENT");
                if (path.endsWith(Hex.encode(TopicKeys.queued("games/", 3)))) {
                    pendingReads.incrementAndGet();
                    value.put("presence", "PRESENT")
                            .put("valueHex", Hex.encode(TopicWireCodec.encodeQueuedRevision(queued)));
                    if (wrongRoot.get()) value.put("stateRoot", "ff".repeat(32));
                }
                response = value;
            }
            byte[] bytes = TopicPlan.JSON.writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String peer = "http://127.0.0.1:" + server.getAddress().getPort();
            StringWriter output = new StringWriter();
            StringWriter errors = new StringWriter();
            int result = new AppChainDevtoolsCli().run(new String[] {"doctor", "--bindings", bindings.toString(),
                    "--chain", original.chainId(), "--peer", peer}, new PrintWriter(output), new PrintWriter(errors));
            assertThat(result).as(errors.toString()).isZero();
            JsonNode checked = TopicPlan.JSON.readTree(output.toString());
            assertThat(checked.path("checks")).allSatisfy(check ->
                    assertThat(check.path("phase").asText()).isEqualTo("pending@13"));
            assertThat(output.toString()).contains("RULE_PAYLOAD_SCHEMA_MISMATCH", "RULE_TOPIC_POLICY_CROSSING",
                    "pendingProjection");
            assertThat(pendingReads.get()).isEqualTo(1);
            wrongRoot.set(true);
            assertThat(new AppChainDevtoolsCli().run(new String[] {"topics", "doctor", "--bindings", bindings.toString(),
                    "--chain", original.chainId(), "--peer", peer}, new PrintWriter(new StringWriter()),
                    new PrintWriter(new StringWriter()))).isEqualTo(AppChainDevtoolsCli.EXIT_IO);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void warnsForUnregisteredRetiredSchemaMismatchAndCrossingTargets() throws Exception {
        ObjectNode document = (ObjectNode) TopicPlan.YAML.readTree("""
                components:
                  - {id: rides, machine: ordered-log, topic: games/rides}
                  - {id: balances, machine: balances, topic: protected/balances}
                  - {id: lost, machine: ordered-log}
                bindings:
                  - {id: award, to: {component: balances, command: mint}}
                """);
        var state = TopicPlanFixtures.snapshot();
        var owner = state.entries().get("games/").active().content().owners();
        String schema = "77".repeat(32);
        var protectedEntry = new EntryRecord("protected/", new ActiveRevision(2, 13, 1,
                new EntryContent(owner, new Policy(new Writers(owner), null, null, schema, null), false)), 2, 0, 1);
        var retired = new EntryRecord("games/", new ActiveRevision(2, 13, 1,
                new EntryContent(owner, Policy.INHERIT, true)), 2, 0, 1);
        ArrayNode checks = TopicPlan.JSON.createArrayNode();
        TopicDoctor.evaluate(document, state, Map.of("games/", retired, "protected/", protectedEntry),
                "pending@13", checks);
        assertThat(checks).anyMatch(check -> check.path("code").asText().equals("TOPIC_UNREGISTERED"));
        assertThat(checks).anyMatch(check -> check.path("code").asText().equals("TOPIC_RETIRED"));
        assertThat(checks).anyMatch(check -> check.path("code").asText().equals("RULE_PAYLOAD_SCHEMA_MISMATCH"));
        assertThat(checks).anyMatch(check -> check.path("code").asText().equals("RULE_TOPIC_POLICY_CROSSING"));
        assertThat(checks).anyMatch(check -> check.path("code").asText().equals("TOPIC_SCHEMA_COMPATIBILITY_CHECK"));
        ((ObjectNode) document.path("bindings").get(0)).put("crossesTopicPolicy", true);
        ((ObjectNode) document.path("components").get(1)).putArray("payloadSchemas").add(schema);
        ArrayNode permitted = TopicPlan.JSON.createArrayNode();
        TopicDoctor.evaluate(document, state, Map.of("games/", retired, "protected/", protectedEntry), "active", permitted);
        assertThat(permitted).noneMatch(check -> check.path("code").asText().equals("RULE_TOPIC_POLICY_CROSSING"));
        assertThat(permitted).noneMatch(check -> check.path("code").asText().equals("RULE_PAYLOAD_SCHEMA_MISMATCH"));
        assertThat(permitted).anyMatch(check -> check.path("code").asText().equals("TOPIC_RETIRED"));
    }
}
