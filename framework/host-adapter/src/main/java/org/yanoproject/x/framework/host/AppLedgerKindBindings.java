package org.yanoproject.x.framework.host;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.plugin.spi.ContributionKindBinding;
import org.yanoproject.api.plugin.spi.FacadeSupport;
import org.yanoproject.api.plugin.spi.PluginCallbackScope;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppCapabilityManifest;
import org.yanoproject.x.framework.api.appchain.AppChainInfo;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.authmap.AuthenticatedMapValueValidator;
import org.yanoproject.x.framework.api.appchain.authmap.AuthenticatedMapValueValidatorFactory;
import org.yanoproject.x.framework.api.appchain.authmap.ValidatorInitContext;
import org.yanoproject.x.framework.api.appchain.authmap.ValidatorVerdict;
import org.yanoproject.x.framework.api.appchain.codec.MessageCodec;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.effects.EffectResult;
import org.yanoproject.x.framework.api.appchain.l1view.EpochObservationManifest;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochBoundary;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochObservationSink;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochObserver;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochObserverProvider;
import org.yanoproject.x.framework.api.appchain.l1view.L1EpochState;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverProvider;
import org.yanoproject.x.framework.api.appchain.observation.AppObservationEmitter;
import org.yanoproject.x.framework.api.appchain.observation.ObservationCandidate;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProvider;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProviderFactory;
import org.yanoproject.x.framework.api.appchain.observation.ObservationRequest;
import org.yanoproject.x.framework.api.appchain.observation.ObservationResult;
import org.yanoproject.x.framework.api.appchain.proof.ProofSubjectDescriptorV1;
import org.yanoproject.x.framework.api.appchain.proof.ProofSubjectProvider;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerContext;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerMode;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerMode.ProposalEligibility;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoff;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGate;
import org.yanoproject.x.framework.api.appchain.membership.MembershipHandoffGateProvider;
import org.yanoproject.x.framework.api.appchain.sequencer.SequencerModeProvider;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;
import org.yanoproject.x.framework.api.appchain.signer.SignerProviderFactory;
import org.yanoproject.x.framework.api.appchain.sink.FinalizedStreamSink;
import org.yanoproject.x.framework.api.appchain.sink.FinalizedStreamSinkFactory;
import org.yanoproject.x.framework.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import org.yanoproject.x.framework.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import org.yanoproject.x.framework.api.appchain.snapshot.SnapshotDescriptorDraftV1;
import org.yanoproject.x.framework.api.appchain.snapshot.SnapshotEntry;
import org.yanoproject.x.framework.api.appchain.transition.CommandDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.EventDescriptor;
import org.yanoproject.x.framework.api.appchain.transition.RuleFact;
import org.yanoproject.x.framework.api.appchain.transition.RuleValueView;
import org.yanoproject.x.framework.api.appchain.transition.TransitionContext;
import org.yanoproject.x.framework.api.appchain.transition.TransitionDecision;
import org.yanoproject.x.framework.api.appchain.transition.TransitionKernel;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkBudget;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkReference;
import org.yanoproject.x.framework.api.appchain.transition.TransitionWorkRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRoute;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelContext;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Host-side {@link ContributionKindBinding}s of the application-ledger contribution kinds declared by
 * {@code META-INF/yano/contribution-kinds/org.yanoproject.x.framework.api.json}, and the facades that
 * route every plugin callback through the host's {@link PluginCallbackScope}.
 *
 * <p>The {@code effect-executor} kind is deliberately not bound here: framework/cardano declares and binds it
 * with its executor SPI. A standalone-only composition (framework/standalone without framework/cardano)
 * therefore offers no effect-executor contributions; that is intended.</p>
 */
public final class AppLedgerKindBindings {
    /** Owner of the descriptor that declares these kinds, and its file name. */
    public static final String DESCRIPTOR_OWNER = "org.yanoproject.x.framework.api";
    /** One observer callback may expose many block observations, but remains bounded. */
    public static final int MAX_OBSERVATIONS_PER_CALLBACK = 4_096;
    public static final int MAX_OBSERVATION_CLAIM_BYTES = 1_048_576;
    public static final int MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES = 4 * 1_048_576;
    /** ADR-028 epoch chunks use a dedicated 6 MiB app-message profile. */
    public static final int MAX_EPOCH_OBSERVATION_CLAIM_BYTES = 5 * 1_048_576;
    public static final int MAX_OBSERVER_ID_LENGTH = 160;

    private AppLedgerKindBindings() {
    }

    private static void runActivation(PluginCallbackScope scope, String action, Runnable callback) {
        scope.activation(action, () -> {
            callback.run();
            return null;
        });
    }

    /** One binding: a kind key, its SPI, the selector the manifest name must equal and the facade. */
    public abstract static class Binding<P> implements ContributionKindBinding<P> {
        private final String descriptorOwner;
        private final String manifestKey;
        private final Class<P> serviceType;
        private final Function<P, String> selector;
        private final BiFunction<P, PluginCallbackScope, P> facade;

        protected Binding(String manifestKey, Class<P> serviceType, Function<P, String> selector,
                          BiFunction<P, PluginCallbackScope, P> facade) {
            this(DESCRIPTOR_OWNER, manifestKey, serviceType, selector, facade);
        }

        protected Binding(String descriptorOwner, String manifestKey, Class<P> serviceType,
                          Function<P, String> selector, BiFunction<P, PluginCallbackScope, P> facade) {
            this.descriptorOwner = descriptorOwner;
            this.manifestKey = manifestKey;
            this.serviceType = serviceType;
            this.selector = selector;
            this.facade = facade;
        }

        @Override public final String descriptorOwner() { return descriptorOwner; }
        @Override public final Class<P> serviceType() { return serviceType; }
        @Override public final String manifestKey() { return manifestKey; }
        @Override public final String selector(P provider) { return selector.apply(provider); }
        @Override public final P facade(P provider, PluginCallbackScope scope) {
            return facade.apply(provider, scope);
        }
    }

    public static final class StateMachine extends Binding<AppStateMachineProvider> {
        public StateMachine() {
            super("app-state-machine", AppStateMachineProvider.class, AppStateMachineProvider::id,
                    StateMachineProviderFacade::new);
        }
    }

    public static final class AuthenticatedMapValidator extends Binding<AuthenticatedMapValueValidatorFactory> {
        public AuthenticatedMapValidator() {
            super("authenticated-map-validator", AuthenticatedMapValueValidatorFactory.class,
                    AuthenticatedMapValueValidatorFactory::id, AuthenticatedMapValidatorFactoryFacade::new);
        }
    }

    public static final class Sequencer extends Binding<SequencerModeProvider> {
        public Sequencer() {
            super("sequencer-mode", SequencerModeProvider.class, SequencerModeProvider::id,
                    SequencerProviderFacade::new);
        }
    }

    public static final class HandoffGate extends Binding<MembershipHandoffGateProvider> {
        public HandoffGate() {
            super("membership-handoff-gate", MembershipHandoffGateProvider.class,
                    MembershipHandoffGateProvider::id, HandoffGateProviderFacade::new);
        }
    }

    public static final class Observer extends Binding<L1ObserverProvider> {
        public Observer() {
            super("l1-observer", L1ObserverProvider.class, L1ObserverProvider::type, ObserverProviderFacade::new);
        }
    }

    public static final class EpochObserver extends Binding<L1EpochObserverProvider> {
        public EpochObserver() {
            super("l1-epoch-observer", L1EpochObserverProvider.class, L1EpochObserverProvider::type,
                    EpochObserverProviderFacade::new);
        }
    }

    public static final class Observation extends Binding<ObservationProviderFactory> {
        public Observation() {
            super("observation-provider", ObservationProviderFactory.class, ObservationProviderFactory::type,
                    ObservationProviderFactoryFacade::new);
        }
    }

    public static final class Signer extends Binding<SignerProviderFactory> {
        public Signer() {
            super("signer-provider", SignerProviderFactory.class, SignerProviderFactory::scheme,
                    SignerFactoryFacade::new);
        }
    }

    public static final class Sink extends Binding<FinalizedStreamSinkFactory> {
        public Sink() {
            super("finalized-sink", FinalizedStreamSinkFactory.class, FinalizedStreamSinkFactory::scheme,
                    SinkFactoryFacade::new);
        }
    }

    public static final class Domain extends Binding<DomainApiProvider> {
        public Domain() {
            super("domain-api", DomainApiProvider.class, DomainApiProvider::id, DomainApiProviderFacade::new);
        }
    }

    public static final class LocalReadModel extends Binding<LocalReadModelProvider> {
        public LocalReadModel() {
            super("local-read-model", LocalReadModelProvider.class, LocalReadModelProvider::id,
                    LocalReadModelProviderFacade::new);
        }
    }

    private record DomainApiProviderFacade(
            DomainApiProvider delegate,
            PluginCallbackScope scope
    ) implements DomainApiProvider {
        private DomainApiProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read domain-api provider identity",
                    () -> scope.call(delegate::id));
        }

        @Override
        public DomainApi create(DomainApiContext context) {
            Objects.requireNonNull(context, "context");
            return scope.activation("create domain-api product", () -> scope.call(() -> scope.product(
                    delegate.create(context), api -> new DomainApiFacade(api, scope))));
        }
    }

    private record LocalReadModelProviderFacade(
            LocalReadModelProvider delegate,
            PluginCallbackScope scope
    ) implements LocalReadModelProvider {
        private LocalReadModelProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read local read-model provider identity",
                    () -> scope.call(delegate::id));
        }

        @Override
        public AutoCloseable start(LocalReadModelContext context) {
            Objects.requireNonNull(context, "context");
            return scope.activation("start local read-model product", () -> scope.call(() -> scope.product(
                    delegate.start(context), lifecycle -> () -> scope.run(lifecycle::close))));
        }
    }

    private record DomainApiFacade(
            DomainApi delegate,
            PluginCallbackScope scope
    ) implements DomainApi {
        private DomainApiFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<DomainApiRoute> routes() {
            return scope.activation("enumerate domain-api routes", () -> {
                List<DomainApiRoute> values = Objects.requireNonNull(
                        scope.call(delegate::routes),
                        "DomainApi.routes() must not return null");
                Iterator<DomainApiRoute> iterator = Objects.requireNonNull(
                        scope.call(values::iterator),
                        "DomainApi route iterator must not be null");
                List<DomainApiRoute> snapshot = new ArrayList<>();
                while (scope.call(iterator::hasNext)) {
                    if (snapshot.size() == DomainApi.MAX_ROUTES) {
                        throw new IllegalStateException(
                                "DomainApi must declare at most "
                                        + DomainApi.MAX_ROUTES + " routes");
                    }
                    snapshot.add(Objects.requireNonNull(
                            scope.call(iterator::next),
                            "DomainApi routes must not contain null entries"));
                }
                return List.copyOf(snapshot);
            });
        }

        @Override
        public DomainApiResponse handle(DomainApiRequest request) throws Exception {
            Objects.requireNonNull(request, "request");
            return Objects.requireNonNull(
                    scope.call(() -> delegate.handle(request)),
                    "DomainApi.handle() must not return null");
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }

    private record StateMachineProviderFacade(
            AppStateMachineProvider delegate,
            PluginCallbackScope scope
    ) implements AppStateMachineProvider {
        private StateMachineProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read state-machine provider identity",
                    () -> scope.call(delegate::id));
        }

        @Override
        public AppStateMachine create() {
            return scope.activation("create state-machine product", () -> scope.call(() -> scope.product(
                    delegate.create(), machine -> new StateMachineFacade(machine, scope))));
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return scope.activation("create state-machine product", () -> scope.call(() -> scope.product(
                    delegate.create(context), machine -> new StateMachineFacade(machine, scope))));
        }
    }

    private record AuthenticatedMapValidatorFactoryFacade(
            AuthenticatedMapValueValidatorFactory delegate,
            PluginCallbackScope scope
    ) implements AuthenticatedMapValueValidatorFactory {
        private AuthenticatedMapValidatorFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("read authenticated-map validator provider identity",
                    () -> scope.call(delegate::id));
        }

        @Override
        public String contractVersion() {
            return scope.activation("read authenticated-map validator contract version",
                    () -> scope.call(delegate::contractVersion));
        }

        @Override
        public AuthenticatedMapValueValidator create(ValidatorInitContext context) {
            Objects.requireNonNull(context, "context");
            ValidatorInitContext input = new ValidatorInitContext(
                    context.descriptorId(), context.providerId(), context.contractVersion(),
                    context.parameters(), context.collectionIds());
            return scope.activation("create authenticated-map validator product",
                    () -> scope.call(() -> scope.product(
                            delegate.create(input),
                            validator -> new AuthenticatedMapValidatorFacade(validator, scope))));
        }
    }

    private record AuthenticatedMapValidatorFacade(
            AuthenticatedMapValueValidator delegate,
            PluginCallbackScope scope
    ) implements AuthenticatedMapValueValidator {
        private AuthenticatedMapValidatorFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ValidatorVerdict validate(
                String collectionId,
                byte[] applicationKey,
                byte[] value
        ) {
            Objects.requireNonNull(collectionId, "collectionId");
            byte[] keyInput = Objects.requireNonNull(applicationKey, "applicationKey").clone();
            byte[] valueInput = Objects.requireNonNull(value, "value").clone();
            return Objects.requireNonNull(scope.call(() -> delegate.validate(collectionId, keyInput, valueInput)),
                    "AuthenticatedMapValueValidator.validate() must not return null");
        }
    }

    private record KernelFacade<C, F>(TransitionKernel<C, F> delegate,
                                       PluginCallbackScope scope)
            implements TransitionKernel<C, F> {
        @Override public MessageCodec<C> codec() {
            MessageCodec<C> codec = scope.call(delegate::codec);
            return new MessageCodec<>() {
                @Override public byte[] encode(C value) {
                    return scope.call(() -> codec.encode(value)).clone();
                }
                @Override public C decode(byte[] body) {
                    byte[] input = body.clone();
                    return scope.call(() -> codec.decode(input));
                }
                @Override public Class<C> type() { return scope.call(codec::type); }
            };
        }
        @Override public AppStateMachine.AdmissionResult admit(C command) {
            return scope.call(() -> delegate.admit(command));
        }
        @Override public AppStateMachine.AdmissionResult admit(C command, TransitionContext context) {
            return scope.call(() -> delegate.admit(command, context));
        }
        @Override public F facts(C command, TransitionContext context, AppStateReader state) {
            return scope.call(() -> delegate.facts(command, context, state));
        }
        @Override public List<String> readParticipants() {
            return FacadeSupport.snapshotList(scope.call(delegate::readParticipants), scope,
                    256, "too many kernel read participants");
        }
        @Override public List<TransitionWorkBudget> workBudgets() {
            return FacadeSupport.snapshotList(scope.call(delegate::workBudgets), scope,
                    TransitionWorkBudget.MAX_DECLARATIONS, "too many kernel work budgets");
        }
        @Override public List<TransitionWorkReference> workReferences() {
            return FacadeSupport.snapshotList(scope.call(delegate::workReferences), scope,
                    TransitionWorkBudget.MAX_DECLARATIONS, "too many kernel work references");
        }
        @Override public Optional<TransitionWorkRequest> workRequest(C command, TransitionContext context) {
            return Objects.requireNonNull(scope.call(() -> delegate.workRequest(command, context)),
                    "kernel work request must not return null");
        }
        @Override public F facts(C command, TransitionContext context, AppStateReader state,
                                 Map<String, AppStateReader> participants) {
            Map<String, AppStateReader> views = Map.copyOf(participants);
            return scope.call(() -> delegate.facts(command, context, state, views));
        }
        @Override public TransitionDecision decide(C command, TransitionContext context, F facts) {
            return scope.call(() -> delegate.decide(command, context, facts));
        }
        @Override public List<CommandDescriptor> commands() {
            return FacadeSupport.snapshotList(scope.call(delegate::commands), scope,
                    256, "too many kernel commands");
        }
        @Override public List<EventDescriptor> events() {
            return FacadeSupport.snapshotList(scope.call(delegate::events), scope,
                    256, "too many kernel event descriptors");
        }
        @Override public ConfigurationDescriptor configuration() {
            return scope.call(delegate::configuration);
        }
        @Override public byte[] lookupKey(byte[] logicalKey) {
            byte[] input = Objects.requireNonNull(logicalKey, "logicalKey").clone();
            return Objects.requireNonNull(scope.call(() -> delegate.lookupKey(input)),
                    "kernel lookup key must not be null").clone();
        }
        @Override public List<RuleFact> ruleFacts() {
            return snapshotDeclarations(scope.call(delegate::ruleFacts), RuleFact.class, scope, RuleFact.MAX_FACTS,
                    "too many kernel rule facts");
        }
        /**
         * Forwards verified fact values without interpreting them. The caller validates names, types and
         * bounds and turns a violation into a deterministic rejection, so this snapshot never throws for a
         * data shape (see {@link #snapshotRuleFactValues}). A null result passes through: the caller treats it
         * as a violation too. An exception thrown by the plugin itself propagates like one from decide.
         */
        @Override public Map<String, Object> ruleFactValues(C command, TransitionContext context, F facts) {
            Map<String, Object> values = scope.call(() -> delegate.ruleFactValues(command, context, facts));
            return values == null ? null : snapshotRuleFactValues(values, scope);
        }
        @Override public List<RuleValueView> ruleValueViews() {
            return snapshotDeclarations(scope.call(delegate::ruleValueViews), RuleValueView.class, scope,
                    RuleValueView.MAX_VIEWS,
                    "too many kernel rule value views");
        }
        /**
         * Forwards a value key. A plugin's {@link IllegalArgumentException} reaches the caller unchanged, which turns
         * it into a deterministic rule error; a {@code null} key passes through as a data shape the caller rejects.
         */
        @Override public byte[] ruleValueKey(String namespace, byte[] key) {
            String space = Objects.requireNonNull(namespace, "namespace");
            byte[] input = Objects.requireNonNull(key, "key").clone();
            byte[] local = scope.call(() -> delegate.ruleValueKey(space, input));
            return local == null ? null : local.clone();
        }
        /** Forwards decoded values like {@link #ruleFactValues}: a bounded, host-owned snapshot, never interpreted. */
        @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
            String space = Objects.requireNonNull(namespace, "namespace");
            byte[] keyInput = Objects.requireNonNull(key, "key").clone();
            byte[] storedInput = Objects.requireNonNull(stored, "stored").clone();
            Map<String, Object> values = scope.call(() -> delegate.ruleValueFields(space, keyInput, storedInput));
            return values == null ? null : snapshotRuleValues(values, RULE_ELEMENT_ENTRIES, scope);
        }
        @Override public List<RuleFact> ruleWriteFields() {
            return snapshotDeclarations(scope.call(delegate::ruleWriteFields), RuleFact.class, scope,
                    RuleValueView.MAX_FIELDS,
                    "too many kernel rule write fields");
        }
        @Override public List<RuleFact> ruleWriteCoverageFields() {
            return snapshotDeclarations(scope.call(delegate::ruleWriteCoverageFields),
                    RuleFact.class, scope, RuleValueView.MAX_FIELDS,
                    "too many kernel rule write coverage fields");
        }
        @Override public List<Map<String, Object>> ruleWrites(C command) {
            List<Map<String, Object>> writes = scope.call(() -> delegate.ruleWrites(command));
            return writes == null ? null : snapshotRuleElements(writes, scope);
        }
        @Override public List<Map<String, Object>> ruleWriteCoverage(C command, TransitionContext context,
                                                                    F facts) {
            List<Map<String, Object>> coverage = scope.call(() -> delegate.ruleWriteCoverage(command, context, facts));
            return coverage == null ? null : snapshotRuleElements(coverage, scope);
        }
    }

    /**
     * Declaration lists of final host records ({@link RuleFact}, {@link RuleValueView}):
     * {@link FacadeSupport#snapshotList} with an element check, so a raw-cast plugin list cannot put a null or a
     * plugin object in front of the engine. A violation fails construction, like an overflow.
     */
    private static <T> List<T> snapshotDeclarations(List<T> values, Class<T> type, PluginCallbackScope scope,
                                                   int maximumSize, String overflowMessage) {
        List<T> snapshot = FacadeSupport.snapshotList(values, scope, maximumSize, overflowMessage);
        if (snapshot != null && snapshot.stream().anyMatch(value -> !type.isInstance(value))) {
            throw new IllegalStateException("kernel rule declarations must be non-null " + type.getSimpleName()
                    + " records");
        }
        return snapshot;
    }

    /**
     * Entries one decoded value or write-view element may carry: declared fields plus value fields
     * ({@link RuleValueView}), each list bounded by {@link RuleValueView#MAX_FIELDS}.
     */
    private static final int RULE_ELEMENT_ENTRIES = 2 * RuleValueView.MAX_FIELDS;

    /**
     * Host-owned stand-in for a fact, decoded value or write-element entry that is not a conforming scalar or text
     * list.
     */
    private static final Object NON_CONFORMING_FACT = new Object() {
        @Override public String toString() { return "non-conforming rule fact"; }
    };
    /** A key no declared fact can have ({@link RuleFact} names are non-empty identifiers). */
    private static final String NON_CONFORMING_FACT_NAME = "";

    /**
     * Copies a plugin's fact values into host-owned objects without interpreting them. Every plugin read happens
     * inside one callback and traversal is bounded by iterations (not distinct keys). A map with more entries than
     * its bound becomes the marker alone, since which entries a salted or identity map yields first differs between
     * nodes; a byte array or text list keeps one element beyond its bound so the caller can detect overflow. Nothing
     * here throws for a data shape: a null entry set, a null or foreign entry, a non-text or repeated key, or a value
     * that is not a conforming scalar or text list becomes a marker the caller rejects as a violation. Only
     * {@code Long}, {@code String}, {@code Boolean}, copied {@code byte[]}, and copied lists of text survive, so no
     * plugin object escapes the class-loader boundary.
     */
    private static Map<String, Object> snapshotRuleFactValues(Map<String, Object> values, PluginCallbackScope scope) {
        return snapshotRuleValues(values, RuleFact.MAX_FACTS, scope);
    }

    /**
     * {@link #snapshotRuleFactValues} with an explicit entry bound: a map with more than {@code maximumEntries}
     * entries becomes the marker alone.
     */
    private static Map<String, Object> snapshotRuleValues(Map<String, Object> values, int maximumEntries,
                                                          PluginCallbackScope scope) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Iterator<?> entries = scope.call(() -> {
            Set<?> entrySet = ((Map<?, ?>) values).entrySet();
            return entrySet == null ? null : entrySet.iterator();
        });
        if (entries == null) {
            snapshot.put(NON_CONFORMING_FACT_NAME, NON_CONFORMING_FACT);
            return Collections.unmodifiableMap(snapshot);
        }
        for (int visited = 0; scope.call(entries::hasNext); visited++) {
            if (visited == maximumEntries) return Map.of(NON_CONFORMING_FACT_NAME, NON_CONFORMING_FACT);
            Object[] pair = scope.call(() -> {
                Object element = entries.next();
                if (!(element instanceof Map.Entry<?, ?> entry)) return null;
                return new Object[]{entry.getKey(), snapshotRuleFactValue(entry.getValue())};
            });
            if (pair == null || !(pair[0] instanceof String name)) {
                snapshot.put(NON_CONFORMING_FACT_NAME, NON_CONFORMING_FACT);
            } else {
                // A repeated key keeps the marker, never the value that iterated last: an identity map orders equal
                // keys differently on each node.
                snapshot.put(name, snapshot.containsKey(name) ? NON_CONFORMING_FACT : pair[1]);
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Copies a write view or its coverage (Yano X ADR-031.4) like {@link #snapshotRuleValues}: at most one element
     * beyond {@link RuleValueView#MAX_WRITES}, each element a bounded snapshot. An element that is not a map becomes
     * a map holding only the non-conforming marker, which the caller rejects; nothing here throws for a data shape.
     */
    private static List<Map<String, Object>> snapshotRuleElements(List<Map<String, Object>> elements,
                                                                  PluginCallbackScope scope) {
        Iterator<?> items = scope.call(() -> ((List<?>) elements).iterator());
        List<Map<String, Object>> snapshot = new ArrayList<>();
        if (items == null) {
            snapshot.add(Map.of(NON_CONFORMING_FACT_NAME, NON_CONFORMING_FACT));
            return Collections.unmodifiableList(snapshot);
        }
        for (int visited = 0; visited <= RuleValueView.MAX_WRITES && scope.call(items::hasNext);
                visited++) {
            Object item = scope.call(items::next);
            if (item instanceof Map<?, ?> element) {
                @SuppressWarnings("unchecked")
                Map<String, Object> values = (Map<String, Object>) element;
                snapshot.add(snapshotRuleValues(values, RULE_ELEMENT_ENTRIES, scope));
            } else {
                snapshot.add(Map.of(NON_CONFORMING_FACT_NAME, NON_CONFORMING_FACT));
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    /** Runs inside the plugin callback; returns a host-owned copy or the non-conforming marker. */
    private static Object snapshotRuleFactValue(Object value) {
        if (value == null || value instanceof Long || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return Arrays.copyOf(bytes, Math.min(bytes.length, RuleFact.MAX_VALUE_BYTES + 1));
        }
        if (!(value instanceof List<?> list)) return NON_CONFORMING_FACT;
        Iterator<?> items = list.iterator();
        if (items == null) return NON_CONFORMING_FACT;
        List<Object> copy = new ArrayList<>();
        for (int visited = 0; visited <= RuleFact.MAX_SET_ENTRIES && items.hasNext(); visited++) {
            Object item = items.next();
            copy.add(item == null || item instanceof String ? item : NON_CONFORMING_FACT);
        }
        return Collections.unmodifiableList(copy);
    }

    private record StateMachineFacade(
            AppStateMachine delegate,
            PluginCallbackScope scope
    ) implements AppStateMachine {
        private StateMachineFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Optional<TransitionKernel<?, ?>> transitionKernel() {
            return scope.call(delegate::transitionKernel)
                    .map(kernel -> new KernelFacade<>(kernel, scope));
        }

        @Override
        public String id() {
            return scope.activation("identify state-machine product",
                    () -> scope.call(delegate::id));
        }

        @Override
        public void init(AppStateReader state, AppChainInfo info) {
            runActivation(scope, "initialize state-machine product",
                    () -> scope.run(() -> delegate.init(state, info)));
        }

        @Override
        public AdmissionResult validate(AppMessage message) {
            return scope.call(() -> delegate.validate(message));
        }

        @Override
        public AdmissionResult validateForBlock(
                AppMessage message,
                long candidateHeight,
                AppStateReader committedState
        ) {
            return scope.call(() -> delegate.validateForBlock(
                            message, candidateHeight, committedState));
        }

        @Override
        public AdmissionResult validatePrivilegedSystemSubmission(
                String topic,
                byte[] body
        ) {
            byte[] input = body != null ? body.clone() : null;
            return scope.call(() -> delegate.validatePrivilegedSystemSubmission(topic, input));
        }

        @Override
        public Map<String, Object> operationalStatus() {
            Map<String, Object> values = scope.call(delegate::operationalStatus);
            return FacadeSupport.snapshotStatus(values, scope);
        }

        @Override
        public AppCapabilityManifest capabilityManifest() {
            return scope.call(delegate::capabilityManifest);
        }

        @Override
        public List<ProofSubjectProvider>
        proofSubjectProviders() {
            var values = Objects.requireNonNull(scope.call(delegate::proofSubjectProviders),
                    "AppStateMachine.proofSubjectProviders() must not return null");
            var snapshot = FacadeSupport.snapshotList(values, scope,
                    ProofSubjectDescriptorV1
                            .MAX_DESCRIPTORS,
                    "AppStateMachine must declare at most 64 proof subject providers");
            return snapshot.stream().map(provider -> {
                var source = Objects.requireNonNull(provider, "proof subject provider");
                return (ProofSubjectProvider)
                        new ProofSubjectProvider() {
                    @Override
                    public List<ProofSubjectDescriptorV1>
                    descriptors(AppCapabilityManifest profile) {
                        return List.copyOf(scope.call(() -> source.descriptors(profile)));
                    }

                    @Override
                    public ResolvedProofSubject resolve(String subjectId,
                                                        Map<String, String> coordinates,
                                                        ProofView view) {
                        return scope.call(() -> source.resolve(subjectId, Map.copyOf(coordinates), view));
                    }

                    @Override
                    public TypedFact decode(String subjectId, byte[] canonicalValue) {
                        byte[] value = canonicalValue.clone();
                        return scope.call(() -> source.decode(subjectId, value));
                    }

                    @Override
                    public ClaimResult evaluate(String subjectId, TypedFact fact,
                                                ClaimRequest claim) {
                        return scope.call(() -> source.evaluate(subjectId, fact, claim));
                    }
                };
            }).toList();
        }

        @Override
        public List<AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
            List<AuthenticatedSnapshotSeriesDescriptorV1> values = Objects.requireNonNull(
                    scope.call(delegate::authenticatedSnapshotSeries),
                    "AppStateMachine.authenticatedSnapshotSeries() must not return null");
            List<AuthenticatedSnapshotSeriesDescriptorV1> snapshot = FacadeSupport.snapshotList(
                    values, scope, FacadeSupport.MAX_FACTORY_PRODUCTS_PER_CALLBACK,
                    "AppStateMachine must declare at most 256 authenticated snapshot series");
            snapshot.forEach(value -> Objects.requireNonNull(
                    value, "authenticated snapshot series must not contain null entries"));
            return snapshot;
        }

        @Override
        public List<AuthenticatedSnapshotSourceCommitmentV1>
                authenticatedSnapshotSourceCommitments() {
            List<AuthenticatedSnapshotSourceCommitmentV1> values = Objects.requireNonNull(
                    scope.call(delegate::authenticatedSnapshotSourceCommitments),
                    "AppStateMachine.authenticatedSnapshotSourceCommitments() must not return null");
            List<AuthenticatedSnapshotSourceCommitmentV1> snapshot = FacadeSupport.snapshotList(
                    values, scope, FacadeSupport.MAX_FACTORY_PRODUCTS_PER_CALLBACK,
                    "AppStateMachine must declare at most 256 authenticated snapshot commitments");
            return snapshot.stream().map(value -> (AuthenticatedSnapshotSourceCommitmentV1)
                    new SnapshotSourceCommitmentFacade(
                    Objects.requireNonNull(value,
                            "authenticated snapshot commitments must not contain null entries"), scope)).toList();
        }

        @Override
        public void apply(
                AppBlockExecutionContext context,
                AppStateWriter writer,
                AppEffectEmitter effects
        ) {
            scope.run(() -> delegate.apply(context, writer, effects));
        }

        @Override
        public void onEffectResult(
                AppBlockExecutionContext context,
                EffectResult result,
                AppStateWriter writer,
                AppEffectEmitter effects
        ) {
            scope.run(() -> delegate.onEffectResult(context, result, writer, effects));
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext state) {
            byte[] input = params != null ? params.clone() : null;
            byte[] response = scope.call(() -> delegate.query(path, input, state));
            return response != null ? response.clone() : null;
        }

        @Override
        public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                          AppEffectEmitter effects, AppObservationEmitter observations) {
            scope.run(() -> delegate.apply(context, writer, effects, observations));
        }

        @Override
        public void onEffectResult(AppBlockExecutionContext context, EffectResult result,
                                   AppStateWriter writer, AppEffectEmitter effects,
                                   AppObservationEmitter observations) {
            scope.run(() -> delegate.onEffectResult(context, result, writer, effects, observations));
        }

        @Override
        public void onObservationResult(AppBlockExecutionContext context, ObservationResult result,
                                        AppStateWriter writer, AppEffectEmitter effects,
                                        AppObservationEmitter observations) {
            scope.run(() -> delegate.onObservationResult(context, result, writer, effects, observations));
        }
    }

    private record SnapshotSourceCommitmentFacade(
            AuthenticatedSnapshotSourceCommitmentV1 delegate,
            PluginCallbackScope scope
    ) implements AuthenticatedSnapshotSourceCommitmentV1 {
        @Override public String seriesId() {
            return scope.call(delegate::seriesId);
        }

        @Override public String algorithm() {
            return scope.call(delegate::algorithm);
        }

        @Override public String wireVersion() {
            return scope.call(delegate::wireVersion);
        }

        @Override public String compatibilityId() {
            return scope.call(delegate::compatibilityId);
        }

        @Override public byte[] initial(SnapshotDescriptorDraftV1 draft) {
            byte[] result = scope.call(() -> delegate.initial(
                    Objects.requireNonNull(draft, "draft")));
            return Objects.requireNonNull(result,
                    "AuthenticatedSnapshotSourceCommitmentV1.initial() must not return null")
                    .clone();
        }

        @Override
        public byte[] append(byte[] accumulator, long chunkIndex, List<SnapshotEntry> entries) {
            byte[] input = Objects.requireNonNull(accumulator, "accumulator").clone();
            List<SnapshotEntry> entrySnapshot = List.copyOf(
                    Objects.requireNonNull(entries, "entries"));
            byte[] result = scope.call(() -> delegate.append(input, chunkIndex, entrySnapshot));
            return Objects.requireNonNull(result,
                    "AuthenticatedSnapshotSourceCommitmentV1.append() must not return null")
                    .clone();
        }

        @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
            byte[] input = Objects.requireNonNull(accumulator, "accumulator").clone();
            byte[] result = scope.call(() -> delegate.finish(input, chunks, entries));
            return Objects.requireNonNull(result,
                    "AuthenticatedSnapshotSourceCommitmentV1.finish() must not return null")
                    .clone();
        }
    }

    private record SequencerProviderFacade(
            SequencerModeProvider delegate,
            PluginCallbackScope scope
    ) implements SequencerModeProvider {
        private SequencerProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.call(delegate::id);
        }

        @Override
        public SequencerMode create(SequencerContext context) {
            return scope.activation("create sequencer product", () -> scope.call(() -> scope.product(
                    delegate.create(context), mode -> new SequencerModeFacade(mode, scope))));
        }
    }

    private record SequencerModeFacade(
            SequencerMode delegate,
            PluginCallbackScope scope
    ) implements SequencerMode {
        private SequencerModeFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("identify sequencer product",
                    () -> scope.call(delegate::id));
        }

        @Override
        public void init(SequencerContext context) {
            runActivation(scope, "initialize sequencer product",
                    () -> scope.run(() -> delegate.init(context)));
        }

        @Override
        public boolean shouldProposeNow(long height) {
            return scope.call(() -> delegate.shouldProposeNow(height));
        }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            byte[] input = Objects.requireNonNull(proposerKey, "proposerKey").clone();
            return scope.call(() -> delegate.checkProposal(input, height));
        }

        @Override
        public Map<String, Object> status() {
            return scope.activation("read sequencer product status",
                    () -> scope.call(() -> FacadeSupport.snapshotStatus(delegate.status(), scope)));
        }
    }

    private record ObserverProviderFacade(
            L1ObserverProvider delegate,
            PluginCallbackScope scope
    ) implements L1ObserverProvider {
        private ObserverProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String type() {
            return scope.call(delegate::type);
        }

        @Override
        public L1ObserverConsensusIdentity consensusIdentity(
                String observerId, Map<String, String> settings) {
            return scope.call(() -> delegate.consensusIdentity(observerId, settings));
        }

        @Override
        public L1Observer create(String observerId, Map<String, String> settings) {
            return scope.activation("create L1-observer product", () -> scope.call(() -> scope.product(
                    delegate.create(observerId, settings),
                    observer -> new ObserverFacade(observer, observerId, scope))));
        }
    }

    private record EpochObserverProviderFacade(
            L1EpochObserverProvider delegate,
            PluginCallbackScope scope
    ) implements L1EpochObserverProvider {
        private EpochObserverProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String type() {
            return scope.call(delegate::type);
        }

        @Override
        public L1ObserverConsensusIdentity consensusIdentity(
                String observerId, Map<String, String> settings) {
            return scope.call(() -> delegate.consensusIdentity(observerId, settings));
        }

        @Override
        public L1EpochObserver create(String observerId, Map<String, String> settings) {
            return scope.activation("create L1-epoch-observer product", () -> scope.call(() -> scope.product(
                    delegate.create(observerId, settings),
                    observer -> new EpochObserverFacade(observer, observerId, scope))));
        }
    }

    private record ObservationProviderFactoryFacade(
            ObservationProviderFactory delegate,
            PluginCallbackScope scope
    ) implements ObservationProviderFactory {
        private ObservationProviderFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String type() {
            return scope.call(delegate::type);
        }

        @Override
        public ObservationProvider create(String definitionId,
                                          Map<String, String> operationalSettings) {
            Map<String, String> snapshot = operationalSettings == null
                    ? Map.of() : Map.copyOf(operationalSettings);
            return scope.activation("create observation-provider product", () ->
                    scope.call(() -> scope.product(
                            delegate.create(definitionId, snapshot),
                            provider -> new ObservationProviderFacade(provider, scope))));
        }
    }

    private record ObservationProviderFacade(
            ObservationProvider delegate,
            PluginCallbackScope scope
    ) implements ObservationProvider {
        private ObservationProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public ObservationCandidate acquire(ObservationRequest request) throws Exception {
            ObservationCandidate candidate = scope.call(() -> delegate.acquire(request));
            if (candidate == null) {
                throw new IllegalStateException(
                        "Observation provider returned a null candidate");
            }
            return new ObservationCandidate(candidate.sourceId(), candidate.value(),
                    candidate.evidence(), candidate.sourceVersion(),
                    candidate.freshnessAnchorType(), candidate.freshnessAnchor());
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }

    private record EpochObserverFacade(
            L1EpochObserver delegate,
            String configuredObserverId,
            PluginCallbackScope scope
    ) implements L1EpochObserver {
        private static final int MAX_EPOCH_CHUNKS = 1_000_000;

        private EpochObserverFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String observerId() {
            return scope.activation("identify L1-epoch-observer product", () -> {
                String actual = scope.call(delegate::observerId);
                if (!Objects.equals(configuredObserverId, actual)) {
                    throw new IllegalStateException(
                            "L1-epoch-observer product id does not match configured id");
                }
                return actual;
            });
        }

        @Override
        public EpochObservationManifest prepare(
                L1EpochBoundary boundary,
                L1EpochState state
        ) {
            Objects.requireNonNull(boundary, "boundary");
            Objects.requireNonNull(state, "state");
            EpochObservationManifest manifest = scope.activation(
                    "prepare L1 epoch observations", () -> scope.call(() -> delegate.prepare(boundary, state)));
            Objects.requireNonNull(manifest, "L1 epoch manifest must not be null");
            if (!configuredObserverId.equals(manifest.observerId())
                    || manifest.previousEpoch() != boundary.previousEpoch()
                    || manifest.newEpoch() != boundary.newEpoch()
                    || manifest.chunkCount() > MAX_EPOCH_CHUNKS) {
                throw new IllegalArgumentException(
                        "L1 epoch manifest does not match the configured boundary");
            }
            return manifest;
        }

        @Override
        public void writeObservations(
                EpochObservationManifest manifest,
                L1EpochState state,
                L1EpochObservationSink sink
        ) {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(sink, "sink");
            if (!configuredObserverId.equals(manifest.observerId())) {
                throw new IllegalArgumentException("L1 epoch manifest observer does not match");
            }
            runActivation(scope, "write L1 epoch observations", () -> scope.run(() -> delegate.writeObservations(
                            manifest, state, (index, claim) -> {
                                byte[] snapshot = Objects.requireNonNull(
                                        claim, "L1 epoch claim must not be null").clone();
                                if (snapshot.length > MAX_EPOCH_OBSERVATION_CLAIM_BYTES) {
                                    throw new IllegalArgumentException(
                                            "L1 epoch claim exceeds "
                                                    + MAX_EPOCH_OBSERVATION_CLAIM_BYTES
                                                    + " bytes");
                                }
                                sink.write(index, snapshot);
                            })));
        }

        @Override
        public Map<String, Object> status() {
            return scope.activation("read L1-epoch-observer product status", () ->
                    scope.call(() -> FacadeSupport.snapshotStatus(delegate.status(), scope)));
        }
    }

    private record ObserverFacade(
            L1Observer delegate,
            String configuredObserverId,
            PluginCallbackScope scope
    ) implements L1Observer {
        private ObserverFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String observerId() {
            return scope.activation("identify L1-observer product",
                    () -> {
                        String actual = scope.call(delegate::observerId);
                        if (!Objects.equals(configuredObserverId, actual)) {
                            throw new IllegalStateException(
                                    "L1-observer product id does not match configured id");
                        }
                        return actual;
                    });
        }

        @Override
        public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
            byte[] expectedBlockHash = Objects.requireNonNull(
                    blockHash, "blockHash").clone();
            if (expectedBlockHash.length != 32) {
                throw new IllegalArgumentException("L1 callback block hash must be 32 bytes");
            }
            return scope.call(() -> {
                List<L1Observation> values = delegate.observe(slot, expectedBlockHash.clone(), block);
                if (values == null) {
                    throw new IllegalStateException("L1 observer result must not be null");
                }
                List<L1Observation> snapshots = new ArrayList<>();
                long aggregateClaimBytes = 0;
                Iterator<L1Observation> iterator = scope.call(values::iterator);
                while (scope.call(iterator::hasNext)) {
                    if (snapshots.size() == MAX_OBSERVATIONS_PER_CALLBACK) {
                        throw new IllegalStateException(
                                "L1 observer result must contain at most "
                                        + MAX_OBSERVATIONS_PER_CALLBACK + " observations");
                    }
                    ObservationSnapshot snapshot = snapshotObservation(
                            scope.call(iterator::next),
                            configuredObserverId, slot, expectedBlockHash);
                    int claimBytes = snapshot.claimBytes();
                    if (aggregateClaimBytes
                            > MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES - claimBytes) {
                        throw new IllegalArgumentException(
                                "L1 observer aggregate claims exceed "
                                        + MAX_OBSERVATION_AGGREGATE_CLAIM_BYTES + " bytes");
                    }
                    aggregateClaimBytes += claimBytes;
                    snapshots.add(snapshot.observation());
                }
                return List.copyOf(snapshots);
            });
        }

        private static ObservationSnapshot snapshotObservation(
                L1Observation observation,
                String configuredObserverId,
                long callbackSlot,
                byte[] callbackBlockHash
        ) {
            Objects.requireNonNull(observation,
                    "L1 observer result must not contain null observations");
            String observerId = observation.observerId();
            if (observerId == null || observerId.isBlank()
                    || observerId.length() > MAX_OBSERVER_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "L1 observation id must be non-blank and at most "
                                + MAX_OBSERVER_ID_LENGTH + " characters");
            }
            if (!observerId.equals(configuredObserverId)) {
                throw new IllegalArgumentException(
                        "L1 observation id does not match configured observer");
            }
            if (observation.slot() != callbackSlot) {
                throw new IllegalArgumentException(
                        "L1 observation slot does not match callback slot");
            }
            if (callbackBlockHash == null || callbackBlockHash.length != 32
                    || !java.util.Arrays.equals(
                            observation.blockHash(), callbackBlockHash)) {
                throw new IllegalArgumentException(
                        "L1 observation block hash does not match callback block hash");
            }
            byte[] claim = observation.claim();
            if (claim.length > MAX_OBSERVATION_CLAIM_BYTES) {
                throw new IllegalArgumentException(
                        "L1 observation claim exceeds "
                                + MAX_OBSERVATION_CLAIM_BYTES + " bytes");
            }
            return new ObservationSnapshot(
                    new L1Observation(observerId, observation.anchor(),
                            observation.slot(), observation.blockHash(), claim),
                    claim.length);
        }

        private record ObservationSnapshot(
                L1Observation observation,
                int claimBytes
        ) {
        }

        @Override
        public Map<String, Object> status() {
            return scope.activation("read L1-observer product status",
                    () -> scope.call(() -> FacadeSupport.snapshotStatus(delegate.status(), scope)));
        }
    }

    private record SignerFactoryFacade(
            SignerProviderFactory delegate,
            PluginCallbackScope scope
    ) implements SignerProviderFactory {
        private SignerFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return scope.call(delegate::scheme);
        }

        @Override
        public SignerProvider create(String keyReference) {
            return scope.activation("create signer product", () -> scope.call(() -> scope.product(
                    delegate.create(keyReference), signer -> new SignerFacade(signer, scope))));
        }
    }

    private record SignerFacade(
            SignerProvider delegate,
            PluginCallbackScope scope
    ) implements SignerProvider {
        private SignerFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public byte[] sign(byte[] message) {
            byte[] input = Objects.requireNonNull(message, "message").clone();
            return scope.activation("sign an app-chain message", () -> {
                byte[] signature = scope.call(() -> delegate.sign(input));
                if (signature == null || signature.length != 64) {
                    throw new IllegalStateException(
                            "Signer product must return a 64-byte Ed25519 signature");
                }
                return signature.clone();
            });
        }

        @Override
        public byte[] publicKey() {
            return scope.activation("initialize signer product",
                    () -> {
                        byte[] publicKey = scope.call(delegate::publicKey);
                        if (publicKey == null || publicKey.length != 32) {
                            throw new IllegalStateException(
                                    "Signer product must return a 32-byte Ed25519 public key");
                        }
                        return publicKey.clone();
                    });
        }

        @Override
        public String publicKeyHex() {
            return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(publicKey());
        }
    }


    private record HandoffGateProviderFacade(
            MembershipHandoffGateProvider delegate,
            PluginCallbackScope scope
    ) implements MembershipHandoffGateProvider {
        private HandoffGateProviderFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.call(delegate::id);
        }

        @Override
        public MembershipHandoffGate create(String chainId, Map<String, String> chainSettings) {
            return scope.activation("create membership handoff gate", () -> scope.call(() -> scope.product(
                    delegate.create(chainId, chainSettings), gate -> new HandoffGateFacade(gate, scope))));
        }
    }

    /** Called on the consensus path once per finalized block while a handoff is pending. */
    private record HandoffGateFacade(
            MembershipHandoffGate delegate,
            PluginCallbackScope scope
    ) implements MembershipHandoffGate {
        private HandoffGateFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public boolean finalizesHandoff(AppBlock block, MembershipHandoff handoff) {
            return scope.call(() -> delegate.finalizesHandoff(block, handoff));
        }
    }

    private record SinkFactoryFacade(
            FinalizedStreamSinkFactory delegate,
            PluginCallbackScope scope
    ) implements FinalizedStreamSinkFactory {
        private SinkFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return scope.call(delegate::scheme);
        }

        @Override
        public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
            return scope.activation("create finalized-sink products", () -> scope.call(() -> scope.products(
                    delegate.create(chainId, config), sink -> new SinkFacade(sink, scope))));
        }
    }

    private record SinkFacade(
            FinalizedStreamSink delegate,
            PluginCallbackScope scope
    ) implements FinalizedStreamSink {
        private SinkFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("identify finalized-sink product",
                    () -> scope.call(delegate::id));
        }

        @Override
        public boolean deliver(AppBlock block) throws Exception {
            return scope.call(() -> delegate.deliver(block));
        }

        @Override
        public String legacyCursorKey() {
            return scope.call(delegate::legacyCursorKey);
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }
}
