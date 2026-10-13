package org.yanoproject.x.stdlib;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract.MapActionV1;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract.Mutation;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema.MapField;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * ADR-031.4 §5.2 and §5.4 views of the authenticated map: one value view per collection over its stored entries and
 * the schema's top-level scalar members, and the write view of a command. Coverage in governed collections is
 * covered with the verified evidence in {@link AuthenticatedMapRuleFactsTest} and
 * {@link AuthenticatedMapTransitionKernelTest}.
 */
class AuthenticatedMapTypedViewsTest {
    private static final byte[] OWNER = new byte[32];
    private static final AuthenticatedMapSchema.Node UINT = AuthenticatedMapSchema.IntegerNode.uint();
    private static final AuthenticatedMapSchema.Node TEXT = AuthenticatedMapSchema.TextNode.any();

    /** {@code priced}: a typed product value; {@code labels}: {@code price} as text; {@code blobs}: opaque. */
    private final AuthenticatedMapTransitionKernel kernel = kernel(List.of(
            collection("blobs", AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""),
            collection("labels", AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, "label-schema"),
            collection("priced", AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, "priced-schema")),
            List.of(AuthenticatedMapContract.ValidatorDescriptor.schema("label-schema", AuthenticatedMapSchema.of(
                            new AuthenticatedMapSchema.MapNode(List.of(new MapField("price", false, TEXT))))
                            .definition()),
                    AuthenticatedMapContract.ValidatorDescriptor.schema("priced-schema", pricedSchema())));

    @Test
    void eachCollectionIsAViewOfEntriesAndItsSchemasScalarMembers() {
        var views = kernel.ruleValueViews();
        assertThat(views).extracting(RuleValueView::namespace).containsExactly("blobs", "labels", "priced");
        for (var view : views) {
            assertThat(view.fields()).extracting(RuleFact::name).containsExactly("status", "revision",
                    "createdHeight", "lastMutationHeight", "valueLength", "controller");
        }
        assertThat(views.get(0).valueFields()).isEmpty();
        assertThat(views.get(1).valueFields()).extracting(RuleFact::name, RuleFact::type)
                .containsExactly(tuple("price", RuleFact.Type.TEXT));
        // Only single-kind scalar members with CEL-identifier keys, in the schema's canonical member order (shorter
        // keys first); `present` stays addressable as value.present.
        assertThat(views.get(2).valueFields()).extracting(RuleFact::name).containsExactly("big", "flag", "name",
                "price", "present");
    }

    @Test
    void readKeysAreCanonicalCollectionKeysWithinTheCollectionBound() {
        assertThat(kernel.ruleValueKey("priced", bytes("sku-1")))
                .isEqualTo(AuthenticatedMapContract.canonicalKey("priced", bytes("sku-1")));
        assertThatThrownBy(() -> kernel.ruleValueKey("unknown", bytes("sku-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.ruleValueKey("priced", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kernel.ruleValueKey("priced", new byte[65]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedEntriesRoundTripThroughTheirValueView() {
        var state = new State();
        byte[] value = value(Map.of("name", new UnicodeString("widget"), "price", new UnsignedInteger(12),
                "flag", SimpleValue.TRUE, "present", new UnicodeString("yes")));
        decide(state, 3, Mutation.put("priced", bytes("sku-1"), value));
        var fields = read(state, "priced", "sku-1");
        assertThat(fields).containsEntry("status", "ACTIVE").containsEntry("revision", 1L)
                .containsEntry("createdHeight", 3L).containsEntry("lastMutationHeight", 3L)
                .containsEntry("valueLength", (long) value.length).containsEntry("value.name", "widget")
                .containsEntry("value.price", 12L).containsEntry("value.flag", true)
                .containsEntry("value.present", "yes").doesNotContainKey("value.big");
        assertThat((byte[]) fields.get("controller")).isEqualTo(OWNER);
        var entry = AuthenticatedMapContract.decodeEntry(state.get(AuthenticatedMapContract.canonicalKey("priced",
                bytes("sku-1"))).orElseThrow());
        decide(state, 4, Mutation.revoke("priced", bytes("sku-1"), entry.revision(), entry.logicalValueHash()));
        var revoked = read(state, "priced", "sku-1");
        assertThat(revoked).containsEntry("status", "REVOKED").containsEntry("revision", 2L)
                .containsEntry("lastMutationHeight", 4L);
        assertThat(revoked.keySet()).noneMatch(name -> name.startsWith(RuleValueView.VALUE_PREFIX));
        assertThat(kernel.ruleValueFields("priced", bytes("sku-1"), new byte[]{1, 2})).isEmpty();
    }

    @Test
    void aStoredIntegerBeyondInt64IsReportedExactlyAndOversizedTextIsAbsent() {
        BigInteger beyond = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        var fields = kernel.ruleValueFields("priced", bytes("k"), entry(value(Map.of("big",
                new UnsignedInteger(beyond), "name", new UnicodeString("x".repeat(RuleFact.MAX_VALUE_BYTES + 1))))));
        assertThat(fields).containsEntry("value.big", beyond).doesNotContainKey("value.name");
        // A value that is not a map, or not CBOR, exposes the entry fields only.
        for (byte[] opaque : List.of(CborSerializationUtil.serialize(new Array()), new byte[]{(byte) 0xff})) {
            assertThat(kernel.ruleValueFields("priced", bytes("k"), entry(opaque)).keySet())
                    .noneMatch(name -> name.startsWith(RuleValueView.VALUE_PREFIX));
        }
    }

    @Test
    void aSchemaExposesItsFirst32EligibleMembersInCanonicalOrder() {
        List<MapField> members = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            members.add(new MapField(String.format("m%02d", 39 - index), false, UINT));
        }
        var wide = kernel(List.of(collection("wide", AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
                "wide-schema")), List.of(AuthenticatedMapContract.ValidatorDescriptor.schema("wide-schema",
                AuthenticatedMapSchema.of(new AuthenticatedMapSchema.MapNode(members)).definition())));
        var exposed = wide.ruleValueViews().getFirst().valueFields();
        assertThat(exposed).hasSize(RuleValueView.MAX_FIELDS);
        assertThat(exposed.getFirst().name()).isEqualTo("m00");
        assertThat(exposed.getLast().name()).isEqualTo("m31");
    }

    @Test
    void theWriteViewDescribesEveryMutationInCommandOrder() {
        byte[] priced = value(Map.of("price", new UnsignedInteger(7), "name", new UnicodeString("w")));
        byte[] label = value(Map.of("price", new UnicodeString("seven")));
        var action = action(Mutation.put("priced", bytes("sku-1"), priced),
                Mutation.putIfAbsent("labels", bytes("sku-1"), label),
                Mutation.compareAndSet("priced", new byte[]{(byte) 0xff}, priced, 4, new byte[32]),
                Mutation.transferController("blobs", bytes("b"), new byte[32], 2),
                Mutation.revoke("priced", bytes("sku-2"), 5, new byte[32]),
                Mutation.restore("priced", bytes("sku-3"), priced));
        var writes = kernel.ruleWrites(new AuthenticatedMapTransitionKernel.Command(action, ""));
        assertThat(writes).extracting(write -> write.get("op")).containsExactly("PUT", "PUT_IF_ABSENT",
                "COMPARE_AND_SET", "TRANSFER_CONTROLLER", "REVOKE", "RESTORE");
        assertThat(writes).extracting(write -> write.get("hasValue")).containsExactly(true, true, true, false,
                false, true);
        assertThat(writes.get(0)).containsEntry("collection", "priced").containsEntry("keyText", "sku-1")
                .containsEntry("valueLength", (long) priced.length).containsEntry("expectedRevision", 0L)
                .containsEntry("value.name", "w").doesNotContainKey("index");
        // `price` is an integer in one collection and text in another, so no write declares it.
        assertThat(writes.get(0)).doesNotContainKey("value.price");
        assertThat(writes.get(1)).doesNotContainKey("value.price");
        assertThat(writes.get(2)).doesNotContainKey("keyText").containsEntry("expectedRevision", 4L);
        assertThat(writes.get(3)).containsEntry("valueLength", 0L).noneSatisfy((name, ignored) ->
                assertThat(name).startsWith(RuleValueView.VALUE_PREFIX));
        assertThat(writes.get(4)).containsEntry("hasValue", false).doesNotContainKey("value.name");
        assertThat(writes.get(5)).containsEntry("value.name", "w");
        assertThat(kernel.ruleWriteFields()).extracting(RuleFact::name).containsExactly("collection", "key",
                "keyText", "op", "hasValue", "valueLength", "expectedRevision");
        assertThat(kernel.ruleWriteCoverageFields()).extracting(RuleFact::name).containsExactly("coverage",
                "actorId", "actorOrganizationId", "actorRoles");
    }

    @Test
    void ownerCollectionsReportNoCoverageAndOnlyFromAnApprovedDecision() {
        var state = new State();
        var action = action(Mutation.put("blobs", bytes("b-1"), bytes("v")), Mutation.put("blobs", bytes("b-2"),
                bytes("w")));
        var command = new AuthenticatedMapTransitionKernel.Command(action, "");
        var context = context(3);
        var facts = kernel.facts(command, context, state, Map.of());
        assertThat(kernel.decide(command, context, facts)).isInstanceOf(TransitionDecision.Approved.class);
        assertThat(kernel.ruleWriteCoverage(command, context, facts)).containsExactly(Map.of("coverage", "none"),
                Map.of("coverage", "none"));
    }

    private void decide(State state, long height, Mutation mutation) {
        var command = new AuthenticatedMapTransitionKernel.Command(action(mutation), "");
        var context = context(height);
        var decision = kernel.decide(command, context, kernel.facts(command, context, state, Map.of()));
        assertThat(decision).isInstanceOf(TransitionDecision.Approved.class);
        for (StateMutation write : ((TransitionDecision.Approved) decision).plan().mutations()) {
            if (write.kind() == StateMutation.Kind.PUT) state.put(write.key(), write.value());
            else state.delete(write.key());
        }
    }

    private Map<String, Object> read(State state, String collection, String key) {
        byte[] stored = state.get(kernel.ruleValueKey(collection, bytes(key))).orElseThrow();
        return kernel.ruleValueFields(collection, bytes(key), stored);
    }

    private static MapActionV1 action(Mutation... mutations) {
        List<AuthorizationAssignmentV1> assignments = new ArrayList<>();
        for (int index = 0; index < mutations.length; index++) {
            assignments.add(new AuthorizationAssignmentV1(index, AuthenticatedMapContract.AUTH_OWNER, "",
                    AuthenticatedMapAuthorizationContract.NO_EVIDENCE_HANDLE));
        }
        return new MapActionV1(mutations.length > 1, List.of(mutations), assignments);
    }

    private static TransitionContext context(long height) {
        byte[] id = new byte[32];
        id[0] = (byte) height;
        return new TransitionContext(height, 0, 0, id, "registry", OWNER);
    }

    /** Integer, text, boolean and a `present` member; a hyphenated key, a nested map and a choice are hidden. */
    private static byte[] pricedSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.MapNode(List.of(
                new MapField("big", false, UINT), new MapField("name", false, TEXT),
                new MapField("flag", false, AuthenticatedMapSchema.BooleanNode.any()),
                new MapField("price", false, UINT), new MapField("a-b", false, TEXT),
                new MapField("present", false, TEXT),
                new MapField("nested", false, new AuthenticatedMapSchema.MapNode(List.of())),
                new MapField("choice", false, new AuthenticatedMapSchema.ChoiceNode(List.of(UINT, TEXT))))))
                .definition();
    }

    /** An active stored entry holding {@code value}. */
    private static byte[] entry(byte[] value) {
        return AuthenticatedMapContract.encodeEntry(new AuthenticatedMapContract.Entry(
                AuthenticatedMapContract.STATUS_ACTIVE, 1, OWNER, value,
                AuthenticatedMapContract.logicalValueHash(value), 1, 1));
    }

    private static byte[] value(Map<String, DataItem> members) {
        var map = new co.nstant.in.cbor.model.Map();
        new LinkedHashMap<>(members).forEach((name, item) -> map.put(new UnicodeString(name), item));
        return CborSerializationUtil.serialize(map);
    }

    private static AuthenticatedMapContract.CollectionDescriptor collection(String id, int encoding,
                                                                           String validator) {
        return new AuthenticatedMapContract.CollectionDescriptor(id, AuthenticatedMapContract.AUTH_OWNER, "", true,
                64, 8192, encoding, validator);
    }

    private static AuthenticatedMapTransitionKernel kernel(List<AuthenticatedMapContract.CollectionDescriptor> all,
                                                           List<AuthenticatedMapContract.ValidatorDescriptor> schemas) {
        var genesis = new AuthenticatedMapContract.Genesis("typed-views", StateCommitmentProfiles.MPF_BLAKE2B256_V1,
                StateCommitmentProfiles.MPF.formatFingerprint(), new byte[32], new byte[32], new byte[32], 16, 32768,
                all, schemas, List.of(), null);
        return new AuthenticatedMapTransitionKernel(new AuthenticatedMapStateMachine(genesis), "", "");
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static final class State implements AppStateWriter {
        private final Map<String, byte[]> values = new HashMap<>();
        @Override public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }
        @Override public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }
        @Override public void delete(byte[] key) { values.remove(HexFormat.of().formatHex(key)); }
        @Override public byte[] stateRoot() { return new byte[32]; }
    }
}
