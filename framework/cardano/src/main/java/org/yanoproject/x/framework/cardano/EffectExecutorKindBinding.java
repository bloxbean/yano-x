package org.yanoproject.x.framework.cardano;

import org.yanoproject.api.plugin.spi.PluginCallbackScope;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectExecutor;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecution;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutionContext;
import org.yanoproject.x.framework.api.appchain.effects.EffectExecutorOperationalSnapshot;
import org.yanoproject.x.framework.api.appchain.effects.PendingEffect;
import org.yanoproject.x.framework.cardano.api.effects.AppChainEffectContext;
import org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory;
import org.yanoproject.x.framework.host.AppLedgerKindBindings;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-side binding of the {@code effect-executor} contribution kind declared by
 * {@code META-INF/yano/contribution-kinds/org.yanoproject.x.framework.cardano.api.json}.
 */
public final class EffectExecutorKindBinding extends AppLedgerKindBindings.Binding<AppEffectExecutorFactory> {
    /** Owner of the descriptor that declares this kind, and its file name. */
    public static final String DESCRIPTOR_OWNER = "org.yanoproject.x.framework.cardano.api";

    public EffectExecutorKindBinding() {
        super(DESCRIPTOR_OWNER, "effect-executor", AppEffectExecutorFactory.class, AppEffectExecutorFactory::scheme,
                EffectExecutorFactoryFacade::new);
    }

    private record EffectExecutorFactoryFacade(
            AppEffectExecutorFactory delegate,
            PluginCallbackScope scope
    ) implements AppEffectExecutorFactory {
        private EffectExecutorFactoryFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String scheme() {
            return scope.call(delegate::scheme);
        }

        @Override
        public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
            return createProducts(() -> delegate.create(chainId, config));
        }

        @Override
        public List<AppEffectExecutor> create(
                String chainId, Map<String, String> config,
                AppChainEffectContext context) {
            // Context-aware creation (ADR-UTXO-009 SP-M6) must reach the
            // DELEGATE's override — routing through the 2-arg form would
            // silently trigger the interface default and lose the context.
            return createProducts(() -> delegate.create(chainId, config, context));
        }

        private List<AppEffectExecutor> createProducts(
                java.util.function.Supplier<List<AppEffectExecutor>> invocation) {
            return scope.activation("create effect-executor products", () -> scope.call(() -> scope.products(
                    invocation.get(), executor -> new EffectExecutorFacade(executor, scope))));
        }
    }

    private record EffectExecutorFacade(
            AppEffectExecutor delegate,
            PluginCallbackScope scope
    ) implements AppEffectExecutor {
        private EffectExecutorFacade {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String id() {
            return scope.activation("identify effect-executor product",
                    () -> scope.call(delegate::id));
        }

        @Override
        public boolean supports(String effectType) {
            return scope.call(() -> delegate.supports(effectType));
        }

        @Override
        public Set<String> effectTypes() {
            return scope.activation("declare effect-executor types",
                    () -> scope.call(() -> {
                        Set<String> declared = delegate.effectTypes();
                        return declared != null ? Set.copyOf(declared) : null;
                    }));
        }

        @Override
        public EffectExecutorOperationalSnapshot operationalSnapshot() {
            return scope.call(delegate::operationalSnapshot);
        }

        @Override
        public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect)
                throws Exception {
            return scope.call(() -> delegate.execute(ctx, effect));
        }

        @Override
        public void close() {
            scope.runCleanup(delegate::close);
        }
    }
}
