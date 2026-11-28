package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A node-local operator wallet that pays fees and collateral (ADR-059 D9, D11): an enterprise key address of its own
 * Ed25519 key. It must not be a member key, or a transaction would carry that key's witness twice and its witness
 * would authorize the member's own inputs.
 */
final class FeeWallet {
    private final byte[] seed;
    private final byte[] publicKey;
    private final byte[] address;

    FeeWallet(byte[] seed, int networkId, byte[] memberPublicKey, String setting) {
        this.seed = seed.clone();
        this.publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(this.seed);
        if (memberPublicKey != null && Arrays.equals(publicKey, memberPublicKey)) {
            throw new IllegalArgumentException(setting + " holds this node's member key; the fee wallet needs its own"
                    + " key");
        }
        this.address = new byte[29];
        address[0] = (byte) (0x60 | networkId);
        System.arraycopy(Blake2bUtil.blake2bHash224(publicKey), 0, address, 1, 28);
    }

    byte[] address() {
        return address.clone();
    }

    /**
     * The funding candidates among this wallet's outputs: lovelace only, no reference script, none that a pending
     * submission spends; the largest first, at most three.
     */
    List<ResolvedOutput> funding(List<ResolvedOutput> walletUtxos, Set<ResolvedOutput.Outpoint> held) {
        List<ResolvedOutput> utxos = new ArrayList<>(walletUtxos.stream()
                .filter(out -> out.assets().isEmpty() && out.referenceScript().isEmpty()
                        && !held.contains(out.outpoint()))
                .toList());
        utxos.sort((a, b) -> b.lovelace().compareTo(a.lovelace()));
        return utxos.subList(0, Math.min(3, utxos.size()));
    }

    /** {@code cbor} with the given member witnesses (public key hex to signature) and this wallet's own. */
    byte[] witness(byte[] cbor, Hash32 txId, Map<String, byte[]> memberSignatures) throws Exception {
        Transaction tx = Transaction.deserialize(cbor);
        List<VkeyWitness> witnesses = new ArrayList<>();
        memberSignatures.forEach((key, signature) ->
                witnesses.add(new VkeyWitness(HexFormat.of().parseHex(key), signature)));
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(txId.bytes(), seed);
        witnesses.add(new VkeyWitness(publicKey, signature));
        tx.getWitnessSet().setVkeyWitnesses(witnesses);
        return tx.serialize();
    }

    /** The outpoints a built transaction spends, as its body lists them. */
    static Set<ResolvedOutput.Outpoint> spends(Transaction tx) {
        Set<ResolvedOutput.Outpoint> spends = new java.util.HashSet<>();
        for (var input : tx.getBody().getInputs()) {
            spends.add(new ResolvedOutput.Outpoint(Hash32.ofHex(input.getTransactionId()), input.getIndex()));
        }
        return spends;
    }
}
