package org.yanoproject.x.feed.client;

import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedDatum;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.trust.client.StatusAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HexFormat;

/**
 * What a consumer or operator sees of a round (ADR-052 §2.2): the feed policy, one row per
 * configured source with its observation and disposition, the recomputed result beside the
 * recorded one, and the candidate datum. It adds presentation over a bundle's answers, never
 * trust; every row keeps the presence, provenance, and height of the answer it came from.
 */
public final class RoundView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private RoundView() {
    }

    public static ObjectNode of(RoundBundle bundle) {
        ObjectNode root = JSON.createObjectNode();
        root.put("feedId", bundle.feedId());
        root.put("round", bundle.round());
        root.put("chainId", bundle.chainId());
        root.put("status", bundle.status());
        root.put("observationHeight", bundle.observationHeight());
        root.put("recordHeight", bundle.recordHeight());
        root.put("stateRoot", bundle.stateRootHex());
        root.put("starter", FeedStarterProfile.STARTER_NOTICE);

        FeedValues.FeedValue feed = bundle.feedValue();
        ObjectNode feedNode = root.putObject("feed");
        feedNode.put("presence", bundle.feed().presence().name());
        if (feed != null) {
            feedNode.put("description", feed.description());
            feedNode.put("unit", feed.unit());
            feedNode.put("scale", feed.scale());
            feedNode.put("epochStart", feed.epochStart());
            feedNode.put("roundSeconds", feed.roundSeconds());
            ArrayNode sources = feedNode.putArray("sources");
            feed.sources().forEach(sources::add);
            feedNode.put("minimumSources", feed.minimumSources());
            feedNode.put("maximumDeviationPpm", feed.maximumDeviationPpm());
            feedNode.put("maximumDeviationAbsolute", feed.maximumDeviationAbsolute());
            feedNode.put("minimumValue", feed.minimumValue());
            feedNode.put("maximumValue", feed.maximumValue());
            feedNode.put("status", feed.statusName());
            try {
                feedNode.put("roundStart", feed.roundStart(bundle.round()));
                feedNode.put("roundEnd", feed.roundEnd(bundle.round()));
            } catch (ArithmeticException overflow) {
                feedNode.put("roundStart", -1);
                feedNode.put("roundEnd", -1);
            }
        }
        answerRows(feedNode, bundle.feed());

        Aggregation.Result result = bundle.recompute();
        ArrayNode sources = root.putArray("sources");
        for (int index = 0; index < bundle.observations().size(); index++) {
            StatusAnswer answer = bundle.observations().get(index);
            ObjectNode node = sources.addObject();
            String sourceId = feed != null && index < feed.sources().size() ? feed.sources().get(index) : null;
            if (sourceId == null) {
                try {
                    sourceId = FeedStarterProfile.parseObservationKey(answer.key()).sourceId();
                } catch (RuntimeException malformed) {
                    sourceId = answer.keyText();
                }
            }
            node.put("sourceId", sourceId);
            if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
                try {
                    FeedValues.ObservationValue value = FeedValues.ObservationValue.decode(answer.entry().value());
                    node.put("value", value.value());
                    node.put("decimal", feed != null ? FeedValues.decimal(value.value(), feed.scale())
                            : Long.toString(value.value()));
                    node.put("observedAt", value.observedAt());
                    node.put("evidenceSha256", HEX.formatHex(value.evidenceSha256()));
                    node.put("note", value.note());
                } catch (RuntimeException malformed) {
                    node.put("disposition", "MALFORMED");
                }
            }
            Aggregation.SourceResult source = result != null ? result.source(sourceId) : null;
            if (source != null) {
                node.put("disposition", source.disposition().name());
            } else if (!node.has("disposition")) {
                node.put("disposition", answer.presence() == StatusAnswer.Presence.ABSENT ? "ABSENT" : "UNKNOWN");
            }
            answerRows(node, answer);
        }

        ObjectNode recomputed = root.putObject("recomputed");
        if (result != null) {
            recomputed.put("status", result.statusName());
            recomputed.put("aggregate", result.aggregate());
            recomputed.put("decimal", FeedValues.decimal(result.aggregate(), result.scale()));
            recomputed.put("scale", result.scale());
            recomputed.put("unit", feed.unit());
            ArrayNode accepted = recomputed.putArray("acceptedSources");
            result.acceptedSources().forEach(accepted::add);
            recomputed.put("candidateCount", result.candidateCount());
            recomputed.put("rule", Aggregation.RULE_ID);
        } else {
            recomputed.put("status", "UNAVAILABLE");
        }

        ObjectNode recordNode = root.putObject("record");
        FeedValues.RoundValue record = bundle.recordValue();
        recordNode.put("presence", bundle.record().presence().name());
        boolean agrees = false;
        if (record != null) {
            recordNode.put("status", record.statusName());
            recordNode.put("closedAtHeight", record.closedAtHeight());
            recordNode.put("aggregate", record.aggregate());
            recordNode.put("decimal", FeedValues.decimal(record.aggregate(), record.scale()));
            recordNode.put("scale", record.scale());
            ArrayNode accepted = recordNode.putArray("acceptedSources");
            record.acceptedSources().forEach(accepted::add);
            recordNode.put("policySha256", HEX.formatHex(record.policySha256()));
            recordNode.put("datumSha256", HEX.formatHex(record.datumSha256()));
            agrees = result != null && record.closedAtHeight() == bundle.observationHeight()
                    && record.status() == result.status() && record.aggregate() == result.aggregate()
                    && record.scale() == result.scale() && record.acceptedSources().equals(result.acceptedSources());
            recordNode.put("agreesWithRecomputation", agrees);
            recordNode.put("sameHeight", record.closedAtHeight() == bundle.observationHeight());
        }
        recordNode.put("approvalConsumption", bundle.record().fact(FeedClient.APPROVAL_CONSUMPTION_FACT) != null);
        answerRows(recordNode, bundle.record());

        if (record != null && record.closed() && agrees && feed != null) {
            byte[] datum = FeedDatum.encode(FeedClient.datumFields(bundle.chainId(), bundle.feedId(),
                    bundle.round(), feed, result, bundle.observationHeight(), bundle.stateRootHex()));
            ObjectNode datumNode = root.putObject("datum");
            datumNode.put("type", FeedDatum.DATUM_ID);
            datumNode.put("hex", HEX.formatHex(datum));
            datumNode.put("sha256", HEX.formatHex(FeedValues.sha256(datum)));
            datumNode.put("bindsRecord", Arrays.equals(FeedValues.sha256(datum), record.datumSha256()));
        } else {
            root.putNull("datum");
        }
        return root;
    }

    private static void answerRows(ObjectNode node, StatusAnswer answer) {
        node.put("presence", answer.presence().name());
        node.put("height", answer.height());
        node.put("keyHex", answer.keyHex());
        if (answer.entry() != null) {
            node.put("revision", answer.entry().revision());
            node.put("createdHeight", answer.entry().createdHeight());
            node.put("lastMutationHeight", answer.entry().lastMutationHeight());
        }
        StatusAnswer.Provenance provenance = answer.provenance();
        ObjectNode who = node.putObject("provenance");
        who.put("kind", provenance.kind().name());
        if (provenance.messageIdHex() != null) who.put("messageId", provenance.messageIdHex());
        if (provenance.appliedHeight() > 0) who.put("appliedHeight", provenance.appliedHeight());
        if (provenance.actorId() != null) {
            who.put("actorId", provenance.actorId());
            who.put("organizationId", provenance.organizationId());
            who.put("keyId", provenance.keyId());
            who.put("policyId", provenance.policyId());
            who.put("policyRevision", provenance.policyRevision());
            who.put("role", provenance.role());
        }
        node.put("facts", answer.facts().size());
    }

    private static final class Arrays {
        static boolean equals(byte[] left, byte[] right) {
            return java.util.Arrays.equals(left, right);
        }
    }
}
