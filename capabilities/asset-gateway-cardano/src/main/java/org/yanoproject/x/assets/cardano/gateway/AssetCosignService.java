package org.yanoproject.x.assets.cardano.gateway;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.assets.cardano.support.settlement.SettleBodyCheck;
import org.yanoproject.x.framework.api.appchain.l1view.BridgeDiffusionHandler;
import org.yanoproject.x.framework.api.appchain.signer.SignerProvider;

import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The co-sign round of a {@code Settle} (ADR-059 D8h §1, D9), the pattern of the EUTxO {@code SettlementCosignService}
 * carried on its own {@code ~bridge/asset-settlement/*} channel:
 * <ul>
 *   <li>the owner broadcasts the <b>full unsigned transaction</b> (body plus redeemers, D8h §1 step 0) on
 *       {@link #TOPIC_SIGN}, after running the same body check on it and signing it itself;</li>
 *   <li>every member runs {@link SettleBodyCheck} against its own L1 view and committed state and, only when the body
 *       is signable, replies on {@link #TOPIC_SIG} with an Ed25519 signature over the body hash;</li>
 *   <li>the owner keeps a reply only from a requested signer whose signature verifies over the round's body hash, so a
 *       forged reply cannot occupy a member's slot.</li>
 * </ul>
 * A refusal is recorded (kind and detail) for status, as the script-anchor service does. Signatures are body-hash
 * specific; any rebuild starts a new round. The round blocks the caller for at most the configured timeout and then
 * hands over what arrived; the executor fails closed when a required signer is missing.
 */
final class AssetCosignService implements BridgeDiffusionHandler {
    static final String TOPIC_SIGN = "~bridge/asset-settlement/sign";
    static final String TOPIC_SIG = "~bridge/asset-settlement/sig";
    private static final int WIRE_VERSION = 1;
    private static final int MAX_TX_BYTES = 65_536;

    private final BiConsumer<String, byte[]> diffuser;
    private final SignerProvider memberSigner;
    private final Function<byte[], SettleBodyCheck.Outcome> bodyCheck;
    private final Duration roundTimeout;
    private final SigningProvider signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider();
    private final Map<String, Round> rounds = new ConcurrentHashMap<>();
    private volatile SettleBodyCheck.Refusal lastRefusal;

    AssetCosignService(BiConsumer<String, byte[]> diffuser, SignerProvider memberSigner,
                       Function<byte[], SettleBodyCheck.Outcome> bodyCheck, Duration roundTimeout) {
        this.diffuser = Objects.requireNonNull(diffuser, "diffuser");
        this.memberSigner = Objects.requireNonNull(memberSigner, "member signer");
        this.bodyCheck = Objects.requireNonNull(bodyCheck, "body check");
        this.roundTimeout = Objects.requireNonNull(roundTimeout, "round timeout");
    }

    Optional<SettleBodyCheck.Refusal> lastRefusal() {
        return Optional.ofNullable(lastRefusal);
    }

    /**
     * Owner side: one round over {@code fullUnsignedTx}. Returns the verified signatures by signer public key (lower-case
     * hex), the owner's own included, or empty when the owner's own body check refuses.
     *
     * @param requested the public keys (lower-case hex) of the required signers
     */
    Optional<Map<String, byte[]>> collect(byte[] fullUnsignedTx, Set<String> requested) throws InterruptedException {
        SettleBodyCheck.Outcome own = bodyCheck.apply(fullUnsignedTx);
        if (!(own instanceof SettleBodyCheck.Signable signable)) {
            record(((SettleBodyCheck.Refused) own).refusal());
            return Optional.empty();
        }
        byte[] bodyHash = signable.verified().bodyHash();
        String key = HexFormat.of().formatHex(bodyHash);
        Round round = new Round(bodyHash, Set.copyOf(requested));
        rounds.put(key, round);
        try {
            String self = memberSigner.publicKeyHex().toLowerCase(Locale.ROOT);
            if (requested.contains(self)) round.accept(self, memberSigner.sign(bodyHash));
            diffuser.accept(TOPIC_SIGN, encodeRequest(fullUnsignedTx));
            try {
                return Optional.of(round.done.get(roundTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                return Optional.of(Map.copyOf(round.signatures));
            } catch (java.util.concurrent.ExecutionException impossible) {
                return Optional.of(Map.copyOf(round.signatures));
            }
        } finally {
            rounds.remove(key);
        }
    }

    @Override
    public void onBridgeMessage(AppMessage message) {
        if (message == null || message.getTopic() == null) return;
        if (TOPIC_SIGN.equals(message.getTopic())) {
            onSignRequest(message);
        } else if (TOPIC_SIG.equals(message.getTopic())) {
            onSignature(message);
        }
    }

    /** Member side: sign only a body this member positively verified against its own state. */
    private void onSignRequest(AppMessage message) {
        byte[] tx = decodeRequest(message.getBody());
        if (tx == null) return;
        SettleBodyCheck.Outcome outcome;
        try {
            outcome = bodyCheck.apply(tx);
        } catch (RuntimeException failure) {
            outcome = new SettleBodyCheck.Refused(new SettleBodyCheck.Refusal(SettleBodyCheck.Refusal.Kind.HAZARD,
                    "the body check failed"));
        }
        if (outcome instanceof SettleBodyCheck.Refused refused) {
            record(refused.refusal());
            return;
        }
        byte[] bodyHash = ((SettleBodyCheck.Signable) outcome).verified().bodyHash();
        lastRefusal = null;
        diffuser.accept(TOPIC_SIG, encodeSignature(bodyHash, memberSigner.sign(bodyHash)));
    }

    /** Owner side: a reply joins its round only from a requested signer, with a signature that verifies. */
    private void onSignature(AppMessage message) {
        if (message.getSender() == null) return;
        byte[][] reply = decodeSignature(message.getBody());
        if (reply == null) return;
        Round round = rounds.get(HexFormat.of().formatHex(reply[0]));
        if (round == null) return;
        String sender = HexFormat.of().formatHex(message.getSender());
        if (!round.requested.contains(sender)) return;
        try {
            if (!signingProvider.verify(reply[1], round.bodyHash, message.getSender())) return;
        } catch (Exception invalid) {
            return;
        }
        round.accept(sender, reply[1]);
    }

    private void record(SettleBodyCheck.Refusal refusal) {
        // Kept for the executor's operational snapshot (the body itself is never logged).
        lastRefusal = refusal;
    }

    static byte[] encodeRequest(byte[] tx) {
        Array array = new Array();
        array.add(new UnsignedInteger(WIRE_VERSION));
        array.add(new ByteString(tx));
        return serialize(array);
    }

    static byte[] decodeRequest(byte[] body) {
        try {
            if (body == null || body.length > MAX_TX_BYTES + 16) return null;
            List<DataItem> items = ((Array) CborSerializationUtil.deserialize(body)).getDataItems();
            if (items.size() != 2 || ((UnsignedInteger) items.get(0)).getValue().intValueExact() != WIRE_VERSION) {
                return null;
            }
            byte[] tx = ((ByteString) items.get(1)).getBytes();
            return tx.length == 0 || tx.length > MAX_TX_BYTES ? null : tx;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static byte[] encodeSignature(byte[] bodyHash, byte[] signature) {
        Array array = new Array();
        array.add(new UnsignedInteger(WIRE_VERSION));
        array.add(new ByteString(bodyHash));
        array.add(new ByteString(signature));
        return serialize(array);
    }

    static byte[][] decodeSignature(byte[] body) {
        try {
            if (body == null || body.length > 256) return null;
            List<DataItem> items = ((Array) CborSerializationUtil.deserialize(body)).getDataItems();
            if (items.size() != 3 || ((UnsignedInteger) items.get(0)).getValue().intValueExact() != WIRE_VERSION) {
                return null;
            }
            byte[] hash = ((ByteString) items.get(1)).getBytes();
            byte[] signature = ((ByteString) items.get(2)).getBytes();
            return hash.length == 32 && signature.length == 64 ? new byte[][] {hash, signature} : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static byte[] serialize(DataItem item) {
        try {
            return CborSerializationUtil.serialize(item);
        } catch (Exception failure) {
            throw new IllegalStateException("co-sign wire encoding", failure);
        }
    }

    private static final class Round {
        private final byte[] bodyHash;
        private final Set<String> requested;
        private final Map<String, byte[]> signatures = new ConcurrentHashMap<>();
        private final CompletableFuture<Map<String, byte[]>> done = new CompletableFuture<>();

        private Round(byte[] bodyHash, Set<String> requested) {
            this.bodyHash = bodyHash.clone();
            this.requested = requested;
        }

        private void accept(String signer, byte[] signature) {
            signatures.putIfAbsent(signer, signature.clone());
            if (signatures.keySet().containsAll(requested)) done.complete(Map.copyOf(signatures));
        }
    }
}
