package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.network.server.NodeServer;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2NVersionTableConstant;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppBlockHeader;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.api.appchain.effects.EffectView;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.client.AppChainClient;
import org.yanoproject.x.client.ProofVerifier;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;
import org.yanoproject.x.roles.contracts.StagedActorCommandV1;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compiles checked-in author recipes through real catalog bundles, executes them on three actual N2N hosts,
 * and replays their finalized blocks through the offline rehearsal API. Receipt-byte equality is tested,
 * not inferred from decoded fields. Temporary state is never an external deployment or production trust pin.
 *
 * <p>The ADR-031.3 admission recipes (§6.1, §6.2) also qualify expected refusals: a static rule rejects at
 * ingress on the submitting node, and a block-time denial is a finalized, provable receipt naming the rule.
 */
@Timeout(180)
class BindingRecipesIT {
    @TempDir Path temporary;

    @Test void procurementYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("procurement"); }
    @Test void attestationYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("attestation"); }
    @Test void dppYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("dpp-approval"); }
    @Test void feedYamlHasLiveAndOfflineReceiptParity() throws Exception { qualify("feed-approval"); }
    @Test void payloadAdmissionAndLazyBaselineHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("payload-admission");
    }
    @Test void balancesTransferLimitRulesHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("balances-transfer-limit");
    }
    @Test void procurementAdmissionRulesHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("procurement-admission");
    }
    @Test void roleGatedDppRulesHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("dpp-role-gated");
    }
    @Test void assetGovernedLimitsHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("asset-governed-limits");
    }
    @Test void dppNamespaceIsolationHasLiveAndOfflineReceiptParity() throws Exception {
        qualify("dpp-namespace-isolation");
    }
    @Test void feedSlotRulesHaveLiveAndOfflineReceiptParity() throws Exception {
        qualify("feed-slot-rules");
    }
    @Test void balancesHoldingCapHasLiveAndOfflineReceiptParity() throws Exception {
        qualify("balances-holding-cap");
    }

    /**
     * An expected refusal: the receipt code, the failed step's exact rule trace, and the business keys its block
     * may still write. An admission-slot refusal reserves no work, so its block writes framework keys only; a
     * refusal after the kernel's work reservation keeps that non-refundable reservation and nothing else.
     */
    record Refusal(String code, BindingReceiptV1.RuleTrace rules, Set<String> keptKeys) {
        static Refusal denied(String rule, String denyCode, Set<String> keptKeys) {
            return new Refusal("ADMISSION_RULE_DENIED", new BindingReceiptV1.RuleTrace(0,
                    new BindingReceiptV1.RuleFailure(rule, 0, denyCode)), keptKeys);
        }

        /** An ADR-031.4 denial after {@code held} rules held, at a clause and, for a quantifier, a write. */
        static Refusal denied(int held, String rule, int clause, String denyCode, Integer write,
                              Set<String> keptKeys) {
            return new Refusal("ADMISSION_RULE_DENIED", new BindingReceiptV1.RuleTrace(held,
                    new BindingReceiptV1.RuleFailure(rule, clause, denyCode, write)), keptKeys);
        }
    }

    /** The actor component's shared crypto-work counter, which governed evidence reserves before verification. */
    private static final String ACTOR_WORK_KEY = hex(CompositeStateKeys.componentKey("actors",
            RoleWorkflowKeys.cryptoWork()));

    private void qualify(String recipe) throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        for (String bundle : System.getProperty("yano.test.binding-bundles").split(java.io.File.pathSeparator)) {
            Path source = Path.of(bundle);
            Files.copy(source, plugins.resolve(source.getFileName()));
        }
        String yaml = recipe.equals("payload-admission") ? """
                composite:
                  components:
                    - {id: source, machine: ordered-log}
                    - {id: target, machine: ordered-log}
                  bindings:
                    - id: copy
                      from: {component: source, event: composite.command-accepted.v1}
                      to: {component: target, command: append, rawBody: body}
                """ : Files.readString(Path.of("..", "..", "examples", "bindings", recipe + ".yaml"));
        var genesis = productGenesis(yaml);
        String chain = genesis == null ? "binding-" + recipe + "-parity" : genesis.chainId();
        List<byte[]> seeds = memberSeeds();
        List<String> members = seeds.stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(BindingRecipesIT::hex).sorted().toList();
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                MessageDigest.getInstance("SHA-256").digest(chain.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> hostSettings = genesis != null ? Map.of("membership.mode", "governed")
                : recipe.equals("procurement") ? Map.of("effects.enabled", "true") : Map.of();
        long interval = genesis == null ? 100 : 1000;
        var base = configuration(chain, seeds.getFirst(), members, identity, hostSettings, interval, List.of());
        var contextSettings = new LinkedHashMap<>(identity.settings());
        contextSettings.putAll(hostSettings);
        var input = new BindingCatalogSession.ContextInput(chain, contextSettings,
                AppChainEffectsConfig.from(base).consensusProfile(base),
                new AppChainMembershipEpoch(0, members, 2));

        try (var environment = BindingPluginEnvironment.open(plugins)) {
            var catalog = new BindingCatalogSession(environment.providers(), input);
            BindingIrV1 ir = BindingDocumentCompiler.compile(yaml, catalog);
            assertThat(catalog.validate(ir).capabilityManifest().components()).isNotEmpty();
            List<Integer> ports = ports();
            List<AppChainConfig> configs = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                var settings = new LinkedHashMap<>(hostSettings);
                settings.put(BindingCatalogSession.IR_SETTING, hex(ir.encode()));
                int own = ports.get(index);
                configs.add(configuration(chain, seeds.get(index), members, identity, settings, interval,
                        ports.stream().filter(port -> port != own)
                                .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList()));
            }
            List<EffectView> retainedOutbox = List.of();
            Map<String, String> retainedReceipts = new LinkedHashMap<>();
            byte[] retainedRoot;
            long retainedHeight;
            byte[] receiptProofKey;
            byte[] pinnedFinalizedContext;
            StateCommitmentIdentity pinnedIdentity;
            Object pinnedManifest;
            Object pinnedConsensus;
            try (Cluster cluster = new Cluster(configs, ports, temporary, environment.providers())) {
                byte[] initialRoot = cluster.nodes[0].stateRoot();
                pinnedIdentity = cluster.nodes[0].stateCommitmentIdentity().orElseThrow();
                pinnedManifest = cluster.nodes[0].status().get("capabilityManifest");
                pinnedConsensus = cluster.nodes[0].status().get("consensusProfile");
                Map<String, Refusal> denials = new LinkedHashMap<>();
                var ingress = new Ingress(catalog.validate(ir), chain);
                List<String> sourceIds = submitRecipe(recipe, genesis, cluster, seeds, denials, ingress);
                pinnedFinalizedContext = cluster.finalizedContextPin.clone();
                long tip = cluster.nodes[0].tipHeight();
                for (var node : cluster.nodes) {
                    assertThat(node.tipHeight()).isEqualTo(tip);
                    assertThat(node.stateRoot()).isEqualTo(cluster.nodes[0].stateRoot());
                    assertThat(node.stateCommitmentIdentity()).contains(pinnedIdentity);
                    assertThat(node.status().get("consensusProfile"))
                            .isEqualTo(pinnedConsensus);
                    assertThat(node.status().get("capabilityManifest"))
                            .isEqualTo(pinnedManifest);
                    assertThat(node.block(tip).orElseThrow().cert().signatures()).hasSizeGreaterThanOrEqualTo(2);
                }
                // Replay in canonical block order, carrying the plugin engine's physical state changes forward.
                // Host metadata is not invented or needed; context/root are explicit assumptions in dry-run.
                Map<String, String> state = new TreeMap<>();
                int compared = 0;
                int comparedEffects = 0;
                for (long height = 1; height <= tip; height++) {
                    var block = cluster.nodes[0].block(height).orElseThrow();
                    assertThat(block.messages()).isNotEmpty();
                    assertThat(block.messages()).allSatisfy(message ->
                            assertThat(message.getTopic()).doesNotStartWith("~"));
                    byte[] beforeRoot = height == 1 ? initialRoot
                            : cluster.nodes[0].block(height - 1).orElseThrow().stateRoot();
                    var fixture = new BindingDryRun.Fixture(height, block.timestamp(), hex(beforeRoot), 0,
                            state.entrySet().stream().map(entry ->
                                    new BindingDryRun.Entry(entry.getKey(), entry.getValue())).toList(),
                            block.messages().stream().map(message -> new BindingDryRun.Message(
                                    hex(message.getMessageId()), hex(message.getSender()), message.getSenderSeq(),
                                    message.getExpiresAt(), message.getTopic(), hex(message.getBody()),
                                    hex(message.getAuthProof()))).toList());
                    var rehearsal = BindingDryRun.execute(catalog.validate(ir), input, fixture);
                    int effectOrdinal = 0;
                    for (var effect : rehearsal.effects()) {
                        var durable = cluster.nodes[0].effect(height, effectOrdinal).orElseThrow();
                        assertThat(effect.get("payloadHex")).isEqualTo(durable.payloadHex());
                        assertThat(effect.get("type")).isEqualTo(durable.type());
                        for (var peer : cluster.nodes) {
                            assertThat(peer.effect(height, effectOrdinal)).contains(durable);
                        }
                        effectOrdinal++;
                        comparedEffects++;
                    }
                    for (var receipt : rehearsal.receipts()) {
                        String id = (String) receipt.get("messageIdHex");
                        byte[] actual = cluster.nodes[0].query("composite/binding-receipt-v1/" + id,
                                new byte[0]).payload();
                        assertThat((String) receipt.get("receiptHex")).isEqualTo(hex(actual));
                        var decoded = BindingReceiptV1.decode(actual);
                        var denial = denials.get(id);
                        assertThat(decoded.accepted()).as(id).isEqualTo(denial == null);
                        if (denial != null) {
                            assertThat(decoded.code()).isEqualTo(denial.code());
                            var failed = decoded.steps().stream()
                                    .filter(step -> step.ordinal() == decoded.failedStepOrdinal()).findFirst()
                                    .orElseThrow();
                            assertThat(failed.rules()).isEqualTo(denial.rules());
                            // A refusal is one per-source-message no-op: besides its receipt and framework keys
                            // ("~" namespace), its block changes no business value (physical keys starting with
                            // "yano-composite-state-v1\0") except the refusal's kept keys. A component's per-height
                            // lifecycle may rewrite a value unchanged. submit() waits for each receipt before the
                            // next submission, so this block holds only the refused message.
                            String receiptKey = hex(cluster.nodes[0].query("composite/binding-receipt-key-v1/"
                                    + id, new byte[0]).payload());
                            assertThat(rehearsal.stateChanges()).extracting(BindingDryRun.Entry::keyHex)
                                    .contains(receiptKey);
                            for (var change : rehearsal.stateChanges()) {
                                if (change.keyHex().startsWith("7e") || denial.keptKeys().contains(change.keyHex())) {
                                    continue;
                                }
                                assertThat(change.valueHex()).as(change.keyHex()).isEqualTo(state.get(change.keyHex()));
                            }
                        }
                        retainedReceipts.put(id, hex(actual));
                        compared++;
                    }
                    for (var change : rehearsal.stateChanges()) {
                        if (change.valueHex() == null) state.remove(change.keyHex());
                        else state.put(change.keyHex(), change.valueHex());
                    }
                }
                assertThat(compared).isEqualTo(sourceIds.size());
                assertThat(comparedEffects).isEqualTo(recipe.equals("procurement") ? 1 : 0);
                retainedOutbox = cluster.nodes[0].effects(1, 100);
                String last = sourceIds.getLast();
                receiptProofKey = cluster.nodes[0].query(
                        "composite/binding-receipt-key-v1/" + last, new byte[0]).payload();
                assertThat(state.get(hex(receiptProofKey))).isEqualTo(retainedReceipts.get(last));
                for (var node : cluster.nodes) {
                    assertThat(node.query("composite/binding-receipt-key-v1/" + last, new byte[0]).payload())
                            .containsExactly(receiptProofKey);
                    verifyReceiptProof(node, receiptProofKey, new LinkedHashSet<>(members), chain,
                            pinnedIdentity, pinnedFinalizedContext);
                }
                // A denial is a finalized, provable receipt like any other outcome (ADR-031.3 §7).
                for (String denied : denials.keySet()) {
                    byte[] deniedKey = cluster.nodes[0].query("composite/binding-receipt-key-v1/" + denied,
                            new byte[0]).payload();
                    for (var node : cluster.nodes) {
                        verifyReceiptProof(node, deniedKey, new LinkedHashSet<>(members), chain,
                                pinnedIdentity, pinnedFinalizedContext);
                    }
                }
                retainedRoot = cluster.nodes[0].stateRoot();
                retainedHeight = cluster.nodes[0].tipHeight();
                assertThat(BindingReceiptV1.decode(cluster.nodes[0]
                        .query("composite/binding-receipt-v1/" + last, new byte[0]).payload()).steps().size())
                        .isGreaterThanOrEqualTo(Set.of("balances-transfer-limit", "dpp-role-gated").contains(recipe)
                                || TYPED_VIEW_RECIPES.contains(recipe) ? 1 : 2);
            }
            // Every recipe reopens all three original stores, not only the outbox-bearing recipe.
            try (Cluster restarted = new Cluster(configs, ports, temporary, environment.providers())) {
                for (var node : restarted.nodes) {
                    assertThat(node.effects(1, 100)).isEqualTo(retainedOutbox);
                    assertThat(node.tipHeight()).isEqualTo(retainedHeight);
                    assertThat(node.stateRoot()).isEqualTo(retainedRoot);
                    assertThat(node.stateCommitmentIdentity()).contains(pinnedIdentity);
                    assertThat(node.status().get("capabilityManifest")).isEqualTo(pinnedManifest);
                    assertThat(node.status().get("consensusProfile")).isEqualTo(pinnedConsensus);
                    retainedReceipts.forEach((id, receipt) -> assertThat(hex(node
                            .query("composite/binding-receipt-v1/" + id, new byte[0]).payload())).isEqualTo(receipt));
                    verifyReceiptProof(node, receiptProofKey, new LinkedHashSet<>(members), chain,
                            pinnedIdentity, pinnedFinalizedContext);
                }
                // Start one member with no ledger or authenticated state. N2N replay now starts at genesis,
                // recomputes the authoritative commitment root, and validates every certified block.
                // This is distinct from retained-state restart and from the root-free CLI rehearsal.
                restarted.stop(2);
                Path freshReplay = temporary.resolve("fresh-genesis-replay");
                assertThat(Files.exists(freshReplay)).isFalse();
                restarted.start(2, freshReplay);
                await(() -> restarted.nodes[2].tipHeight() == retainedHeight);
                var replayed = restarted.nodes[2];
                assertThat(replayed.stateRoot()).isEqualTo(retainedRoot);
                assertThat(replayed.stateCommitmentIdentity()).contains(pinnedIdentity);
                assertThat(replayed.status().get("consensusProfile")).isEqualTo(pinnedConsensus);
                assertThat(replayed.status().get("capabilityManifest")).isEqualTo(pinnedManifest);
                assertThat(replayed.effects(1, 100)).isEqualTo(retainedOutbox);
                retainedReceipts.forEach((id, receipt) -> assertThat(hex(replayed
                        .query("composite/binding-receipt-v1/" + id, new byte[0]).payload())).isEqualTo(receipt));
                verifyReceiptProof(replayed, receiptProofKey, new LinkedHashSet<>(members), chain,
                        pinnedIdentity, pinnedFinalizedContext);
            }
        }
    }

    private static List<String> submitRecipe(String recipe, AuthenticatedMapContract.Genesis genesis,
                                              Cluster cluster, List<byte[]> seeds,
                                              Map<String, Refusal> denials,
                                              Ingress ingress) throws Exception {
        List<String> ids = new ArrayList<>();
        if (recipe.equals("balances-transfer-limit")) {
            String alice = hex(KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst()));
            String bob = hex(KeyGenUtil.getPublicKeyFromPrivateKey(seeds.get(1)));
            ids.add(submit(cluster, 0, "points.command.v1",
                    BalancesContract.mint(alice, BigInteger.valueOf(50_000))));
            // transfer-limit reads only command.* and params.*, so it is static: refused before pooling.
            ingress.rejects(cluster, "points.command.v1", BalancesContract.transfer(bob, BigInteger.valueOf(20_000)),
                    "transfer-limit", "TRANSFER_LIMIT_EXCEEDED");
            ids.add(submit(cluster, 0, "points.command.v1",
                    BalancesContract.transfer(bob, BigInteger.valueOf(10_000))));
            // mint is not selected by the rule, so a large mint is unaffected.
            ids.add(submitWithFollowerCatchup(cluster, 0, "points.command.v1",
                    BalancesContract.mint(bob, BigInteger.valueOf(20_000))));
        } else if (recipe.equals("procurement-admission")) {
            byte[] supplier = KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst());
            // §7.1: an order before the sender is registered is refused itself; the lookup keeps it pooled.
            String unregistered = submit(cluster, 0, "orders.command.v1", KvRegistryContract.put(new byte[]{9},
                    new byte[]{1}));
            ids.add(unregistered);
            denials.put(unregistered, Refusal.denied("registered-supplier", "NOT_A_REGISTERED_SUPPLIER", Set.of()));
            ids.add(submit(cluster, 0, "suppliers.command.v1", KvRegistryContract.put(supplier,
                    "approved".getBytes(StandardCharsets.UTF_8))));
            // minimum-quorum reads only command.* and params.*: a direct low-quorum proposal fails at ingress.
            ingress.rejects(cluster, "approvals.command.v1", ApprovalsContract.propose("02", new byte[]{7}, 1, 0),
                    "minimum-quorum", "QUORUM_TOO_LOW");
            // §7.2: the registered supplier's order derives a quorum-2 proposal at depth 1.
            ids.add(submit(cluster, 0, "orders.command.v1", KvRegistryContract.put(new byte[]{1}, new byte[]{42})));
            // §7.4: only-via-binding reads context, so a direct audit append is pooled and denied at block time.
            String direct = submit(cluster, 0, "audit.command.v1", DocTrailContract.append("01", new byte[32],
                    "forged"));
            ids.add(direct);
            denials.put(direct, Refusal.denied("only-via-binding", "DIRECT_SUBMISSION_FORBIDDEN", Set.of()));
            ids.add(submit(cluster, 0, "approvals.command.v1", ApprovalsContract.approve("01")));
            // §7.5: the second approval derives the audit append through approved-to-audit, which the rule admits.
            ids.add(submitWithFollowerCatchup(cluster, 1, "approvals.command.v1", ApprovalsContract.approve("01")));
        } else if (recipe.equals("dpp-role-gated")) {
            submitRoleGated(genesis, cluster, ids, denials);
        } else if (TYPED_VIEW_RECIPES.contains(recipe)) {
            var steps = TypedViewStep.of(recipe, genesis);
            for (int index = 0; index < steps.size(); index++) {
                var step = steps.get(index);
                String id = index == steps.size() - 1
                        ? submitWithFollowerCatchup(cluster, step.node(), step.topic(), step.body())
                        : submit(cluster, step.node(), step.topic(), step.body());
                ids.add(id);
                if (step.refusal() != null) denials.put(id, step.refusal());
            }
        } else if (recipe.equals("payload-admission")) {
            // A valid host-sized command whose baseline envelope cannot fit must fail at submission,
            // not disappear from the pool later or masquerade as a finalized business outcome.
            assertThatThrownBy(() -> cluster.nodes[0].submit("source.command.v1", new byte[65_536]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COMMAND_PAYLOAD_TOO_LARGE");
            // No baseline subscriber: the full host-sized command needs no body-carrying event.
            ids.add(submit(cluster, 0, "target.command.v1", new byte[65_536]));
            String topic = "source.command.v1";
            int overhead = TransitionScalars.encode(Map.of("topic", topic, "sender", new byte[32],
                    "messageId", new byte[32], "body", new byte[65000], "bodyHash", new byte[32],
                    "bodyLength", 65000L)).length - 65000;
            ids.add(submitWithFollowerCatchup(cluster, 0, topic, new byte[65_536 - overhead]));
        } else if (recipe.equals("procurement")) {
            // The recipe's eligibility guard is false for this large value, but the ordinary KV write
            // still commits. This catches the old accidental ~3.8 KiB cap on non-deriving commands.
            ids.add(submit(cluster, 0, "orders.command.v1",
                    KvRegistryContract.put(new byte[]{2}, new byte[60 * 1024])));
            ids.add(submit(cluster, 0, "suppliers.command.v1", KvRegistryContract.put(
                    KeyGenUtil.getPublicKeyFromPrivateKey(seeds.getFirst()),
                    "approved".getBytes(StandardCharsets.UTF_8))));
            ids.add(submit(cluster, 0, "orders.command.v1", KvRegistryContract.put(new byte[]{1}, new byte[]{42})));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.approve("01")));
            ids.add(submitWithFollowerCatchup(cluster, 1, "reviews.command.v1", ApprovalsContract.approve("01")));
        } else if (recipe.equals("attestation")) {
            byte[] payload = TransitionScalars.encode(Map.of("documentHash", new byte[32], "reference", "document-1"));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.propose("document-1", payload, 2, 0)));
            ids.add(submit(cluster, 0, "reviews.command.v1", ApprovalsContract.approve("document-1")));
            ids.add(submitWithFollowerCatchup(cluster, 1, "reviews.command.v1",
                    ApprovalsContract.approve("document-1")));
        } else {
            boolean dpp = recipe.startsWith("dpp");
            String collection = dpp ? "certificates" : "rounds";
            String key = dpp ? "certificate-1" : "cold-store-7/0";
            String policy = dpp ? "certification" : "round-close";
            String clause = dpp ? "independent-auditors" : "independent-publishers";
            byte[] value = dpp ? BindingCbor.encode(List.of(1L, "product-1", "independent-audit", "cert-body-a",
                    new byte[32], 1L, 0L))
                    : BindingCbor.encode(List.of(1L, 2L, 1L, 0L, 2L, List.of(), new byte[32], new byte[0]));
            var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                    List.of(AuthenticatedMapContract.Mutation.put(
                            collection, key.getBytes(StandardCharsets.UTF_8), value)),
                    List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                            0, AuthenticatedMapContract.AUTH_APPROVAL, policy, 1)));
            byte[] hash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                    AuthenticatedMapContract.genesisId(genesis),
                    AuthenticatedMapAuthorizationContract.actionCommitment(action));
            String proposer = dpp ? "certifier-a" : "ops-a";
            String first = dpp ? "auditor-a" : "publisher-a";
            String independent = dpp ? "auditor-b" : "publisher-b";
            ids.add(submit(cluster, 0, "reviews.v1", actorCommand(dpp, genesis.chainId(), policy, clause,
                    proposer, ActorStatementV1.Action.PROPOSE, hash,
                    AuthenticatedMapAuthorizationContract.encodeAction(action))));
            ids.add(submit(cluster, 0, "reviews.v1", actorCommand(dpp, genesis.chainId(), policy, clause,
                    first, ActorStatementV1.Action.APPROVE, hash, new byte[0])));
            ids.add(submitWithFollowerCatchup(cluster, 0, "reviews.v1", actorCommand(
                    dpp, genesis.chainId(), policy, clause,
                    independent, ActorStatementV1.Action.APPROVE, hash, new byte[0])));
        }
        return ids;
    }

    /**
     * The §6.3 command bodies on the DPP demo registry, signed with the deterministic demo actor keys: a
     * certificate proposal, approvals from cert-body-a and audit-guild-b, a forged approval, a direct claim by an
     * actor without operator, and an operator's direct event. Shared with the Studio fixture generator.
     */
    record RoleGatedCommands(byte[] propose, byte[] approve, byte[] outsideApprove, byte[] forgedApprove,
                             byte[] claimWithoutRole, byte[] operatorEvent) {
        static RoleGatedCommands of(AuthenticatedMapContract.Genesis genesis) throws Exception {
            String chain = genesis.chainId();
            var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                    List.of(AuthenticatedMapContract.Mutation.put(DppStarterProfile.CERTIFICATES,
                            DppStarterProfile.certificateKey("certificate-1"), BindingCbor.encode(List.of(1L,
                                    "product-1", "independent-audit", "cert-body-a", new byte[32], 1L, 0L)))),
                    List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                            0, AuthenticatedMapContract.AUTH_APPROVAL, DppStarterProfile.CERTIFICATION_POLICY, 1)));
            byte[] hash = AuthenticatedMapAuthorizationContract.approvalPayloadHash(
                    AuthenticatedMapContract.genesisId(genesis),
                    AuthenticatedMapAuthorizationContract.actionCommitment(action));
            String policy = DppStarterProfile.CERTIFICATION_POLICY;
            String clause = DppStarterProfile.CERTIFICATION_CLAUSE;
            var statement = new ActorStatementV1(ActorStatementV1.Action.APPROVE, chain, "qualification", policy, 1,
                    AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, hash, 500, "auditor-b", 1,
                    "auditor-b-k1", clause);
            byte[] claim = new DppValues.ClaimValue(DppStarterProfile.VISIBILITY_PUBLIC,
                    "recycled".getBytes(StandardCharsets.UTF_8), "green-labs", 0, 0, new byte[0]).encode();
            byte[] event = new DppValues.EventValue("shipped", "swift-logistics", 1_700_000_000L, "", new byte[0],
                    "").encode();
            return new RoleGatedCommands(
                    actorCommand(true, chain, policy, clause, "certifier-a", ActorStatementV1.Action.PROPOSE, hash,
                            AuthenticatedMapAuthorizationContract.encodeAction(action)),
                    actorCommand(true, chain, policy, clause, "auditor-a", ActorStatementV1.Action.APPROVE, hash,
                            new byte[0]),
                    actorCommand(true, chain, policy, clause, "auditor-b", ActorStatementV1.Action.APPROVE, hash,
                            new byte[0]),
                    new StagedActorCommandV1(SignedActorCommandV1.sign(statement,
                            DppGenesis.demoActorSeed("auditor-a")), new byte[0]).encode(),
                    directWrite(genesis, DppStarterProfile.CLAIMS,
                            DppStarterProfile.claimKey("product-1", "material", "claim-1"), claim,
                            DppStarterProfile.CLAIM_ISSUER_POLICY, "issuer-a", 1),
                    directWrite(genesis, DppStarterProfile.EVENTS, DppStarterProfile.eventKey("product-1", "event-1"),
                            event, DppStarterProfile.OPERATOR_POLICY, "logistics-a", 2));
        }
    }

    /** ADR-031.4 §6 recipes and the §5.10 holding cap. */
    static final List<String> TYPED_VIEW_RECIPES = List.of("asset-governed-limits", "dpp-namespace-isolation",
            "feed-slot-rules", "balances-holding-cap");

    /**
     * One message of an ADR-031.4 recipe: the member that submits it (node index), its topic and body, and its
     * expected refusal ({@code null} when accepted). Shared with the Studio fixture generator.
     */
    record TypedViewStep(int node, String topic, byte[] body, Refusal refusal) {
        static List<TypedViewStep> of(String recipe, AuthenticatedMapContract.Genesis genesis) {
            List<String> senders = memberSeeds().stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                    .map(BindingRecipesIT::hex).toList();
            List<TypedViewStep> steps = new ArrayList<>();
            switch (recipe) {
                case "asset-governed-limits" -> {
                    for (String sender : senders) {
                        steps.add(new TypedViewStep(0, "token.v1", BalancesContract.mint(sender,
                                BigInteger.valueOf(10_000)), null));
                    }
                    // Within the governed maximum (1000), the first member's tier (500), and its lock-up.
                    steps.add(new TypedViewStep(0, "token.v1", transfer(senders.get(2), 100), null));
                    steps.add(new TypedViewStep(0, "token.v1", transfer(senders.get(2), 2_000),
                            Refusal.denied(0, "governed-transfer-limit", 0, "TRANSFER_LIMIT_EXCEEDED", null,
                                    Set.of())));
                    steps.add(new TypedViewStep(0, "token.v1", transfer(senders.get(2), 700),
                            Refusal.denied(1, "tier-limit", 1, "TIER_LIMIT_EXCEEDED", null, Set.of())));
                    // The second member acquired at height 1,000,000 and is locked; the third holds no record.
                    steps.add(new TypedViewStep(1, "token.v1", transfer(senders.get(2), 10),
                            Refusal.denied(2, "lock-up", 0, "LOCKED", null, Set.of())));
                    steps.add(new TypedViewStep(2, "token.v1", transfer(senders.get(0), 10),
                            Refusal.denied(1, "tier-limit", 0, "TIER_LIMIT_EXCEEDED", null, Set.of())));
                    steps.add(new TypedViewStep(0, "token.v1", transfer(senders.get(1), 50), null));
                }
                case "dpp-namespace-isolation" -> {
                    String events = DppStarterProfile.EVENTS;
                    String policy = DppStarterProfile.OPERATOR_POLICY;
                    byte[] swift = event("swift-logistics");
                    byte[] acme = event("acme-manufacturing");
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(put(events,
                            "swift-logistics/p1/e1", swift)), policy, "logistics-a", 0x31), null));
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(put(events,
                            "acme-manufacturing/p1/e2", swift)), policy, "logistics-a", 0x32),
                            Refusal.denied(0, "manufacturer-owns-product", 0, "FOREIGN_PRODUCT", 0,
                                    Set.of(ACTOR_WORK_KEY))));
                    // In a batch the receipt names the write that decided.
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(
                            put(events, "swift-logistics/p1/e3", swift), put(events, "green-labs/p1/e4", swift)),
                            policy, "logistics-a", 0x33), Refusal.denied(0, "manufacturer-owns-product", 0,
                            "FOREIGN_PRODUCT", 1, Set.of(ACTOR_WORK_KEY))));
                    // An operator may write its organization's events but not revoke them; a manufacturer may.
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(revoke(events,
                            "swift-logistics/p1/e1", swift)), policy, "logistics-a", 0x34),
                            Refusal.denied(1, "admin-only-lifecycle-ops", 0, "ADMIN_ROLE_REQUIRED", 0,
                                    Set.of(ACTOR_WORK_KEY))));
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(put(events,
                            "acme-manufacturing/p2/e1", acme)), policy, "maker-a", 0x35), null));
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(revoke(events,
                            "acme-manufacturing/p2/e1", acme)), policy, "maker-a", 0x36), null));
                }
                case "feed-slot-rules" -> {
                    String policy = DppStarterProfile.OPERATOR_POLICY;
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(observation(
                            "logistics-a/1", 150)), policy, "logistics-a", 0x41), null));
                    // The fact rule runs after the kernel approved: a replaceable value or another source's slot.
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(
                            AuthenticatedMapContract.Mutation.put("observations", bytes("logistics-a/2"), price(150))),
                            policy, "logistics-a", 0x42), Refusal.denied(1, "own-insert-only-slot", 0,
                            "OBSERVATION_REJECTED", 0, Set.of(ACTOR_WORK_KEY))));
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(observation(
                            "maker-a/1", 150)), policy, "logistics-a", 0x43), Refusal.denied(1,
                            "own-insert-only-slot", 0, "OBSERVATION_REJECTED", 0, Set.of(ACTOR_WORK_KEY))));
                    // The range rule reads the feed record in the admission slot, before any work is reserved.
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(observation(
                            "logistics-a/3", 250)), policy, "logistics-a", 0x44), Refusal.denied(0,
                            "feed-open-and-in-range", 1, "OBSERVATION_OUT_OF_RANGE", 0, Set.of())));
                    steps.add(new TypedViewStep(0, "registry.v1", mapWrite(genesis, List.of(observation(
                            "logistics-a/4", 200)), policy, "logistics-a", 0x45), null));
                }
                case "balances-holding-cap" -> {
                    String alice = senders.get(0);
                    String bob = senders.get(1);
                    steps.add(new TypedViewStep(0, "points.command.v1", BalancesContract.mint(alice,
                            BigInteger.valueOf(20_000)), null));
                    steps.add(new TypedViewStep(0, "points.command.v1", BalancesContract.mint(bob,
                            BigInteger.valueOf(20_000)), null));
                    steps.add(new TypedViewStep(0, "points.command.v1", transfer(bob, 5_000), null));
                    // Bob would hold 25,001: the kernel's post-state fact is over the cap.
                    steps.add(new TypedViewStep(0, "points.command.v1", transfer(bob, 1),
                            Refusal.denied(0, "holding-cap", 0, "HOLDING_CAP_EXCEEDED", null, Set.of())));
                    steps.add(new TypedViewStep(0, "points.command.v1", transfer(senders.get(2), 100), null));
                }
                default -> throw new IllegalArgumentException(recipe);
            }
            return List.copyOf(steps);
        }

        private static byte[] transfer(String to, long amount) {
            return BalancesContract.transfer(to, BigInteger.valueOf(amount));
        }

        private static byte[] event(String organization) {
            return new DppValues.EventValue("shipped", organization, 1_700_000_000L, "", new byte[0], "").encode();
        }

        private static AuthenticatedMapContract.Mutation put(String collection, String key, byte[] value) {
            return AuthenticatedMapContract.Mutation.put(collection, bytes(key), value);
        }

        /** Revokes the first revision of an entry written with {@code value}. */
        private static AuthenticatedMapContract.Mutation revoke(String collection, String key, byte[] value) {
            return AuthenticatedMapContract.Mutation.revoke(collection, bytes(key), 1,
                    AuthenticatedMapContract.logicalValueHash(value));
        }

        private static AuthenticatedMapContract.Mutation observation(String key, long price) {
            return AuthenticatedMapContract.Mutation.putIfAbsent("observations", bytes(key), price(price));
        }

        /** A canonical {@code {price: uint}} observation value. */
        private static byte[] price(long price) {
            return BindingCbor.encode(Map.of("price", price));
        }

        private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    }

    /** An {@code apply-authorized} map command whose single direct authorization covers every write. */
    private static byte[] mapWrite(AuthenticatedMapContract.Genesis genesis,
                                   List<AuthenticatedMapContract.Mutation> mutations, String policy, String actor,
                                   int authorization) {
        List<AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1> assignments = new ArrayList<>();
        List<Integer> covered = new ArrayList<>();
        for (int index = 0; index < mutations.size(); index++) {
            assignments.add(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(index,
                    AuthenticatedMapContract.AUTH_GOVERNED_ROLE, policy, 1));
            covered.add(index);
        }
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(mutations.size() > 1, mutations,
                assignments);
        byte[] seed = DppGenesis.demoActorSeed(actor);
        byte[] authorizationId = new byte[32];
        authorizationId[0] = (byte) authorization;
        var signed = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(authorizationId,
                genesis.chainId(), AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action), covered, policy, 1, actor, 1,
                actor + "-k1", KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1,
                DppStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS, seed);
        return TransitionScalars.encode(Map.of("command", AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of(signed)))));
    }

    /**
     * ADR-031.3 §6.3 and §7 items 6 to 8 on the DPP demo registry. Both rules read verified facts, so they run
     * only after the kernels verified the signatures; their refusals keep the actor-owned work reservation.
     */
    private static void submitRoleGated(AuthenticatedMapContract.Genesis genesis, Cluster cluster, List<String> ids,
                                        Map<String, Refusal> denials) throws Exception {
        var commands = RoleGatedCommands.of(genesis);
        ids.add(submit(cluster, 0, "reviews.v1", commands.propose()));
        ids.add(submit(cluster, 0, "reviews.v1", commands.approve()));
        // The same signed approval in a new message from another member: an exact replay that decide verifies
        // again, so its facts exist and allowed-organization holds (ADR-031.3 §5.5).
        String replayed = submit(cluster, 1, "reviews.v1", commands.approve());
        ids.add(replayed);
        var replayReceipt = BindingReceiptV1.decode(cluster.nodes[0].query("composite/binding-receipt-v1/"
                + replayed, new byte[0]).payload());
        assertThat(replayReceipt.accepted()).isTrue();
        assertThat(replayReceipt.steps().getFirst().rules()).isEqualTo(new BindingReceiptV1.RuleTrace(1, null));
        // §7.6: an auditor from audit-guild-b is verified, then refused by allowed-organization.
        String independent = submit(cluster, 0, "reviews.v1", commands.outsideApprove());
        ids.add(independent);
        denials.put(independent, Refusal.denied("allowed-organization", "ORGANIZATION_NOT_ALLOWED",
                Set.of(ACTOR_WORK_KEY)));
        // §7.7: a forged approval fails the kernel's own signature check; no rule runs.
        String forged = submit(cluster, 0, "reviews.v1", commands.forgedApprove());
        ids.add(forged);
        denials.put(forged, new Refusal("INVALID_SIGNATURE", BindingReceiptV1.RuleTrace.NONE, Set.of(ACTOR_WORK_KEY)));
        // §7.8: a direct write by an actor without operator is verified by the map, then refused.
        String withoutRole = submit(cluster, 0, "registry.v1", commands.claimWithoutRole());
        ids.add(withoutRole);
        denials.put(withoutRole, Refusal.denied("operator-for-direct-writes", "ROLE_REQUIRED",
                Set.of(ACTOR_WORK_KEY)));
        // An operator's direct write passes.
        ids.add(submitWithFollowerCatchup(cluster, 0, "registry.v1", commands.operatorEvent()));
    }

    /** An {@code apply-authorized} map command with one direct actor authorization covering its single write. */
    private static byte[] directWrite(AuthenticatedMapContract.Genesis genesis, String collection, byte[] key,
                                      byte[] value, String policy, String actor, int authorization) {
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                List.of(AuthenticatedMapContract.Mutation.put(collection, key, value)),
                List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, policy, 1)));
        byte[] seed = DppGenesis.demoActorSeed(actor);
        byte[] authorizationId = new byte[32];
        authorizationId[0] = (byte) authorization;
        var signed = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(authorizationId,
                genesis.chainId(), AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action), List.of(0), policy, 1, actor, 1,
                actor + "-k1", KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1,
                DppStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS, seed);
        return TransitionScalars.encode(Map.of("command", AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of(signed)))));
    }

    /**
     * Local ingress of an ADR-031.3 static rule. The node's submission reports the code with structured details
     * (ADR-031.4 §5.9, bloxbean/yano#153), and the catalog-built composite the nodes run gives the same refusal.
     */
    private record Ingress(AppStateMachine machine, String chain) {
        void rejects(Cluster cluster, String topic, byte[] body, String rule, String deny) {
            assertThatThrownBy(() -> cluster.nodes[0].submit(topic, body))
                    .isInstanceOfSatisfying(AppSubmissionRejectedException.class, rejected -> {
                        assertThat(rejected.code()).isEqualTo("ADMISSION_RULE_DENIED");
                        assertThat(rejected.details()).containsExactly(Map.entry("rule", rule),
                                Map.entry("deny", deny));
                    });
            var message = AppMessage.builder().messageId(new byte[32]).chainId(chain).topic(topic)
                    .sender(new byte[32]).senderSeq(1).expiresAt(0).body(body).authScheme(0)
                    .authProof(new byte[0]).build();
            var refused = machine.validate(message);
            assertThat(refused.reason()).isEqualTo("ADMISSION_RULE_DENIED");
            assertThat(refused.details()).containsExactly(Map.entry("rule", rule), Map.entry("deny", deny));
        }
    }

    private static byte[] actorCommand(boolean dpp, String chain, String policy, String clause, String actor,
                                         ActorStatementV1.Action action, byte[] hash, byte[] staged) throws Exception {
        String domain = dpp ? "yano-dpp-starter-demo-actor:" : "yano-attestation-feed-demo-actor:";
        byte[] seed = MessageDigest.getInstance("SHA-256").digest((domain + actor).getBytes(StandardCharsets.UTF_8));
        var statement = new ActorStatementV1(action, chain, "qualification", policy, 1,
                AuthenticatedMapAuthorizationContract.APPROVAL_PAYLOAD_DOMAIN, hash, 500, actor, 1, actor + "-k1",
                action == ActorStatementV1.Action.APPROVE ? clause : "");
        return new StagedActorCommandV1(SignedActorCommandV1.sign(statement, seed), staged).encode();
    }

    private static String submit(Cluster cluster, int node, String topic, byte[] body) throws Exception {
        String id = cluster.nodes[node].submit(topic, body);
        await(() -> Arrays.stream(cluster.nodes).filter(java.util.Objects::nonNull).allMatch(peer -> {
            try { return peer.query("composite/binding-receipt-v1/" + id, new byte[0]).payload().length > 0; }
            catch (RuntimeException notFinalized) { return false; }
        }));
        return id;
    }

    /** Stops a non-proposer, commits the cascade using the remaining quorum, then catches up its retained store. */
    private static String submitWithFollowerCatchup(Cluster cluster, int sender, String topic, byte[] body)
            throws Exception {
        int follower = -1;
        for (int index = 0; index < 3; index++) {
            String member = hex(KeyGenUtil.getPublicKeyFromPrivateKey(memberSeeds().get(index)));
            if (index != sender && !member.equals(cluster.configs.getFirst().proposerKeyHex())) follower = index;
        }
        assertThat(follower).isNotNegative();
        long previous = cluster.nodes[follower].tipHeight();
        cluster.stop(follower);
        String id = submit(cluster, sender, topic, body);
        long target = cluster.nodes[sender].tipHeight();
        assertThat(target).isGreaterThan(previous);
        // Pin the surviving fixture-owned quorum's context before the recovering peer can supply any proof/header.
        cluster.finalizedContextPin = cluster.nodes[sender].block(target).orElseThrow().consensusContextDigest();
        cluster.start(follower);
        await(() -> Arrays.stream(cluster.nodes).allMatch(node -> node.tipHeight() == target));
        byte[] expected = cluster.nodes[sender].query("composite/binding-receipt-v1/" + id, new byte[0]).payload();
        for (var node : cluster.nodes) {
            assertThat(node.stateRoot()).isEqualTo(cluster.nodes[sender].stateRoot());
            assertThat(node.query("composite/binding-receipt-v1/" + id, new byte[0]).payload()).isEqualTo(expected);
        }
        return id;
    }

    /** Verifies the receipt's certified membership proof against fixture-owned membership and initial identity pins. */
    private static void verifyReceiptProof(AppChainSubsystem node, byte[] key, Set<String> members, String chain,
                                           StateCommitmentIdentity pinnedIdentity, byte[] pinnedContext) {
        var envelope = node.stateProofEnvelope(key).orElseThrow();
        var nativeProof = envelope.proof();
        var snapshot = nativeProof.snapshot();
        var header = AppBlockHeader.from(node.block(snapshot.height()).orElseThrow());
        assertThat(header.consensusContextDigest()).isEqualTo(pinnedContext);
        var metadata = ProofVerifier.profileMetadata(pinnedIdentity.profile().id()).orElseThrow();
        var block = new AppChainClient.CertifiedBlockHeader(header.version(), header.height(), hex(header.prevHash()),
                header.l1Slot(), hex(header.l1BlockHash()), header.timestamp(), hex(header.messagesRoot()),
                hex(header.stateRoot()), hex(header.blockHash()), header.view(), hex(header.consensusContextDigest()),
                hex(header.proposer()), hex(header.justificationDigest()));
        var certificate = new AppChainClient.FinalityCertificate(envelope.finalityCertificate().scheme(),
                envelope.finalityCertificate().signatures().stream().map(signature ->
                        new AppChainClient.FinalitySignature(hex(signature.signer()), hex(signature.signature())))
                        .toList());
        var proof = new AppChainClient.Proof(hex(key), chain, hex(snapshot.stateRoot()), hex(nativeProof.nativeProof()),
                hex(nativeProof.value()), null, snapshot.height(), envelope.proofSchemaVersion(), metadata.id(),
                metadata.backend(), metadata.commitmentFormatId(), metadata.formatFingerprintHex(),
                hex(snapshot.identity().genesisId()), metadata.proofEncodingId(), metadata.nativeVersioning(),
                metadata.physicalDelete(), snapshot.height(), AppChainClient.ProofPresence.PRESENT, block, certificate);
        var trust = new ProofVerifier.FinalityTrustContext(chain, metadata.id(), hex(pinnedIdentity.genesisId()),
                members, 2, hex(pinnedContext));
        assertThat(ProofVerifier.verifyCertified(proof, trust)).isTrue();
    }

    /**
     * The exact offline catalog context the live recipe qualification uses for {@code recipe}: product genesis
     * chains are governed, procurement enables effects. Shared with the Studio round-trip test.
     */
    static BindingCatalogSession.ContextInput recipeContext(String recipe, String yaml) throws Exception {
        var genesis = productGenesis(yaml);
        String chain = genesis == null ? "binding-" + recipe + "-parity" : genesis.chainId();
        List<byte[]> seeds = memberSeeds();
        List<String> members = seeds.stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(BindingRecipesIT::hex).sorted().toList();
        var identity = StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                MessageDigest.getInstance("SHA-256").digest(chain.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> hostSettings = genesis != null ? Map.of("membership.mode", "governed")
                : recipe.equals("procurement") ? Map.of("effects.enabled", "true") : Map.of();
        long interval = genesis == null ? 100 : 1000;
        var base = configuration(chain, seeds.getFirst(), members, identity, hostSettings, interval, List.of());
        var contextSettings = new LinkedHashMap<>(identity.settings());
        contextSettings.putAll(hostSettings);
        return new BindingCatalogSession.ContextInput(chain, contextSettings,
                AppChainEffectsConfig.from(base).consensusProfile(base), new AppChainMembershipEpoch(0, members, 2));
    }

    static AuthenticatedMapContract.Genesis productGenesis(String yaml) throws Exception {
        var document = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        for (var component : document.path("composite").path("components")) {
            if (component.path("machine").asText().equals("authenticated-map-component")) {
                return AuthenticatedMapContract.decodeGenesis(HexFormat.of()
                        .parseHex(component.path("config").path("genesis-cbor-hex").asText()));
            }
        }
        return null;
    }

    static AppChainConfig configuration(String chain, byte[] seed, List<String> members,
                                                  StateCommitmentIdentity identity, Map<String, String> settings,
                                                  long interval, List<AppChainConfig.AppPeer> peers) {
        return AppChainConfig.builder(chain).signingKeyHex(hex(seed)).memberKeysHex(new LinkedHashSet<>(members))
                .proposerKeyHex(members.getFirst()).threshold(2).blockIntervalMs(interval).peers(peers)
                .stateCommitmentIdentity(identity).stateMachineId(BindingCatalogSession.MACHINE)
                .pluginSettings(settings).build();
    }
    static List<byte[]> memberSeeds() {
        List<byte[]> values = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            byte[] seed = new byte[32]; seed[0] = (byte) (111 + index); values.add(seed);
        }
        return values;
    }
    private static List<Integer> ports() throws Exception {
        Set<Integer> values = new LinkedHashSet<>();
        while (values.size() < 3) try (ServerSocket socket = new ServerSocket(0)) { values.add(socket.getLocalPort()); }
        return List.copyOf(values);
    }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("recipe cluster did not finalize its source message");
    }

    private static final class Cluster implements AutoCloseable {
        final List<AppChainConfig> configs;
        final List<Integer> ports;
        final Path directory;
        final PluginProviderRegistry providers;
        byte[] finalizedContextPin;
        final AppChainSubsystem[] nodes = new AppChainSubsystem[3];
        final NodeServer[] servers = new NodeServer[3];
        final Thread[] threads = new Thread[3];
        Cluster(List<AppChainConfig> configs, List<Integer> ports, Path directory,
                PluginProviderRegistry providers) throws Exception {
            this.configs = configs;
            this.ports = ports;
            this.directory = directory;
            this.providers = providers;
            try {
                for (int index = 0; index < 3; index++) start(index);
                await(() -> Arrays.stream(nodes).allMatch(node -> {
                    Object peers = node.status().get("peers");
                    return peers instanceof Map<?, ?> map && !map.isEmpty()
                            && map.values().stream().allMatch(Boolean.TRUE::equals);
                }));
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void start(int index) throws Exception {
            start(index, directory);
        }
        void start(int index, Path stateDirectory) throws Exception {
            var node = new AppChainSubsystem(configs.get(index), 42, null, null,
                    stateDirectory.resolve("node-" + index).toString(), null, providers,
                    LoggerFactory.getLogger(BindingRecipesIT.class));
            nodes[index] = node;
            node.start();
            var server = new NodeServer(ports.get(index),
                    N2NVersionTableConstant.v11AndAboveWithAppLayer(42, false, 0, false),
                    new EmptyL1(), null, null, node.serverAgentFactories());
            servers[index] = server;
            Thread thread = new Thread(server::start, "binding-recipe-server-" + index);
            threads[index] = thread;
            thread.setDaemon(true);
            thread.start();
        }
        void stop(int index) throws Exception {
            if (nodes[index] != null) { nodes[index].close(); nodes[index] = null; }
            if (servers[index] != null) { servers[index].shutdown(); servers[index] = null; }
            if (threads[index] != null) {
                threads[index].join(5000);
                assertThat(threads[index].isAlive()).isFalse();
                threads[index] = null;
            }
        }
        @Override public void close() throws Exception {
            for (int index = 0; index < 3; index++) stop(index);
        }
    }

    private static final class EmptyL1 implements ChainState {
        @Override public void storeBlock(byte[] hash, Long number, Long slot, byte[] block) { }
        @Override public byte[] getBlock(byte[] hash) { return null; }
        @Override public boolean hasBlock(byte[] hash) { return false; }
        @Override public void storeBlockHeader(byte[] hash, Long number, Long slot, byte[] header) { }
        @Override public byte[] getBlockHeader(byte[] hash) { return null; }
        @Override public byte[] getBlockByNumber(Long number) { return null; }
        @Override public byte[] getBlockHeaderByNumber(Long number) { return null; }
        @Override public Point findNextBlock(Point point) { return null; }
        @Override public Point findNextBlockHeader(Point point) { return null; }
        @Override public List<Point> findBlocksInRange(Point from, Point to) { return List.of(); }
        @Override public Point findLastPointAfterNBlocks(Point from, long count) { return null; }
        @Override public boolean hasPoint(Point point) { return false; }
        @Override public Point getFirstBlock() { return null; }
        @Override public Long getBlockNumberBySlot(Long slot) { return null; }
        @Override public Long getSlotByBlockNumber(Long number) { return null; }
        @Override public void rollbackTo(Long slot) { }
        @Override public ChainTip getTip() { return null; }
        @Override public ChainTip getHeaderTip() { return null; }
    }
}
