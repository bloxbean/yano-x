package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.GovernedMutationCommandV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.PolicyMutationV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RegistryMutationV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleBootstrapPlanTest {
    private static final HexFormat HEX = HexFormat.of();

    @TempDir
    Path temporary;

    @Test
    void encodesTheGeneratedPlanAsGovernedMutations() throws Exception {
        Path project = initializeRoleApproval();
        byte[] proposerSeed = seed(0x11);
        byte[] reviewerSeed = seed(0x22);
        Path plan = fill(project, proposerSeed, reviewerSeed);
        String chainId = new ObjectMapper(new YAMLFactory())
                .readTree(plan.toFile()).path("chainId").asText();
        ActorKeyEpochV1 proposerKey = key("proposer-key-v1", proposerSeed);
        ActorKeyEpochV1 reviewerKey = key("reviewer-key-v1", reviewerSeed);
        ActorKeyProofV1 proposerProof = ActorKeyProofV1.sign(chainId, "proposer-a", 1, proposerKey, proposerSeed);
        ActorKeyProofV1 reviewerProof = ActorKeyProofV1.sign(chainId, "reviewer-a", 1, reviewerKey, reviewerSeed);

        JsonNode result = run(0, "--plan", plan.toString(), "--expiry-height", "500",
                "--key-proof", write("proposer.hex", proposerProof),
                "--key-proof", write("reviewer.hex", reviewerProof));

        assertThat(result.path("planThreshold").asInt()).isEqualTo(2);
        List<String> order = new ArrayList<>();
        result.path("steps").forEach(step -> order.add(step.path("record").asText() + ":" + step.path("id").asText()));
        assertThat(order).containsExactly("organization:organization-a", "organization:organization-b",
                "actor:proposer-a", "actor:reviewer-a", "policy:application-approval");

        expect(result.path("steps").get(0), "actors.command.v1", "bootstrap-organization-organization-a-r1",
                new RegistryMutationV1.PutOrganization(new OrganizationRecordV1(
                        "organization-a", 1, RecordStatus.ACTIVE, new byte[0])).encode());
        expect(result.path("steps").get(2), "actors.command.v1", "bootstrap-actor-proposer-a-r1",
                new RegistryMutationV1.PutActor(new ActorRecordV1("proposer-a", "organization-a", 1,
                        RecordStatus.ACTIVE, List.of("proposer"), List.of(proposerKey), new byte[0]),
                        List.of(proposerProof)).encode());
        expect(result.path("steps").get(4), "role-approvals.command.v1", "bootstrap-policy-application-approval-r1",
                new PolicyMutationV1.PutPolicy(new ApprovalPolicyV1("application-approval", 1, RecordStatus.ACTIVE,
                        List.of("proposer"), List.of(new ApprovalPolicyV1.RequiredClause("reviewers", "reviewer", 1,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)), ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE,
                        1000)).encode());
    }

    @Test
    void refusesPlaceholdersAndMissingOrForgedKeyProofs() throws Exception {
        Path project = initializeRoleApproval();
        Path untouched = project.resolve("bootstrap/role-approvals-plan.yaml");
        assertThat(run(64, "--plan", untouched.toString(), "--expiry-height", "500").path("error").asText())
                .contains("replace REPLACE_");

        Path plan = fill(project, seed(0x11), seed(0x22));
        assertThat(run(64, "--plan", plan.toString(), "--expiry-height", "500").path("error").asText())
                .contains("no --key-proof for actor proposer-a key proposer-key-v1")
                .contains("./yano.sh appchain role key-proof --chain");

        // A proof whose signature bytes were altered does not verify.
        ActorKeyProofV1 honest = ActorKeyProofV1.sign("x", "proposer-a", 1, key("proposer-key-v1", seed(0x11)),
                seed(0x11));
        byte[] encoded = honest.encode();
        encoded[encoded.length - 1] ^= 1;
        Path forged = temporary.resolve("forged.hex");
        Files.writeString(forged, HEX.formatHex(encoded));
        assertThat(run(64, "--plan", plan.toString(), "--expiry-height", "500", "--key-proof", forged.toString())
                .path("error").asText()).contains("not a valid 'role key-proof' output");
    }

    @Test
    void mutationIdsStayWithinTheIdentifierGrammarAndNeverCollide() {
        assertThat(RoleBootstrapPlan.mutationId("organization", "organization-a", 1, 1))
                .isEqualTo("bootstrap-organization-organization-a-r1");
        assertThat(RoleBootstrapPlan.mutationId("organization", "organization-a", 1, 2))
                .isEqualTo("bootstrap-organization-organization-a-r1-a2");
        String longId = "a" + "b".repeat(61);
        String hashed = RoleBootstrapPlan.mutationId("organization", longId, 1, 1);
        assertThat(hashed).matches("[a-z][a-z0-9-]{0,62}").startsWith("bootstrap-hashed-organization-");
        // A readable id equal to the hashed id's digest cannot produce the same mutation id.
        String digest = hashed.substring("bootstrap-hashed-organization-".length());
        assertThat(RoleBootstrapPlan.mutationId("organization", digest, 1, 1)).isNotEqualTo(hashed);
        assertThat(RoleBootstrapPlan.mutationId("organization", longId, Long.MAX_VALUE, 999))
                .matches("[a-z][a-z0-9-]{0,62}");
    }

    @Test
    void parsesThePlanStrictly() throws Exception {
        Path project = initializeRoleApproval();
        byte[] proposerSeed = seed(0x11);
        byte[] reviewerSeed = seed(0x22);
        Path plan = fill(project, proposerSeed, reviewerSeed);
        String chainId = new ObjectMapper(new YAMLFactory()).readTree(plan.toFile()).path("chainId").asText();
        String[] proofs = {"--key-proof", write("p.hex", ActorKeyProofV1.sign(chainId, "proposer-a", 1,
                key("proposer-key-v1", proposerSeed), proposerSeed)), "--key-proof", write("r.hex",
                ActorKeyProofV1.sign(chainId, "reviewer-a", 1, key("reviewer-key-v1", reviewerSeed), reviewerSeed))};
        String text = Files.readString(plan);

        // A scalar where a list belongs would otherwise drop the proposer restriction.
        assertThat(error(edited(text, "proposerRoles: [proposer]", "proposerRoles: proposer"), proofs))
                .contains("policies[application-approval].proposerRoles must be a list");
        assertThat(error(edited(text, "minimumCount: 1", "minimumCount: 1.9"), proofs))
                .contains("minimumCount must be an integer from 1");
        assertThat(error(edited(text, "minimumCount: 1", "minimumCount: 4294967297"), proofs))
                .contains("minimumCount must be an integer from 1 to 2147483647");
        assertThat(error(edited(text, "distinctBy: ORGANIZATION", "distinctBy: organisation"), proofs))
                .contains("distinctBy must be one of");
        assertThat(error(edited(text, "organizationId: organization-b", "organizationId: Organization-B"), proofs))
                .contains("organizations[Organization-B] is refused by the role-workflow contracts (INVALID_PAYLOAD)");
    }

    @Test
    void attachesProofsOnlyForTheKeysARevisionAdds() throws Exception {
        Path project = initializeRoleApproval();
        byte[] proposerSeed = seed(0x11);
        byte[] reviewerSeed = seed(0x22);
        Path plan = fill(project, proposerSeed, reviewerSeed);
        String chainId = new ObjectMapper(new YAMLFactory()).readTree(plan.toFile()).path("chainId").asText();
        String reviewerProof = write("r1.hex", ActorKeyProofV1.sign(chainId, "reviewer-a", 1,
                key("reviewer-key-v1", reviewerSeed), reviewerSeed));
        String text = Files.readString(plan);

        // Revision 1: every key is new, so OPTIONAL is refused.
        String optional = edited(text, "        proofOfPossession: REQUIRED\n",
                "        proofOfPossession: OPTIONAL\n");
        assertThat(error(optional, "--key-proof", reviewerProof)).contains("every key of a revision-1 actor is new");

        // Revision 2 keeps proposer-key-v1 (OPTIONAL) and adds proposer-key-v2, which alone carries a proof.
        byte[] newSeed = seed(0x33);
        String update = text.substring(0, text.indexOf("  - actorId: reviewer-a"))
                .replace("    revision: 1\n    roles: [proposer]", "    revision: 2\n    roles: [proposer]")
                .replace("        proofOfPossession: REQUIRED\n", "        proofOfPossession: OPTIONAL\n"
                        + "      - keyId: proposer-key-v2\n        publicKey: " + publicKey(newSeed)
                        + "\n        validFromHeight: 1\n        validUntilHeight: 0\n"
                        + "        proofOfPossession: REQUIRED\n")
                + text.substring(text.indexOf("policies:"));
        String added = write("p2.hex", ActorKeyProofV1.sign(chainId, "proposer-a", 2, key("proposer-key-v2", newSeed),
                newSeed));
        Path updated = temporary.resolve("update.yaml");
        Files.writeString(updated, update.replaceAll("(?s)organizations:.*?actors:", "organizations: []\nactors:"));
        JsonNode result = run(0, "--plan", updated.toString(), "--expiry-height", "500", "--key-proof", added);
        assertThat(result.path("steps").get(0).path("keyProofs").toString()).isEqualTo("[\"proposer-key-v2\"]");

        // A proof for the kept key is refused: the registry wants proofs only for added keys.
        String kept = write("p1.hex", ActorKeyProofV1.sign(chainId, "proposer-a", 2, key("proposer-key-v1",
                proposerSeed), proposerSeed));
        assertThat(run(64, "--plan", updated.toString(), "--expiry-height", "500", "--key-proof", added,
                "--key-proof", kept).path("error").asText()).contains("must not have a --key-proof");
    }

    @Test
    void refusesDuplicateRecordsAndUnusedOrOversizedProofs() throws Exception {
        Path project = initializeRoleApproval();
        byte[] proposerSeed = seed(0x11);
        byte[] reviewerSeed = seed(0x22);
        Path plan = fill(project, proposerSeed, reviewerSeed);
        String chainId = new ObjectMapper(new YAMLFactory()).readTree(plan.toFile()).path("chainId").asText();
        String[] proofs = {"--key-proof", write("p.hex", ActorKeyProofV1.sign(chainId, "proposer-a", 1,
                key("proposer-key-v1", proposerSeed), proposerSeed)), "--key-proof", write("r.hex",
                ActorKeyProofV1.sign(chainId, "reviewer-a", 1, key("reviewer-key-v1", reviewerSeed), reviewerSeed))};
        String text = Files.readString(plan);

        String duplicate = text.replace("    metadataCommitment: \"\"\n  - organizationId: organization-b",
                "    metadataCommitment: \"\"\n  - organizationId: organization-a\n    revision: 1\n"
                        + "    status: SUSPENDED\n    metadataCommitment: \"\"\n  - organizationId: organization-b");
        assertThat(error(duplicate, proofs)).contains("lists organization organization-a revision 1 more than once");

        String stray = write("stray.hex", ActorKeyProofV1.sign("another-chain", "proposer-a", 1,
                key("proposer-key-v1", proposerSeed), proposerSeed));
        String[] withStray = java.util.Arrays.copyOf(proofs, proofs.length + 2);
        withStray[proofs.length] = "--key-proof";
        withStray[proofs.length + 1] = stray;
        assertThat(error(text, withStray)).contains("matches no key in the plan");

        Path oversized = temporary.resolve("oversized.hex");
        Files.writeString(oversized, "0".repeat(20_000));
        assertThat(error(text, "--key-proof", oversized.toString())).contains("is larger than 16384 bytes");
    }

    private String error(String plan, String... options) throws Exception {
        Path file = temporary.resolve("edited-" + System.nanoTime() + ".yaml");
        Files.writeString(file, plan);
        String[] args = new String[options.length + 4];
        args[0] = "--plan";
        args[1] = file.toString();
        args[2] = "--expiry-height";
        args[3] = "500";
        System.arraycopy(options, 0, args, 4, options.length);
        return run(64, args).path("error").asText();
    }

    private static String edited(String text, String from, String to) {
        assertThat(text).contains(from);
        return text.replace(from, to);
    }

    private static void expect(JsonNode step, String topic, String mutationId, byte[] mutation) {
        GovernedMutationCommandV1.Propose propose = new GovernedMutationCommandV1.Propose(mutationId, mutation, 500);
        assertThat(step.path("topic").asText()).isEqualTo(topic);
        assertThat(step.path("mutationId").asText()).isEqualTo(mutationId);
        assertThat(step.path("mutation").asText()).isEqualTo(HEX.formatHex(mutation));
        assertThat(step.path("mutationHash").asText()).isEqualTo(HEX.formatHex(propose.mutationHash()));
        assertThat(step.path("propose").asText()).isEqualTo(HEX.formatHex(propose.encode()));
        assertThat(step.path("approve").asText()).isEqualTo(HEX.formatHex(
                new GovernedMutationCommandV1.Approve(mutationId, propose.mutationHash()).encode()));
        assertThat(step.path("activate").asText()).isEqualTo(HEX.formatHex(
                new GovernedMutationCommandV1.Activate(mutationId, propose.mutationHash()).encode()));
    }

    private JsonNode run(int expectedExit, String... options) throws Exception {
        String[] args = new String[options.length + 3];
        args[0] = "appchain";
        args[1] = "role";
        args[2] = "bootstrap";
        System.arraycopy(options, 0, args, 3, options.length);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(args, new PrintWriter(out), new PrintWriter(err));
        assertThat(exit).as(err.toString()).isEqualTo(expectedExit);
        if (exit != 0) {
            return new ObjectMapper().createObjectNode().put("error", err.toString());
        }
        return new ObjectMapper().readTree(out.toString());
    }

    private Path initializeRoleApproval() throws Exception {
        Path project = temporary.resolve("role-approval-" + System.nanoTime());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new AppChainDevtoolsCli().run(new String[]{"appchain", "init", "--non-interactive",
                "--recipe", "role-approval", "--network", "devnet", "--members", "3",
                "--output", project.toString()}, new PrintWriter(out), new PrintWriter(err));
        assertThat(exit).as(err.toString()).isZero();
        return project;
    }

    private Path fill(Path project, byte[] proposerSeed, byte[] reviewerSeed) throws Exception {
        Path plan = project.resolve("bootstrap/role-approvals-plan.yaml");
        String text = Files.readString(plan).replace("REPLACE_64_HEX_OR_EMPTY", "\"\"");
        text = text.replaceFirst("REPLACE_64_HEX", publicKey(proposerSeed));
        text = text.replaceFirst("REPLACE_64_HEX", publicKey(reviewerSeed));
        Path filled = temporary.resolve("plan-" + System.nanoTime() + ".yaml");
        Files.writeString(filled, text);
        return filled;
    }

    private String write(String name, ActorKeyProofV1 proof) throws Exception {
        Path file = temporary.resolve(name);
        Files.writeString(file, HEX.formatHex(proof.encode()) + "\n");
        return file.toString();
    }

    private static ActorKeyEpochV1 key(String keyId, byte[] seed) throws Exception {
        return new ActorKeyEpochV1(keyId, KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1, 0, RecordStatus.ACTIVE);
    }

    private static String publicKey(byte[] seed) throws Exception {
        return HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
