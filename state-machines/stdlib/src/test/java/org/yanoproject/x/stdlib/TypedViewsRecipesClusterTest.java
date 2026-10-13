package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.yanoproject.api.appchain.AppCapabilityManifest;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.DeclarativeRoleProviders;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActionV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract.Mutation;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema.MapField;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.4 §6 configuration examples on three real members, as committed Java IR: a tokenized asset whose
 * transfers read a governed limit and the holder's record, product-passport namespace isolation over the map's
 * write view and verified coverage, and a data feed whose observations must be the source's own insert-only slot
 * and within the feed's range; and, for Phase 6, a governed limit raised by an approved proposal without a profile
 * epoch, and a block of maximal refused write batches. Every member stores identical receipts and roots.
 */
@Timeout(300)
class TypedViewsRecipesClusterTest {
    private static final AuthenticatedMapSchema.Node UINT = AuthenticatedMapSchema.IntegerNode.uint();

    @Test
    void assetTransfersReadTheGovernedLimitAndTheHoldersRecord(@TempDir Path directory) throws Exception {
        String chain = "typed-views-asset";
        List<String> senders = senders();
        List<AuthenticatedMapContract.GenesisEntry> holders = List.of(
                holder(senders.get(0), 500, 0), holder(senders.get(1), 5_000, 1_000_000),
                new AuthenticatedMapContract.GenesisEntry("settings", bytes("transfer"), new byte[0],
                        value(Map.of("max", new UnsignedInteger(1_000)))));
        var genesis = genesis(chain, List.of(
                collection("holders", AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        DppStarterProfile.MANUFACTURER_POLICY, "holder-v1"),
                collection("settings", AuthenticatedMapContract.AUTH_APPROVAL, DppStarterProfile.CERTIFICATION_POLICY,
                        "setting-v1")), List.of(
                schema("holder-v1", new MapField("acquiredHeight", true, UINT),
                        new MapField("maxTransfer", true, UINT)),
                schema("setting-v1", new MapField("max", true, UINT))), holders);
        var holder = new BindingIrV1.Read("holder", "registry", "holders",
                new BindingSourceV1.Field(Scope.CONTEXT, "sender"));
        var rules = List.of(
                new BindingIrV1.AdmissionRule("governed-transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer",
                        List.of(), List.of(new BindingIrV1.Read("limits", "registry", "settings",
                        new BindingSourceV1.Literal("transfer"))), List.of(clause(call("le",
                        new Field(Scope.COMMAND, "amount"), Field.readValue("limits", "max"))))),
                new BindingIrV1.AdmissionRule("lock-up", "LOCKED", "transfer", List.of(new BindingIrV1.Parameter(
                        "blocks", BindingIrV1.ParameterType.INTEGER, null)), List.of(holder), List.of(clause(call(
                        "ge", new Field(Scope.CONTEXT, "height"), call("add", Field.readValue("holder",
                                "acquiredHeight"), new Field(Scope.PARAMS, "blocks")))))),
                new BindingIrV1.AdmissionRule("tier-limit", "TIER_LIMIT_EXCEEDED", "transfer", List.of(),
                        List.of(holder), List.of(
                        clause(call("and", Field.read("holder", "present"), call("eq", Field.read("holder",
                                "status"), new Literal("ACTIVE")))),
                        clause(call("le", new Field(Scope.COMMAND, "amount"), Field.readValue("holder",
                                "maxTransfer"))))));
        var token = new BindingIrV1.Component("token", "balances", "token.v1", Map.of("minter",
                new BindingSourceV1.Literal("")), 0, 1, List.of(
                new BindingIrV1.RuleAttachment("governed-transfer-limit", Map.of()),
                new BindingIrV1.RuleAttachment("tier-limit", Map.of()),
                new BindingIrV1.RuleAttachment("lock-up", Map.of("blocks", new BindingSourceV1.Literal(2L)))));
        var ir = document(genesis, token, rules, List.of());
        var ports = DeclarativeBindingsClusterTest.ports(3);
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs(chain, ir, ports), ports, directory)) {
            for (int node = 0; node < 3; node++) {
                assertAccepted(cluster, submit(cluster, node, "token.v1",
                        BalancesContract.mint(senders.get(node), BigInteger.valueOf(10_000))));
            }
            // Within the governed maximum (1000), the holder's tier (500), and past its lock-up.
            assertAccepted(cluster, submit(cluster, 0, "token.v1", transfer(senders.get(2), 100)));
            assertDenied(cluster, submit(cluster, 0, "token.v1", transfer(senders.get(2), 2_000)),
                    new RuleFailure("governed-transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED", null));
            assertDenied(cluster, submit(cluster, 0, "token.v1", transfer(senders.get(2), 700)),
                    new RuleFailure("tier-limit", 1, "TIER_LIMIT_EXCEEDED", null));
            // Acquired at height 1,000,000: locked for now.
            assertDenied(cluster, submit(cluster, 1, "token.v1", transfer(senders.get(2), 10)),
                    new RuleFailure("lock-up", 0, "LOCKED", null));
            // Not a holder: the guarded read is absent, so its first clause is false.
            assertDenied(cluster, submit(cluster, 2, "token.v1", transfer(senders.get(0), 10)),
                    new RuleFailure("tier-limit", 0, "TIER_LIMIT_EXCEEDED", null));
            // Reads make every rule a non-static admission-slot rule, which the manifest records.
            assertThat(attributes(cluster)).containsEntry("admission.token.00.slot", "admission")
                    .containsEntry("admission.token.00.static", "false")
                    .containsEntry("admission.token.00.reads", "true")
                    .containsEntry("admission.token.00.writes", "false");
            DeclarativeBindingsClusterTest.assertConvergence(cluster, new LinkedHashSet<>(members()));
        }
    }

    @Test
    void productPassportWritesStayInTheActorsOrganizationAndLifecycleOpsNeedTheRole(@TempDir Path directory)
            throws Exception {
        String chain = "typed-views-dpp";
        var genesis = DppGenesis.genesis(DppGenesis.demo(chain), members(), 2);
        Node lifecycle = call("and", call("ne", Field.element("op"), new Literal("REVOKE")),
                call("ne", Field.element("op"), new Literal("RESTORE")));
        var rules = List.of(
                new BindingIrV1.AdmissionRule("admin-only-lifecycle-ops", "ADMIN_ROLE_REQUIRED", null,
                        List.of(new BindingIrV1.Parameter("role", BindingIrV1.ParameterType.TEXT, null)),
                        List.of(clause(new Quantifier(false, call("or", lifecycle, call("and", call("eq",
                                Field.element("coverage"), new Literal("direct")), call("in",
                                new Field(Scope.PARAMS, "role"), Field.element("actorRoles")))))))),
                new BindingIrV1.AdmissionRule("manufacturer-owns-product", "FOREIGN_PRODUCT", null, List.of(),
                        List.of(clause(new Quantifier(false, call("or", call("and",
                                call("ne", Field.element("collection"), new Literal(DppStarterProfile.VERSIONS)),
                                call("ne", Field.element("collection"), new Literal(DppStarterProfile.EVENTS))),
                                call("and", call("eq", Field.element("coverage"), new Literal("direct")),
                                        call("startsWith", Field.element("keyText"), call("concat",
                                                Field.element("actorOrganizationId"), new Literal("/"))))))))));
        var ir = document(genesis, null, rules, List.of(
                new BindingIrV1.RuleAttachment("manufacturer-owns-product", Map.of()),
                new BindingIrV1.RuleAttachment("admin-only-lifecycle-ops", Map.of("role",
                        new BindingSourceV1.Literal(DppStarterProfile.MANUFACTURER_ROLE)))));
        var ports = DeclarativeBindingsClusterTest.ports(3);
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs(chain, ir, ports), ports, directory)) {
            // Reading coverage makes both rules fact rules, which the IR alone cannot show.
            assertThat(attributes(cluster)).containsEntry("admission.registry.00.slot", "fact")
                    .containsEntry("admission.registry.00.static", "false")
                    .containsEntry("admission.registry.00.reads", "false")
                    .containsEntry("admission.registry.00.writes", "true")
                    .containsEntry("admission.registry.01.slot", "fact");
            var events = new Writer(genesis, DppStarterProfile.EVENTS, DppStarterProfile.OPERATOR_POLICY);
            assertAccepted(cluster, submit(cluster, 0, "registry.v1", events.put("logistics-a",
                    "swift-logistics/p1/e1", event("swift-logistics"))));
            assertDenied(cluster, submit(cluster, 0, "registry.v1", events.put("logistics-a",
                    "acme-manufacturing/p1/e2", event("swift-logistics"))),
                    new RuleFailure("manufacturer-owns-product", 0, "FOREIGN_PRODUCT", 0));
            // In a batch the receipt names the write that decided.
            assertDenied(cluster, submit(cluster, 1, "registry.v1", events.batch("logistics-a", List.of(
                    Mutation.put(DppStarterProfile.EVENTS, bytes("swift-logistics/p1/e3"), event("swift-logistics")),
                    Mutation.put(DppStarterProfile.EVENTS, bytes("green-labs/p1/e4"), event("swift-logistics"))))),
                    new RuleFailure("manufacturer-owns-product", 0, "FOREIGN_PRODUCT", 1));
            // An operator may write its own organization's events but not revoke them; a manufacturer may.
            var entry = entry(cluster, DppStarterProfile.EVENTS, "swift-logistics/p1/e1");
            assertDenied(cluster, submit(cluster, 2, "registry.v1", events.write("logistics-a",
                    Mutation.revoke(DppStarterProfile.EVENTS, bytes("swift-logistics/p1/e1"), entry.revision(),
                            entry.logicalValueHash()))),
                    new RuleFailure("admin-only-lifecycle-ops", 0, "ADMIN_ROLE_REQUIRED", 0));
            assertAccepted(cluster, submit(cluster, 0, "registry.v1", events.put("maker-a",
                    "acme-manufacturing/p2/e1", event("acme-manufacturing"))));
            var own = entry(cluster, DppStarterProfile.EVENTS, "acme-manufacturing/p2/e1");
            assertAccepted(cluster, submit(cluster, 0, "registry.v1", events.write("maker-a",
                    Mutation.revoke(DppStarterProfile.EVENTS, bytes("acme-manufacturing/p2/e1"), own.revision(),
                            own.logicalValueHash()))));
            DeclarativeBindingsClusterTest.assertConvergence(cluster, new LinkedHashSet<>(members()));
        }
    }

    @Test
    void feedObservationsUseTheSourcesOwnInsertOnlySlotWithinTheOpenFeedsRange(@TempDir Path directory)
            throws Exception {
        String chain = "typed-views-feed";
        var genesis = genesis(chain, List.of(
                collection("feeds", AuthenticatedMapContract.AUTH_APPROVAL, DppStarterProfile.CERTIFICATION_POLICY,
                        "feed-v1"),
                collection("observations", AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        DppStarterProfile.OPERATOR_POLICY, "observation-v1")), List.of(
                schema("feed-v1", new MapField("max", true, UINT), new MapField("min", true, UINT),
                        new MapField("status", true, AuthenticatedMapSchema.TextNode.any())),
                schema("observation-v1", new MapField("price", true, UINT))), List.of(
                new AuthenticatedMapContract.GenesisEntry("feeds", bytes("main"), new byte[0], value(Map.of(
                        "status", new UnicodeString("OPEN"), "min", new UnsignedInteger(100),
                        "max", new UnsignedInteger(200))))));
        Node observations = call("ne", Field.element("collection"), new Literal("observations"));
        var rules = List.of(
                new BindingIrV1.AdmissionRule("feed-open-and-in-range", "OBSERVATION_OUT_OF_RANGE", null, List.of(),
                        List.of(new BindingIrV1.Read("feed", "registry", "feeds", new BindingSourceV1.Literal(
                                "main"))), List.of(
                        clause(call("and", Field.read("feed", "present"), call("eq", Field.readValue("feed",
                                "status"), new Literal("OPEN")))),
                        clause(new Quantifier(false, call("or", observations, call("and",
                                call("ge", Field.elementValue("price"), Field.readValue("feed", "min")),
                                call("le", Field.elementValue("price"), Field.readValue("feed", "max")))))))),
                new BindingIrV1.AdmissionRule("own-insert-only-slot", "OBSERVATION_REJECTED", null, List.of(),
                        List.of(clause(new Quantifier(false, call("or", observations, call("and",
                                call("eq", Field.element("op"), new Literal("PUT_IF_ABSENT")), call("and",
                                call("eq", Field.element("coverage"), new Literal("direct")),
                                call("startsWith", Field.element("keyText"), call("concat", Field.element("actorId"),
                                        new Literal("/")))))))))));
        var ir = document(genesis, null, rules, List.of(
                new BindingIrV1.RuleAttachment("own-insert-only-slot", Map.of()),
                new BindingIrV1.RuleAttachment("feed-open-and-in-range", Map.of())));
        var ports = DeclarativeBindingsClusterTest.ports(3);
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs(chain, ir, ports), ports, directory)) {
            assertThat(attributes(cluster)).containsEntry("admission.registry.00.slot", "fact")
                    .containsEntry("admission.registry.01.slot", "admission")
                    .containsEntry("admission.registry.01.static", "false")
                    .containsEntry("admission.registry.01.reads", "true")
                    .containsEntry("admission.registry.01.writes", "true");
            var feed = new Writer(genesis, "observations", DppStarterProfile.OPERATOR_POLICY);
            assertAccepted(cluster, submit(cluster, 0, "registry.v1", feed.write("logistics-a",
                    Mutation.putIfAbsent("observations", bytes("logistics-a/1"), price(150)))));
            // The fact rule runs after the kernel approved: a replaced value, or another source's slot, is refused.
            assertDenied(cluster, submit(cluster, 1, "registry.v1", feed.write("logistics-a",
                    Mutation.put("observations", bytes("logistics-a/2"), price(150)))),
                    new RuleFailure("own-insert-only-slot", 0, "OBSERVATION_REJECTED", 0));
            assertDenied(cluster, submit(cluster, 1, "registry.v1", feed.write("logistics-a",
                    Mutation.putIfAbsent("observations", bytes("maker-a/1"), price(150)))),
                    new RuleFailure("own-insert-only-slot", 0, "OBSERVATION_REJECTED", 0));
            // The range rule reads the feed record before the kernel runs.
            assertDenied(cluster, submit(cluster, 2, "registry.v1", feed.write("logistics-a",
                    Mutation.putIfAbsent("observations", bytes("logistics-a/3"), price(250)))),
                    new RuleFailure("feed-open-and-in-range", 1, "OBSERVATION_OUT_OF_RANGE", 0));
            // The same slot again is the kernel's own rejection, after every admission-slot rule held.
            String again = submit(cluster, 0, "registry.v1", feed.write("logistics-a",
                    Mutation.putIfAbsent("observations", bytes("logistics-a/1"), price(160))));
            var receipt = agreedReceipt(cluster, again);
            assertThat(receipt.accepted()).isFalse();
            assertThat(receipt.code()).startsWith("MAP_");
            DeclarativeBindingsClusterTest.assertConvergence(cluster, new LinkedHashSet<>(members()));
        }
    }

    /**
     * ADR-031.4 §6.1 and §11: the governed limit is a map record, so raising it is an approved proposal applied
     * through the approval binding, not a profile epoch. The transfer the old limit refused is admitted from the next
     * block, the refusal stays provable, and the settings write carries verified approval coverage.
     */
    @Test
    void anApprovedSettingsChangeRaisesTheLimitFromTheNextBlockWithoutAProfileEpoch(@TempDir Path directory)
            throws Exception {
        String chain = "typed-views-governed";
        List<String> senders = senders();
        var genesis = genesis(chain, List.of(collection("settings", AuthenticatedMapContract.AUTH_APPROVAL,
                DppStarterProfile.CERTIFICATION_POLICY, "setting-v1")), List.of(schema("setting-v1",
                new MapField("max", true, UINT))), List.of(new AuthenticatedMapContract.GenesisEntry("settings",
                bytes("transfer"), new byte[0], value(Map.of("max", new UnsignedInteger(1_000))))));
        var limit = new BindingIrV1.AdmissionRule("governed-transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer",
                List.of(), List.of(new BindingIrV1.Read("limits", "registry", "settings",
                new BindingSourceV1.Literal("transfer"))), List.of(clause(call("le",
                new Field(Scope.COMMAND, "amount"), Field.readValue("limits", "max")))));
        var approvalOnly = new BindingIrV1.AdmissionRule("settings-by-approval", "SETTINGS_NEED_APPROVAL", null,
                List.of(), List.of(clause(new Quantifier(false, call("or", call("ne", Field.element("collection"),
                new Literal("settings")), call("eq", Field.element("coverage"), new Literal("approval")))))));
        var token = new BindingIrV1.Component("token", "balances", "token.v1", Map.of("minter",
                new BindingSourceV1.Literal("")), 0, 1, List.of(new BindingIrV1.RuleAttachment(
                "governed-transfer-limit", Map.of())));
        var ir = document(genesis, token, List.of(limit, approvalOnly), List.of(
                new BindingIrV1.RuleAttachment("settings-by-approval", Map.of())));
        var raise = new MapActionV1(false, List.of(Mutation.compareAndSet("settings", bytes("transfer"),
                value(Map.of("max", new UnsignedInteger(5_000))), 1, AuthenticatedMapContract.logicalValueHash(
                value(Map.of("max", new UnsignedInteger(1_000)))))), List.of(new AuthorizationAssignmentV1(0,
                AuthenticatedMapContract.AUTH_APPROVAL, DppStarterProfile.CERTIFICATION_POLICY, 1)));
        byte[] hash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(AuthenticatedMapContract.genesisId(
                genesis), AuthenticatedMapAuthorizationContract.actionCommitment(raise));
        var ports = DeclarativeBindingsClusterTest.ports(3);
        var members = new LinkedHashSet<>(members());
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs(chain, ir, ports), ports, directory)) {
            assertAccepted(cluster, submit(cluster, 0, "token.v1", BalancesContract.mint(senders.get(0),
                    BigInteger.valueOf(10_000))));
            String refused = submit(cluster, 0, "token.v1", transfer(senders.get(1), 2_000));
            assertDenied(cluster, refused, new RuleFailure("governed-transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED",
                    null));
            byte[] marker = cluster.node(0).stateValue(CompositeStateKeys.profileMarkerKey()).orElseThrow();
            assertAccepted(cluster, submit(cluster, 0, "reviews.v1", review(chain, "certifier-a",
                    ActorStatementV1.Action.PROPOSE, hash, AuthenticatedMapAuthorizationContract.encodeAction(raise))));
            assertAccepted(cluster, submit(cluster, 0, "reviews.v1", review(chain, "auditor-a",
                    ActorStatementV1.Action.APPROVE, hash, new byte[0])));
            String approved = submit(cluster, 0, "reviews.v1", review(chain, "auditor-b",
                    ActorStatementV1.Action.APPROVE, hash, new byte[0]));
            var applied = agreedReceipt(cluster, approved);
            assertThat(applied.accepted()).as(applied.code()).isTrue();
            // The derived map write held the coverage rule: approval coverage on three members.
            assertThat(applied.steps().getLast().targetComponentId()).isEqualTo("registry");
            assertThat(applied.steps().getLast().rules()).isEqualTo(new BindingReceiptV1.RuleTrace(1, null));
            long raised = cluster.node(0).messageHeight(HexFormat.of().parseHex(approved)).orElseThrow();
            String admitted = submit(cluster, 0, "token.v1", transfer(senders.get(1), 2_000));
            assertAccepted(cluster, admitted);
            assertThat(cluster.node(0).messageHeight(HexFormat.of().parseHex(admitted)).orElseThrow())
                    .isEqualTo(raised + 1);
            // The new maximum is exactly the approved value.
            assertDenied(cluster, submit(cluster, 0, "token.v1", transfer(senders.get(1), 5_001)),
                    new RuleFailure("governed-transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED", null));
            for (AppChainSubsystem node : cluster.liveNodes()) {
                // No profile change: the committed profile marker is untouched and no epoch was recorded.
                assertThat(node.stateValue(CompositeStateKeys.profileMarkerKey())).hasValueSatisfying(value ->
                        assertThat(value).isEqualTo(marker));
                assertThat(node.stateValue(CompositeStateKeys.currentProfileEpochKey())).isEmpty();
                DeclarativeBindingsClusterTest.verifyCertifiedProof(node, CompositeStateKeys.workflowStateKey(
                        EventBindingWorkflow.ID, HexFormat.of().parseHex(refused)), members, chain);
            }
            DeclarativeBindingsClusterTest.assertConvergence(cluster, members);
        }
    }

    /**
     * ADR-031.4 §9 Phase 6 anti-poison: twelve maximal 128-write batches in one block, each refused by a write rule
     * at its last write, finalize with a legitimate 128-write batch after them, and the pool keeps flowing.
     */
    @Test
    void aBlockOf128WriteDenialsFinalizesWithALegitimateBatchAfterThem(@TempDir Path directory) throws Exception {
        String chain = "typed-views-anti-poison";
        // An ungoverned registry of one open collection, with the largest batch a map accepts.
        var genesis = AuthenticatedMapGenesisFactory.mpf(TrustRegistryGenesis.unsignedConfig(chain, members(), 2),
                new byte[32], AuthenticatedMapContract.MAX_BATCH_ITEMS, AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES,
                List.of(new AuthenticatedMapContract.CollectionDescriptor("records", AuthenticatedMapContract.AUTH_OPEN,
                        "", true, 64, 1024, AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, "")), List.of());
        // Reading the height keeps the rule out of ingress, so every poisoned batch is pooled and refused in a block.
        var small = new BindingIrV1.AdmissionRule("small-values", "VALUE_TOO_LARGE", null, List.of(), List.of(
                clause(call("and", call("gt", new Field(Scope.CONTEXT, "height"), new Literal(0L)),
                        new Quantifier(false, call("le", Field.element("valueLength"), new Literal(1L)))))));
        var ir = new BindingIrV1(List.of(new BindingIrV1.Component("registry", AuthenticatedMapLeafStateMachine.ID,
                "registry.v1", Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(hex(
                AuthenticatedMapContract.encodeGenesis(genesis))), "actors", new BindingSourceV1.Literal(""),
                "approvals", new BindingSourceV1.Literal("")), 0, 1, List.of(new BindingIrV1.RuleAttachment(
                "small-values", Map.of())))), List.of(small), List.of(), BindingIrV1.Limits.DEFAULT, 1);
        var ports = DeclarativeBindingsClusterTest.ports(3);
        try (var cluster = new DeclarativeBindingsClusterTest.Cluster(configs(chain, ir, ports, 1_500), ports,
                directory)) {
            var node = cluster.node(0);
            int position = 0;
            for (int attempt = 0; attempt < 6 && position == 0; attempt++) {
                List<String> refused = new ArrayList<>();
                for (int batch = 0; batch < 12; batch++) {
                    refused.add(node.submit("registry.v1", openBatch("p" + attempt + "-" + batch, 2)));
                }
                String legitimate = node.submit("registry.v1", openBatch("ok" + attempt, 1));
                DeclarativeBindingsClusterTest.awaitReceipt(cluster, legitimate);
                for (String id : refused) DeclarativeBindingsClusterTest.awaitReceipt(cluster, id);
                for (String id : refused) {
                    assertDenied(cluster, id, new RuleFailure("small-values", 0, "VALUE_TOO_LARGE",
                            AuthenticatedMapContract.MAX_BATCH_ITEMS - 1));
                }
                assertAccepted(cluster, legitimate);
                long height = node.messageHeight(HexFormat.of().parseHex(legitimate)).orElseThrow();
                var messages = node.block(height).orElseThrow().messages();
                position = 0;
                while (!HexFormat.of().formatHex(messages.get(position).getMessageId()).equals(legitimate)) {
                    position++;
                }
            }
            // The legitimate batch finalized in a block after a run of refused maximal batches.
            assertThat(position).as("refused batches before the legitimate batch in its block").isPositive();
            assertAccepted(cluster, submit(cluster, 0, "registry.v1", openBatch("after", 1)));
            DeclarativeBindingsClusterTest.assertConvergence(cluster, new LinkedHashSet<>(members()));
        }
    }

    /** 128 open-collection puts of one-byte values; the last value is {@code lastLength} bytes long. */
    private static byte[] openBatch(String prefix, int lastLength) {
        List<Mutation> mutations = new ArrayList<>();
        List<AuthorizationAssignmentV1> assignments = new ArrayList<>();
        for (int index = 0; index < AuthenticatedMapContract.MAX_BATCH_ITEMS; index++) {
            int length = index == AuthenticatedMapContract.MAX_BATCH_ITEMS - 1 ? lastLength : 1;
            mutations.add(Mutation.put("records", bytes(prefix + "/" + index), new byte[length]));
            assignments.add(new AuthorizationAssignmentV1(index, AuthenticatedMapContract.AUTH_OPEN, "",
                    AuthenticatedMapAuthorizationContract.NO_EVIDENCE_HANDLE));
        }
        return TransitionScalars.encode(Map.of("action", AuthenticatedMapAuthorizationContract.encodeAction(
                new MapActionV1(true, mutations, assignments))));
    }

    /** A staged, signed review statement of a demo actor on proposal {@code settings-1}. */
    private static byte[] review(String chain, String actor, ActorStatementV1.Action action, byte[] hash,
                                 byte[] staged) {
        var statement = new ActorStatementV1(action, chain, "settings-1", DppStarterProfile.CERTIFICATION_POLICY, 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, hash, 500, actor, 1, actor + "-k1",
                action == ActorStatementV1.Action.APPROVE ? DppStarterProfile.CERTIFICATION_CLAUSE : "");
        return new StagedActorCommandV1(SignedActorCommandV1.sign(statement, DppGenesis.demoActorSeed(actor)),
                staged).encode();
    }

    /** Signs direct writes of one collection with a demo actor's key. */
    private static final class Writer {
        private final AuthenticatedMapContract.Genesis genesis;
        private final String collection;
        private final String policy;
        private int authorizations;

        Writer(AuthenticatedMapContract.Genesis genesis, String collection, String policy) {
            this.genesis = genesis;
            this.collection = collection;
            this.policy = policy;
        }

        byte[] put(String actor, String key, byte[] value) {
            return write(actor, Mutation.put(collection, bytes(key), value));
        }

        byte[] write(String actor, Mutation mutation) { return batch(actor, List.of(mutation)); }

        byte[] batch(String actor, List<Mutation> mutations) {
            List<AuthorizationAssignmentV1> assignments = new ArrayList<>();
            List<Integer> covered = new ArrayList<>();
            for (int index = 0; index < mutations.size(); index++) {
                assignments.add(new AuthorizationAssignmentV1(index, AuthenticatedMapContract.AUTH_GOVERNED_ROLE,
                        policy, 1));
                covered.add(index);
            }
            var action = new MapActionV1(mutations.size() > 1, mutations, assignments);
            byte[] seed = DppGenesis.demoActorSeed(actor);
            byte[] authorization = new byte[32];
            authorization[0] = (byte) ++authorizations;
            authorization[1] = (byte) collection.length();
            var signed = MapActorAuthorizationV1.sign(authorization, genesis.chainId(),
                    AuthenticatedMapContract.genesisId(genesis),
                    AuthenticatedMapAuthorizationContract.actionCommitment(action), covered, policy, 1, actor, 1,
                    actor + "-k1", KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1,
                    DppStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS, seed);
            return TransitionScalars.encode(Map.of("command", AuthenticatedMapAuthorizationContract.encodeCommand(
                    new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of(signed)))));
        }
    }

    /** Actors, reviews and the registry of the product recipes, plus an optional extra component. */
    private static BindingIrV1 document(AuthenticatedMapContract.Genesis genesis, BindingIrV1.Component extra,
                                        List<BindingIrV1.AdmissionRule> rules,
                                        List<BindingIrV1.RuleAttachment> registryRules) {
        String roleGenesis = hex(genesis.governedGenesis().encode());
        List<BindingIrV1.Component> components = new ArrayList<>(List.of(
                new BindingIrV1.Component("actors", DeclarativeRoleProviders.ACTORS_ID, "actors.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(roleGenesis)), 0),
                new BindingIrV1.Component("reviews", DeclarativeRoleProviders.APPROVALS_ID, "reviews.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(roleGenesis),
                                "actor-component", new BindingSourceV1.Literal("actors")), 0),
                new BindingIrV1.Component("registry", AuthenticatedMapLeafStateMachine.ID, "registry.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(
                                        hex(AuthenticatedMapContract.encodeGenesis(genesis))),
                                "actors", new BindingSourceV1.Literal("actors"),
                                "approvals", new BindingSourceV1.Literal("reviews")), 0, 1, registryRules)));
        if (extra != null) components.add(extra);
        List<BindingIrV1.AdmissionRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(BindingIrV1.AdmissionRule::id));
        return new BindingIrV1(components, sorted, List.of(new BindingIrV1.Binding("apply-approved", "reviews",
                DeclarativeRoleProviders.APPROVED_EVENT, List.of(), new BindingIrV1.CommandTarget("registry",
                "apply-action", BindingIrV1.Mapping.fields(List.of(
                new BindingIrV1.Assignment("action", new BindingSourceV1.Field("action")),
                new BindingIrV1.Assignment("approvalReference", new BindingSourceV1.Field("proposalId"))))))),
                BindingIrV1.Limits.DEFAULT, 1);
    }

    private static AuthenticatedMapContract.Genesis genesis(String chain,
                                                            List<AuthenticatedMapContract.CollectionDescriptor> all,
                                                            List<AuthenticatedMapContract.ValidatorDescriptor> schemas,
                                                            List<AuthenticatedMapContract.GenesisEntry> entries) {
        return AuthenticatedMapGenesisFactory.mpf(TrustRegistryGenesis.unsignedConfig(chain, members(), 2),
                new byte[32], DppGenesis.MAX_BATCH_ITEMS, AppChainConfig.DEFAULT_MAX_MESSAGE_BYTES, all, schemas,
                entries, DppGenesis.governedGenesis(DppGenesis.demo(chain)));
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(String id, int authorization,
                                                                           String policy, String schema) {
        return new AuthenticatedMapContract.CollectionDescriptor(id, authorization, policy, false, 64, 1024,
                AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, schema);
    }

    private static AuthenticatedMapContract.ValidatorDescriptor schema(String id, MapField... members) {
        return AuthenticatedMapContract.ValidatorDescriptor.schema(id, AuthenticatedMapSchema.of(
                new AuthenticatedMapSchema.MapNode(List.of(members))).definition());
    }

    private static AuthenticatedMapContract.GenesisEntry holder(String sender, long maxTransfer, long acquired) {
        return new AuthenticatedMapContract.GenesisEntry("holders", HexFormat.of().parseHex(sender), new byte[0],
                value(Map.of("maxTransfer", new UnsignedInteger(maxTransfer),
                        "acquiredHeight", new UnsignedInteger(acquired))));
    }

    private static List<AppChainConfig> configs(String chain, BindingIrV1 ir, List<Integer> ports) {
        return configs(chain, ir, ports, 100);
    }

    private static List<AppChainConfig> configs(String chain, BindingIrV1 ir, List<Integer> ports, long interval) {
        List<AppChainConfig> configs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int own = ports.get(index);
            configs.add(AppChainConfig.builder(chain).signingKeyHex(hex(BindingProductFixtures.memberSeeds()
                            .get(index))).memberKeysHex(new LinkedHashSet<>(members()))
                    .proposerKeyHex(members().getFirst()).threshold(2).blockIntervalMs(interval)
                    .peers(ports.stream().filter(port -> port != own)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList())
                    .stateCommitmentIdentity(StdlibTestStateCommitments.mpf(chain))
                    .stateMachineId(DeclarativeCompositeProvider.ID)
                    .pluginSettings(Map.of("membership.mode", "governed", DeclarativeCompositeProvider.IR_SETTING,
                            hex(ir.encode()))).build());
        }
        return configs;
    }

    private static String submit(DeclarativeBindingsClusterTest.Cluster cluster, int node, String topic,
                                 byte[] body) throws Exception {
        String id = cluster.node(node).submit(topic, body);
        DeclarativeBindingsClusterTest.awaitReceipt(cluster, id);
        return id;
    }

    /** The receipt every member stores for the message, which must be byte-identical. */
    private static BindingReceiptV1 agreedReceipt(DeclarativeBindingsClusterTest.Cluster cluster, String id) {
        byte[] key = CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID, HexFormat.of().parseHex(id));
        byte[] first = cluster.node(0).stateValue(key).orElseThrow();
        for (AppChainSubsystem node : cluster.liveNodes()) assertThat(node.stateValue(key)).hasValue(first);
        return BindingReceiptV1.decode(cluster.node(0).query("composite/binding-receipt-v1/" + id, new byte[0])
                .payload());
    }

    /** The declarative bindings entry of the agreed capability manifest. */
    private static Map<String, String> attributes(DeclarativeBindingsClusterTest.Cluster cluster) {
        var manifest = (AppCapabilityManifest) cluster.node(0).status().get("capabilityManifest");
        return manifest.crossCutting().stream()
                .filter(entry -> entry.capabilityId().equals("declarative-event-bindings")).findFirst()
                .orElseThrow().attributes();
    }

    private static void assertAccepted(DeclarativeBindingsClusterTest.Cluster cluster, String id) {
        var receipt = agreedReceipt(cluster, id);
        assertThat(receipt.accepted()).as(receipt.code()).isTrue();
    }

    private static void assertDenied(DeclarativeBindingsClusterTest.Cluster cluster, String id,
                                     RuleFailure failure) {
        var receipt = agreedReceipt(cluster, id);
        assertThat(receipt.code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(receipt.steps().getLast().rules().failure()).isEqualTo(failure);
    }

    private static AuthenticatedMapContract.Entry entry(DeclarativeBindingsClusterTest.Cluster cluster,
                                                        String collection, String key) {
        return cluster.node(0).stateValue(CompositeStateKeys.componentKey("registry",
                AuthenticatedMapContract.canonicalKey(collection, bytes(key))))
                .map(AuthenticatedMapContract::decodeEntry).orElseThrow();
    }

    private static byte[] transfer(String to, long amount) {
        return BalancesContract.transfer(to, BigInteger.valueOf(amount));
    }

    private static byte[] event(String organization) {
        return new DppValues.EventValue("shipped", organization, 1_700_000_000L, "", new byte[0], "").encode();
    }

    private static byte[] price(long price) { return value(Map.of("price", new UnsignedInteger(price))); }

    private static BindingIrV1.ExpressionClause clause(Node condition) {
        return new BindingIrV1.ExpressionClause(new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN, condition));
    }

    private static Call call(String operator, Node... arguments) { return new Call(operator, List.of(arguments)); }

    private static byte[] value(Map<String, DataItem> members) {
        var map = new co.nstant.in.cbor.model.Map();
        new LinkedHashMap<>(members).forEach((name, item) -> map.put(new UnicodeString(name), item));
        return CborSerializationUtil.serialize(map);
    }

    /** Each node's sender key, in node order. */
    private static List<String> senders() {
        return BindingProductFixtures.memberSeeds().stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(TypedViewsRecipesClusterTest::hex).toList();
    }

    private static List<String> members() { return BindingProductFixtures.members(); }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
