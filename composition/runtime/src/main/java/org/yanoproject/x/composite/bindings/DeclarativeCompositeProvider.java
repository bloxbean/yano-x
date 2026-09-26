package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.api.appchain.AppChainConsensusProfile;
import org.yanoproject.api.appchain.AppChainMembershipView;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineContext;
import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import org.yanoproject.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.composite.ComponentDescriptor;
import org.yanoproject.x.composite.ComponentGeneration;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeProfileCatalog;
import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.composite.WorkflowDescriptor;
import org.yanoproject.x.composite.contracts.AggregateQueryLimitsV1;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingCbor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog-only assembly of committed binding documents. No machine implementations are linked here.
 *
 * <p>The genesis document is selected by {@link #IR_SETTING}; optional indexed catalog settings describe
 * dormant governed replacements. Component configuration comes exclusively from normalized IR scalars,
 * not arbitrary node YAML. The host resolver constructs each selected machine under its normal plugin
 * boundary. Child contexts deliberately omit the resolver so components cannot recursively activate
 * undeclared composites through this provider.
 *
 * <p>Construction-local caches preserve object identity for unchanged component generations across profiles.
 * Changed configuration requires a new component generation height; changed programs require a new workflow
 * generation height. These heights are committed input, not derived from catalog iteration order. The existing
 * composite runtime remains responsible for lifecycle, governance activation, namespace isolation and quotas.
 */
public final class DeclarativeCompositeProvider implements AppStateMachineProvider {
    public static final String ID = "declarative-composite";
    /** Pins admission payload bounds and pre-kernel mandatory-work reservation in the committed profile. */
    public static final String EXECUTION_VERSION = "1.1.0";
    public static final String IR_SETTING = "machines.composite.binding-ir";
    @Override public String id() { return ID; }
    @Override public AppStateMachine create() {
        throw new IllegalArgumentException("binding IR and host context required");
    }

    @Override public AppStateMachine create(AppStateMachineContext context) {
        String encoded = context.settings().get(IR_SETTING);
        if (encoded == null) throw new IllegalArgumentException("missing " + IR_SETTING);
        Assembly assembly = new Assembly();
        CompositeProfileCatalog.Entry initial = entry(context, decodeHex(encoded), assembly);
        if (initial.profile().workflows().getFirst().fromHeight() != 1) {
            throw new IllegalArgumentException("genesis binding workflow must start at height 1");
        }
        // Governed assembly uses the existing profile catalog construction path.
        List<CompositeProfileCatalog.Entry> entries = new ArrayList<>();
        entries.add(initial);
        for (int index = 0; index < CompositeProfileCatalog.MAX_ENTRIES; index++) {
            String key = "machines.composite.binding-ir-catalog[" + index + "]";
            String dormant = context.settings().get(key);
            if (dormant == null) break;
            if (index == CompositeProfileCatalog.MAX_ENTRIES - 1) {
                throw new IllegalArgumentException("binding catalog exceeds 64 profiles including genesis");
            }
            entries.add(entry(context, decodeHex(dormant), assembly));
        }
        if (entries.size() == 1 && !"governed".equals(context.settings().get("machines.composite.profile-mode"))) {
            return CompositeStateMachine.create(ID, context, initial.profile(), initial.machines(),
                    initial.workflows());
        }
        return CompositeStateMachine.create(ID, context,
                new CompositeProfileCatalog(entries, context.consensusProfile().orElseThrow().effectsMaxPerBlock()),
                initial.profile().digest());
    }

    /**
     * Assembles a standalone profile entry exclusively through the host's catalog resolver.
     * Governed catalogs use a shared assembly internally so unchanged generations retain object identity.
     *
     * @param context host construction capabilities; node-local component settings are not forwarded
     * @param ir canonical document with descriptor defaults already normalized
     * @return executable profile and its ordered component/workflow products
     * @throws IllegalArgumentException if a component lacks a kernel or its configuration/schema is invalid
     */
    public static CompositeProfileCatalog.Entry entry(AppStateMachineContext context, BindingIrV1 ir) {
        return entry(context, ir, new Assembly());
    }

    private static CompositeProfileCatalog.Entry entry(AppStateMachineContext context, BindingIrV1 ir,
                                                        Assembly assembly) {
        var resolver = context.stateMachineResolver().orElseThrow(() ->
                new IllegalArgumentException("catalog machine resolver is unavailable"));
        Map<String, TransitionKernel<?, ?>> kernels = new LinkedHashMap<>();
        Map<String, ComponentGeneration> generations = new LinkedHashMap<>();
        List<AppStateMachine> machines = new ArrayList<>();
        List<ComponentDescriptor> components = new ArrayList<>();
        int effects = 0;
        for (var component : ir.components()) {
            if (ID.equals(component.machineId()))
                    throw new IllegalArgumentException("nested declarative composites are unsupported");
            Map<String, String> settings = new LinkedHashMap<>();
            Map<String, Object> supplied = new LinkedHashMap<>();
            component.configuration().forEach((key, value) -> {
                supplied.put(key, value.value());
                Object scalar = value.value();
                settings.put("machines." + component.machineId() + "." + key,
                        scalar instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : scalar.toString());
            });
            String generationKey = component.id() + "@" + component.fromHeight();
            byte[] specification = BindingCbor.encode(List.of(component.machineId(), supplied,
                    component.maxEffectsPerBlock()));
            ComponentProduct cached = assembly.components.get(generationKey);
            if (cached != null) {
                if (!Arrays.equals(cached.specification(), specification)) {
                    throw new IllegalArgumentException(
                            "component changes require a new generation height: " + component.id());
                }
                components.add(cached.descriptor());
                generations.put(component.id(), cached.descriptor().generation());
                kernels.put(component.id(), cached.kernel());
                machines.add(cached.machine());
                effects = Math.addExact(effects, component.maxEffectsPerBlock());
                continue;
            }
            AppStateMachine machine = resolver.create(component.machineId(), new ComponentContext(context, settings));
            TransitionKernel<?, ?> kernel = machine.transitionKernel().orElseThrow(() ->
                    new IllegalArgumentException("component has no transition kernel: " + component.id()));
            byte[] normalized = TransitionScalars.encode(kernel.configuration().normalize(supplied));
            if (!Arrays.equals(normalized, TransitionScalars.encode(supplied))) {
                throw new IllegalArgumentException(
                        "component configuration must include normalized defaults: " + component.id());
            }
            byte[] domain = "yano-x-component-config-v1\0".getBytes(StandardCharsets.US_ASCII);
            String configId = HexFormat.of().formatHex(Blake2bUtil.blake2bHash256(
                    ByteBuffer.allocate(domain.length + normalized.length).put(domain).put(normalized).array()));
            String version = machine.capabilityManifest().applicationVersion();
            List<String> queries = machine.capabilityManifest().components().stream()
                    .flatMap(entry -> entry.querySubjects().stream()).distinct().sorted().toList();
            ComponentDescriptor descriptor = new ComponentDescriptor(component.id(), version, configId,
                    component.machineId() + ":" + version, component.fromHeight(), 0,
                    List.of(), queries, component.maxEffectsPerBlock());
            assembly.components.put(generationKey, new ComponentProduct(specification, descriptor, machine, kernel));
            components.add(descriptor);
            generations.put(component.id(), descriptor.generation());
            kernels.put(component.id(), kernel);
            machines.add(machine);
            effects = Math.addExact(effects, component.maxEffectsPerBlock());
        }
        BindingProgram program = new BindingProgram(ir, kernels);
        WorkflowDescriptor workflowDescriptor = new WorkflowDescriptor(EventBindingWorkflow.ID, EXECUTION_VERSION,
                ir.components().stream().map(BindingIrV1.Component::ingressTopic).toList(), ir.workflowFromHeight(), 0,
                components.stream().map(ComponentDescriptor::generation).toList(), effects);
        var consensus = context.consensusProfile().orElseThrow();
        byte[] document = ir.encode();
        WorkflowProduct existing = assembly.workflows.get(ir.workflowFromHeight());
        if (existing != null && !Arrays.equals(existing.document(), document)) {
            throw new IllegalArgumentException("binding changes require a new workflow generation height");
        }
        var workflow = existing == null
                ? new EventBindingWorkflow(program, workflowDescriptor, generations, consensus) : existing.workflow();
        assembly.workflows.putIfAbsent(ir.workflowFromHeight(), new WorkflowProduct(document, workflow));
        CompositeProfile profile = new CompositeProfile(2, ID, EXECUTION_VERSION, components,
                List.of(workflowDescriptor), List.of(), AggregateQueryLimitsV1.DEFAULT, ir.encode());
        profile.validateEffectBudget(consensus.effectsMaxPerBlock());
        return new CompositeProfileCatalog.Entry(profile, machines, List.of(workflow));
    }

    private static BindingIrV1 decodeHex(String encoded) {
        if (encoded.length() > 131072) throw new IllegalArgumentException("binding IR hex exceeds limit");
        return BindingIrV1.decode(HexFormat.of().parseHex(encoded));
    }

    /** Construction-local caches, never shared between chains or consulted during block application. */
    private static final class Assembly {
        private final Map<String, ComponentProduct> components = new LinkedHashMap<>();
        private final Map<Long, WorkflowProduct> workflows = new LinkedHashMap<>();
    }
    private record ComponentProduct(byte[] specification, ComponentDescriptor descriptor,
                                    AppStateMachine machine, TransitionKernel<?, ?> kernel) { }
    private record WorkflowProduct(byte[] document, EventBindingWorkflow workflow) { }

    /** No resolver is forwarded: children cannot recursively activate further composites. */
    private record ComponentContext(AppStateMachineContext parent, Map<String, String> settings)
            implements AppStateMachineContext {
        private ComponentContext { settings = Map.copyOf(settings); }
        @Override public String chainId() { return parent.chainId(); }
        @Override public Optional<AppChainConsensusProfile> consensusProfile() { return parent.consensusProfile(); }
        @Override public Optional<ObservationProfileV1> observationProfile() { return parent.observationProfile(); }
        @Override public Optional<AppChainMembershipView> membershipView() { return parent.membershipView(); }
        @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
            return parent.stateCommitmentIdentity();
        }
        @Override public Optional<AuthenticatedMapValidatorResolver> authenticatedMapValidatorResolver() {
            return parent.authenticatedMapValidatorResolver();
        }
    }
}
