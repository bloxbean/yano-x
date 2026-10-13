package org.yanoproject.x.devtools;

import org.yanoproject.x.roles.contracts.ActorGovernanceCommandV1;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.GovernedMutationCommandV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.PolicyMutationV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RegistryMutationV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns a generated {@code bootstrap/role-approvals-plan.yaml} into the member-governance commands that create its
 * organizations, actors and policies: for each record, the mutation, its hash, and the encoded propose, approve and
 * activate commands with the topic they go to. Nothing is submitted and no secret is read; actors prove possession
 * of their keys with {@code role key-proof} beforehand.
 *
 * <p>The plan is parsed strictly: a field of the wrong shape or an out-of-range number is refused rather than
 * coerced, because a coerced policy (an empty proposer-role list, say) would silently weaken it.
 */
final class RoleBootstrapPlan {
    static final String USAGE = """
            Usage: ./yano.sh appchain role bootstrap --plan <bootstrap/role-approvals-plan.yaml>
                     --expiry-height <n> [--key-proof <file>]... [--attempt <n>]
            Prints JSON: for each organization, actor and policy in the plan, the governed mutation and the hex
            propose, approve and activate commands with their topic.
              --key-proof   hex output of 'role key-proof' for an actor key. At revision 1 every key needs one.
                            In a later revision, mark each key kept from the prior revision with
                            proofOfPossession: OPTIONAL and pass proofs only for the keys that revision adds.
              --expiry-height  the app height after which an unfinished proposal expires: the chain's current
                            height plus at most machines.composite.roles.maximum-mutation-lifetime-blocks.
              --attempt     starts a fresh governance attempt for every record, for when an earlier attempt
                            expired or failed to activate; the default is 1.""";
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_PLAN_BYTES = 1_048_576;
    private static final int MAX_KEY_PROOFS = 1_024;
    private static final int MAX_KEY_PROOF_BYTES = 16_384;
    private static final int MAX_ATTEMPT = 999;
    private static final Set<String> RECORDS = Set.of("organization", "actor", "policy");

    private RoleBootstrapPlan() {
    }

    static String execute(String[] args) throws IOException {
        Path plan = null;
        Long expiryHeight = null;
        Long attempt = null;
        List<Path> keyProofFiles = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (index + 1 >= args.length) throw new IllegalArgumentException(option + " requires a value");
            String value = args[++index];
            switch (option) {
                case "--plan" -> {
                    if (plan != null) throw new IllegalArgumentException("--plan may be given once");
                    plan = Path.of(value);
                }
                case "--expiry-height" -> {
                    if (expiryHeight != null) throw new IllegalArgumentException("--expiry-height may be given once");
                    expiryHeight = parsePositive(value, "--expiry-height", Long.MAX_VALUE);
                }
                case "--attempt" -> {
                    if (attempt != null) throw new IllegalArgumentException("--attempt may be given once");
                    attempt = parsePositive(value, "--attempt", MAX_ATTEMPT);
                }
                case "--key-proof" -> {
                    if (keyProofFiles.size() == MAX_KEY_PROOFS) {
                        throw new IllegalArgumentException("too many key proofs");
                    }
                    keyProofFiles.add(Path.of(value));
                }
                default -> throw new IllegalArgumentException("unknown option " + option);
            }
        }
        if (plan == null || expiryHeight == null) {
            throw new IllegalArgumentException("--plan and --expiry-height are required");
        }
        long attemptNumber = attempt == null ? 1 : attempt;
        long expiry = expiryHeight;
        if (!Files.isRegularFile(plan)) throw new IllegalArgumentException("--plan is not a regular file: " + plan);
        if (Files.size(plan) > MAX_PLAN_BYTES) throw new IllegalArgumentException("the plan is larger than 1 MiB");
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(plan.toFile());
        if (document == null || !document.isObject() || !"RoleBootstrapPlan".equals(document.path("kind").asText())) {
            throw new IllegalArgumentException("not a RoleBootstrapPlan: " + plan);
        }
        rejectPlaceholders(document, "$");
        String chainId = text(document, "chainId", "$");
        List<ActorKeyProofV1> proofs = new ArrayList<>();
        for (Path file : keyProofFiles) proofs.add(readProof(file));
        Set<ActorKeyProofV1> used = Collections.newSetFromMap(new IdentityHashMap<>());

        List<Map<String, Object>> steps = new ArrayList<>();
        for (JsonNode organization : records(document, "organizations")) {
            String at = "organizations[" + organization.path("organizationId").asText("?") + "]";
            steps.add(located(at, () -> organizationStep(organization, at, attemptNumber, expiry)));
        }
        for (JsonNode actor : records(document, "actors")) {
            String at = "actors[" + actor.path("actorId").asText("?") + "]";
            steps.add(located(at, () -> actorStep(actor, at, chainId, proofs, used, attemptNumber, expiry)));
        }
        for (JsonNode policy : records(document, "policies")) {
            String at = "policies[" + policy.path("policyId").asText("?") + "]";
            steps.add(located(at, () -> policyStep(policy, at, attemptNumber, expiry)));
        }
        for (ActorKeyProofV1 proof : proofs) {
            if (!used.contains(proof)) {
                throw new IllegalArgumentException("--key-proof for actor " + proof.actorId() + " revision "
                        + proof.actorRevision() + " key " + proof.key().keyId()
                        + " matches no key in the plan (chain, revision, public key and validity must all match)");
            }
        }
        Set<String> mutationIds = new HashSet<>();
        for (Map<String, Object> step : steps) {
            if (!mutationIds.add((String) step.get("mutationId"))) {
                throw new IllegalArgumentException("the plan lists " + step.get("record") + " " + step.get("id")
                        + " revision " + step.get("revision") + " more than once");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chainId);
        result.put("planThreshold", integer(document.path("governance").isObject() ? document.path("governance")
                : missingGovernance(), "threshold", "governance", 1, Integer.MAX_VALUE));
        result.put("expiryHeight", expiryHeight);
        result.put("attempt", attemptNumber);
        result.put("procedure", "For each step in order: query the record and skip it if that exact revision is "
                + "committed. Otherwise submit propose through one member, which counts as its approval, then "
                + "approve through each further member until the threshold, then activate; wait for each "
                + "message to be final before the next. If the record is still absent after activate, the "
                + "attempt expired or failed: fix the cause and rerun with the next --attempt.");
        result.put("steps", steps);
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result);
    }

    private static Map<String, Object> organizationStep(JsonNode organization, String at, long attemptNumber,
                                                        long expiryHeight) {
        OrganizationRecordV1 record = new OrganizationRecordV1(text(organization, "organizationId", at),
                integer(organization, "revision", at, 1, Long.MAX_VALUE), status(organization, at),
                commitment(organization, at));
        return step("organization", record.organizationId(), record.revision(), attemptNumber,
                ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC,
                new RegistryMutationV1.PutOrganization(record).encode(), expiryHeight);
    }

    private static Map<String, Object> actorStep(JsonNode actor, String at, String chainId,
                                                 List<ActorKeyProofV1> proofs, Set<ActorKeyProofV1> used,
                                                 long attemptNumber, long expiryHeight) {
        String actorId = text(actor, "actorId", at);
        long revision = integer(actor, "revision", at, 1, Long.MAX_VALUE);
        List<ActorKeyEpochV1> keys = new ArrayList<>();
        List<ActorKeyProofV1> actorProofs = new ArrayList<>();
        List<String> provedKeys = new ArrayList<>();
        for (JsonNode key : array(actor, "publicKeys", at)) {
            String keyAt = at + ".publicKeys[" + key.path("keyId").asText("?") + "]";
            ActorKeyEpochV1 epoch = new ActorKeyEpochV1(text(key, "keyId", keyAt),
                    hex32(key, "publicKey", keyAt), integer(key, "validFromHeight", keyAt, 1, Long.MAX_VALUE),
                    integer(key, "validUntilHeight", keyAt, 0, Long.MAX_VALUE), status(key, keyAt));
            keys.add(epoch);
            String possession = optionalText(key, "proofOfPossession", keyAt, "REQUIRED");
            if (!possession.equals("REQUIRED") && !possession.equals("OPTIONAL")) {
                throw new IllegalArgumentException(keyAt + ".proofOfPossession must be REQUIRED or OPTIONAL");
            }
            // The registry wants a proof for exactly the keys a revision adds: every key at revision 1, and
            // the REQUIRED ones later; OPTIONAL marks a key kept from the prior revision.
            boolean retained = revision > 1 && possession.equals("OPTIONAL");
            if (revision == 1 && possession.equals("OPTIONAL")) {
                throw new IllegalArgumentException(keyAt + ": every key of a revision-1 actor is new and needs"
                        + " a proof; proofOfPossession: OPTIONAL marks a key kept from a prior revision");
            }
            ActorKeyProofV1 proof = proofs.stream().filter(candidate -> matches(candidate, chainId, actorId,
                    revision, epoch)).findFirst().orElse(null);
            if (proof != null && retained) {
                throw new IllegalArgumentException(keyAt + " is kept from the prior revision"
                        + " (proofOfPossession: OPTIONAL), so it must not have a --key-proof");
            } else if (proof != null) {
                actorProofs.add(proof);
                provedKeys.add(epoch.keyId());
                used.add(proof);
            } else if (!retained) {
                if (epoch.status() != RecordStatus.ACTIVE) {
                    throw new IllegalArgumentException(keyAt + ": a new key must be ACTIVE");
                }
                throw new IllegalArgumentException("no --key-proof for actor " + actorId + " key "
                        + epoch.keyId() + "; the actor creates it with: ./yano.sh appchain role key-proof"
                        + " --chain " + chainId + " --actor " + actorId + " --actor-revision " + revision
                        + " --key " + epoch.keyId() + " --public-key " + HEX.formatHex(epoch.publicKey())
                        + " --valid-from-height " + epoch.validFromHeight() + " --valid-until-height "
                        + epoch.validUntilHeight() + " --seed-file <seed-file>");
            }
        }
        ActorRecordV1 record = new ActorRecordV1(actorId, text(actor, "organizationId", at), revision,
                status(actor, at), strings(actor, "roles", at), keys, commitment(actor, at));
        Map<String, Object> step = step("actor", actorId, revision, attemptNumber,
                ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC,
                new RegistryMutationV1.PutActor(record, actorProofs).encode(), expiryHeight);
        step.put("keyProofs", provedKeys);
        return step;
    }

    private static Map<String, Object> policyStep(JsonNode policy, String at, long attemptNumber, long expiryHeight) {
        List<ApprovalPolicyV1.RequiredClause> clauses = new ArrayList<>();
        for (JsonNode clause : array(policy, "clauses", at)) {
            String clauseAt = at + ".clauses[" + clause.path("clauseId").asText("?") + "]";
            clauses.add(new ApprovalPolicyV1.RequiredClause(text(clause, "clauseId", clauseAt),
                    text(clause, "role", clauseAt),
                    (int) integer(clause, "minimumCount", clauseAt, 1, Integer.MAX_VALUE),
                    choice(clause, "distinctBy", clauseAt, ApprovalPolicyV1.DistinctBy.class)));
        }
        ApprovalPolicyV1 record = new ApprovalPolicyV1(text(policy, "policyId", at),
                integer(policy, "revision", at, 1, Long.MAX_VALUE), status(policy, at),
                // An empty list lets any eligible actor propose, so it must be written as [] on purpose.
                strings(policy, "proposerRoles", at), clauses,
                choice(policy, "rejectionMode", at, ApprovalPolicyV1.RejectionMode.class),
                integer(policy, "maximumLifetimeBlocks", at, 1, Long.MAX_VALUE));
        return step("policy", record.policyId(), record.revision(), attemptNumber,
                ActorGovernanceCommandV1.POLICY_TOPIC, new PolicyMutationV1.PutPolicy(record).encode(),
                expiryHeight);
    }

    private static Map<String, Object> step(String record, String id, long revision, long attempt, String topic,
                                            byte[] mutation, long expiryHeight) {
        String mutationId = mutationId(record, id, revision, attempt);
        GovernedMutationCommandV1.Propose propose =
                new GovernedMutationCommandV1.Propose(mutationId, mutation, expiryHeight);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("record", record);
        step.put("id", id);
        step.put("revision", revision);
        step.put("topic", topic);
        step.put("mutationId", mutationId);
        step.put("mutationHash", HEX.formatHex(propose.mutationHash()));
        step.put("mutation", HEX.formatHex(mutation));
        step.put("propose", HEX.formatHex(propose.encode()));
        step.put("approve", HEX.formatHex(
                new GovernedMutationCommandV1.Approve(mutationId, propose.mutationHash()).encode()));
        step.put("activate", HEX.formatHex(
                new GovernedMutationCommandV1.Activate(mutationId, propose.mutationHash()).encode()));
        return step;
    }

    /**
     * {@code bootstrap-<record>-<id>-r<revision>[-a<attempt>]}. When that exceeds the 63-character identifier
     * grammar, {@code bootstrap-hashed-<record>-<digest of the whole tuple>}: no readable id starts with
     * {@code bootstrap-hashed-}, because record kinds are fixed, so the two forms never collide.
     */
    static String mutationId(String record, String id, long revision, long attempt) {
        if (!RECORDS.contains(record)) throw new IllegalArgumentException("unknown record kind " + record);
        String readable = "bootstrap-" + record + "-" + id + "-r" + revision + (attempt == 1 ? "" : "-a" + attempt);
        if (readable.length() <= 63) return readable;
        String digest = AppChainProjectCatalog.sha256((record + "\0" + id + "\0" + revision + "\0" + attempt)
                .getBytes(StandardCharsets.UTF_8));
        return "bootstrap-hashed-" + record + "-" + digest.substring(0, 24);
    }

    /** A contract refusal names only its result code, so say which record it was. */
    private static Map<String, Object> located(String at, Supplier<Map<String, Object>> build) {
        try {
            return build.get();
        } catch (RoleWorkflowException invalid) {
            throw new IllegalArgumentException(at + " is refused by the role-workflow contracts ("
                    + invalid.getMessage() + "): check its identifiers ([a-z][a-z0-9-], at most 63 characters),"
                    + " list sizes, duplicates and ranges", invalid);
        }
    }

    private static ActorKeyProofV1 readProof(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("--key-proof is not a regular file: " + file);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_KEY_PROOF_BYTES + 1);
        }
        if (bytes.length > MAX_KEY_PROOF_BYTES) {
            throw new IllegalArgumentException("--key-proof " + file + " is larger than " + MAX_KEY_PROOF_BYTES
                    + " bytes");
        }
        try {
            ActorKeyProofV1 proof = ActorKeyProofV1.decode(HEX.parseHex(
                    new String(bytes, StandardCharsets.US_ASCII).trim()));
            if (!proof.verify()) throw new IllegalArgumentException("the key proof signature is invalid");
            return proof;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("not a valid 'role key-proof' output: " + file, invalid);
        }
    }

    private static boolean matches(ActorKeyProofV1 proof, String chainId, String actorId, long revision,
                                   ActorKeyEpochV1 key) {
        ActorKeyEpochV1 proved = proof.key();
        return proof.chainId().equals(chainId) && proof.actorId().equals(actorId)
                && proof.actorRevision() == revision && proved.keyId().equals(key.keyId())
                && Arrays.equals(proved.publicKey(), key.publicKey())
                && proved.validFromHeight() == key.validFromHeight()
                && proved.validUntilHeight() == key.validUntilHeight() && proved.status() == key.status();
    }

    private static void rejectPlaceholders(JsonNode node, String path) {
        if (node.isTextual() && node.asText().startsWith("REPLACE_")) {
            throw new IllegalArgumentException("replace " + node.asText() + " at " + path + " in the plan first");
        }
        if (node.isObject()) node.fields().forEachRemaining(field ->
                rejectPlaceholders(field.getValue(), path + "." + field.getKey()));
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectPlaceholders(node.get(index), path + "[" + index + "]");
            }
        }
    }

    /** A top-level record section: absent means none, otherwise a list of objects. */
    private static List<JsonNode> records(JsonNode document, String section) {
        JsonNode value = document.get(section);
        if (value == null || value.isNull()) return List.of();
        List<JsonNode> records = new ArrayList<>();
        for (JsonNode item : array(document, section, "$")) {
            if (!item.isObject()) throw new IllegalArgumentException("$." + section + " must list objects");
            records.add(item);
        }
        return records;
    }

    private static JsonNode array(JsonNode node, String field, String at) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(at + "." + field + " must be a list, for example [a, b] or []");
        }
        return value;
    }

    private static List<String> strings(JsonNode node, String field, String at) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array(node, field, at)) {
            if (!item.isTextual()) throw new IllegalArgumentException(at + "." + field + " must list text values");
            values.add(item.asText());
        }
        return values;
    }

    private static String text(JsonNode node, String field, String at) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(at + "." + field + " is required text");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field, String at, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual()) throw new IllegalArgumentException(at + "." + field + " must be text");
        return value.asText();
    }

    private static long integer(JsonNode node, String field, String at, long minimum, long maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < minimum || value.asLong() > maximum) {
            throw new IllegalArgumentException(at + "." + field + " must be an integer from " + minimum
                    + (maximum == Long.MAX_VALUE ? "" : " to " + maximum));
        }
        return value.asLong();
    }

    private static <E extends Enum<E>> E choice(JsonNode node, String field, String at, Class<E> type) {
        String value = text(node, field, at);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) return constant;
        }
        throw new IllegalArgumentException(at + "." + field + " must be one of "
                + Arrays.toString(type.getEnumConstants()));
    }

    private static RecordStatus status(JsonNode node, String at) {
        JsonNode value = node.get("status");
        return value == null || value.isNull() ? RecordStatus.ACTIVE : choice(node, "status", at, RecordStatus.class);
    }

    private static byte[] commitment(JsonNode node, String at) {
        String value = optionalText(node, "metadataCommitment", at, "");
        if (value.isEmpty()) return new byte[0];
        return hex32(node, "metadataCommitment", at);
    }

    private static byte[] hex32(JsonNode node, String field, String at) {
        String value = text(node, field, at);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(at + "." + field + " must be 64 lowercase hex characters");
        }
        return HEX.parseHex(value);
    }

    private static JsonNode missingGovernance() {
        throw new IllegalArgumentException("$.governance is required");
    }

    private static long parsePositive(String value, String field, long maximum) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= 1 && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException(field + " must be an integer from 1"
                + (maximum == Long.MAX_VALUE ? "" : " to " + maximum));
    }
}
