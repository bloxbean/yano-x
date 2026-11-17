package org.yanoproject.x.framework.cardano;

import org.yanoproject.api.CardanoHostServices;
import org.yanoproject.api.config.RuntimeMode;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.plugin.domain.L1TransactionBuilderService;
import org.yanoproject.x.framework.cardano.AppChainSubsystem.AnchorFeeParams;
import org.yanoproject.x.framework.host.AppLedgerHostPlugin;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerManager;
import org.yanoproject.x.framework.runtime.appchain.AppLedgerRuntime;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Integrated root preserving legacy executor factories, including in standalone mode. */
public final class CardanoAppLedgerPlugin extends AppLedgerHostPlugin {
    @Override protected void validateDeployment(YanoConfig node) { }

    @Override
    protected AppLedgerRuntime createChain(AppChainConfig chain, long magic, String storage,
            YanoConfig node, HostApi host, PluginContext context) {
        return new AppChainSubsystem(chain, magic, context.eventBus(), null, storage,
                context.pluginClassLoader().orElse(null), host.providers(), context.logger());
    }

    @Override
    protected void wireChain(AppLedgerRuntime chain, YanoConfig node, PluginContext context) {
        if (node.getMode() == RuntimeMode.APP_LEDGER) return;
        CardanoHostServices services = cardano(context);
        AppChainSubsystem cardano = (AppChainSubsystem) chain;
        cardano.wireL1(services.txSubmitter(), services.utxoState());
        cardano.wireL1Chain(services.chain(), services.retainBodies().apply(OptionalLong.of(0)));
        cardano.wireTxEvaluation(services.txEvaluation());
        cardano.wireAnchorFees(() -> anchorFees(services, context));
        cardano.wireAnchorProtocolParams(services.pricingParameters());
    }

    @Override
    protected AppLedgerManager createManager(List<AppLedgerRuntime> chains, YanoConfig node,
            PluginContext context) {
        List<AppChainSubsystem> cardanoChains = chains.stream().map(AppChainSubsystem.class::cast).toList();
        if (node.getMode() == RuntimeMode.APP_LEDGER) {
            return new AppChainManager(cardanoChains, context.logger(), Optional::empty);
        }
        CardanoHostServices services = cardano(context);
        return new AppChainManager(cardanoChains, context.logger(),
                () -> services.epochSnapshots().get().map(CardanoEpochStateProvider::new));
    }

    @Override
    protected L1TransactionBuilderService l1Transactions(YanoConfig node, PluginContext context) {
        if (node.getMode() == RuntimeMode.APP_LEDGER) return L1TransactionBuilderService.unavailable();
        CardanoHostServices services = cardano(context);
        return new DefaultL1TransactionBuilderService(services.utxoState(), services.pricingParameters(),
                () -> services.chain().getLocalTip() == null ? 0 : services.chain().getLocalTip().getSlot());
    }

    private static CardanoHostServices cardano(PluginContext context) {
        return requireService(context, CardanoHostServices.SERVICE_KEY, CardanoHostServices.class);
    }

    private static AnchorFeeParams anchorFees(CardanoHostServices services, PluginContext context) {
        try {
            var params = services.protocolParameters().get();
            if (params != null && params.minFeeA() != null && params.minFeeB() != null) {
                long[] costModelV3 = null;
                if (params.costModelsRaw() != null && params.costModelsRaw().get("PlutusV3") != null) {
                    costModelV3 = params.costModelsRaw().get("PlutusV3").stream()
                            .mapToLong(Long::longValue).toArray();
                }
                return new AnchorFeeParams(params.minFeeA(), params.minFeeB(),
                        params.priceMem(), params.priceStep(), costModelV3);
            }
        } catch (Exception failure) {
            context.logger().debug("Anchor fee params unavailable: {}", failure.toString());
        }
        return null;
    }
}
