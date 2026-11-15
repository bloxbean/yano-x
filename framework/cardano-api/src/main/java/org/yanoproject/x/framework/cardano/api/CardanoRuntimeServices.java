package org.yanoproject.x.framework.cardano.api;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.model.ProtocolParamsSnapshot;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.chain.BlockBodyRetentionRegistry;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Existing Cardano host capabilities used by the optional ledger adapter.
 * Parameter suppliers stay live and distinct: linear fees use tracked params;
 * script pricing retains the host's static-file fallback. A newly registered
 * retention requirement is owned by its chain until permanent close.
 */
public record CardanoRuntimeServices(
        Function<byte[], String> txSubmitter,
        Supplier<UtxoState> utxoState,
        ChainBlockReader chain,
        Function<OptionalLong, BlockBodyRetentionRegistry.Registration> retainBodies,
        TxEvaluationGateway txEvaluation,
        Supplier<ProtocolParamsSnapshot> protocolParameters,
        Supplier<ProtocolParams> pricingParameters) {
    public static final String SERVICE_KEY = "org.yanoproject.app-ledger.cardano-services";

    public CardanoRuntimeServices {
        Objects.requireNonNull(txSubmitter, "txSubmitter");
        Objects.requireNonNull(utxoState, "utxoState");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(retainBodies, "retainBodies");
        Objects.requireNonNull(txEvaluation, "txEvaluation");
        Objects.requireNonNull(protocolParameters, "protocolParameters");
        Objects.requireNonNull(pricingParameters, "pricingParameters");
    }
}
