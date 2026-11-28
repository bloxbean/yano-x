package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/** Integrated root preserving legacy executor factories, including in standalone mode. */
public final class CardanoAppLedgerPlugin extends AppLedgerHostPlugin {
    private static final ObjectMapper COPIER = new ObjectMapper();

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
        context.getService(HostApi.SERVICE_KEY, HostApi.class)
                .ifPresent(host -> cardano.wireL1UtxoFilter(host.runtimeOptions().globals()));
        cardano.wireL1Chain(services.chain(), services.retainBodies().apply(OptionalLong.of(0)));
        cardano.wireTxEvaluation(services.txEvaluation());
        cardano.wireAnchorFees(() -> anchorFees(services, context));
        cardano.wireAnchorProtocolParams(pricing(services));
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
        return new DefaultL1TransactionBuilderService(services.utxoState(), pricing(services),
                () -> services.chain().getLocalTip() == null ? 0 : services.chain().getLocalTip().getSlot());
    }

    /** The host's pricing parameters, with the Conway reference-script fee price filled in from the snapshot. */
    private static Supplier<ProtocolParams> pricing(CardanoHostServices services) {
        return withReferenceScriptCost(services.pricingParameters(), () -> {
            var snapshot = services.protocolParameters().get();
            return snapshot == null ? null : snapshot.minFeeRefScriptCostPerByte();
        });
    }

    /**
     * The host's cardano-client-lib pricing parameters leave {@code minFeeRefScriptCostPerByte} unset, so a builder
     * that prices the Conway reference-script fee from them computes it as zero and the ledger rejects the transaction
     * as {@code FeeTooSmallUTxO} whenever it references a script (ADR-059 M4 devnet run: every gateway {@code Accept},
     * {@code Settle} and {@code Exit}). Fills the price from the host's protocol-parameter snapshot when the pricing
     * parameters lack it; a returned value is never mutated, and anything unavailable is left as it was.
     */
    static Supplier<ProtocolParams> withReferenceScriptCost(Supplier<ProtocolParams> pricing,
                                                            Supplier<BigDecimal> costPerByte) {
        return () -> {
            ProtocolParams params = pricing.get();
            if (params == null || params.getMinFeeRefScriptCostPerByte() != null) return params;
            BigDecimal cost;
            try {
                cost = costPerByte.get();
            } catch (RuntimeException unavailable) {
                return params;
            }
            if (cost == null) return params;
            ProtocolParams copy = COPIER.convertValue(params, ProtocolParams.class);
            copy.setMinFeeRefScriptCostPerByte(cost);
            return copy;
        };
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
