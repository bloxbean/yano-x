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
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a generated {@code bootstrap/role-approvals-plan.yaml} into the member-governance commands that create its
 * organizations, actors and policies: for each record, the mutation, its hash, and the encoded propose, approve and
 * activate commands with the topic they go to. Nothing is submitted and no secret is read; actors prove possession
 * of their keys with {@code role key-proof} beforehand.
 */
final class RoleBootstrapPlan {
    static final String USAGE = """
            Usage: ./yano.sh appchain role bootstrap --plan <bootstrap/role-approvals-plan.yaml>
                     --expiry-height <n> [--key-proof <file>]...
            Prints, for each organization, actor and policy in the plan, the governed mutation and the hex
            propose, approve and activate commands with their topic. Each --key-proof file holds the hex output
            of 'role key-proof' for one actor key the plan lists. --expiry-height is the app height after which
            an unfinished proposal expires; use the chain's current height plus at most the configured
            machines.composite.roles.maximum-mutation-lifetime-blocks.""";
    private static final HexFormat HEX = HexFormat.of();
    private static final int MAX_PLAN_BYTES = 1_048_576;
    private static final int MAX_KEY_PROOFS = 1_024;

    private RoleBootstrapPlan() {
    }

    static String execute(String[] args) throws IOException {
        Path plan = null;
        Long expiryHeight = null;
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
                    expiryHeight = positive(value, "--expiry-height");
                }
                case "--key-proof" -> {
                    if (keyProofFiles.size() == MAX_KEY_PROOFS) throw new IllegalArgumentException("too many key proofs");
                    keyProofFiles.add(Path.of(value));
                }
                default -> throw new IllegalArgumentException("unknown option " + option);
            }
        }
        if (plan == null || expiryHeight == null) throw new IllegalArgumentException("--plan and --expiry-height are required");
        if (Files.size(plan) > MAX_PLAN_BYTES) throw new IllegalArgumentException("the plan is larger than 1 MiB");
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(plan.toFile());
        if (!"RoleBootstrapPlan".equals(document.path("kind").asText())) {
            throw new IllegalArgumentException("not a RoleBootstrapPlan: " + plan);
        }
        rejectPlaceholders(document, "$");
        String chainId = required(document, "chainId");
        List<ActorKeyProofV1> proofs = new ArrayList<>();
        for (Path file : keyProofFiles) {
            try {
                ActorKeyProofV1 proof = ActorKeyProofV1.decode(HEX.parseHex(Files.readString(file).trim()));
                if (!proof.verify()) throw new IllegalArgumentException("the key proof signature is invalid");
                proofs.add(proof);
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("not a valid 'role key-proof' output: " + file, invalid);
            }
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        for (JsonNode organization : document.path("organizations")) {
            OrganizationRecordV1 record = new OrganizationRecordV1(required(organization, "organizationId"),
                    revision(organization), status(organization), commitment(organization));
            steps.add(step("organization", record.organizationId(), record.revision(),
                    ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC,
                    new RegistryMutationV1.PutOrganization(record).encode(), expiryHeight));
        }
        for (JsonNode actor : document.path("actors")) {
            String actorId = required(actor, "actorId");
            long revision = revision(actor);
            List<String> roles = new ArrayList<>();
            actor.path("roles").forEach(role -> roles.add(role.asText()));
            List<ActorKeyEpochV1> keys = new ArrayList<>();
            List<ActorKeyProofV1> actorProofs = new ArrayList<>();
            for (JsonNode key : actor.path("publicKeys")) {
                ActorKeyEpochV1 epoch = new ActorKeyEpochV1(required(key, "keyId"),
                        HEX.parseHex(required(key, "publicKey")), key.path("validFromHeight").asLong(1),
                        key.path("validUntilHeight").asLong(0), status(key));
                keys.add(epoch);
                ActorKeyProofV1 proof = proofs.stream().filter(candidate -> matches(candidate, chainId, actorId,
                        revision, epoch)).findFirst().orElse(null);
                if (proof != null) {
                    actorProofs.add(proof);
                } else if (!"OPTIONAL".equals(key.path("proofOfPossession").asText("REQUIRED"))) {
                    throw new IllegalArgumentException("no --key-proof for actor " + actorId + " key "
                            + epoch.keyId() + "; the actor creates it with: ./yano.sh appchain role key-proof"
                            + " --chain " + chainId + " --actor " + actorId + " --actor-revision " + revision
                            + " --key " + epoch.keyId() + " --public-key " + HEX.formatHex(epoch.publicKey())
                            + " --valid-from-height " + epoch.validFromHeight() + " --valid-until-height "
                            + epoch.validUntilHeight() + " --seed-file <seed-file>");
                }
            }
            ActorRecordV1 record = new ActorRecordV1(actorId, required(actor, "organizationId"), revision,
                    status(actor), roles, keys, commitment(actor));
            steps.add(step("actor", actorId, revision, ActorGovernanceCommandV1.ACTOR_REGISTRY_TOPIC,
                    new RegistryMutationV1.PutActor(record, actorProofs).encode(), expiryHeight));
        }
        for (JsonNode policy : document.path("policies")) {
            List<String> proposerRoles = new ArrayList<>();
            policy.path("proposerRoles").forEach(role -> proposerRoles.add(role.asText()));
            List<ApprovalPolicyV1.RequiredClause> clauses = new ArrayList<>();
            for (JsonNode clause : policy.path("clauses")) {
                clauses.add(new ApprovalPolicyV1.RequiredClause(required(clause, "clauseId"),
                        required(clause, "role"), clause.path("minimumCount").asInt(),
                        ApprovalPolicyV1.DistinctBy.valueOf(required(clause, "distinctBy")
                                .toUpperCase(Locale.ROOT))));
            }
            ApprovalPolicyV1 record = new ApprovalPolicyV1(required(policy, "policyId"), revision(policy),
                    status(policy), proposerRoles, clauses,
                    ApprovalPolicyV1.RejectionMode.valueOf(required(policy, "rejectionMode")
                            .toUpperCase(Locale.ROOT)),
                    policy.path("maximumLifetimeBlocks").asLong());
            steps.add(step("policy", record.policyId(), record.revision(), SignedActorCommandV1.DEFAULT_TOPIC,
                    new PolicyMutationV1.PutPolicy(record).encode(), expiryHeight));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chainId);
        result.put("threshold", document.path("governance").path("threshold").asInt());
        result.put("expiryHeight", expiryHeight);
        result.put("procedure", "For each step in order: query the record and skip it if that exact revision is "
                + "committed. Otherwise submit propose through one member, which counts as its approval, then "
                + "approve through each further member until the threshold, then activate; wait for each "
                + "message to be final before the next.");
        result.put("steps", steps);
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(result);
    }

    private static Map<String, Object> step(String record, String id, long revision, String topic,
                                            byte[] mutation, long expiryHeight) {
        String mutationId = mutationId(record, id, revision);
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

    /** {@code bootstrap-<record>-<id>-r<revision>}, hashed when the identifier grammar's 63 characters run out. */
    static String mutationId(String record, String id, long revision) {
        String readable = "bootstrap-" + record + "-" + id + "-r" + revision;
        if (readable.length() <= 63) return readable;
        String digest = AppChainProjectCatalog.sha256((record + "\0" + id).getBytes(StandardCharsets.UTF_8));
        return "bootstrap-" + record + "-" + digest.substring(0, 24) + "-r" + revision;
    }

    private static boolean matches(ActorKeyProofV1 proof, String chainId, String actorId, long revision,
                                   ActorKeyEpochV1 key) {
        ActorKeyEpochV1 proved = proof.key();
        return proof.chainId().equals(chainId) && proof.actorId().equals(actorId)
                && proof.actorRevision() == revision && proved.keyId().equals(key.keyId())
                && java.util.Arrays.equals(proved.publicKey(), key.publicKey())
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
            for (int index = 0; index < node.size(); index++) rejectPlaceholders(node.get(index), path + "[" + index + "]");
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("the plan is missing " + field);
        return value;
    }

    private static long revision(JsonNode node) {
        return positive(node.path("revision").asText("1"), "revision");
    }

    private static RecordStatus status(JsonNode node) {
        return RecordStatus.valueOf(node.path("status").asText("ACTIVE").toUpperCase(Locale.ROOT));
    }

    private static byte[] commitment(JsonNode node) {
        String value = node.path("metadataCommitment").asText("");
        return value.isEmpty() ? new byte[0] : HEX.parseHex(value);
    }

    private static long positive(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= 1) return parsed;
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException(field + " must be a positive integer");
    }
}
