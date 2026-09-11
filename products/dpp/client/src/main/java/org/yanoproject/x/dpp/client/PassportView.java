package org.yanoproject.x.dpp.client;

import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.trust.client.StatusAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a consumer, operator, or regulator sees of a passport (ADR-051 §2.2): the records decoded
 * with the profile's codecs, the timeline in ledger order, and the flags a prototype cannot
 * prevent but can expose. It adds presentation over a bundle's answers, never trust; every row
 * keeps the presence, provenance, and height of the answer it came from.
 */
public final class PassportView {
    public static final String FLAG_REWRITTEN = "REWRITTEN";
    public static final String FLAG_DANGLING = "DANGLING";
    public static final String FLAG_FOREIGN_WRITER = "FOREIGN_WRITER";
    public static final String FLAG_EXPIRED = "EXPIRED";
    public static final String FLAG_NOT_YET_VALID = "NOT_YET_VALID";
    public static final String FLAG_MALFORMED = "MALFORMED";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private PassportView() {
    }

    /** The view as JSON; {@code documentAvailable} says whether a version's document is served. */
    public static ObjectNode of(PassportBundle bundle, Predicate<String> documentAvailable) {
        ObjectNode root = JSON.createObjectNode();
        root.put("productId", bundle.productId());
        root.put("chainId", bundle.chainId());
        root.put("height", bundle.height());
        root.put("stateRoot", bundle.stateRootHex());
        root.put("blockHash", bundle.blockHashHex());
        root.put("prototype", DppStarterProfile.PROTOTYPE_NOTICE);
        List<String> flags = new ArrayList<>();

        StatusAnswer product = bundle.product();
        ObjectNode productNode = root.putObject("product");
        productNode.put("presence", product.presence().name());
        DppValues.ProductValue productValue = null;
        String status = switch (product.presence()) {
            case ABSENT -> "ABSENT";
            case REVOKED -> "REVOKED";
            case ACTIVE -> null;
        };
        if (product.presence() == StatusAnswer.Presence.ACTIVE) {
            try {
                productValue = DppValues.ProductValue.decode(product.entry().value());
                status = productValue.statusName();
                productNode.put("manufacturerOrganizationId", productValue.manufacturerOrganizationId());
                productNode.put("currentVersion", productValue.currentVersion());
                productNode.put("successorProductId", productValue.successorProductId());
                productNode.put("passportProfileId", productValue.passportProfileId());
                if (foreignWriter(product, productValue.manufacturerOrganizationId())) {
                    flags.add(FLAG_FOREIGN_WRITER);
                }
            } catch (RuntimeException malformed) {
                status = FLAG_MALFORMED;
                flags.add(FLAG_MALFORMED);
            }
        }
        root.put("status", status);
        answerRows(productNode, product);

        ArrayNode versions = root.putArray("versions");
        boolean currentSeen = productValue == null || productValue.currentVersion() == 0;
        for (StatusAnswer answer : bundle.versions()) {
            ObjectNode node = versions.addObject();
            List<String> rowFlags = new ArrayList<>();
            long version = 0;
            try {
                version = DppStarterProfile.parseVersionKey(answer.key()).version();
                node.put("version", version);
            } catch (RuntimeException malformed) {
                rowFlags.add(FLAG_MALFORMED);
            }
            boolean current = productValue != null && version == productValue.currentVersion()
                    && answer.presence() == StatusAnswer.Presence.ACTIVE;
            currentSeen |= current;
            node.put("current", current);
            if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
                try {
                    DppValues.VersionValue value = DppValues.VersionValue.decode(answer.entry().value());
                    String digest = HEX.formatHex(value.documentSha256());
                    node.put("documentSha256", digest);
                    node.put("mediaType", value.mediaType());
                    node.put("reference", value.reference());
                    node.put("byteLength", value.byteLength());
                    node.put("availability", documentAvailable.test(digest) ? "CONTENT_VERIFIED" : "FINALIZED");
                } catch (RuntimeException malformed) {
                    rowFlags.add(FLAG_MALFORMED);
                }
                if (answer.entry().revision() > 1) {
                    rowFlags.add(FLAG_REWRITTEN);
                }
                if (productValue != null && foreignWriter(answer, productValue.manufacturerOrganizationId())) {
                    rowFlags.add(FLAG_FOREIGN_WRITER);
                }
            }
            answerRows(node, answer);
            flagsNode(node, rowFlags);
        }
        if (!currentSeen) {
            flags.add(FLAG_DANGLING);
        }

        ArrayNode claims = root.putArray("claims");
        for (StatusAnswer answer : bundle.claims()) {
            ObjectNode node = claims.addObject();
            List<String> rowFlags = new ArrayList<>();
            try {
                DppStarterProfile.ClaimKey key = DppStarterProfile.parseClaimKey(answer.key());
                node.put("claimType", key.claimType());
                node.put("claimId", key.claimId());
            } catch (RuntimeException malformed) {
                rowFlags.add(FLAG_MALFORMED);
            }
            if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
                try {
                    DppValues.ClaimValue value = DppValues.ClaimValue.decode(answer.entry().value());
                    node.put("visibility", value.isPublic() ? "PUBLIC" : "COMMITTED");
                    if (value.isPublic()) {
                        node.put("text", value.text());
                    } else {
                        node.put("commitment", HEX.formatHex(value.value()));
                    }
                    node.put("issuerOrganizationId", value.issuerOrganizationId());
                    node.put("validFromHeight", value.validFromHeight());
                    node.put("validUntilHeight", value.validUntilHeight());
                    node.put("evidenceSha256", HEX.formatHex(value.evidenceSha256()));
                    validity(node, rowFlags, value.validFromHeight(), value.validUntilHeight(), bundle.height());
                    if (foreignWriter(answer, value.issuerOrganizationId())) {
                        rowFlags.add(FLAG_FOREIGN_WRITER);
                    }
                } catch (RuntimeException malformed) {
                    rowFlags.add(FLAG_MALFORMED);
                }
            }
            answerRows(node, answer);
            flagsNode(node, rowFlags);
        }

        Map<String, StatusAnswer> eventsByKey = new LinkedHashMap<>();
        for (StatusAnswer answer : bundle.events()) {
            eventsByKey.put(answer.keyHex(), answer);
        }
        ArrayNode events = root.putArray("events");
        // Ledger order: the timeline's first mention of each event key.
        List<String> ordered = new ArrayList<>();
        for (PassportProjection.Applied applied : bundle.timeline()) {
            if (DppStarterProfile.EVENTS.equals(applied.collection()) && !ordered.contains(applied.keyHex())) {
                ordered.add(applied.keyHex());
            }
        }
        for (String keyHex : eventsByKey.keySet()) {
            if (!ordered.contains(keyHex)) ordered.add(keyHex);
        }
        for (String keyHex : ordered) {
            StatusAnswer answer = eventsByKey.get(keyHex);
            if (answer == null) continue;
            ObjectNode node = events.addObject();
            List<String> rowFlags = new ArrayList<>();
            try {
                node.put("eventId", DppStarterProfile.parseEventKey(answer.key()).eventId());
            } catch (RuntimeException malformed) {
                rowFlags.add(FLAG_MALFORMED);
            }
            if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
                try {
                    DppValues.EventValue value = DppValues.EventValue.decode(answer.entry().value());
                    node.put("eventType", value.eventType());
                    node.put("actorOrganizationId", value.actorOrganizationId());
                    node.put("observedAt", value.observedAt());
                    node.put("location", value.location());
                    node.put("evidenceSha256", HEX.formatHex(value.evidenceSha256()));
                    node.put("note", value.note());
                    if (foreignWriter(answer, value.actorOrganizationId())) {
                        rowFlags.add(FLAG_FOREIGN_WRITER);
                    }
                } catch (RuntimeException malformed) {
                    rowFlags.add(FLAG_MALFORMED);
                }
            }
            answerRows(node, answer);
            flagsNode(node, rowFlags);
        }

        ArrayNode certificates = root.putArray("certificates");
        for (StatusAnswer answer : bundle.certificates()) {
            ObjectNode node = certificates.addObject();
            List<String> rowFlags = new ArrayList<>();
            node.put("certificateId", answer.keyText() != null ? answer.keyText() : answer.keyHex());
            if (answer.presence() == StatusAnswer.Presence.ACTIVE) {
                try {
                    DppValues.CertificateValue value = DppValues.CertificateValue.decode(answer.entry().value());
                    node.put("certificateType", value.certificateType());
                    node.put("issuerOrganizationId", value.issuerOrganizationId());
                    node.put("evidenceSha256", HEX.formatHex(value.evidenceSha256()));
                    node.put("validFromHeight", value.validFromHeight());
                    node.put("validUntilHeight", value.validUntilHeight());
                    validity(node, rowFlags, value.validFromHeight(), value.validUntilHeight(), bundle.height());
                } catch (RuntimeException malformed) {
                    rowFlags.add(FLAG_MALFORMED);
                }
            }
            node.put("approvalConsumption", answer.fact(DppClient.APPROVAL_CONSUMPTION_FACT) != null);
            answerRows(node, answer);
            flagsNode(node, rowFlags);
        }

        ArrayNode timeline = root.putArray("timeline");
        for (PassportProjection.Applied applied : bundle.timeline()) {
            ObjectNode node = timeline.addObject();
            node.put("height", applied.height());
            node.put("position", applied.position());
            node.put("messageId", applied.messageIdHex());
            node.put("collection", applied.collection());
            node.put("key", applied.keyText());
            node.put("operation", applied.operation());
        }
        flagsNode(root, flags);
        return root;
    }

    private static boolean foreignWriter(StatusAnswer answer, String organizationId) {
        String writer = answer.provenance().organizationId();
        return writer != null && !writer.equals(organizationId);
    }

    private static void validity(ObjectNode node, List<String> flags, long from, long until, long height) {
        if (height < from) {
            node.put("validity", FLAG_NOT_YET_VALID);
            flags.add(FLAG_NOT_YET_VALID);
        } else if (until != 0 && height > until) {
            node.put("validity", FLAG_EXPIRED);
            flags.add(FLAG_EXPIRED);
        } else {
            node.put("validity", "VALID");
        }
    }

    private static void answerRows(ObjectNode node, StatusAnswer answer) {
        node.put("presence", answer.presence().name());
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

    private static void flagsNode(ObjectNode node, List<String> flags) {
        ArrayNode array = node.putArray("flags");
        flags.forEach(array::add);
    }
}
