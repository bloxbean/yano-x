package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateMachineResolver;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.x.composite.bindings.BindingValidationException;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 §11 criterion 2 through the real composite: rules read the typed views of balances, kv-registry, doc-trail
 * and approvals, and the post-state facts of kv-registry, doc-trail and approvals, exactly as the kernels declare them;
 * reads are type-checked at construction and fail closed at runtime.
 */
class StockViewReadsTest {
    private static final AppEffectEmitter NO_EFFECTS = AppEffectEmitter.rejecting("no effects");
    private static final String CHAIN = "stock-view-reads";
    private static final byte[] SENDER = KeyGenUtil.getPublicKeyFromPrivateKey(seed(0x51));
    private static final String SENDER_HEX = HexFormat.of().formatHex(SENDER);
    private static final byte[] OTHER = KeyGenUtil.getPublicKeyFromPrivateKey(seed(0x52));
    private static final List<String> MEMBERS = Stream.of(SENDER, OTHER).map(HexFormat.of()::formatHex).sorted()
            .toList();

    @Test
    void rulesReadEveryStockViewAndPostStateFactsAndFailClosedUntilTheyHold() {
        var machine = composite(document(List.of(
                // A target write needs the sender funded, an open registry entry, a started trail and an approved
                // item; each is one read of another stock component.
                rule("gated", "NOT_READY", List.of(
                        // A balances account id is the sender's key as hex text.
                        read("bal", "points", new BindingSourceV1.Function("hex", List.of(
                                new BindingSourceV1.Field(Scope.CONTEXT, "sender")))),
                        read("entry", "records", new BindingSourceV1.Literal("status")),
                        read("head", "trail", new BindingSourceV1.Literal("doc-1")),
                        read("item", "reviews", new BindingSourceV1.Literal("item-1"))),
                        call("ge", Field.read("bal", "balance"), new Literal(100L)),
                        call("eq", Field.read("entry", "valueText"), new Literal("open")),
                        call("ge", Field.read("head", "count"), new Literal(1L)),
                        call("eq", Field.read("item", "status"), new Literal("APPROVED"))),
                // Post-state facts of each kernel, as the engine admits them.
                rule("short-values", "VALUE_TOO_LONG", List.of(), call("or", call("not", new Field(Scope.FACTS,
                        "existed")), call("le", new Field(Scope.FACTS, "valueLength"), new Literal(8L)))),
                rule("two-entries", "TRAIL_FULL", List.of(), call("le", new Field(Scope.FACTS, "countAfter"),
                        new Literal(2L))),
                rule("no-self-approval", "SELF_APPROVAL", List.of(), call("not", call("and", new Field(Scope.FACTS,
                        "approvedNow"), new Field(Scope.FACTS, "proposerIsSender")))))),
                Map.of("target", List.of("gated"), "records", List.of("short-values"), "trail", List.of("two-entries"),
                        "reviews", List.of("no-self-approval")));
        var state = new MemoryState();
        machine.init(state, new AppChainInfo(CHAIN, SENDER_HEX, 1));
        var run = new Run(machine, state);
        // The sender holds no balance yet: the unguarded read of an absent account fails closed.
        assertThat(run.receipt("target.v1", KvRegistryContract.put(bytes("k"), bytes("v"))).code())
                .isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(run.accepted("points.v1", BalancesContract.mint(SENDER_HEX, BigInteger.valueOf(500)))).isTrue();
        assertThat(run.receipt("target.v1", KvRegistryContract.put(bytes("k"), bytes("v"))).steps().getFirst()
                .rules().failure()).isEqualTo(new BindingReceiptV1.RuleFailure("gated", 1, null));
        // A first put of any length is admitted (nothing existed); replacing it with a long value is not.
        assertThat(run.accepted("records.v1", KvRegistryContract.put(bytes("status"), bytes("closed-for-now"))))
                .isTrue();
        assertThat(run.receipt("records.v1", KvRegistryContract.put(bytes("status"), bytes("closed-for-good")))
                .code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(run.accepted("records.v1", KvRegistryContract.put(bytes("status"), bytes("open")))).isTrue();
        assertThat(run.accepted("trail.v1", DocTrailContract.append("doc-1", new byte[32], "r1"))).isTrue();
        assertThat(run.accepted("trail.v1", DocTrailContract.append("doc-1", new byte[32], "r2"))).isTrue();
        assertThat(run.receipt("trail.v1", DocTrailContract.append("doc-1", new byte[32], "r3")).code())
                .isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(run.accepted("reviews.v1", ApprovalsContract.propose("item-1", new byte[]{1}, 1, 0))).isTrue();
        // The proposer's own deciding vote is refused by the approvals fact rule; nothing changed.
        assertThat(run.receipt("reviews.v1", ApprovalsContract.approve("item-1")).code())
                .isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(run.receipt("target.v1", KvRegistryContract.put(bytes("k"), bytes("v"))).steps().getFirst()
                .rules().failure()).isEqualTo(new BindingReceiptV1.RuleFailure("gated", 3, "NOT_READY"));
        run.sender = OTHER;
        assertThat(run.accepted("reviews.v1", ApprovalsContract.approve("item-1"))).isTrue();
        run.sender = SENDER;
        assertThat(run.accepted("target.v1", KvRegistryContract.put(bytes("k"), bytes("v")))).isTrue();
    }

    @Test
    void readsAreTypeCheckedAgainstTheStockViewsAtConstruction() {
        var wrongField = rule("gated", "NOT_READY", List.of(read("bal", "points",
                new BindingSourceV1.Field(Scope.CONTEXT, "sender"))), call("ge", Field.read("bal", "amount"),
                new Literal(1L)));
        var wrongType = rule("gated", "NOT_READY", List.of(read("head", "trail", new BindingSourceV1.Literal("d"))),
                call("eq", Field.read("head", "count"), new Literal("one")));
        var textOnlyWhenUtf8 = rule("gated", "NOT_READY", List.of(read("entry", "target",
                new BindingSourceV1.Literal("k"))), call("eq", Field.read("entry", "valueText"), new Literal("x")));
        for (var entry : Map.of(wrongField, "RULE_READ_UNKNOWN_FIELD", wrongType, "EXPRESSION_INVALID",
                textOnlyWhenUtf8, "RULE_READ_UNKNOWN_FIELD").entrySet()) {
            assertThatThrownBy(() -> composite(document(List.of(entry.getKey())), Map.of("target",
                    List.of("gated")))).satisfies(thrown -> assertThat(root(thrown))
                    .isInstanceOfSatisfying(BindingValidationException.class, invalid ->
                            assertThat(invalid.code()).as(entry.getValue()).isEqualTo(entry.getValue())));
        }
    }

    /** Points (balances), records (utf8 kv-registry), trail (doc-trail), reviews (approvals) and target (raw kv). */
    private static List<BindingIrV1.Component> components(Map<String, List<String>> attachments) {
        record Stock(String id, String machine, Map<String, BindingSourceV1.Literal> config) { }
        List<BindingIrV1.Component> components = new ArrayList<>();
        for (var stock : List.of(new Stock("points", "balances", Map.of("minter", new BindingSourceV1.Literal(""))),
                new Stock("records", "kv-registry", Map.of("value-format", new BindingSourceV1.Literal("utf8"))),
                new Stock("reviews", "approvals", Map.of()), new Stock("target", "kv-registry",
                        Map.of("value-format", new BindingSourceV1.Literal("raw"))),
                new Stock("trail", "doc-trail", Map.of()))) {
            components.add(new BindingIrV1.Component(stock.id(), stock.machine(), stock.id() + ".v1",
                    new LinkedHashMap<>(stock.config()), 0, 1, attachments.getOrDefault(stock.id(), List.of())
                    .stream().map(rule -> new BindingIrV1.RuleAttachment(rule, Map.of())).toList()));
        }
        return components;
    }

    private record Document(List<BindingIrV1.AdmissionRule> rules) { }

    private static Document document(List<BindingIrV1.AdmissionRule> rules) { return new Document(rules); }

    private static AppStateMachine composite(Document document, Map<String, List<String>> attachments) {
        var ir = new BindingIrV1(components(attachments), document.rules().stream()
                .sorted((left, right) -> left.id().compareTo(right.id())).toList(), List.of(),
                BindingIrV1.Limits.DEFAULT, 1);
        return new DeclarativeCompositeProvider().create(context(ir));
    }

    private static BindingIrV1.AdmissionRule rule(String id, String deny, List<BindingIrV1.Read> reads,
                                                  Node... clauses) {
        return new BindingIrV1.AdmissionRule(id, deny, null, List.of(), reads, List.of(clauses).stream()
                .map(clause -> (BindingIrV1.Clause) new BindingIrV1.ExpressionClause(new BindingExpressionV1(
                        BindingExpressionV1.Type.BOOLEAN, clause))).toList());
    }

    private static BindingIrV1.Read read(String name, String component, BindingSourceV1 key) {
        return new BindingIrV1.Read(name, component, "", key);
    }

    private static Call call(String operator, Node... arguments) { return new Call(operator, List.of(arguments)); }

    /** Applies one message per block and returns its receipt. */
    private static final class Run {
        private final AppStateMachine machine;
        private final MemoryState state;
        private long height = 1;
        byte[] sender = SENDER;

        /** The genesis block (height 1) commits the profile before any message. */
        Run(AppStateMachine machine, MemoryState state) {
            this.machine = machine;
            this.state = state;
            apply(List.of());
        }

        boolean accepted(String topic, byte[] body) { return receipt(topic, body).accepted(); }

        BindingReceiptV1 receipt(String topic, byte[] body) {
            height++;
            byte[] id = new byte[32];
            id[0] = (byte) height;
            var message = AppMessage.builder().messageId(id).chainId(CHAIN).topic(topic).sender(sender)
                    .senderSeq(height).expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[0])
                    .build();
            apply(List.of(message));
            return BindingReceiptV1.decode(machine.query("composite/binding-receipt-v1/"
                    + HexFormat.of().formatHex(id), new byte[0], state));
        }

        private void apply(List<AppMessage> messages) {
            machine.apply(AppBlockExecutionContext.fromValidatedBlock(new AppBlock(AppBlock.BLOCK_VERSION, CHAIN,
                    height, new byte[32], 0, new byte[0], 1_000 * height, new byte[32], new byte[32], messages,
                    SENDER, FinalityCert.empty())), state, NO_EFFECTS);
            state.height = height;
        }
    }

    private static AppStateMachineContext context(BindingIrV1 ir) {
        var config = AppChainConfig.builder(CHAIN).signingKeyHex(HexFormat.of().formatHex(seed(0x51)))
                .memberKeysHex(new LinkedHashSet<>(MEMBERS)).proposerKeyHex(MEMBERS.getFirst())
                .threshold(1).stateCommitmentIdentity(StdlibTestStateCommitments.mpf(CHAIN)).build();
        StateCommitmentIdentity identity = StdlibTestStateCommitments.mpf(CHAIN);
        Map<String, String> settings = new TreeMap<>(identity.settings());
        settings.put(DeclarativeCompositeProvider.IR_SETTING, HexFormat.of().formatHex(ir.encode()));
        AppChainConsensusProfile consensus = AppChainEffectsConfig.from(config).consensusProfile(config);
        var membership = new AppChainMembershipEpoch(0, MEMBERS, 1);
        return new AppStateMachineContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public Map<String, String> settings() { return Map.copyOf(settings); }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() { return Optional.of(consensus); }
            @Override public Optional<AppChainMembershipView> membershipView() {
                return Optional.of(height -> membership);
            }
            @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
                return Optional.of(identity);
            }
            @Override public Optional<AppStateMachineResolver> stateMachineResolver() {
                return Optional.of((id, child) -> StdlibTestPluginProviders.registry()
                        .require(AppStateMachineProvider.class, id).create(child));
            }
        };
    }

    private static Throwable root(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null && !(root instanceof BindingValidationException)) root = root.getCause();
        return root;
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        seed[0] = (byte) value;
        return seed;
    }

    private static final class MemoryState implements AppStateWriter, AppQueryContext {
        private final Map<String, byte[]> values = new TreeMap<>();
        private long height;
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return height; }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
    }
}
