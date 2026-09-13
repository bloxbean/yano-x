package org.yanoproject.x.explorer;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-machine modules of ADR-050 §2.2. Each decodes the finalized commands of one stock
 * contract by topic into {@link SubjectRow}s. Rows record what was finalized, never what the
 * state became: that question is answered by a proof-backed read (§2.2).
 */
public final class StockModules {
    private static final HexFormat HEX = HexFormat.of();

    /** One module: a topic filter and a decoder. */
    public interface Module {
        String id();

        /** The subject kind this module names, for example {@code entity}. */
        String subjectKind();

        boolean accepts(String topic);

        /** Rows for one message; an empty list when the body is not a command of this module. */
        List<SubjectRow> decode(IndexedMessage message);
    }

    public static final String DOC_TRAIL = "doc-trail";
    public static final String KV_REGISTRY = "kv-registry";
    public static final String BALANCES = "balances";
    public static final String APPROVALS = "approvals";
    public static final String AUTHENTICATED_MAP = "authenticated-map";
    public static final String MALFORMED_OP = "MALFORMED";

    private static final List<Module> ALL = List.of(new DocTrail(), new KvRegistry(),
            new Balances(), new Approvals(), new AuthenticatedMap());

    private StockModules() {
    }

    public static List<Module> all() {
        return ALL;
    }

    /** The module that accepts the topic, or null for generic rows only. */
    public static Module forTopic(String topic) {
        for (Module module : ALL) {
            if (module.accepts(topic)) return module;
        }
        return null;
    }

    /**
     * How one chain routes topics to modules. A plain stock chain applies its machine to every
     * message whatever the topic (the machine ignores topics), so its module is the catch-all; a
     * composite chain routes by the topics its manifest components declare, with the contracts'
     * default topics as the fallback for components that declare none.
     */
    public record Routing(Map<String, String> topicToModule, String catchAll) {
        public Routing {
            topicToModule = Map.copyOf(topicToModule == null ? Map.of() : topicToModule);
            catchAll = catchAll == null ? "" : catchAll;
        }

        /** The contracts' default topics only. */
        public static Routing defaults() {
            Map<String, String> topics = new LinkedHashMap<>();
            topics.put(DocTrailContract.DEFAULT_TOPIC, DOC_TRAIL);
            topics.put(KvRegistryContract.DEFAULT_TOPIC, KV_REGISTRY);
            topics.put(BalancesContract.DEFAULT_TOPIC, BALANCES);
            topics.put(ApprovalsContract.DEFAULT_TOPIC, APPROVALS);
            topics.put(AuthenticatedMapContract.DEFAULT_TOPIC, AUTHENTICATED_MAP);
            return new Routing(topics, "");
        }

        /** Routing derived from a chain's capability manifest ({@code /status}). */
        public static Routing fromManifest(com.fasterxml.jackson.databind.JsonNode manifest) {
            Map<String, String> topics = new LinkedHashMap<>();
            String applicationId = manifest.path("applicationId").asText("");
            com.fasterxml.jackson.databind.JsonNode components = manifest.path("components");
            String catchAll = "";
            if (components.size() <= 1 && isStock(applicationId)) {
                catchAll = applicationId;
            }
            for (com.fasterxml.jackson.databind.JsonNode component : components) {
                String id = component.path("id").asText("");
                if (!isStock(id)) continue;
                if (component.path("topics").isEmpty()) {
                    topics.put(defaultTopic(id), id);
                }
                for (com.fasterxml.jackson.databind.JsonNode topic : component.path("topics")) {
                    topics.put(topic.asText(""), id);
                }
            }
            for (Map.Entry<String, String> entry : defaults().topicToModule().entrySet()) {
                topics.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return new Routing(topics, catchAll);
        }

        public String moduleFor(String topic) {
            if (!catchAll.isEmpty()) return catchAll;
            return topicToModule.get(topic);
        }
    }

    static boolean isStock(String id) {
        return ALL.stream().anyMatch(module -> module.id().equals(id));
    }

    static String defaultTopic(String module) {
        return switch (module) {
            case DOC_TRAIL -> DocTrailContract.DEFAULT_TOPIC;
            case KV_REGISTRY -> KvRegistryContract.DEFAULT_TOPIC;
            case BALANCES -> BalancesContract.DEFAULT_TOPIC;
            case APPROVALS -> ApprovalsContract.DEFAULT_TOPIC;
            case AUTHENTICATED_MAP -> AuthenticatedMapContract.DEFAULT_TOPIC;
            default -> "";
        };
    }

    public static Module byId(String id) {
        for (Module module : ALL) {
            if (module.id().equals(id)) return module;
        }
        return null;
    }

    /** Rows for one message under the contracts' default topics; tombstones yield none. */
    public static List<SubjectRow> decode(IndexedMessage message) {
        return decode(message, Routing.defaults());
    }

    /** Rows for one message through the module the chain's routing selects; tombstones yield none. */
    public static List<SubjectRow> decode(IndexedMessage message, Routing routing) {
        if (message.state() == IndexedMessage.State.TOMBSTONE) return List.of();
        String id = routing.moduleFor(message.topic());
        Module module = id == null ? null : byId(id);
        if (module == null) return List.of();
        try {
            return module.decode(message);
        } catch (RuntimeException malformed) {
            return List.of(new SubjectRow(module.id(), module.subjectKind(), "malformed:" + message.messageIdHex(),
                    MALFORMED_OP, Map.of("error", String.valueOf(malformed.getMessage()))));
        }
    }

    static final class DocTrail implements Module {
        @Override public String id() { return DOC_TRAIL; }
        @Override public String subjectKind() { return "entity"; }
        @Override public boolean accepts(String topic) { return DocTrailContract.DEFAULT_TOPIC.equals(topic); }

        @Override
        public List<SubjectRow> decode(IndexedMessage message) {
            DocTrailContract.Append append = DocTrailContract.decodeCommand(message.body());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("entryHashHex", HEX.formatHex(append.entryHash()));
            fields.put("reference", append.reference());
            fields.put("authorHex", message.senderHex());
            return List.of(new SubjectRow(DOC_TRAIL, "entity", append.entityId(), "APPEND", fields));
        }
    }

    static final class KvRegistry implements Module {
        @Override public String id() { return KV_REGISTRY; }
        @Override public String subjectKind() { return "key"; }
        @Override public boolean accepts(String topic) { return KvRegistryContract.DEFAULT_TOPIC.equals(topic); }

        @Override
        public List<SubjectRow> decode(IndexedMessage message) {
            KvRegistryContract.Command command = KvRegistryContract.decodeCommand(message.body());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("keyText", printable(command.key()));
            fields.put("valueHex", HEX.formatHex(command.value()));
            fields.put("valueText", printable(command.value()));
            fields.put("senderHex", message.senderHex());
            return List.of(new SubjectRow(KV_REGISTRY, "key", HEX.formatHex(command.key()),
                    command.put() ? "PUT" : "DELETE", fields));
        }
    }

    static final class Balances implements Module {
        @Override public String id() { return BALANCES; }
        @Override public String subjectKind() { return "account"; }
        @Override public boolean accepts(String topic) { return BalancesContract.DEFAULT_TOPIC.equals(topic); }

        @Override
        public List<SubjectRow> decode(IndexedMessage message) {
            BalancesContract.Command command = BalancesContract.decodeCommand(message.body());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("amount", command.amount().toString());
            fields.put("senderHex", message.senderHex());
            return List.of(new SubjectRow(BALANCES, "account", command.account(),
                    command.mint() ? "MINT" : "TRANSFER", fields));
        }
    }

    static final class Approvals implements Module {
        @Override public String id() { return APPROVALS; }
        @Override public String subjectKind() { return "item"; }
        @Override public boolean accepts(String topic) { return ApprovalsContract.DEFAULT_TOPIC.equals(topic); }

        @Override
        public List<SubjectRow> decode(IndexedMessage message) {
            ApprovalsContract.Command command = ApprovalsContract.decodeCommand(message.body());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("senderHex", message.senderHex());
            String op;
            if (command.operation() == ApprovalsContract.OP_PROPOSE) {
                op = "PROPOSE";
                fields.put("payloadHex", HEX.formatHex(command.payload()));
                fields.put("payloadText", printable(command.payload()));
                fields.put("payloadHashHex", HEX.formatHex(Blake2bUtil.blake2bHash256(command.payload())));
                fields.put("required", command.required());
                fields.put("deadlineMillis", command.deadlineMillis());
            } else {
                op = command.operation() == ApprovalsContract.OP_APPROVE ? "APPROVE" : "REJECT";
            }
            return List.of(new SubjectRow(APPROVALS, "item", command.itemId(), op, fields));
        }
    }

    static final class AuthenticatedMap implements Module {
        @Override public String id() { return AUTHENTICATED_MAP; }
        @Override public String subjectKind() { return "entry"; }
        @Override public boolean accepts(String topic) { return AuthenticatedMapContract.DEFAULT_TOPIC.equals(topic); }

        @Override
        public List<SubjectRow> decode(IndexedMessage message) {
            byte[] body = message.body();
            boolean governed;
            AuthenticatedMapContract.Command command;
            List<Integer> kinds = new ArrayList<>();
            List<String> policies = new ArrayList<>();
            String commitmentHex = "";
            try {
                AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1 wrapped =
                        AuthenticatedMapAuthorizationContract.decodeCommand(body);
                governed = true;
                command = new AuthenticatedMapContract.Command(wrapped.action().batch(),
                        wrapped.action().mutations());
                for (var assignment : wrapped.action().authorizations()) {
                    kinds.add(assignment.authorizationKind());
                    policies.add(assignment.policyId());
                }
                commitmentHex = HEX.formatHex(
                        AuthenticatedMapAuthorizationContract.actionCommitment(wrapped.action()));
            } catch (RuntimeException notGoverned) {
                governed = false;
                command = AuthenticatedMapContract.decodeCommand(body);
            }
            List<SubjectRow> rows = new ArrayList<>();
            List<AuthenticatedMapContract.Mutation> mutations = command.mutations();
            for (int index = 0; index < mutations.size(); index++) {
                AuthenticatedMapContract.Mutation mutation = mutations.get(index);
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("collection", mutation.collectionId());
                fields.put("keyHex", HEX.formatHex(mutation.applicationKey()));
                fields.put("keyText", printable(mutation.applicationKey()));
                fields.put("valueHex", HEX.formatHex(mutation.value()));
                fields.put("expectedRevision", mutation.expectedRevision());
                fields.put("batch", command.batch());
                fields.put("mutationIndex", index);
                fields.put("governed", governed);
                if (governed) {
                    fields.put("authorizationKind", authorizationKind(kinds.get(index)));
                    fields.put("policyId", policies.get(index));
                    fields.put("actionCommitmentHex", commitmentHex);
                }
                fields.put("senderHex", message.senderHex());
                rows.add(new SubjectRow(AUTHENTICATED_MAP, "entry",
                        mutation.collectionId() + "/" + HEX.formatHex(mutation.applicationKey()),
                        operation(mutation.operation()), fields));
            }
            return rows;
        }

        private static String operation(int operation) {
            return switch (operation) {
                case AuthenticatedMapContract.OP_PUT -> "PUT";
                case AuthenticatedMapContract.OP_PUT_IF_ABSENT -> "PUT_IF_ABSENT";
                case AuthenticatedMapContract.OP_COMPARE_AND_SET -> "COMPARE_AND_SET";
                case AuthenticatedMapContract.OP_TRANSFER_CONTROLLER -> "TRANSFER_CONTROLLER";
                case AuthenticatedMapContract.OP_REVOKE -> "REVOKE";
                case AuthenticatedMapContract.OP_RESTORE -> "RESTORE";
                default -> "OP_" + operation;
            };
        }

        private static String authorizationKind(int kind) {
            return switch (kind) {
                case AuthenticatedMapContract.AUTH_OPEN -> "OPEN";
                case AuthenticatedMapContract.AUTH_OWNER -> "OWNER";
                case AuthenticatedMapContract.AUTH_MEMBER -> "MEMBER";
                case AuthenticatedMapContract.AUTH_GOVERNED_ROLE -> "GOVERNED_ROLE";
                case AuthenticatedMapContract.AUTH_APPROVAL -> "APPROVAL";
                default -> "KIND_" + kind;
            };
        }
    }

    /** UTF-8 text when the bytes decode cleanly and contain no control characters, else empty. */
    static String printable(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) return "";
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') return "";
            }
            return text;
        } catch (CharacterCodingException binary) {
            return "";
        }
    }
}
