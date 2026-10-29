package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.Hex;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicPlanTest {
    @Test
    void frozenStudioFixturesMatchEveryUnsignedCommandAndPreview() throws Exception {
        try (var input = getClass().getResourceAsStream("/topics/plan-v1.json")) {
            assertThat(input).isNotNull();
            JsonNode frozen = TopicPlan.JSON.readTree(input);
            assertThat(TopicPlan.equivalent(TopicPlanFixtures.fixtures(), frozen)).isTrue();
            for (JsonNode fixture : frozen) {
                assertThat(TopicPlan.equivalent(TopicPlan.validate(fixture.get("plan")), fixture.get("plan"))).isTrue();
            }
        }
    }

    @Test
    void commandBytesBindGenerationHeadRevisionAndSchema() {
        JsonNode fixture = TopicPlanFixtures.fixtures().get(1);
        JsonNode plan = fixture.get("plan");
        var statement = TopicPlan.statement(plan, 0);
        assertThat(statement.expected()).isEqualTo(1);
        assertThat(statement.generationId()).isEqualTo(TopicPlanFixtures.GENERATION);
        assertThat(TopicWireCodec.blake2b256Hex(TopicWireCodec.encodeCommandStatement(statement)))
                .isEqualTo(plan.path("commands").get(0).path("statementHash").asText());
        assertThat(plan.path("commands").get(0).path("afterActivation").path("policy").path("schema").asText())
                .hasSize(64);
        assertThat(plan.path("commands").get(0).path("earliestActivation").longValue()).isEqualTo(13);
    }

    @Test
    void editedStatementsPreviewsAndSignerRequirementsCannotBeSigned() {
        JsonNode original = TopicPlanFixtures.fixtures().get(1).get("plan");
        for (String field : new String[] {"statementHex", "statementHash", "before", "requiredSigners"}) {
            ObjectNode changed = original.deepCopy();
            ((ObjectNode) changed.path("commands").get(0)).put(field, "changed");
            assertThatThrownBy(() -> TopicPlan.validate(changed)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode changed = original.deepCopy();
        ((ArrayNode) changed.path("commands")).set(0, TopicPlan.JSON.getNodeFactory().numberNode(2));
        assertThatThrownBy(() -> TopicPlan.validate(changed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmbiguousDocumentsAndFieldsThatWouldBeIgnored() {
        assertThatThrownBy(() -> TopicPlan.read("{\"kind\":\"Topics\",\"kind\":\"Other\"}"
                .getBytes(StandardCharsets.UTF_8), false)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> TopicPlan.read("{} {}".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(Exception.class);
        ObjectNode doc = TopicPlanFixtures.document("games/", "retire", "games/");
        ((ObjectNode) doc.path("commands").get(0)).putObject("policy").put("writers", "open");
        assertThatThrownBy(() -> TopicPlan.compile(doc, TopicPlanFixtures.snapshot()))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode nestedGovernance = TopicPlanFixtures.document("games/nested/", "create", "governance");
        assertThatThrownBy(() -> TopicPlan.compile(nestedGovernance, TopicPlanFixtures.snapshot()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingHeadsFailClosedAndRecreatedNamesKeepMonotonicRevisions() {
        var snapshot = TopicPlanFixtures.snapshot();
        var entry = snapshot.entries().get("games/");
        var pending = new EntryRecord(entry.name(), entry.active(), 2, 1, entry.subtree());
        var withPending = new TopicPlan.Snapshot(snapshot.chainId(), snapshot.generationId(), snapshot.height(),
                snapshot.stateRoot(), snapshot.maxMessageBytes(), snapshot.config(), Map.of("games/", pending), Map.of());
        assertThatThrownBy(() -> TopicPlan.compile(TopicPlanFixtures.document("games/", "update", "games/"), withPending))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("TOPIC_COMMAND_PENDING");
        var freed = new TopicPlan.Snapshot(snapshot.chainId(), snapshot.generationId(), snapshot.height(),
                snapshot.stateRoot(), snapshot.maxMessageBytes(), snapshot.config(), snapshot.entries(),
                Map.of("reused/", 9L));
        JsonNode plan = TopicPlan.compile(TopicPlanFixtures.document("reused/", "create", "~claim"), freed);
        assertThat(plan.path("commands").get(0).path("afterActivation").path("sources").get(0)
                .path("revision").longValue()).isEqualTo(10);
    }

    @Test
    void expiryAndAuthorityAreFailClosed() {
        ObjectNode plan = TopicPlan.compile(TopicPlanFixtures.document("games/", "update", "games/"),
                TopicPlanFixtures.snapshot());
        assertThatThrownBy(() -> TopicsCli.checkExpiry(plan, 1_000_001))
                .hasMessage("TOPIC_COMMAND_EXPIRED");
        ObjectNode wrong = TopicPlanFixtures.document("games/", "update", "unrelated/");
        assertThatThrownBy(() -> TopicPlan.compile(wrong, TopicPlanFixtures.snapshot()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revocationChecksBothSignaturesDomainsAndScopeWithoutRequiringATargetRevision() {
        for (String authority : new String[] {"games/", "~issuer"}) {
            var snapshot = TopicPlanFixtures.revocationSnapshot(false);
            var document = TopicPlanFixtures.revocation(authority);
            var plan = TopicPlan.compile(document, snapshot);
            assertThat(TopicPlan.statement(plan, 0).expected()).isNull();
            assertThat(plan.path("commands").get(0).has("earliestActivation")).isFalse();
            assertThat(plan.path("commands").get(0).has("afterActivation")).isFalse();
            for (String change : new String[] {"signature", "scope", "chain", "generation"}) {
                ObjectNode changed = document.deepCopy();
                ObjectNode command = (ObjectNode) changed.path("commands").get(0);
                if (change.equals("signature")) {
                    byte[] bytes = Hex.decode(command.path("delegationHex").asText());
                    bytes[bytes.length - 1] ^= 1;
                    command.put("delegationHex", Hex.encode(bytes));
                } else if (change.equals("scope")) command.put("target", "games/other/");
                String chain = change.equals("chain") ? "other-chain" : snapshot.chainId();
                changed.put("chainId", chain);
                var different = new TopicPlan.Snapshot(chain,
                        change.equals("generation") ? "55".repeat(32) : snapshot.generationId(),
                        snapshot.height(), snapshot.stateRoot(), snapshot.maxMessageBytes(), snapshot.config(),
                        snapshot.entries(), snapshot.freedHeads());
                assertThatThrownBy(() -> TopicPlan.compile(changed, different))
                        .as(authority + ": " + change).hasMessage("invalid delegation for revocation");
            }
        }
    }
}
