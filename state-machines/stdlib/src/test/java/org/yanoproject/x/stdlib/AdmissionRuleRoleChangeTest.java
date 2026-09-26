package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
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
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.appchain.config.AppChainEffectsConfig;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-031.3 §6.3 role change: rule facts come from the actor record current at execution, so an actor whose
 * {@code operator} role is removed is refused on its next direct write without any profile change.
 *
 * <p>The declarative actor leaf is genesis-fixed and has no governance route, so the next actor revision and its
 * current pointer are written into the actor component's state exactly as an actor-governance change would write
 * them, between two blocks of one unchanged composite.
 */
class AdmissionRuleRoleChangeTest {
    private static final AppEffectEmitter NO_EFFECTS = AppEffectEmitter.rejecting("no effects");

    @Test
    void removingTheOperatorRoleRefusesTheActorsNextDirectWriteWithoutAProfileChange() {
        var fixture = BindingProductFixtures.fixture(true);
        var genesis = fixture.genesis();
        var machine = (CompositeStateMachine) new DeclarativeCompositeProvider().create(context(fixture,
                gated(fixture.ir())));
        var state = new MemoryState();
        machine.init(state, new AppChainInfo(genesis.chainId(), BindingProductFixtures.members().getFirst(), 3));
        apply(machine, state, 1, List.of());
        byte[] marker = state.get(CompositeStateKeys.profileMarkerKey()).orElseThrow();

        var first = message(1, write(genesis, "product-1", 1, 1));
        apply(machine, state, 2, List.of(first));
        assertThat(receipt(machine, state, first).accepted()).isTrue();
        assertThat(state.get(product("product-1"))).isPresent();

        // Actor governance removes operator: maker-a's next revision keeps only manufacturer.
        byte[] current = CompositeStateKeys.componentKey("actors", RoleWorkflowKeys.actorRevision("maker-a", 1));
        var revision1 = ActorRecordV1.decode(state.get(current).orElseThrow());
        assertThat(revision1.roles()).contains(DppStarterProfile.OPERATOR_ROLE);
        var revision2 = new ActorRecordV1("maker-a", revision1.organizationId(), 2, RecordStatus.ACTIVE,
                List.of(DppStarterProfile.MANUFACTURER_ROLE), revision1.keys(), revision1.metadataCommitment());
        state.put(CompositeStateKeys.componentKey("actors", RoleWorkflowKeys.actorRevision("maker-a", 2)),
                revision2.encode());
        state.put(CompositeStateKeys.componentKey("actors", RoleWorkflowKeys.actorCurrent("maker-a")),
                ByteBuffer.allocate(Long.BYTES).putLong(2).array());

        // The map still verifies maker-a for manufacturer-write; operator-for-direct-writes now refuses it.
        var second = message(2, write(genesis, "product-2", 2, 2));
        apply(machine, state, 3, List.of(second));
        var refused = receipt(machine, state, second);
        assertThat(refused.code()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(refused.steps().getFirst().rules().failure())
                .isEqualTo(new BindingReceiptV1.RuleFailure("operator-for-direct-writes", 0, "ROLE_REQUIRED"));
        assertThat(state.get(product("product-2"))).isEmpty();
        // No profile change: the committed profile marker is untouched, so no epoch or reconfiguration happened.
        assertThat(state.get(CompositeStateKeys.profileMarkerKey())).hasValueSatisfying(value ->
                assertThat(value).isEqualTo(marker));
        assertThat(state.get(CompositeStateKeys.currentProfileEpochKey())).isEmpty();
    }

    private static byte[] product(String id) {
        return CompositeStateKeys.componentKey("registry", AuthenticatedMapContract.canonicalKey(
                DppStarterProfile.PRODUCTS, DppStarterProfile.productKey(id)));
    }

    /** The DPP recipe's registry with operator-for-direct-writes attached. */
    private static BindingIrV1 gated(BindingIrV1 ir) {
        var rule = new BindingIrV1.AdmissionRule("operator-for-direct-writes", "ROLE_REQUIRED", null,
                List.of(new BindingIrV1.Parameter("role", BindingIrV1.ParameterType.TEXT, null)),
                List.of(new BindingIrV1.ExpressionClause(new BindingExpressionV1(BindingExpressionV1.Type.BOOLEAN,
                        new Call("or", List.of(
                                new Call("eq", List.of(new Field(Scope.FACTS, "directActorCount"), new Literal(0L))),
                                new Call("in", List.of(new Field(Scope.PARAMS, "role"),
                                        new Field(Scope.FACTS, "roles")))))))));
        List<BindingIrV1.Component> components = new ArrayList<>();
        for (var component : ir.components()) {
            components.add(!component.id().equals("registry") ? component : new BindingIrV1.Component(
                    component.id(), component.machineId(), component.ingressTopic(), component.configuration(),
                    component.maxEffectsPerBlock(), component.fromHeight(), List.of(new BindingIrV1.RuleAttachment(
                    rule.id(), Map.of("role", new BindingSourceV1.Literal(DppStarterProfile.OPERATOR_ROLE))))));
        }
        return new BindingIrV1(components, List.of(rule), ir.bindings(), ir.limits(), ir.workflowFromHeight());
    }

    /** A direct manufacturer-write of one product by maker-a, signed against the given actor revision. */
    private static byte[] write(AuthenticatedMapContract.Genesis genesis, String product, long actorRevision,
                                int authorization) {
        byte[] value = new DppValues.ProductValue("acme-manufacturing", DppStarterProfile.STATUS_ACTIVE, 0, "",
                "dpp-v1").encode();
        var action = new AuthenticatedMapAuthorizationContract.MapActionV1(false,
                List.of(AuthenticatedMapContract.Mutation.put(DppStarterProfile.PRODUCTS,
                        DppStarterProfile.productKey(product), value)),
                List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(0,
                        AuthenticatedMapContract.AUTH_GOVERNED_ROLE, DppStarterProfile.MANUFACTURER_POLICY, 1)));
        byte[] seed = DppGenesis.demoActorSeed("maker-a");
        byte[] authorizationId = new byte[32];
        authorizationId[0] = (byte) authorization;
        var signed = AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(authorizationId,
                genesis.chainId(), AuthenticatedMapContract.genesisId(genesis),
                AuthenticatedMapAuthorizationContract.actionCommitment(action), List.of(0),
                DppStarterProfile.MANUFACTURER_POLICY, 1, "maker-a", actorRevision, "maker-a-k1",
                KeyGenUtil.getPublicKeyFromPrivateKey(seed), 1, DppStarterProfile.DIRECT_AUTHORIZATION_LIFETIME_BLOCKS,
                seed);
        return TransitionScalars.encode(Map.of("command", AuthenticatedMapAuthorizationContract.encodeCommand(
                new AuthenticatedMapAuthorizationContract.AuthenticatedMapCommandV1(action, List.of(signed)))));
    }

    private static AppMessage message(int discriminator, byte[] body) {
        byte[] id = new byte[32];
        id[0] = (byte) discriminator;
        return AppMessage.builder().messageId(id).chainId("binding-dpp-demo").topic("registry.v1")
                .sender(HexFormat.of().parseHex(BindingProductFixtures.members().getFirst())).senderSeq(discriminator)
                .expiresAt(Long.MAX_VALUE).body(body).authScheme(0).authProof(new byte[0]).build();
    }

    private static void apply(AppStateMachine machine, MemoryState state, long height, List<AppMessage> messages) {
        byte[] proposer = HexFormat.of().parseHex(BindingProductFixtures.members().getFirst());
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(new AppBlock(AppBlock.BLOCK_VERSION,
                "binding-dpp-demo", height, new byte[32], 0, new byte[0], 1_000 * height, new byte[32], new byte[32],
                messages, proposer, FinalityCert.empty())), state, NO_EFFECTS);
        state.height = height;
    }

    private static BindingReceiptV1 receipt(AppStateMachine machine, MemoryState state, AppMessage message) {
        return BindingReceiptV1.decode(machine.query("composite/binding-receipt-v1/"
                + HexFormat.of().formatHex(message.getMessageId()), new byte[0], state));
    }

    private static AppStateMachineContext context(BindingProductFixtures.Fixture fixture, BindingIrV1 ir) {
        var config = BindingProductFixtures.configurations(fixture, List.of(1, 2, 3)).getFirst();
        var membership = new AppChainMembershipEpoch(0, BindingProductFixtures.members(), 2);
        StateCommitmentIdentity identity = StdlibTestStateCommitments.mpf(fixture.chain());
        Map<String, String> settings = new TreeMap<>(identity.settings());
        settings.put("membership.mode", "governed");
        settings.put(DeclarativeCompositeProvider.IR_SETTING, HexFormat.of().formatHex(ir.encode()));
        AppChainConsensusProfile consensus = AppChainEffectsConfig.from(config).consensusProfile(config);
        return new AppStateMachineContext() {
            @Override public String chainId() { return fixture.chain(); }
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
