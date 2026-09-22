package org.yanoproject.x.composite.bindings;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainInfo;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineResolver;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.appchain.testkit.AppChainTestProfiles;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.composite.CompositeStateKeys;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeclarativeCompositeProviderTest {
    @Test
    void retainedSchemaTwoProfileRejectsChangedIrOnStartup() {
        var provider = new DeclarativeCompositeProvider();
        var original = (CompositeStateMachine) provider.create(new Context(document(1, "records.v1")));
        assertThat(original.profile().schemaVersion()).isEqualTo(2);
        byte[] committed = original.profile().canonicalBytes();
        AppStateReader retained = new AppStateReader() {
            @Override public Optional<byte[]> get(byte[] key) {
                return java.util.Arrays.equals(key, CompositeStateKeys.profileMarkerKey())
                        ? Optional.of(committed.clone()) : Optional.empty();
            }
            @Override public byte[] stateRoot() {
                byte[] root = new byte[32];
                root[0] = 1;
                return root;
            }
        };
        var info = new AppChainInfo("chain", "00", 1);
        assertThatCode(() -> provider.create(new Context(document(1, "records.v1"))).init(retained, info))
                .doesNotThrowAnyException();
        var changed = (CompositeStateMachine) provider.create(new Context(document(1, "records.v2")));
        assertThat(changed.profile().canonicalBytes()).isNotEqualTo(committed);
        assertThatThrownBy(() -> changed.init(retained, info)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retained composite profile marker");
    }

    @Test
    void governedCatalogReusesUnchangedComponentAndSeparatesWorkflowGenerations() {
        var fixture = new Context(document(1, "records.v1"), document(2, "records.v2"));
        assertThatCode(() -> new DeclarativeCompositeProvider().create(fixture)).doesNotThrowAnyException();
        assertThat(fixture.constructions).isEqualTo(1);
    }

    @Test
    void changedProgramCannotRedefineTheSameWorkflowGeneration() {
        var fixture = new Context(document(1, "records.v1"), document(1, "records.v2"));
        assertThatThrownBy(() -> new DeclarativeCompositeProvider().create(fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("binding changes require a new workflow generation height");
    }

    @Test
    void componentSettingsAreIsolatedFromNodeSettingsAndNestedResolution() {
        var fixture = new Context(document(1, "records.v1"));
        new DeclarativeCompositeProvider().create(fixture);
        assertThat(fixture.child.settings()).isEmpty();
        assertThat(fixture.child.stateMachineResolver()).isEmpty();
        assertThat(fixture.child.chainId()).isEqualTo("chain");
    }

    @Test
    void workflowCannotStartBeforeItsParticipant() {
        assertThatThrownBy(() -> new BindingIrV1(List.of(new BindingIrV1.Component(
                "records", "ordered-log", "records.v1", Map.of(), 0, 10)), List.of(), BindingIrV1.Limits.DEFAULT, 9))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot precede");
    }

    private static BindingIrV1 document(long generation, String topic) {
        return new BindingIrV1(List.of(new BindingIrV1.Component("records", "ordered-log", topic, Map.of(), 0)),
                List.of(), BindingIrV1.Limits.DEFAULT, generation);
    }

    private static final class Context implements AppStateMachineContext {
        private final Map<String, String> settings = new LinkedHashMap<>();
        private int constructions;
        private AppStateMachineContext child;
        Context(BindingIrV1... documents) {
            settings.put(DeclarativeCompositeProvider.IR_SETTING, HexFormat.of().formatHex(documents[0].encode()));
            settings.put("machines.ordered-log.uncommitted-local-setting", "must-not-leak");
            if (documents.length > 1) {
                settings.put("membership.mode", "governed");
                settings.put("machines.composite.profile-mode", "governed");
            }
            for (int i = 1; i < documents.length; i++) {
                settings.put("machines.composite.binding-ir-catalog[" + (i - 1) + "]",
                        HexFormat.of().formatHex(documents[i].encode()));
            }
        }
        @Override public String chainId() { return "chain"; }
        @Override public Map<String, String> settings() { return Map.copyOf(settings); }
        @Override public Optional<AppChainConsensusProfile> consensusProfile() {
            return Optional.of(AppChainTestProfiles.enabledEffects(10));
        }
        @Override public Optional<AppChainMembershipView> membershipView() {
            return Optional.of(height -> new AppChainMembershipEpoch(0, List.of("01".repeat(32)), 1));
        }
        @Override public Optional<AppStateMachineResolver> stateMachineResolver() {
            return Optional.of((id, context) -> {
                constructions++;
                child = context;
                return new AppStateMachine() {
                    @Override public String id() { return "ordered-log"; }
                    @Override public Optional<TransitionKernel<?, ?>> transitionKernel() {
                        return Optional.of(new OrderedLogKernel());
                    }
                    @Override public void apply(AppBlockExecutionContext execution, AppStateWriter state,
                                                AppEffectEmitter effects) {
                        throw new AssertionError("workflow owns all ingress");
                    }
                };
            });
        }
    }
}
