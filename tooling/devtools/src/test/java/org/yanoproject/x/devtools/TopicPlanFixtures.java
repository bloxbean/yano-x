package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.yanoproject.api.appchain.topics.TopicWire.ActiveRevision;
import org.yanoproject.api.appchain.topics.TopicWire.Config;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.EntryContent;
import org.yanoproject.api.appchain.topics.TopicWire.EntryRecord;
import org.yanoproject.api.appchain.topics.TopicWire.KeySet;
import org.yanoproject.api.appchain.topics.TopicWire.Policy;
import org.yanoproject.api.appchain.topics.TopicWire.Settings;
import org.yanoproject.api.appchain.topics.TopicWire.Writers;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.x.client.Hex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** CLI/Studio conformance oracle: deterministic inputs and the authoritative compiler's unsigned plans. */
public final class TopicPlanFixtures {
    static final String KEY = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8";
    static final String GENERATION = "22".repeat(32);
    static final String ROOT = "33".repeat(32);
    static final String CHAIN = "topics-demo";
    private static final byte[] ISSUER_SEED = Hex.decode("09".repeat(32));
    static final String ISSUER = Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(ISSUER_SEED));

    private TopicPlanFixtures() {
    }

    static TopicPlan.Snapshot snapshot() {
        KeySet owner = new KeySet(1, List.of(KEY));
        Settings settings = new Settings(true, owner, true, true, 3_600_000, 3_600_000,
                16, 1_024, 128, 2, 600);
        EntryRecord games = new EntryRecord("games/", new ActiveRevision(1, 2, 1,
                new EntryContent(owner, new Policy(Writers.OPEN, List.of(), null, "", null), false)), 1, 0, 1);
        return new TopicPlan.Snapshot(CHAIN, GENERATION, 10, ROOT, 65_536,
                new Config(1, 1, settings), Map.of("games/", games), Map.of());
    }

    static ObjectNode document(String target, String operation, String authority) {
        ObjectNode document = TopicPlan.JSON.createObjectNode();
        document.put("schemaVersion", 1).put("kind", "Topics").put("chainId", CHAIN);
        ObjectNode command = document.putArray("commands").addObject().put("target", target)
                .put("operation", operation).put("authority", authority).put("nonce", "44".repeat(16));
        if (!"governance".equals(authority)) command.put("notAfter", 1_000_000);
        if (List.of("create", "rotate", "recover").contains(operation)) {
            command.set("owners", TopicPlan.JSON.valueToTree(new KeySet(1, List.of(KEY))));
        }
        return document;
    }

    static ArrayNode fixtures() {
        ArrayNode fixtures = TopicPlan.JSON.createArrayNode();
        ObjectNode governance = document("games-new/", "create", "governance");
        ((ObjectNode) governance.path("commands").get(0)).putObject("policy")
                .put("writers", "open").put("schema", "none");
        add(fixtures, "governance-create", governance, snapshot());

        ObjectNode update = document("games/", "update", "games/");
        ObjectNode policy = ((ObjectNode) update.path("commands").get(0)).putObject("policy");
        policy.set("writers", TopicPlan.JSON.valueToTree(new KeySet(1, List.of(KEY))));
        policy.putArray("delegate").add(KEY);
        policy.put("maxDelegationMs", 3_600_000).put("maxPayloadBytes", 1_024)
                .put("schemaCddl", "root = { frames: 1200..9000, name: tstr .size (1..32) }");
        add(fixtures, "owner-update-schema", update, snapshot());

        ObjectNode child = document("games/neon-rush/", "create", "games/");
        add(fixtures, "inherited-child", child, snapshot());
        ObjectNode claim = document("claimed/", "create", "~claim");
        add(fixtures, "open-claim", claim, snapshot());
        ObjectNode registrar = document("registered/", "create", "~registrars");
        add(fixtures, "registrar-create", registrar, snapshot());
        add(fixtures, "owner-revoke", revocation("games/"), revocationSnapshot(false));
        add(fixtures, "issuer-revoke", revocation("~issuer"), revocationSnapshot(false));
        add(fixtures, "retired-owner-revoke", revocation("games/"), revocationSnapshot(true));
        return fixtures;
    }

    static ObjectNode revocation(String authority) {
        // Synthetic deterministic fixture key: only public credential bytes are written to the oracle.
        Delegation unsigned = new Delegation(KEY, "games/neon-rush/", 1000, 3_601_000, ISSUER, new byte[64]);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                TopicWireCodec.delegationSignedBytes(CHAIN, GENERATION, unsigned), ISSUER_SEED);
        Delegation delegation = new Delegation(unsigned.delegate(), unsigned.scope(), unsigned.notBefore(),
                unsigned.notAfter(), ISSUER, signature);
        ObjectNode document = document(delegation.scope(), "revoke", authority);
        ((ObjectNode) document.path("commands").get(0)).put("delegationHex",
                Hex.encode(TopicWireCodec.encodeDelegation(delegation)));
        return document;
    }

    static TopicPlan.Snapshot revocationSnapshot(boolean retired) {
        var snapshot = snapshot();
        var owner = snapshot.entries().get("games/").active().content().owners();
        var policy = new Policy(new Writers(new KeySet(1, List.of(ISSUER))), List.of(ISSUER),
                3_600_000L, "", null);
        var entry = new EntryRecord("games/", new ActiveRevision(1, 2, 1,
                new EntryContent(owner, policy, retired)), 1, 0, 1);
        return new TopicPlan.Snapshot(CHAIN, GENERATION, snapshot.height(), ROOT, snapshot.maxMessageBytes(),
                snapshot.config(), Map.of("games/", entry), Map.of());
    }

    private static void add(ArrayNode fixtures, String name, JsonNode document, TopicPlan.Snapshot snapshot) {
        ObjectNode fixture = fixtures.addObject().put("name", name);
        fixture.set("document", document);
        fixture.set("snapshot", TopicPlan.JSON.valueToTree(snapshot));
        fixture.set("plan", TopicPlan.compile(document, snapshot));
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, TopicPlan.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(fixtures()) + "\n");
    }
}
