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
        String chainId = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(plan.toFile()).path("chainId").asText();
        ActorKeyEpochV1 proposerKey = key("proposer-key-v1", proposerSeed);
        ActorKeyEpochV1 reviewerKey = key("reviewer-key-v1", reviewerSeed);
        ActorKeyProofV1 proposerProof = ActorKeyProofV1.sign(chainId, "proposer-a", 1, proposerKey, proposerSeed);
        ActorKeyProofV1 reviewerProof = ActorKeyProofV1.sign(chainId, "reviewer-a", 1, reviewerKey, reviewerSeed);

        JsonNode result = run(0, "--plan", plan.toString(), "--expiry-height", "500",
                "--key-proof", write("proposer.hex", proposerProof), "--key-proof", write("reviewer.hex", reviewerProof));

        assertThat(result.path("threshold").asInt()).isEqualTo(2);
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

        // A proof signed by another seed for the same public key does not verify.
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
    void mutationIdsStayWithinTheIdentifierGrammar() {
        assertThat(RoleBootstrapPlan.mutationId("organization", "organization-a", 1))
                .isEqualTo("bootstrap-organization-organization-a-r1");
        String longId = "a" + "b".repeat(62);
        assertThat(RoleBootstrapPlan.mutationId("organization", longId, 12))
                .matches("[a-z][a-z0-9-]{0,62}")
                .isNotEqualTo(RoleBootstrapPlan.mutationId("organization", longId.substring(1) + "c", 12));
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
