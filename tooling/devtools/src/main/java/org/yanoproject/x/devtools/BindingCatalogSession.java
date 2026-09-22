package org.yanoproject.x.devtools;

import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.AppStateMachineResolver;
import org.yanoproject.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.runtime.plugins.CatalogAuthenticatedMapValidatorResolver;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.yanoproject.runtime.appchain.OrderedLogStateMachine;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Descriptor and profile construction bridge using the explicitly selected host catalog for optional machines.
 * The sole host builtin, OrderedLog, follows the same explicit branch as the running host resolver.
 * It passes only host SPI values across class-loader boundaries; executable X implementation classes
 * remain owned by their selected bundles, even when devtools links another copy for pure authoring work.
 */
final class BindingCatalogSession implements BindingDocumentCompiler.DescriptorCatalog {
    static final String MACHINE = "declarative-composite";
    static final String IR_SETTING = "machines.composite.binding-ir";
    private final PluginProviderRegistry providers;
    private final ContextInput input;
    private final StateCommitmentIdentity identity;

    /**
     * Explicit offline context. No secrets, signing material, node defaults, or environment lookups are needed.
     * Settings carry the exact state identity and optional host membership settings; component settings belong
     * in the authoring document and are deliberately rejected here. This context validates a fixed genesis
     * composite, not a composite-profile governance activation.
     */
    record ContextInput(String chainId, Map<String, String> settings, AppChainConsensusProfile consensusProfile,
                        AppChainMembershipEpoch membership) {
        ContextInput {
            Objects.requireNonNull(chainId, "chainId");
            if (chainId.isBlank() || chainId.length() > 127) throw new IllegalArgumentException("invalid chainId");
            settings = Map.copyOf(Objects.requireNonNull(settings, "settings"));
            Objects.requireNonNull(consensusProfile, "consensusProfile");
            Objects.requireNonNull(membership, "membership");
            if (settings.size() > 256) throw new IllegalArgumentException("context setting count limit");
            settings.forEach((key, value) -> {
                if (key.length() > 255 || value.length() > 131_072) {
                    throw new IllegalArgumentException("context setting length limit");
                }
                if (key.startsWith("machines.")) {
                    throw new IllegalArgumentException("component settings must be committed in the binding document");
                }
            });
            StateCommitmentIdentity.fromSettings(settings);
        }
    }

    BindingCatalogSession(PluginProviderRegistry providers, ContextInput input) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.input = Objects.requireNonNull(input, "input");
        this.identity = StateCommitmentIdentity.fromSettings(input.settings());
    }

    @Override public ConfigurationDescriptor configuration(String machineId) {
        return configuration(machineId, Map.of());
    }

    @Override public ConfigurationDescriptor configuration(String machineId, Map<String, Object> supplied) {
        return kernel(machineId, supplied).configuration();
    }

    @Override public Map<String, Type> eventFields(BindingIrV1.Component component, String eventId) {
        Map<String, Object> values = new LinkedHashMap<>();
        component.configuration().forEach((key, literal) -> values.put(key, literal.value()));
        TransitionKernel<?, ?> kernel = kernel(component.machineId(), values);
        if ("composite.command-accepted.v1".equals(eventId)) {
            return Map.of("topic", Type.TEXT, "sender", Type.BYTES, "messageId", Type.BYTES,
                    "body", Type.BYTES, "bodyHash", Type.BYTES, "bodyLength", Type.INTEGER);
        }
        var schemas = kernel.events().stream().filter(event -> event.eventId().equals(eventId)).toList();
        if (schemas.size() != 1) throw new IllegalArgumentException("unknown or duplicate event: " + eventId);
        Map<String, Type> fields = new LinkedHashMap<>();
        schemas.getFirst().fields().forEach(field -> fields.put(field.name(), Type.valueOf(field.type().name())));
        return Map.copyOf(fields);
    }

    /** Constructs the real catalog-selected composite; this is the authoritative profile validation step. */
    AppStateMachine validate(BindingIrV1 ir) {
        Map<String, String> settings = new LinkedHashMap<>(input.settings());
        settings.put(IR_SETTING, HexFormat.of().formatHex(ir.encode()));
        return resolve(MACHINE, new Context(settings, true));
    }

    ContextInput input() { return input; }

    private TransitionKernel<?, ?> kernel(String machineId, Map<String, Object> values) {
        if (MACHINE.equals(machineId)) {
            throw new IllegalArgumentException("nested declarative composites are unsupported");
        }
        Map<String, String> settings = new LinkedHashMap<>(identity.settings());
        values.forEach((key, value) -> settings.put("machines." + machineId + "." + key,
                value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value.toString()));
        return resolve(machineId, new Context(settings, false)).transitionKernel().orElseThrow(() ->
                new IllegalArgumentException("catalog component has no transition kernel: " + machineId));
    }

    private AppStateMachine resolve(String id, AppStateMachineContext context) {
        // OrderedLog is the host's sole builtin, not an optional-provider fallback. Match host resolution exactly.
        if (OrderedLogStateMachine.ID.equals(id)) return new OrderedLogStateMachine();
        return providers.require(AppStateMachineProvider.class, id).create(context);
    }

    private final class Context implements AppStateMachineContext {
        private final Map<String, String> settings;
        private final boolean resolveChildren;

        private Context(Map<String, String> settings, boolean resolveChildren) {
            this.settings = Map.copyOf(settings);
            this.resolveChildren = resolveChildren;
        }

        @Override public String chainId() { return input.chainId(); }
        @Override public Map<String, String> settings() { return settings; }
        @Override public Optional<AppChainConsensusProfile> consensusProfile() {
            return Optional.of(input.consensusProfile());
        }
        @Override public Optional<AppChainMembershipView> membershipView() {
            return Optional.of(height -> input.membership());
        }
        @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() { return Optional.of(identity); }
        @Override public Optional<ObservationProfileV1> observationProfile() {
            return Optional.of(ObservationProfileV1.disabled());
        }
        @Override public Optional<AuthenticatedMapValidatorResolver> authenticatedMapValidatorResolver() {
            return Optional.of(new CatalogAuthenticatedMapValidatorResolver(providers));
        }
        @Override public Optional<AppStateMachineResolver> stateMachineResolver() {
            return resolveChildren ? Optional.of(BindingCatalogSession.this::resolve) : Optional.empty();
        }
    }
}
