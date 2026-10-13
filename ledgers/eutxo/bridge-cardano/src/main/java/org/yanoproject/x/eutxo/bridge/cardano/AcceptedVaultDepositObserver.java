package org.yanoproject.x.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.l1view.L1Observation;
import org.yanoproject.api.appchain.l1view.L1Observer;
import org.yanoproject.api.appchain.l1view.L1ObserverConsensusIdentity;
import org.yanoproject.x.eutxo.contracts.EutxoDepositClaim;
import org.yanoproject.x.eutxo.contracts.EutxoOutpoint;
import org.yanoproject.x.eutxo.contracts.EutxoVaultDatum;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Observes only vault outputs carrying the accepted-deposit datum. Staging
 * outputs are intentionally outside this observer's address and can never be
 * turned into app-chain credits.
 */
final class AcceptedVaultDepositObserver implements L1Observer {
    private static final String LOVELACE = "lovelace";

    private final String observerId;
    private final String chainId;
    private final String vaultAddress;
    private final String vaultScriptHash;
    private final BigInteger maxLovelace;

    AcceptedVaultDepositObserver(String observerId, Map<String, String> settings) {
        this.observerId = required(observerId, "observer id");
        this.chainId = required(settings.get("chain-id"), "chain-id");
        this.vaultAddress = required(settings.get("vault-address"), "vault-address");
        this.vaultScriptHash = canonicalHash(
                settings.get("vault-script-hash"), "vault-script-hash");
        this.maxLovelace = positive(
                settings.getOrDefault("max-lovelace", "45000000000000000"),
                "max-lovelace");
        Address address = new Address(vaultAddress);
        if (!address.isScriptHashInPaymentPart()
                || address.getPaymentCredentialHash().isEmpty()
                || !vaultScriptHash.equals(HexFormat.of().formatHex(
                address.getPaymentCredentialHash().orElseThrow()))) {
            throw new IllegalArgumentException(
                    "vault-address payment credential must match vault-script-hash");
        }
    }

    @Override
    public String observerId() {
        return observerId;
    }

    L1ObserverConsensusIdentity consensusIdentity() {
        return ObserverConsensusIdentity.of("eutxo-deposit-claim-v1", EutxoDepositClaim.ABI_VERSION,
                "chain-id", chainId,
                "vault-address", vaultAddress,
                "vault-script-hash", vaultScriptHash,
                "max-lovelace", maxLovelace.toString());
    }

    @Override
    public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
        if (block == null || block.getTransactionBodies() == null) {
            return List.of();
        }
        List<L1Observation> observations = new ArrayList<>();
        // A phase-2-invalid transaction created no outputs and spent no inputs; only valid ones are read.
        for (TransactionBody transaction : ValidTransactions.of(block)) {
            EutxoDepositClaim claim;
            try {
                claim = transaction == null ? null : claim(slot, blockHash, transaction);
            } catch (RuntimeException notCreditable) {
                continue; // whatever the shape, a transaction that cannot be read is not a deposit
            }
            if (claim != null) {
                observations.add(L1Observation.transaction(
                        observerId,
                        HexUtil.decodeHexString(transaction.getTxHash()),
                        slot,
                        blockHash,
                        claim.encode()));
            }
        }
        return List.copyOf(observations);
    }

    /**
     * The one creditable deposit this transaction creates, or null. Anyone can pay the public vault address with
     * any value and datum, so a vault output that is not a creditable deposit for this chain is skipped, never an
     * error: a throw here makes the host retry the block forever, stopping all L1 observation for the chain.
     */
    private EutxoDepositClaim claim(
            long slot,
            byte[] blockHash,
            TransactionBody transaction
    ) {
        if (transaction.getOutputs() == null) {
            return null;
        }
        EutxoDepositClaim found = null;
        for (int index = 0; index < transaction.getOutputs().size(); index++) {
            TransactionOutput output = transaction.getOutputs().get(index);
            if (!vaultAddress.equals(output.getAddress())) {
                continue;
            }
            EutxoDepositClaim candidate = creditableDeposit(slot, blockHash, transaction, index, output);
            if (candidate == null) {
                continue;
            }
            if (found != null) {
                // A genuine acceptance creates exactly one vault output; credit neither of an ambiguous pair.
                return null;
            }
            found = candidate;
        }
        return found;
    }

    /**
     * The claim for one vault output, or null when it is not a creditable deposit: no inline datum, a datum other
     * than an accepted-deposit datum (including the settlement outputs the withdrawal observers own), native
     * assets, an amount outside the bound, another chain, or any value that cannot be canonically encoded. Whether
     * an L2 key binding applies is the ledger's decision; it never stops the deposit itself.
     */
    private EutxoDepositClaim creditableDeposit(
            long slot,
            byte[] blockHash,
            TransactionBody transaction,
            int index,
            TransactionOutput output
    ) {
        try {
            if (output.getInlineDatum() == null) {
                return null;
            }
            byte[] datumCbor = HexFormat.of().parseHex(output.getInlineDatum());
            if (!BridgeDatumPreflight.bounded(datumCbor)) {
                return null;
            }
            EutxoVaultDatum datum = EutxoVaultDatum.decode(datumCbor);
            BigInteger lovelace = exactLovelace(output);
            if (lovelace == null || lovelace.signum() <= 0 || lovelace.compareTo(maxLovelace) > 0
                    || !chainId.equals(datum.chainId())) {
                return null;
            }
            com.bloxbean.cardano.client.transaction.spec.TransactionOutput accepted =
                    com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                            .address(vaultAddress)
                            .value(Value.fromCoin(lovelace))
                            .inlineDatum(PlutusData.deserialize(datumCbor))
                            .build();
            com.bloxbean.cardano.client.transaction.spec.TransactionOutput mirrored =
                    com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                            .address(datum.l2Address())
                            .value(Value.fromCoin(lovelace))
                            .build();
            byte[] mirroredCbor = CborSerializationUtil.serialize(mirrored.serialize());
            // The ledger re-reads the mirrored output and requires the same address back.
            if (!datum.l2Address().equals(com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                    .deserialize(CborSerializationUtil.deserialize(mirroredCbor)).getAddress())) {
                return null;
            }
            return new EutxoDepositClaim(
                    EutxoDepositClaim.ABI_VERSION,
                    chainId,
                    new EutxoOutpoint(transaction.getTxHash(), index),
                    slot,
                    blockHash,
                    vaultAddress,
                    vaultScriptHash,
                    CborSerializationUtil.serialize(accepted.serialize()),
                    datum.l2Address(),
                    mirroredCbor,
                    datum.depositNonce(),
                    datum.stagingOutpoint(),
                    datum.refundDeadline(),
                    datum.depositorKeyHash(),
                    datum.l2KeyBinding(),
                    depositorSigned(transaction, datum.depositorKeyHash()));
        } catch (Exception notCreditable) {
            return null;
        }
    }

    /**
     * Whether the depositor's key hash is a required signer of the accepting transaction. The Cardano ledger admits
     * a transaction only when every required signer signed it, so this is the depositor's own approval: the vault
     * datum's key hash and L2 address are chosen by whoever creates the output, and anyone can accept a staged
     * deposit. The ledger applies a deposit's L2 key binding only when this holds.
     */
    private static boolean depositorSigned(TransactionBody transaction, byte[] depositorKeyHash) {
        if (transaction.getRequiredSigners() == null) {
            return false;
        }
        String depositor = HexFormat.of().formatHex(depositorKeyHash);
        return transaction.getRequiredSigners().stream()
                .anyMatch(signer -> signer != null && depositor.equalsIgnoreCase(signer.trim()));
    }

    /** The output's lovelace, or null when it also carries a native asset. */
    private static BigInteger exactLovelace(TransactionOutput output) {
        BigInteger lovelace = BigInteger.ZERO;
        if (output.getAmounts() == null) {
            return lovelace;
        }
        for (Amount amount : output.getAmounts()) {
            BigInteger quantity = amount.getQuantity() == null
                    ? BigInteger.ZERO : amount.getQuantity();
            if (LOVELACE.equals(amount.getUnit())) {
                lovelace = lovelace.add(quantity);
            } else if (quantity.signum() != 0) {
                return null; // the initial EUTxO bridge credits lovelace only
            }
        }
        return lovelace;
    }

    @Override
    public Map<String, Object> status() {
        return Map.of(
                "type", CardanoBridgeObserverProvider.TYPE,
                "chainId", chainId,
                "vaultAddress", vaultAddress,
                "vaultScriptHash", vaultScriptHash,
                "assetProfile", LOVELACE);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String canonicalHash(String value, String field) {
        String hash = required(value, field);
        if (hash.length() != 56
                || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be 28-byte lowercase hex");
        }
        try {
            HexFormat.of().parseHex(hash);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    field + " must be 28-byte lowercase hex", failure);
        }
        return hash;
    }

    private static BigInteger positive(String value, String field) {
        try {
            BigInteger parsed = new BigInteger(required(value, field));
            if (parsed.signum() <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }
}
