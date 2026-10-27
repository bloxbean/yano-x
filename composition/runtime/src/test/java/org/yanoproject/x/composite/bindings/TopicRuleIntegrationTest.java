package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.FinalityCert;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectIntent;
import org.yanoproject.api.appchain.effects.EffectId;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicKeys;
import org.yanoproject.api.appchain.topics.TopicMessageView;
import org.yanoproject.api.appchain.topics.TopicPayloadMessage;
import org.yanoproject.api.appchain.topics.TopicState;
import org.yanoproject.api.appchain.topics.TopicWire;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.appchain.topics.TopicWriteRules;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeWorkflowContext;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-038 IX3/IX6/IX7/IX8: verified host views, committed schema contracts and derived-topic boundaries. */
class TopicRuleIntegrationTest {
    private static final String GENERATION = "ab".repeat(32);
    private static final Key OWNER = key(9);
    private static final Key ISSUER = key(4);
    private static final Key SESSION = key(5);
    private static final byte[] RIDE = BindingCbor.encode(Map.of("frames", 1500L));
    private static final byte[] SCHEMA = schema(CborSchema.IntegerNode.uint());
    private static final byte[] NEXT_SCHEMA = schema(new CborSchema.IntegerNode(CborSchema.INTEGER_UINT,
            BigInteger.ZERO, BigInteger.valueOf(9000)));

    @Test
    void payloadSchemaContractIsCommittedAndTypeCheckedUnderEveryHash() {
        var definitions = definitions(SCHEMA, NEXT_SCHEMA);
        var ir = document(definitions, List.of(rule("bounds", ge(field(Scope.PAYLOAD, "frames"), number(1200)))),
                List.of(), false);
        assertThat(BindingIrV1.decode(ir.encode()).encode()).isEqualTo(ir.encode());
        assertThat(TopicRuleViews.commonFields(definitions)).containsExactly(Map.entry("frames", Type.INTEGER));
        assertThatThrownBy(() -> new Harness(document(definitions(SCHEMA, schema(CborSchema.TextNode.any())),
                ir.rules(), List.of(), false))).hasMessageContaining("frames");
        assertThatThrownBy(() -> TopicRuleViews.commonFields(Map.of("ff".repeat(32), SCHEMA)))
                .hasMessageContaining("hash does not match");
        assertThatThrownBy(() -> new Harness(document(Map.of(), ir.rules(), List.of(), false)))
                .isInstanceOfSatisfying(BindingValidationException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RULE_PAYLOAD_SCHEMA_REQUIRED"));
        byte[] retained = ir.encode();
        ir.components().getFirst().payloadSchemas().values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        assertThat(ir.encode()).isEqualTo(retained);
    }

    @Test
    void payloadProjectionUsesOnlyFirst32EligibleTopLevelScalarFields() {
        List<CborSchema.MapField> members = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            members.add(new CborSchema.MapField("f" + String.format("%02d", index), true,
                    CborSchema.IntegerNode.uint()));
        }
        members.add(new CborSchema.MapField("nested", true, new CborSchema.MapNode(List.of())));
        members.add(new CborSchema.MapField("bad-key", true, CborSchema.IntegerNode.uint()));
        members.add(new CborSchema.MapField("false", true, CborSchema.BooleanNode.any()));
        var projection = TopicRuleViews.fields(CborSchema.decode(CborSchema.of(new CborSchema.MapNode(members))
                .definition()));
        assertThat(projection).hasSize(32).containsKey("f00").containsKey("f31")
                .doesNotContainKeys("f32", "nested", "bad-key", "false");
    }

    @Test
    void staticPayloadRulesRefuseAtIngressAndApplyWithTheSameStructuredCode() {
        var h = new Harness(document(definitions(SCHEMA), List.of(rule("bounds",
                ge(field(Scope.PAYLOAD, "frames"), number(1200)))), List.of(), false));
        h.policy("games/", TopicWire.Writers.OPEN, SCHEMA, false, 1);
        var small = h.view(message(1, "games/rides", BindingCbor.encode(Map.of("frames", 1199L))));
        assertThat(h.engine.validate(small).reason()).isEqualTo("ADMISSION_RULE_DENIED");
        h.apply(1, small);
        assertThat(h.receipt(small).code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(h.receipt(small).steps().getFirst().rules().failure().denyCode()).isEqualTo("RIDE_FORBIDDEN");
        assertThat(h.states.get("rides").values).isEmpty();
        var allowed = h.view(message(2, "games/rides", RIDE));
        assertThat(h.engine.validate(allowed).isAccepted()).isTrue();
        h.apply(2, allowed);
        assertThat(h.receipt(allowed).accepted()).isTrue();
    }

    @Test
    void inheritedSchemaActivationRechecksMempoolAndMatchesRestartAndOfflineReplay() {
        var program = document(definitions(SCHEMA), List.of(rule("bounds",
                ge(field(Scope.PAYLOAD, "frames"), number(1200)))), List.of(), false);
        var running = new Harness(program);
        running.policy("games/", TopicWire.Writers.OPEN, SCHEMA, false, 1);
        running.inherit("games/rides", 1);
        var pending = message(1, "games/rides", RIDE);
        assertThat(running.engine.validate(running.view(pending)).isAccepted()).isTrue();
        running.apply(4, running.view(pending));
        var checkpoint = copy(running.registry);
        running.policy("games/", TopicWire.Writers.OPEN, NEXT_SCHEMA, false, 5);
        // A fresh host view at h=5 is required; the old mempool view is never the candidate input.
        var candidate = running.view(message(2, "games/rides", RIDE));
        assertThat(TopicMessageView.of(candidate).orElseThrow().policySources())
                .contains(new TopicWire.Source("games/", 5));
        assertThat(running.engine.validate(candidate).reason()).isEqualTo("RULE_PAYLOAD_SCHEMA_MISMATCH");
        running.apply(5, candidate);
        running.apply(6, running.view(message(3, "games/rides", RIDE)));
        assertThat(running.receipt(candidate).code()).isEqualTo("RULE_PAYLOAD_SCHEMA_MISMATCH");
        for (int run = 0; run < 2; run++) {
            var replay = new Harness(BindingIrV1.decode(program.encode()));
            restore(replay.registry, checkpoint);
            replay.policy("games/", TopicWire.Writers.OPEN, NEXT_SCHEMA, false, 5);
            var replayed = replay.view(message(2, "games/rides", RIDE));
            assertThat(replay.engine.validate(replayed).reason()).isEqualTo("RULE_PAYLOAD_SCHEMA_MISMATCH");
            replay.apply(5, replayed);
            assertThat(replay.receipt(replayed).encode()).isEqualTo(running.receipt(candidate).encode());
        }
        var evolved = new Harness(document(definitions(SCHEMA, NEXT_SCHEMA), program.rules(), List.of(), false));
        restore(evolved.registry, running.registry);
        assertThat(evolved.engine.validate(evolved.view(message(4, "games/rides", RIDE))).isAccepted()).isTrue();
    }

    @Test
    void absentSchemaAndOptionalPayloadFieldsRefuseWithoutDefaults() {
        var h = new Harness(document(definitions(SCHEMA), List.of(rule("bounds",
                ge(field(Scope.PAYLOAD, "frames"), number(1)))), List.of(), false));
        assertThat(h.engine.validate(message(1, "games/rides", RIDE)).reason())
                .isEqualTo("RULE_PAYLOAD_SCHEMA_MISMATCH");
        h.policy("games/", TopicWire.Writers.OPEN, null, false, 1);
        assertThat(h.engine.validate(h.view(message(2, "games/rides", RIDE))).reason())
                .isEqualTo("RULE_PAYLOAD_SCHEMA_MISMATCH");
        byte[] optional = CborSchema.of(new CborSchema.MapNode(List.of(new CborSchema.MapField("frames", false,
                CborSchema.IntegerNode.uint())))).definition();
        var optionalHarness = new Harness(document(definitions(optional), h.program.ir().rules(), List.of(), false));
        optionalHarness.policy("games/", TopicWire.Writers.OPEN, optional, false, 1);
        var empty = optionalHarness.view(message(3, "games/rides", BindingCbor.encode(Map.of())));
        assertThat(optionalHarness.engine.validate(empty).reason()).isEqualTo("RULE_FIELD_UNAVAILABLE");
        optionalHarness.apply(1, empty);
        assertThat(optionalHarness.receipt(empty).code()).isEqualTo("RULE_FIELD_UNAVAILABLE");
    }

    @Test
    void thresholdSignerViewsAreCanonicalAndContainNoIssuer() {
        Key a = key(1);
        Key b = key(2);
        var condition = and(eq(field(Scope.WRITER, "kind"), text("direct")),
                and(ge(new Call("size", List.of(field(Scope.WRITER, "signers"))), number(2)),
                        new Call("in", List.of(text(a.hex()), field(Scope.WRITER, "signers")))));
        var h = new Harness(document(Map.of(), List.of(rule("writers", condition)), List.of(), false));
        h.policy("games/", writers(2, a, b), null, false, 1);
        var one = h.view(signed(1, "games/rides", RIDE, null, a, b));
        var two = h.view(signed(2, "games/rides", RIDE, null, b, a));
        assertThat(TopicRuleViews.writer(one, false)).isEqualTo(TopicRuleViews.writer(two, false))
                .doesNotContainKey("issuer");
        assertThat(h.engine.validate(one).isAccepted()).isTrue();
        h.apply(1, one);
        assertThat(h.receipt(one).accepted()).isTrue();
        var issuer = new Harness(document(Map.of(), List.of(rule("issuer",
                eq(field(Scope.WRITER, "issuer"), text(ISSUER.hex())))), List.of(), false));
        assertThat(issuer.engine.validate(one).reason()).isEqualTo("RULE_FIELD_UNAVAILABLE");
    }

    @Test
    void delegatedSourceCarriesProvenanceButDerivedWriterHasNoAuthority() {
        var source = rule("delegated", eq(field(Scope.WRITER, "kind"), text("delegated")));
        var derived = rule("derived", and(eq(field(Scope.WRITER, "kind"), text("none")),
                and(eq(new Call("size", List.of(field(Scope.WRITER, "signers"))), number(0)),
                        and(eq(field(Scope.SOURCE, "writer.issuer"), text(ISSUER.hex())),
                                eq(field(Scope.SOURCE, "topic"), text("games/rides"))))));
        var h = new Harness(document(Map.of(), List.of(source), List.of(derived), true));
        h.policy("games/", writers(1, ISSUER), null, false, 1);
        h.policy("audit/", writers(1, OWNER), null, false, 1);
        var original = signed(1, "games/rides", RIDE, delegation(), SESSION);
        var viewed = h.view(original);
        assertThat(h.engine.validate(viewed).isAccepted()).isTrue();
        h.apply(1, viewed);
        assertThat(h.receipt(viewed).accepted()).isTrue();
        assertThat(h.receipt(viewed).steps()).hasSize(2);
        assertThat(h.receipt(viewed).sourceMessageId()).isEqualTo(original.getMessageId());
        assertThat(TopicRuleViews.writer(viewed, true)).containsEntry("kind", "none")
                .containsEntry("signers", List.of()).doesNotContainKey("issuer");
        assertThatThrownBy(() -> TopicRuleViews.payload(viewed, true, definitions(SCHEMA), Map.of()))
                .hasMessage("RULE_FIELD_UNAVAILABLE");
    }

    @Test
    void derivedPayloadAndIssuerAreUnavailableEvenWhenSourceHasThem() {
        for (var field : List.of(field(Scope.PAYLOAD, "frames"), field(Scope.WRITER, "issuer"))) {
            var derived = rule("unavailable", eq(field, field));
            var base = document(definitions(SCHEMA), List.of(), List.of(derived), true);
            var parts = new ArrayList<>(base.components());
            var target = parts.getLast();
            parts.set(1, new BindingIrV1.Component(target.id(), target.machineId(), target.ingressTopic(),
                    Map.of(), 0, 1, target.admission(), definitions(SCHEMA)));
            var h = new Harness(new BindingIrV1(parts, base.rules(), base.bindings(), base.limits(), 1));
            h.policy("games/", writers(1, ISSUER), SCHEMA, false, 1);
            h.policy("audit/", writers(1, OWNER), SCHEMA, false, 1);
            var message = h.view(signed(1, "games/rides", RIDE, delegation(), SESSION));
            h.apply(1, message);
            assertThat(h.receipt(message).code()).isEqualTo("RULE_FIELD_UNAVAILABLE");
            assertThat(h.states.values()).allSatisfy(state -> assertThat(state.values).isEmpty());
        }
    }

    @Test
    void crossingPermissionNeverBypassesRetirementOrUnregisteredRejectAndRollsBackTheCascade() {
        for (boolean flag : List.of(false, true)) {
            for (String policy : List.of("open", "protected", "retired", "unregistered", "fallback-open")) {
                var h = new Harness(document(Map.of(), List.of(), List.of(), flag));
                h.policy("games/", TopicWire.Writers.OPEN, null, false, 1);
                if (policy.equals("fallback-open")) h.config(false);
                else if (!policy.equals("unregistered")) h.policy("audit/",
                        policy.equals("protected") ? writers(1, OWNER) : TopicWire.Writers.OPEN,
                        null, policy.equals("retired"), 1);
                var message = h.view(message(1, "games/rides", RIDE));
                h.apply(1, message);
                String expected = switch (policy) {
                    case "protected" -> flag ? "" : "RULE_TOPIC_POLICY_CROSSING";
                    case "retired" -> "TOPIC_RETIRED";
                    case "unregistered" -> "TOPIC_UNREGISTERED";
                    default -> "";
                };
                assertThat(h.receipt(message).code()).as(policy + ": flag=" + flag).isEqualTo(expected);
                assertThat(h.receipt(message).accepted()).isEqualTo(expected.isEmpty());
                if (!expected.isEmpty()) {
                    assertThat(h.states.values()).allSatisfy(state -> assertThat(state.values).isEmpty());
                }
            }
        }
    }

    @Test
    void protectionActivationChangesStepOutcomeWithoutChangingProgramValidityAcrossRestartAndReplay() {
        var ir = document(Map.of(), List.of(), List.of(), false);
        var h = new Harness(ir);
        h.policy("games/", TopicWire.Writers.OPEN, null, false, 1);
        h.policy("audit/", TopicWire.Writers.OPEN, null, false, 1);
        var before = h.view(message(1, "games/rides", RIDE));
        h.apply(4, before);
        assertThat(h.receipt(before).accepted()).isTrue();
        h.policy("audit/", writers(1, OWNER), null, false, 5);
        var at = h.view(message(2, "games/rides", RIDE));
        h.apply(5, at);
        assertThat(h.receipt(at).code()).isEqualTo("RULE_TOPIC_POLICY_CROSSING");
        for (long height : List.of(5L, 6L)) {
            var restarted = new Harness(BindingIrV1.decode(ir.encode()));
            restore(restarted.registry, h.registry);
            var replayed = restarted.view(message(2, "games/rides", RIDE));
            restarted.apply(height, replayed);
            assertThat(restarted.receipt(replayed).code()).isEqualTo("RULE_TOPIC_POLICY_CROSSING");
            if (height == 5) assertThat(restarted.receipt(replayed).encode()).isEqualTo(h.receipt(at).encode());
        }
    }

    @Test
    void crossingPermissionNeverBypassesTargetPayloadLimitsOrSchemaAndRefusalsStayAtomic() {
        for (var writers : List.of(TopicWire.Writers.OPEN, writers(1, OWNER))) {
            for (String constraint : List.of("schema", "noncanonical", "missing-schema", "size", "allowed")) {
                var h = new Harness(document(Map.of(), List.of(), List.of(), true));
                h.policy("games/", TopicWire.Writers.OPEN, null, false, 1);
                byte[] ir = constraint.equals("schema") ? schema(CborSchema.TextNode.any()) : SCHEMA;
                h.policy("audit/", writers, ir, false, 1);
                if (constraint.equals("size")) {
                    h.entry("audit/", new TopicWire.Policy(writers, List.of(), null,
                            TopicWireCodec.blake2b256Hex(ir), (long) RIDE.length - 1), false, 1);
                }
                if (constraint.equals("missing-schema")) h.registry.delete(TopicKeys.schema(
                        TopicWireCodec.blake2b256Hex(ir)));
                byte[] payload = constraint.equals("noncanonical")
                        ? HexFormat.of().parseHex("a1666672616d65731a000005dc") : RIDE;
                var source = h.view(message(1, "games/rides", payload));
                h.apply(1, source);
                String expected = switch (constraint) {
                    case "size" -> "TOPIC_PAYLOAD_TOO_LARGE";
                    case "allowed" -> "";
                    default -> "TOPIC_SCHEMA_REJECTED";
                };
                assertThat(h.receipt(source).code()).as(constraint).isEqualTo(expected);
                if (!expected.isEmpty()) {
                    assertThat(h.states.values()).allSatisfy(state -> assertThat(state.values).isEmpty());
                }
            }
        }
    }

    @Test
    void derivedSchemaEvaluationReservesItsBoundBeforeRunningAndCannotEscapeTheCascadeBudget() {
        var base = document(Map.of(), List.of(), List.of(), true);
        var limits = base.limits();
        var bounded = new BindingIrV1.Limits(limits.maxCascadeDepth(), limits.maxDerivedPerSourceMessage(),
                limits.maxDerivedPerBlock(), limits.maxEventPayloadBytes(), limits.maxLookupsPerCondition(),
                limits.maxFunctionCallsPerMapping(), limits.maxFunctionInputBytes(), limits.maxExpressionNodes(),
                limits.maxExpressionDepth(), limits.maxExpressionValueBytes(), 262_144,
                limits.maxExpressionWorkPerBlock(), limits.maxRulesPerComponent());
        var h = new Harness(new BindingIrV1(base.components(), base.rules(), base.bindings(), bounded, 1));
        h.policy("games/", TopicWire.Writers.OPEN, null, false, 1);
        h.policy("audit/", TopicWire.Writers.OPEN, SCHEMA, false, 1);
        var source = h.view(message(1, "games/rides", RIDE));
        h.apply(1, source);
        assertThat(h.receipt(source).code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(h.states.values()).allSatisfy(state -> assertThat(state.values).isEmpty());
    }

    @Test
    void framedTypedCommandsExecuteTheSamePayloadWhileReceiptsKeepOriginalIdentities() {
        var ir = new BindingIrV1(List.of(BindingContextAndViewTest.component("a", List.of())), List.of(),
                List.of(), BindingIrV1.Limits.DEFAULT, 1);
        var open = new CascadeHarness(ir);
        var protectedChain = new CascadeHarness(ir);
        var registry = new Harness(document(Map.of(), List.of(), List.of(), false));
        registry.policy("a.v1", writers(1, OWNER), null, false, 1);
        var payload = CascadeHarness.transfer(500, "same transfer");
        var plain = message(1, "a.v1", payload);
        var envelope = signed(2, "a.v1", payload, null, OWNER);
        var framed = registry.view(envelope);
        assertThat(protectedChain.engine.validate(framed).isAccepted()).isTrue();
        open.apply(1, List.of(plain));
        protectedChain.apply(1, List.of(framed));
        assertThat(open.states.get("a").get(plain.getMessageId()).orElseThrow())
                .isEqualTo(protectedChain.states.get("a").get(envelope.getMessageId()).orElseThrow());
        assertThat(protectedChain.states.get("a").get(plain.getMessageId())).isEmpty();
        assertThat(protectedChain.receipt(framed).sourceMessageId()).isEqualTo(envelope.getMessageId());
        assertThat(protectedChain.receipt(framed).steps().getFirst().messageId()).isEqualTo(envelope.getMessageId());
        assertThat(envelope.getBody()).isNotEqualTo(payload);
        assertThat(framed.getBody()).isEqualTo(payload);
        registry.policy("a.v1", TopicWire.Writers.OPEN, schema(CborSchema.IntegerNode.uint()), false, 2);
        var fakeView = TopicPayloadMessage.of(plain, new TopicWriteRules.Result(null, null,
                registry.topicPolicy("a.v1"), payload, null, null, 0));
        assertThat(protectedChain.engine.validate(fakeView).reason())
                .isEqualTo("TOPIC_SCHEMA_INCOMPATIBLE");
    }

    private static BindingIrV1 document(Map<String, byte[]> schemas, List<BindingIrV1.AdmissionRule> sourceRules,
                                         List<BindingIrV1.AdmissionRule> targetRules, boolean crossing) {
        var source = new BindingIrV1.Component("rides", "ordered-log", "games/rides", Map.of(), 0, 1,
                sourceRules.stream().map(r -> new BindingIrV1.RuleAttachment(r.id(), Map.of())).toList(), schemas);
        var target = new BindingIrV1.Component("audit", "ordered-log", "audit/rides", Map.of(), 0, 1,
                targetRules.stream().map(r -> new BindingIrV1.RuleAttachment(r.id(), Map.of())).toList());
        var rules = new ArrayList<>(sourceRules);
        rules.addAll(targetRules);
        var edge = new BindingIrV1.Binding("audit", "rides", BindingProgram.BASELINE, List.of(),
                new BindingIrV1.CommandTarget("audit", "append", BindingIrV1.Mapping.raw("body")), crossing);
        // Keep single-component rule tests free of unrelated registry requirements.
        boolean cascade = crossing || !targetRules.isEmpty() || sourceRules.isEmpty();
        return new BindingIrV1(cascade ? List.of(source, target) : List.of(source), rules,
                cascade ? List.of(edge) : List.of(), BindingIrV1.Limits.DEFAULT, 1);
    }

    private static BindingIrV1.AdmissionRule rule(String id, Node condition) {
        return new BindingIrV1.AdmissionRule(id, "RIDE_FORBIDDEN", null, List.of(),
                List.of(new BindingIrV1.ExpressionClause(new BindingExpressionV1(Type.BOOLEAN, condition))));
    }
    private static Field field(Scope scope, String name) { return new Field(scope, name); }
    private static Literal number(long value) { return new Literal(value); }
    private static Literal text(String value) { return new Literal(value); }
    private static Call eq(Node a, Node b) { return new Call("eq", List.of(a, b)); }
    private static Call ge(Node a, Node b) { return new Call("ge", List.of(a, b)); }
    private static Call and(Node a, Node b) { return new Call("and", List.of(a, b)); }
    private static byte[] schema(CborSchema.Node node) {
        return CborSchema.of(new CborSchema.MapNode(List.of(new CborSchema.MapField("frames", true, node))))
                .definition();
    }
    private static Map<String, byte[]> definitions(byte[]... schemas) {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (byte[] schema : schemas) values.put(TopicWireCodec.blake2b256Hex(schema), schema);
        return values;
    }
    private static AppMessage message(int id, String topic, byte[] body) {
        return CascadeHarness.message(id, topic, body);
    }
    private static TopicWire.Writers writers(int threshold, Key... keys) {
        return new TopicWire.Writers(new TopicWire.KeySet(threshold,
                Arrays.stream(keys).map(Key::hex).sorted().toList()));
    }
    private static Key key(int value) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) value);
        return new Key(seed, HexFormat.of().formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed)));
    }
    private record Key(byte[] seed, String hex) {
        byte[] sign(byte[] bytes) { return CryptoConfiguration.INSTANCE.getSigningProvider().sign(bytes, seed); }
    }
    private static TopicWire.Delegation delegation() {
        var unsigned = new TopicWire.Delegation(SESSION.hex(), "games/", 1, 1000, ISSUER.hex(), new byte[64]);
        return new TopicWire.Delegation(SESSION.hex(), "games/", 1, 1000, ISSUER.hex(),
                ISSUER.sign(TopicWireCodec.delegationSignedBytes("chain", GENERATION, unsigned)));
    }
    private static AppMessage signed(int id, String topic, byte[] payload, TopicWire.Delegation delegation,
                                      Key... signers) {
        String nonce = String.format("%032x", id);
        String hash = delegation == null ? null : TopicWireCodec.delegationHash("chain", GENERATION, delegation);
        byte[] statement = TopicWireCodec.encodeWriteStatement(new TopicWire.WriteStatement("chain", GENERATION,
                topic, TopicWireCodec.blake2b256Hex(payload), nonce, 900, hash));
        var signatures = Arrays.stream(signers).sorted((a, b) -> a.hex().compareTo(b.hex()))
                .map(k -> new TopicWire.Signature(k.hex(), k.sign(statement))).toList();
        return message(id, topic, TopicWireCodec.encodeFrame(new TopicWire.Frame(payload,
                new TopicWire.WriterProof(nonce, 900, signatures, delegation))));
    }
    private static CascadeHarness.MemoryState copy(CascadeHarness.MemoryState source) {
        var target = new CascadeHarness.MemoryState();
        restore(target, source);
        return target;
    }
    private static void restore(CascadeHarness.MemoryState target, CascadeHarness.MemoryState source) {
        source.values.forEach((key, value) -> target.values.put(key, value.clone()));
    }

    private static final class Harness implements CompositeWorkflowContext {
        final CascadeHarness.MemoryState registry = new CascadeHarness.MemoryState();
        final CascadeHarness.MemoryState workflow = new CascadeHarness.MemoryState();
        final Map<String, CascadeHarness.MemoryState> states = new LinkedHashMap<>();
        final BindingProgram program;
        final EventBindingWorkflow engine;
        Harness(BindingIrV1 ir) {
            config(true);
            Map<String, TransitionKernel<?, ?>> kernels = new LinkedHashMap<>();
            Map<String, ComponentGeneration> generations = new LinkedHashMap<>();
            for (var component : ir.components()) {
                kernels.put(component.id(), new OrderedLogKernel());
                states.put(component.id(), new CascadeHarness.MemoryState());
                generations.put(component.id(), new ComponentGeneration(component.id(), "1", 1));
            }
            program = new BindingProgram(ir, kernels);
            engine = new EventBindingWorkflow(program, new WorkflowDescriptor(EventBindingWorkflow.ID, "1",
                    ir.components().stream().map(BindingIrV1.Component::ingressTopic).toList(), 1, 0,
                    List.copyOf(generations.values()), 0), generations, AppChainTestProfiles.enabledEffects(16));
        }
        void config(boolean reject) {
            registry.put(TopicKeys.config(), TopicWireCodec.encodeConfig(new TopicWire.Config(1, 1,
                    new TopicWire.Settings(true, null, false, reject, 3_600_000, 3_600_000,
                            4, 100, 100, 1, 600))));
        }
        void policy(String name, TopicWire.Writers writers, byte[] schema, boolean retired, long activation) {
            String hash = schema == null ? "" : TopicWireCodec.blake2b256Hex(schema);
            if (schema != null) registry.put(TopicKeys.schema(hash), schema);
            entry(name, new TopicWire.Policy(writers, List.of(ISSUER.hex()), 3_600_000L, hash, 65_536L),
                    retired, activation);
        }
        void inherit(String name, long activation) { entry(name, TopicWire.Policy.INHERIT, false, activation); }
        void entry(String name, TopicWire.Policy policy, boolean retired, long activation) {
            registry.put(TopicKeys.entry(name), TopicWireCodec.encodeEntryRecord(new TopicWire.EntryRecord(name,
                    new TopicWire.ActiveRevision(activation, activation, 1,
                            new TopicWire.EntryContent(writers(1, OWNER).keys(), policy, retired)), activation, 0, 1)));
        }
        AppMessage view(AppMessage envelope) {
            var result = TopicWriteRules.check(envelope.getTopic(), envelope.getBody(), "chain", GENERATION, 100,
                    new TopicState(registry, 65_536), Set.of());
            assertThat(result.allowed()).as(String.valueOf(result.refusal())).isTrue();
            return TopicPayloadMessage.of(envelope, result);
        }
        void apply(long height, AppMessage message) {
            var block = new AppBlock(1, "chain", height, new byte[32], 0, new byte[0], 100,
                    new byte[32], new byte[32], List.of(message), new byte[32], FinalityCert.empty());
            engine.apply(AppBlockExecutionContext.fromValidatedBlock(block), this);
        }
        BindingReceiptV1 receipt(AppMessage message) {
            return BindingReceiptV1.decode(workflow.get(message.getMessageId()).orElseThrow());
        }
        @Override public AppStateWriter state(ComponentGeneration participant) {
            return states.get(participant.componentId());
        }
        @Override public AppEffectEmitter effects(ComponentGeneration owner) {
            return new AppEffectEmitter() {
                @Override public EffectId emit(EffectIntent intent) {
                    throw new AssertionError("no effects in this recipe");
                }
                @Override public long pendingCount() { return 0; }
            };
        }
        @Override public AppStateWriter workflowState() { return workflow; }
        @Override public int remainingEffectCapacity() { return 0; }
        @Override public ClaimResult claim(String id, byte[] hash) { return ClaimResult.CLAIMED; }
        @Override public TopicWire.EffectiveOutcome topicPolicy(String topic) {
            return new TopicState(registry, 65_536).outcome(topic);
        }
        @Override public Optional<byte[]> topicSchema(String irHash) {
            return new TopicState(registry, 65_536).schema(irHash);
        }
    }
}
