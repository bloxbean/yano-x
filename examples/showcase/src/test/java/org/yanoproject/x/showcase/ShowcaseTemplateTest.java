package org.yanoproject.x.showcase;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.appchain.formats.Draft;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorKeyProofV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-033 catalog parity for the light showcase's templates, and the demo key material they pin. */
class ShowcaseTemplateTest {
    private static final Path CONFIG = Path.of("src/main/showcase/config");
    private static final String SETTLEMENT = "payment-chain-settlement";
    private static final String HISTORY = "cardano-history-chain";

    @Test
    void catalogIsTheOrderedThirteenChainSourceOfTruthForTheTemplates() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONFIG.resolve("showcase-catalog-v1.json")));
        String capabilityGuide = Files.readString(Path.of("docs/CAPABILITY_CATALOG.md"));
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(root.path("profileId").asText()).isEqualTo("light-v1");
        List<String> catalogIds = new ArrayList<>();
        Map<String, Integer> classifications = new HashMap<>();
        for (JsonNode chain : root.path("chains")) {
            catalogIds.add(chain.path("chainId").asText());
            assertThat(capabilityGuide).contains("`" + chain.path("chainId").asText() + "`");
            assertThat(chain.path("scenarioId").asText()).isNotBlank();
            classifications.merge(chain.path("classification").asText(), 1, Integer::sum);
            assertThat(chain.path("proofBackend").asText()).isIn("mpf", "jmt");
            assertThat(chain.path("expectedCapabilities").size()).isPositive();
        }
        assertThat(classifications).containsExactlyInAnyOrderEntriesOf(Map.of(
                "foundation", 7, "reference", 3, "backend-comparison", 1, "l1-boundary", 1, "product", 1));
        List<String> showcase = template("showcase.template.yaml").chains().stream().map(Draft.Chain::id).toList();
        assertThat(template("settlement.template.yaml").chains()).extracting(Draft.Chain::id)
                .containsExactly(SETTLEMENT);
        assertThat(template("history.template.yaml").chains()).extracting(Draft.Chain::id).containsExactly(HISTORY);
        // The two L1-driven chains are applications of their own; every other chain keeps the catalog's order.
        assertThat(catalogIds).hasSize(13).contains(SETTLEMENT, HISTORY);
        assertThat(showcase).containsExactlyElementsOf(catalogIds.stream()
                .filter(id -> !id.equals(SETTLEMENT) && !id.equals(HISTORY)).toList());
    }

    @Test
    void eachChainNamesItsStateMachine() throws Exception {
        Map<String, String> machines = new LinkedHashMap<>();
        for (String file : List.of("showcase.template.yaml", "settlement.template.yaml", "history.template.yaml")) {
            for (Draft.Chain chain : template(file).chains()) {
                machines.put(chain.id(), "custom-plugin".equals(chain.recipe())
                        ? chain.answers().get("stateMachine") : chain.recipe());
            }
        }
        assertThat(machines).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("orders-chain", "audit-log"),
                Map.entry("registry-chain", "owned-registry"),
                Map.entry("approvals-chain", "approval-workflow"),
                Map.entry("balances-chain", "balances"),
                Map.entry("documents-chain", "document-trail"),
                Map.entry("workflow-chain", "showcase-composite"),
                Map.entry("roles-chain", "role-approval"),
                Map.entry("payments-chain", "eutxo-ledger"),
                Map.entry("authenticated-map-chain", "authenticated-map"),
                Map.entry("authenticated-map-jmt-chain", "authenticated-map"),
                Map.entry(SETTLEMENT, "eutxo-ledger"),
                Map.entry("document-review-chain", "document-review"),
                Map.entry("cardano-history-chain", "cardano-history")));
        Draft.Chain workflow = chain("workflow-chain");
        assertThat(workflow.settings()).containsEntry("machines.composite.preset", "order-approval-outbox-v1")
                .containsEntry("effects.enabled", "true");
        assertThat(template("history.template.yaml").chains().getFirst().settings())
                .containsEntry("machines.cardano-history.preset", "params-only-v1")
                .containsEntry("state.l1-proof-consumption-required", "true");
    }

    @Test
    void bothMapsShareOneBusinessProfileOnTheirOwnProofBackend() throws Exception {
        JsonNode mpf = chain("authenticated-map-chain").authenticatedMap();
        JsonNode jmt = chain("authenticated-map-jmt-chain").authenticatedMap();
        assertThat(mpf.path("profile").asText()).isEqualTo(AuthenticatedMapContract.PROFILE_MPF_BLAKE2B256_V1);
        assertThat(jmt.path("profile").asText()).isEqualTo(AuthenticatedMapContract.PROFILE_JMT_BLAKE2B256_V1);
        for (String field : List.of("collections", "schemas", "validators", "authorizationGovernance")) {
            assertThat(jmt.get(field)).as(field).isEqualTo(mpf.get(field));
        }
        assertThat(mpf.path("validators").get(0).path("bundle").asText())
                .isEqualTo("org.yanoproject.x.authenticated-map-validators");
    }

    /** Each pinned proof is the actor's own signature over this chain's id, made with its public demo seed. */
    @Test
    void actorKeysAndProofsComeFromTheDemoSeeds() throws Exception {
        for (String chainId : List.of("authenticated-map-chain", "authenticated-map-jmt-chain")) {
            JsonNode actors = chain(chainId).authenticatedMap().path("genesisRecords").path("actors");
            assertThat(actors).hasSize(4);
            for (JsonNode actor : actors) {
                String id = actor.path("id").asText();
                JsonNode key = actor.path("keys").get(0);
                byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(demoActorSeed(id));
                assertThat(key.path("publicKey").asText()).as(id).isEqualTo(HexFormat.of().formatHex(publicKey));
                ActorKeyEpochV1 epoch = new ActorKeyEpochV1(key.path("id").asText(), publicKey,
                        key.path("validFromHeight").asLong(), key.path("validUntilHeight").asLong(),
                        RecordStatus.ACTIVE);
                ActorKeyProofV1 proof = new ActorKeyProofV1(chainId, id, actor.path("revision").asLong(), epoch,
                        HexFormat.of().parseHex(key.path("proofOfPossession").asText()));
                assertThat(proof.verify()).as(chainId + " " + id).isTrue();
            }
        }
    }

    @Test
    void demoActorSeedsMatchTheShellDerivationContract() throws Exception {
        // showcase.sh derives the identical value with:
        //   printf 'yano-showcase-demo-actor:issuer-a' | shasum -a 256
        assertThat(HexFormat.of().formatHex(demoActorSeed("issuer-a")))
                .isEqualTo("014e1f28afa92a08710af43d06117868d84bf83185f3a4db14eb358e2e8a468a");
        // tools/showcase_signer.py derives the same public key for that seed.
        assertThat(HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(demoActorSeed("issuer-a"))))
                .isEqualTo("67ddc1ac79b76d0e6abae1c133195fa48904948af3b202b761a4c727c4e169ff");
    }

    @Test
    void basicEnvelopeWrappingMatchesTheCompositeAdmissionContract() {
        // showcase.sh authmap_basic_body wraps the codec's legacy command into the final v1 envelope with exactly
        // these CLI invocations; the composite machine admits only the final envelope.
        String legacy = HexFormat.of().formatHex(AuthenticatedMapContract.encodeCommand(
                AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.put("attachments",
                        "demo-key".getBytes(StandardCharsets.UTF_8), "demo-value".getBytes(StandardCharsets.UTF_8)))));
        String action = org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationCli.execute(new String[] {
                "action", "--command-hex", legacy, "--assignments", "0:owner::0"});
        String wrapped = org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationCli.execute(new String[] {
                "command", "--action-hex", action, "--evidence-hex", ""});
        var decoded = org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.decodeCommand(
                HexFormat.of().parseHex(wrapped));
        assertThat(decoded.action().mutations()).hasSize(1);
        assertThat(decoded.evidence()).isEmpty();
        // Cross-language golden vector shared with the console's TS encoder and the python codec.
        assertThat(wrapped).isEqualTo("8301582f8401008187006b6174746163686d656e74734864656d6f2d6b65794a64656d6f2d76"
                + "616c756500404081840001600080");
    }

    private static Draft.Template template(String file) throws Exception {
        return Draft.template(Files.readString(CONFIG.resolve(file)));
    }

    private static Draft.Chain chain(String id) throws Exception {
        return template("showcase.template.yaml").chains().stream().filter(chain -> chain.id().equals(id))
                .findFirst().orElseThrow();
    }

    static byte[] demoActorSeed(String actorId) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(("yano-showcase-demo-actor:" + actorId).getBytes(StandardCharsets.UTF_8));
    }
}
