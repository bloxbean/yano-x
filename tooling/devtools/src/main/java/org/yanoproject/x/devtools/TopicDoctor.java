package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicPolicyResolver;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.QueuedRevision;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.Hex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Advisory active/pending registry diagnostics. Never participates in composite validity or admission. */
final class TopicDoctor {
    private static final int MAX_PENDING_READS = 256;

    private TopicDoctor() {
    }

    static ObjectNode check(JsonNode document, TopicsPeer peer) throws IOException {
        JsonNode composite = document.has("composite") ? document.get("composite") : document;
        JsonNode components = composite.path("components");
        if (!components.isArray() || components.isEmpty() || components.size() > 64) {
            throw TopicPlan.invalid("doctor requires 1..64 composite components");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode component : components) {
            String topic = component.has("topic") ? TopicPlan.text(component, "topic")
                    : TopicPlan.text(component, "id") + ".command.v1";
            if (!TopicNames.isValidTopic(topic)) throw TopicPlan.invalid("invalid component topic");
            names.addAll(TopicNames.applicableNames(topic));
        }
        TopicPlan.Snapshot snapshot = peer.snapshot(names);
        Map<String, EntryRecord> projected = new LinkedHashMap<>(snapshot.entries());
        ArrayNode checks = TopicPlan.JSON.createArrayNode();
        evaluate(composite, snapshot, projected, "active", checks);
        List<Pending> pending = new ArrayList<>();
        int inspected = 0;
        for (EntryRecord entry : snapshot.entries().values()) {
            if (entry.pending() == 0) continue;
            long first = entry.active() == null ? 1 : entry.active().revision() + 1;
            long slots = entry.head() - first + 1;
            if (slots > MAX_PENDING_READS - inspected) {
                warning(checks, "TOPIC_PENDING_INSPECTION_LIMIT", entry.name(), "pending", "",
                        "The 256-slot pending inspection bound was reached; inspect retained history before activation.");
                continue;
            }
            inspected += (int) slots;
            for (long revision = first; revision <= entry.head(); revision++) {
                JsonNode stored = peer.get("/state/entry/" + Hex.encode(TopicKeys.queued(entry.name(), revision))
                        + "?height=" + snapshot.height());
                if (!snapshot.stateRoot().equals(stored.path("stateRoot").asText())) {
                    throw new IOException("pending revision is not from the inspected root");
                }
                String presence = TopicPlan.text(stored, "presence");
                if (!Set.of("PRESENT", "ABSENT", "TOMBSTONED").contains(presence)) {
                    throw new IOException("invalid pending revision presence");
                }
                if (!"PRESENT".equals(presence)) continue;
                QueuedRevision queued = TopicWireCodec.decodeQueuedRevision(TopicPlan.hex(stored, "valueHex", 4096));
                if (queued.revision() != revision) throw new IOException("peer returned another pending revision");
                pending.add(new Pending(entry.name(), queued));
            }
        }
        pending.sort(Comparator.comparingLong((Pending item) -> item.revision().activation())
                .thenComparing(Pending::name).thenComparingLong(item -> item.revision().revision()));
        for (int i = 0; i < pending.size(); i++) {
            Pending item = pending.get(i);
            EntryRecord entry = projected.get(item.name());
            QueuedRevision revision = item.revision();
            long ownersSince = entry.active() != null && !"~governance".equals(revision.authority())
                    && entry.active().content().owners().equals(revision.content().owners())
                    ? entry.active().ownersSince() : revision.revision();
            projected.put(item.name(), new EntryRecord(item.name(), new ActiveRevision(revision.revision(),
                    revision.activation(), ownersSince, revision.content()), entry.head(), entry.pending(), entry.subtree()));
            if (i + 1 == pending.size() || pending.get(i + 1).revision().activation() != revision.activation()) {
                evaluate(composite, snapshot, projected, "pending@" + revision.activation(), checks);
            }
        }
        ObjectNode result = TopicPlan.JSON.createObjectNode();
        result.put("status", checks.isEmpty() ? "TOPICS_OK" : "TOPICS_WARNINGS").put("advisory", true)
                .put("height", snapshot.height()).put("stateRoot", snapshot.stateRoot());
        if (!pending.isEmpty()) {
            result.put("pendingProjection", "Queued contents under the current configuration; authority changes, "
                    + "governance supersession and same-height activation order can change which revisions activate.");
        }
        result.set("checks", checks);
        return result;
    }

    static void evaluate(JsonNode composite, TopicPlan.Snapshot snapshot, Map<String, EntryRecord> entries,
                         String phase, ArrayNode checks) {
        Map<String, TopicWire.EffectiveOutcome> policies = new HashMap<>();
        Map<String, String> topics = new HashMap<>();
        for (JsonNode component : composite.path("components")) {
            String id = TopicPlan.text(component, "id");
            String topic = component.has("topic") ? TopicPlan.text(component, "topic")
                    : TopicPlan.text(component, "id") + ".command.v1";
            topics.put(id, topic);
            TopicWire.EffectiveOutcome outcome = TopicPolicyResolver.resolve(topic, snapshot.config(),
                    snapshot.maxMessageBytes(), entries);
            policies.put(id, outcome);
            if (outcome.kind() == TopicWire.OutcomeKind.UNREGISTERED_REJECT) {
                warning(checks, "TOPIC_UNREGISTERED", id, phase, topic, "Register a covering entry before accepting writes.");
            }
            if (outcome.policy() == null) continue;
            if (outcome.policy().retired()) {
                warning(checks, "TOPIC_RETIRED", id, phase, topic, "This component's topic is retired.");
            }
            String schema = outcome.policy().schema();
            if (!schema.isEmpty()) {
                Set<String> declared = new LinkedHashSet<>();
                component.path("payloadSchemas").forEach(value -> declared.add(value.asText()));
                if (!declared.contains(schema)) {
                    warning(checks, "RULE_PAYLOAD_SCHEMA_MISMATCH", id, phase, topic,
                            "The effective schema " + schema + " is not in this component's payloadSchemas.");
                }
                if (!"ordered-log".equals(component.path("machine").asText())) {
                    warning(checks, "TOPIC_SCHEMA_COMPATIBILITY_CHECK", id, phase, topic,
                            "Validate with the selected plugin catalog: typed-command kernels cannot use topic schemas.");
                }
            }
        }
        for (JsonNode binding : composite.path("bindings")) {
            String target = binding.path("to").path("component").asText();
            TopicWire.EffectiveOutcome outcome = policies.get(target);
            if (outcome != null && outcome.policy() != null && !outcome.policy().writers().open()
                    && !binding.path("crossesTopicPolicy").asBoolean(false)) {
                warning(checks, "RULE_TOPIC_POLICY_CROSSING", TopicPlan.text(binding, "id"), phase, topics.get(target),
                        "The derived target is protected; this binding has no crossesTopicPolicy permission.");
            }
        }
    }

    private static void warning(ArrayNode checks, String code, String subject, String phase, String topic, String detail) {
        checks.addObject().put("code", code).put("subject", subject).put("phase", phase)
                .put("topic", topic).put("detail", detail);
    }

    private record Pending(String name, QueuedRevision revision) {
    }
}
