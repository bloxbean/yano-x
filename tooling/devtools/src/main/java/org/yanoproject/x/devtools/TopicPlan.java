package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.yanoproject.api.appchain.topics.TopicNames;
import org.yanoproject.api.appchain.topics.TopicPolicyResolver;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.CommandStatement;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.GovernanceCommand;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Operation;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.util.StrictEd25519;
import org.yanoproject.x.client.Hex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Pure, deterministic Topics document compiler shared by CLI and Studio's conformance oracle. */
public final class TopicPlan {
    public static final String KIND = "TopicsPlan";
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> COMMAND_FIELDS = Set.of("target", "operation", "authority", "nonce",
            "notAfter", "owners", "policy", "delegationHex");

    private TopicPlan() {
    }

    /** Snapshot is advisory authoring input; the host rechecks every command at admission and inclusion. */
    public record Snapshot(String chainId, String generationId, long height, String stateRoot,
                           long maxMessageBytes, Config config, Map<String, EntryRecord> entries,
                           Map<String, Long> freedHeads) {
        public Snapshot {
            if (chainId == null || chainId.isBlank() || chainId.length() > 128 || height < 1
                    || maxMessageBytes < 1) throw invalid("invalid topic snapshot");
            hash(generationId, "generationId");
            hash(stateRoot, "stateRoot");
            if (entries == null || entries.size() > 16_384) throw invalid("invalid snapshot entries");
            entries = Map.copyOf(entries);
            freedHeads = freedHeads == null ? Map.of() : Map.copyOf(freedHeads);
            freedHeads.forEach((name, head) -> {
                if (!TopicNames.isValidEntryName(name) || head < 1) throw invalid("invalid freed head");
            });
            entries.forEach((name, entry) -> {
                if (!name.equals(entry.name()) || !TopicNames.isValidEntryName(name)) {
                    throw invalid("invalid snapshot entry");
                }
            });
        }
    }

    public static ObjectNode compile(JsonNode document, Snapshot snapshot) {
        fields(document, Set.of("schemaVersion", "kind", "chainId", "commands"));
        if (number(document, "schemaVersion", 1) != 1 || !"Topics".equals(text(document, "kind"))) {
            throw invalid("expected Topics schemaVersion 1");
        }
        if (!snapshot.chainId().equals(text(document, "chainId"))) throw invalid("chainId differs from peer");
        JsonNode commands = document.get("commands");
        if (commands == null || !commands.isArray() || commands.isEmpty() || commands.size() > 64) {
            throw invalid("commands must contain 1..64 items");
        }
        ObjectNode result = JSON.createObjectNode();
        result.put("schemaVersion", 1).put("kind", KIND);
        result.set("document", document.deepCopy());
        result.set("snapshot", JSON.valueToTree(snapshot));
        ArrayNode steps = result.putArray("commands");
        Set<String> seen = new HashSet<>();
        Set<String> nonces = new HashSet<>();
        for (JsonNode command : commands) {
            fields(command, COMMAND_FIELDS);
            String target = text(command, "target");
            if (!TopicNames.isValidEntryName(target) || !seen.add(target)) {
                throw invalid("each command needs a unique valid target; plan sequential changes separately");
            }
            String nonce = text(command, "nonce");
            if (!nonce.matches("[0-9a-f]{32}") || !nonces.add(nonce)) throw invalid("nonce must be unique 16-byte hex");
            String operation = text(command, "operation");
            String authority = text(command, "authority");
            ObjectNode step = steps.addObject();
            step.put("target", target).put("operation", operation).put("authority", authority);
            EntryRecord existing = snapshot.entries().get(target);
            validateOperationFields(command, operation, authority);
            if (existing != null && existing.pending() > 0 && !"governance".equals(authority)) {
                throw invalid("TOPIC_COMMAND_PENDING");
            }
            PolicySource policy = policy(command.get("policy"));
            if ("governance".equals(authority)) {
                if (snapshot.config() == null || !snapshot.config().settings().governance()) {
                    throw invalid("topic governance is not enabled");
                }
                TopicWire.GovernanceOperation change = switch (operation) {
                    case "create" -> {
                        if (existing != null) throw invalid("TOPIC_COMMAND_STALE");
                        if (!TopicNames.isTopLevel(target)) throw invalid("governance creates top-level names");
                        yield new TopicWire.GovCreate(target, keys(command.get("owners")), policy.policy(), policy.ir());
                    }
                    case "recover" -> new TopicWire.GovRecover(target, keys(command.get("owners")));
                    case "retire" -> new TopicWire.GovRetire(target);
                    default -> throw invalid("governance supports create, recover and retire");
                };
                GovernanceCommand wire = new GovernanceCommand(snapshot.chainId(), snapshot.generationId(), nonce, change);
                step.put("mode", "governance").put("commandHex", Hex.encode(TopicWireCodec.encodeGovernanceCommand(wire)));
                step.put("endpoint", "admin/topics/governance");
            } else {
                if (snapshot.config() == null) throw invalid("topics are disabled");
                Operation change = switch (operation) {
                    case "create" -> new TopicWire.Create(keys(command.get("owners")), policy.policy(), policy.ir());
                    case "update" -> new TopicWire.Update(policy.policy(), policy.ir());
                    case "rotate" -> new TopicWire.Rotate(keys(command.get("owners")));
                    case "retire" -> new TopicWire.Retire();
                    case "revoke" -> new TopicWire.Revoke(TopicWireCodec.decodeDelegation(
                            hex(command, "delegationHex", 4096)));
                    default -> throw invalid("unknown registry operation");
                };
                Long expected = change instanceof TopicWire.Create || change instanceof TopicWire.Revoke
                        ? null : existing == null ? null : existing.head();
                if (change instanceof TopicWire.Create && existing != null
                        || !(change instanceof TopicWire.Create || change instanceof TopicWire.Revoke) && existing == null) {
                    throw invalid("TOPIC_COMMAND_STALE: target existence does not match operation");
                }
                CommandStatement statement = new CommandStatement(snapshot.chainId(), snapshot.generationId(),
                        target, authority, expected, nonce, number(command, "notAfter", 0), change);
                KeySet signers = authorityKeys(statement, snapshot);
                byte[] bytes = TopicWireCodec.encodeCommandStatement(statement);
                step.put("mode", "registry").put("statementHex", Hex.encode(bytes));
                step.put("statementHash", TopicWireCodec.blake2b256Hex(bytes));
                step.set("requiredSigners", JSON.valueToTree(signers));
                step.putArray("signatures");
            }
            step.set("before", JSON.valueToTree(TopicPolicyResolver.resolve(target, snapshot.config(),
                    snapshot.maxMessageBytes(), snapshot.entries())));
            Map<String, EntryRecord> after = new LinkedHashMap<>(snapshot.entries());
            EntryContent old = existing == null || existing.active() == null ? null : existing.active().content();
            if ("create".equals(operation) || "update".equals(operation) || "rotate".equals(operation)
                    || "recover".equals(operation) || "retire".equals(operation)) {
                if (!"create".equals(operation) && old == null) throw invalid("target has no active revision");
                KeySet owners = "create".equals(operation) || "rotate".equals(operation) || "recover".equals(operation)
                        ? keys(command.get("owners")) : old.owners();
                Policy changed = "create".equals(operation) || "update".equals(operation) ? policy.policy() : old.policy();
                long revision = Math.addExact(existing == null
                        ? snapshot.freedHeads().getOrDefault(target, 0L) : existing.head(), 1);
                long activeFrom = Math.addExact(Math.addExact(snapshot.height(), 1),
                        snapshot.config().settings().activationLag());
                long ownersSince = existing != null && existing.active() != null
                        && !"governance".equals(authority) && owners.equals(old.owners())
                        ? existing.active().ownersSince() : revision;
                after.put(target, new EntryRecord(target, new ActiveRevision(revision, activeFrom, ownersSince,
                        new EntryContent(owners, changed, "retire".equals(operation) || old != null && old.retired())),
                        revision, 0, existing == null ? 0 : existing.subtree()));
                step.put("earliestActivation", activeFrom);
                step.set("afterActivation", JSON.valueToTree(TopicPolicyResolver.resolve(target, snapshot.config(),
                        snapshot.maxMessageBytes(), after)));
            }
            if (existing != null && existing.pending() > 0) {
                step.put("warning", "Pending revisions exist; preview uses active policy. Replan after activation.");
            }
        }
        return result;
    }

    /** Reconstruct the unsigned plan and reject edited statements, previews or signer requirements. */
    public static ObjectNode validate(JsonNode plan) {
        fields(plan, Set.of("schemaVersion", "kind", "document", "snapshot", "commands"));
        if (number(plan, "schemaVersion", 1) != 1 || !KIND.equals(text(plan, "kind"))) throw invalid("not a TopicsPlan");
        Snapshot snapshot = JSON.convertValue(plan.get("snapshot"), Snapshot.class);
        ObjectNode expected = compile(plan.get("document"), snapshot);
        JsonNode commands = plan.get("commands");
        if (commands == null || !commands.isArray() || commands.size() != expected.path("commands").size()) {
            throw invalid("plan commands differ from source document");
        }
        for (int i = 0; i < commands.size(); i++) {
            if (!commands.get(i).isObject()) throw invalid("invalid plan command");
            ObjectNode actual = ((ObjectNode) commands.get(i)).deepCopy();
            ObjectNode calculated = (ObjectNode) expected.path("commands").get(i);
            if ("registry".equals(calculated.path("mode").asText())) {
                List<Signature> signatures = signatures(actual.path("signatures"));
                byte[] statement = Hex.decode(text(calculated, "statementHex"));
                Set<String> authorized = new HashSet<>(keyList(calculated.path("requiredSigners").path("keys")));
                for (Signature signature : signatures) {
                    if (!StrictEd25519.verify(Hex.decode(signature.key()), signature.sig(), statement)
                            || !authorized.contains(signature.key())) {
                        throw invalid("plan signature is invalid or outside authority");
                    }
                }
                actual.putArray("signatures");
                if (!equivalent(actual, calculated)) throw invalid("plan differs from its canonical compilation");
                calculated.set("signatures", commands.get(i).get("signatures").deepCopy());
            } else if (!equivalent(actual, calculated)) {
                throw invalid("governance plan differs from its canonical compilation");
            }
        }
        return expected;
    }

    static CommandStatement statement(JsonNode plan, int index) {
        Snapshot snapshot = JSON.convertValue(plan.get("snapshot"), Snapshot.class);
        JsonNode command = plan.path("document").path("commands").get(index);
        String target = text(command, "target");
        PolicySource policy = policy(command.get("policy"));
        Operation operation = switch (text(command, "operation")) {
            case "create" -> new TopicWire.Create(keys(command.get("owners")), policy.policy(), policy.ir());
            case "update" -> new TopicWire.Update(policy.policy(), policy.ir());
            case "rotate" -> new TopicWire.Rotate(keys(command.get("owners")));
            case "retire" -> new TopicWire.Retire();
            case "revoke" -> new TopicWire.Revoke(TopicWireCodec.decodeDelegation(hex(command, "delegationHex", 4096)));
            default -> throw invalid("not a registry command");
        };
        Long expected = operation instanceof TopicWire.Create || operation instanceof TopicWire.Revoke
                ? null : snapshot.entries().get(target).head();
        return new CommandStatement(snapshot.chainId(), snapshot.generationId(), target, text(command, "authority"),
                expected, text(command, "nonce"), number(command, "notAfter", 0), operation);
    }

    static KeySet authorityKeys(CommandStatement statement, Snapshot snapshot) {
        String authority = statement.authority();
        boolean create = statement.operation() instanceof TopicWire.Create;
        if (TopicWire.AUTHORITY_CLAIM.equals(authority)) {
            if (!create || !TopicNames.isTopLevel(statement.target()) || !snapshot.config().settings().openClaims()) {
                throw invalid("open claim is not allowed for this target");
            }
            return ((TopicWire.Create) statement.operation()).owners();
        }
        if (TopicWire.AUTHORITY_REGISTRARS.equals(authority)) {
            if (!create || !TopicNames.isTopLevel(statement.target()) || snapshot.config().settings().registrars() == null) {
                throw invalid("registrar creation is not allowed for this target");
            }
            return snapshot.config().settings().registrars();
        }
        if (statement.operation() instanceof TopicWire.Revoke revoke) {
            if (!revoke.delegation().scope().equals(statement.target())
                    || !StrictEd25519.verify(Hex.decode(revoke.delegation().issuer()), revoke.delegation().sig(),
                    TopicWireCodec.delegationSignedBytes(statement.chainId(), statement.generationId(),
                            revoke.delegation()))) throw invalid("invalid delegation for revocation");
            if (TopicWire.AUTHORITY_ISSUER.equals(authority)) {
                TopicWire.EffectiveOutcome outcome = TopicPolicyResolver.resolve(statement.target(), snapshot.config(),
                        snapshot.maxMessageBytes(), snapshot.entries());
                if (outcome.policy() == null || !outcome.policy().delegate().contains(revoke.delegation().issuer())) {
                    throw invalid("issuer is not a delegator for the scope");
                }
                return new KeySet(1, List.of(revoke.delegation().issuer()));
            }
        } else if (TopicWire.AUTHORITY_ISSUER.equals(authority)) throw invalid("issuer may only revoke");
        if (!TopicNames.covers(authority, statement.target()) || create && authority.equals(statement.target())) {
            throw invalid("authority must be the target or an ancestor namespace");
        }
        EntryRecord owner = snapshot.entries().get(authority);
        if (owner == null || owner.active() == null
                || owner.active().content().retired() && !(statement.operation() instanceof TopicWire.Revoke)) {
            throw invalid("authority has no active unretired entry");
        }
        return owner.active().content().owners();
    }

    private static void validateOperationFields(JsonNode command, String operation, String authority) {
        boolean ownerChange = Set.of("create", "rotate", "recover").contains(operation);
        boolean policyChange = Set.of("create", "update").contains(operation);
        if (command.has("owners") != ownerChange || command.has("policy") && !policyChange
                || command.has("delegationHex") != "revoke".equals(operation)
                || "governance".equals(authority) && command.has("notAfter")) {
            throw invalid("fields do not match operation");
        }
    }

    static List<Signature> signatures(JsonNode node) {
        if (!node.isArray() || node.size() > TopicWire.MAX_KEYS) throw invalid("invalid signature list");
        List<Signature> result = new ArrayList<>();
        String previous = "";
        for (JsonNode entry : node) {
            fields(entry, Set.of("key", "sigHex"));
            Signature signature = new Signature(text(entry, "key"), hex(entry, "sigHex", 64));
            if (previous.compareTo(signature.key()) >= 0) throw invalid("signatures must be sorted and unique");
            previous = signature.key();
            result.add(signature);
        }
        return List.copyOf(result);
    }

    static KeySet keys(JsonNode node) {
        fields(node, Set.of("threshold", "keys"));
        List<String> keys = keyList(node.get("keys"));
        return new KeySet(Math.toIntExact(number(node, "threshold", 1)), keys);
    }

    static List<String> keyList(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > TopicWire.MAX_KEYS) throw invalid("invalid key list");
        TreeSet<String> keys = new TreeSet<>();
        for (JsonNode value : node) {
            if (!value.isTextual()) throw invalid("keys must be hex strings");
            String key = hash(value.textValue(), "key");
            if (!keys.add(key)) throw invalid("duplicate key");
        }
        return List.copyOf(keys);
    }

    private static PolicySource policy(JsonNode node) {
        if (node == null || node.isNull()) return new PolicySource(Policy.INHERIT, null);
        fields(node, Set.of("writers", "delegate", "maxDelegationMs", "schema", "schemaCddl", "maxPayloadBytes"));
        TopicWire.Writers writers = null;
        if (node.hasNonNull("writers")) {
            JsonNode value = node.get("writers");
            writers = value.isTextual() && "open".equals(value.textValue())
                    ? TopicWire.Writers.OPEN : new TopicWire.Writers(keys(value));
        }
        byte[] ir = null;
        String schema = null;
        if (node.hasNonNull("schemaCddl")) {
            if (node.has("schema")) throw invalid("choose schema or schemaCddl");
            ir = CddlSchemaCompiler.compile(text(node, "schemaCddl")).definition();
            schema = TopicWireCodec.blake2b256Hex(ir);
        } else if (node.hasNonNull("schema")) {
            schema = text(node, "schema");
            schema = "none".equals(schema) ? "" : hash(schema, "schema");
        }
        return new PolicySource(new Policy(writers, node.hasNonNull("delegate") ? keyList(node.get("delegate")) : null,
                optionalNumber(node, "maxDelegationMs"), schema, optionalNumber(node, "maxPayloadBytes")), ir);
    }

    static JsonNode read(byte[] bytes, boolean yaml) throws IOException {
        if (bytes.length < 1 || bytes.length > MAX_FILE_BYTES) throw invalid("input must contain 1 byte to 2 MiB");
        JsonNode result = (yaml ? YAML : JSON).readTree(bytes);
        if (result == null || !result.isObject()) throw invalid("expected an object");
        return result;
    }

    static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject()) throw invalid("expected an object");
        node.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) throw invalid("unknown field: " + name);
        });
    }

    static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid("required text: " + name);
        return value.textValue();
    }

    static long number(JsonNode node, String name, long minimum) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) {
            throw invalid("invalid integer: " + name);
        }
        return value.longValue();
    }

    static Long optionalNumber(JsonNode node, String name) {
        return node.hasNonNull(name) ? number(node, name, 1) : null;
    }

    static String hash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw invalid(name + " must be 32-byte lowercase hex");
        return value;
    }

    static byte[] hex(JsonNode node, String name, int maximum) {
        String value = text(node, name);
        if (value.length() > maximum * 2 || !value.matches("(?:[0-9a-f]{2})+")) throw invalid("invalid hex: " + name);
        return Hex.decode(value);
    }

    private record PolicySource(Policy policy, byte[] ir) {
    }

    /** JSON integers have one meaning regardless of Jackson's in-memory int/long storage choice. */
    static boolean equivalent(JsonNode left, JsonNode right) {
        return left.equals((first, second) -> first.isIntegralNumber() && second.isIntegralNumber()
                ? first.bigIntegerValue().compareTo(second.bigIntegerValue()) : first.equals(second) ? 0 : 1, right);
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /** A refusal whose text is written by the tool and names no input value, so it is shown as is. */
    static final class Guidance extends IllegalArgumentException {
        Guidance(String message) {
            super(message);
        }
    }

    static IllegalArgumentException guidance(String message) {
        return new Guidance(message);
    }
}
