package org.yanoproject.x.devtools;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppBlockExecutionContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.api.appchain.effects.EffectOutcomeCommitment;
import org.yanoproject.api.appchain.effects.FinalityGate;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.state.StateCommitmentProfiles;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.OrderedLogKernel;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.x.composite.ComponentDescriptor;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingProfileCheckTest {
    @Test
    void exactProfilePassesAndReportsItsLimitedAssurance() {
        var session = session(false);
        var expected = profile(session, document(1, Map.of()));
        var report = BindingProfileCheck.check(List.of(expected), session);
        assertThat(report.reproducesProfiles()).isTrue();
        assertThat(report.profiles().getFirst().expectedDigest())
                .isEqualTo(HexFormat.of().formatHex(expected.digest()));
        assertThat(report.assurance()).contains("not migration", "replay", "semantic equivalence");
    }

    @Test
    void executionVersionApplicationVersionAndQueriesCannotSilentlyChange() {
        var session = session(false);
        var original = profile(session, document(1, Map.of()));
        var component = original.components().getFirst();
        var oldVersion = new ComponentDescriptor(component.componentId(), "old-version",
                component.configurationId(), component.stateAndResultCompatibilityId(), component.fromHeight(),
                component.untilHeight(), component.topics(), component.queryPaths(), component.maxEffectsPerBlock());
        var oldQueries = new ComponentDescriptor(component.componentId(), component.semanticVersion(),
                component.configurationId(), component.stateAndResultCompatibilityId(), component.fromHeight(),
                component.untilHeight(), component.topics(), List.of("old-query"), component.maxEffectsPerBlock());
        for (var changed : List.of(copy(original, "1.0.0", original.components(), original.bindingIr()),
                copy(original, original.profileVersion(), List.of(oldVersion), original.bindingIr()),
                copy(original, original.profileVersion(), List.of(oldQueries), original.bindingIr()))) {
            assertThat(BindingProfileCheck.check(List.of(changed), session).reproducesProfiles()).isFalse();
        }
    }

    @Test
    void configurationDriftFailsConstructionRatherThanRewritingRetainedIr() {
        var session = session(false);
        var original = profile(session, document(1, Map.of()));
        var invalid = document(1, Map.of("new-default", new BindingSourceV1.Literal(1L)));
        var changed = copy(original, original.profileVersion(), original.components(), invalid.encode());
        var result = BindingProfileCheck.check(List.of(changed), session);
        assertThat(result.reproducesProfiles()).isFalse();
        assertThat(result.profiles().getFirst().diagnostic()).contains("construction failed");
    }

    @Test
    void newlyIntroducedDescriptorDefaultCannotSilentlyNormalizeOldProfile() {
        var registry = registry();
        var kernel = new ConfigurableKernel();
        registry.providers.put("custom-log", new AppStateMachineProvider() {
            @Override public String id() { return "custom-log"; }
            @Override public AppStateMachine create() {
                return new AppStateMachine() {
                    @Override public String id() { return "custom-log"; }
                    @Override public Optional<TransitionKernel<?, ?>> transitionKernel() {
                        return Optional.of(kernel);
                    }
                    @Override public void apply(AppBlockExecutionContext execution, AppStateWriter writer,
                                                AppEffectEmitter effects) {
                        throw new AssertionError("profile-check must not execute transitions");
                    }
                };
            }
        });
        var session = session(registry, false);
        var ir = new BindingIrV1(List.of(new BindingIrV1.Component("records", "custom-log", "records.v1",
                Map.of(), 0)), List.of(), BindingIrV1.Limits.DEFAULT);
        var original = profile(session, ir);
        kernel.configuration = new ConfigurationDescriptor(List.of(
                new ConfigurationDescriptor.Setting("new-default", TransitionScalars.Type.INTEGER, 1L)));
        var result = BindingProfileCheck.check(List.of(original), session);
        assertThat(result.reproducesProfiles()).isFalse();
        assertThat(result.profiles().getFirst().diagnostic()).contains("normalized defaults");
    }

    @Test
    void catalogRequiresExplicitGovernedMembershipAndReproducesDormantProfile() {
        var first = document(1, Map.of());
        var next = document(8, Map.of());
        assertThatThrownBy(() -> session(false).validateCatalog(List.of(first, next)))
                .hasMessageContaining("membership.mode=governed");
        var session = session(true);
        var machine = (CompositeStateMachine) session.validateCatalog(List.of(first, next));
        var original = machine.profile();
        // The second document is not a genesis profile; the provider must reconstruct it in a shared catalog.
        var workflow = original.workflows().getFirst();
        var dormant = new CompositeProfile(2, original.profileId(), original.profileVersion(), original.components(),
                List.of(new WorkflowDescriptor(workflow.workflowId(),
                        workflow.semanticVersion(), workflow.topics(), 8, 0, workflow.participants(),
                        workflow.maxEffectsPerBlock())), original.queryAliases(), original.aggregateQueryLimits(),
                next.encode());
        assertThat(BindingProfileCheck.check(List.of(original, dormant), session).reproducesProfiles()).isTrue();
    }

    @Test
    void profileDocumentIsStrictBoundedCanonicalAndRejectsDuplicates() throws Exception {
        var original = profile(session(false), document(1, Map.of()));
        String hex = HexFormat.of().formatHex(original.canonicalBytes());
        assertThat(BindingProfileCheck.parse("[\"" + hex + "\"]")).hasSize(1);
        for (String invalid : List.of("[]", "[\"\"]", "{}", "[null]", "[1]", "[\"zz\"]",
                "[\"" + hex + "\",\"" + hex.toUpperCase(java.util.Locale.ROOT) + "\"]",
                "[\"" + hex + "00\"]", "[\"" + hex + "\"] {}")) {
            assertThatThrownBy(() -> BindingProfileCheck.parse(invalid)).as(invalid.substring(0,
                    Math.min(32, invalid.length()))).isInstanceOf(Exception.class);
        }
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(65, "\"" + hex + "\"")) + "]";
        assertThatThrownBy(() -> BindingProfileCheck.parse(tooMany)).hasMessageContaining("1-64");
        var output = new StringWriter();
        assertThat(BindingProfileCheck.run(new String[]{"profile-check"}, new PrintWriter(output),
                new PrintWriter(output))).isEqualTo(64);
    }

    private static CompositeProfile copy(CompositeProfile original, String version,
                                         List<ComponentDescriptor> components, byte[] ir) {
        var workflows = original.workflows().stream().map(workflow -> new WorkflowDescriptor(workflow.workflowId(),
                workflow.semanticVersion(), workflow.topics(), workflow.fromHeight(), workflow.untilHeight(),
                components.stream().map(ComponentDescriptor::generation).toList(), workflow.maxEffectsPerBlock()))
                .toList();
        return new CompositeProfile(2, original.profileId(), version, components, workflows,
                original.queryAliases(), original.aggregateQueryLimits(), ir);
    }

    private static CompositeProfile profile(BindingCatalogSession session, BindingIrV1 ir) {
        return ((CompositeStateMachine) session.validate(ir)).profile();
    }

    private static BindingIrV1 document(long height, Map<String, BindingSourceV1.Literal> configuration) {
        return new BindingIrV1(List.of(new BindingIrV1.Component("records", "ordered-log", "records.v1",
                configuration, 0)), List.of(), BindingIrV1.Limits.DEFAULT, height);
    }

    private static BindingCatalogSession session(boolean governed) {
        return session(registry(), governed);
    }

    private static TestRegistry registry() {
        return new TestRegistry();
    }

    /** Explicit test registry avoids bytecode instrumentation while exercising real provider construction. */
    private static final class TestRegistry implements PluginProviderRegistry {
        private final Map<String, AppStateMachineProvider> providers = new LinkedHashMap<>(
                Map.of(BindingCatalogSession.MACHINE, new DeclarativeCompositeProvider()));

        @Override public <P> Optional<P> find(Class<P> type, String selector) {
            return type == AppStateMachineProvider.class
                    ? Optional.ofNullable(providers.get(selector)).map(type::cast) : Optional.empty();
        }
        @Override public <P> List<String> names(Class<P> type) {
            return type == AppStateMachineProvider.class ? providers.keySet().stream().sorted().toList() : List.of();
        }
    }

    /** Only configuration is mutable for the two-version fixture; command schemas use the real host kernel. */
    private static final class ConfigurableKernel implements TransitionKernel<byte[], Boolean> {
        private final OrderedLogKernel delegate = new OrderedLogKernel();
        private ConfigurationDescriptor configuration = ConfigurationDescriptor.empty();
        @Override public ConfigurationDescriptor configuration() { return configuration; }
        @Override public MessageCodec<byte[]> codec() { return delegate.codec(); }
        @Override public List<CommandDescriptor> commands() { return delegate.commands(); }
        @Override public List<EventDescriptor> events() { return delegate.events(); }
        @Override public Boolean facts(byte[] command, TransitionContext context, AppStateReader state) {
            throw new AssertionError("profile-check must not read execution facts");
        }
        @Override public TransitionDecision decide(byte[] command, TransitionContext context, Boolean facts) {
            throw new AssertionError("profile-check must not execute decisions");
        }
    }

    private static BindingCatalogSession session(PluginProviderRegistry registry, boolean governed) {
        var settings = new LinkedHashMap<>(StateCommitmentIdentity.explicit(StateCommitmentProfiles.MPF,
                new byte[32]).settings());
        if (governed) settings.put("membership.mode", "governed");
        var consensus = new AppChainConsensusProfile(2, 65536, 100, 1048576, 0, 0, false, false,
                0, 0, 0, 0, FinalityGate.APP_FINAL, EffectOutcomeCommitment.PER_EFFECT, true, List.of());
        return new BindingCatalogSession(registry, new BindingCatalogSession.ContextInput("profile-check", settings,
                consensus, new AppChainMembershipEpoch(0, List.of("11".repeat(32)), 1)));
    }
}
