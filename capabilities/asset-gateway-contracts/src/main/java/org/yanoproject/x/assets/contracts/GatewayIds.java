package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Domain-separated identifiers (ADR-059 D4). Every identifier is
 * {@code blake2b-256(tag ‖ canonical CBOR of its fields)}, where each tag is a distinct ASCII
 * string ending in NUL. The order of derivation is acyclic: the domain, then the vault, then
 * deposit and claim ids. No identifier depends on a script hash or on finality evidence.
 */
public final class GatewayIds {
    public static final String DOMAIN_TAG = "yano-asset-gateway-domain-v1\0";
    public static final String VAULT_TAG = "yano-asset-gateway-vault-v1\0";
    public static final String DEPOSIT_TAG = "yano-asset-gateway-deposit-v1\0";
    public static final String CLAIM_TAG = "yano-asset-gateway-claim-v1\0";
    public static final String INTENT_TAG = "yano-asset-gateway-intent-v1\0";
    public static final String ESCAPE_TAG = "escape\0";

    public static final int MAX_TEXT_BYTES = 64;
    public static final int MAX_WITHDRAWAL_KEY_BYTES = 64;
    public static final int MAX_ADDRESS_BYTES = 128;
    public static final int MAX_HOLDER_ID_BYTES = 64;

    private GatewayIds() {
    }

    /**
     * The gateway domain: Cardano address network id, network magic, app ledger chain id,
     * genesis id, application id, profile id and profile version.
     */
    public static Hash32 domainId(int addressNetworkId, long networkMagic, String chainId,
                                  Hash32 genesisId, String applicationId, String profileId,
                                  long profileVersion) {
        if (addressNetworkId < 0 || addressNetworkId > 15) {
            throw new IllegalArgumentException("address network id is a 4-bit value");
        }
        Array fields = new Array();
        fields.add(AssetCbor.uint(addressNetworkId));
        fields.add(AssetCbor.uint(networkMagic));
        fields.add(text(chainId, "chain id"));
        fields.add(AssetCbor.bstr(genesisId.bytes()));
        fields.add(text(applicationId, "application id"));
        fields.add(text(profileId, "profile id"));
        fields.add(AssetCbor.uint(profileVersion));
        return digest(DOMAIN_TAG, fields);
    }

    /** One vault per asset in v1 (I29); the ordinal is reserved for later profiles and is 0. */
    public static Hash32 vaultId(Hash32 domainId, AssetId asset, long vaultOrdinal) {
        Array fields = new Array();
        fields.add(AssetCbor.bstr(domainId.bytes()));
        fields.add(asset.toItem());
        fields.add(AssetCbor.uint(vaultOrdinal));
        return digest(VAULT_TAG, fields);
    }

    /** The permanent exactly-once key of a deposit: its vault and accepted outpoint. */
    public static Hash32 depositId(Hash32 vaultId, Hash32 txId, long outputIndex) {
        Array fields = new Array();
        fields.add(AssetCbor.bstr(vaultId.bytes()));
        fields.add(AssetCbor.bstr(txId.bytes()));
        fields.add(AssetCbor.uint(outputIndex));
        return digest(DEPOSIT_TAG, fields);
    }

    /** Fixed at reservation from finalized state only (D7). */
    public static Hash32 claimId(Hash32 domainId, Hash32 vaultId, ClaimKind kind,
                                 byte[] withdrawalKey, byte[] recipientAddress, AssetValue value,
                                 BigInteger vaultLovelace, long sequence) {
        requireLength(withdrawalKey, 1, MAX_WITHDRAWAL_KEY_BYTES, "withdrawal key");
        requireLength(recipientAddress, 1, MAX_ADDRESS_BYTES, "recipient address");
        Array fields = new Array();
        fields.add(AssetCbor.bstr(domainId.bytes()));
        fields.add(AssetCbor.bstr(vaultId.bytes()));
        fields.add(AssetCbor.uint(kind.code()));
        fields.add(AssetCbor.bstr(withdrawalKey));
        fields.add(AssetCbor.bstr(recipientAddress));
        fields.add(value.toItem());
        fields.add(AssetCbor.uint(Quantities.requireU64(vaultLovelace, "vault lovelace")));
        fields.add(AssetCbor.uint(sequence));
        return digest(CLAIM_TAG, fields);
    }

    /** The identity of a signed intent: the hash of the exact signed envelope bytes. */
    public static Hash32 intentId(byte[] signedEnvelopeBytes) {
        requireLength(signedEnvelopeBytes, 1, AssetCbor.MAX_WIRE_BYTES, "envelope");
        byte[] tag = INTENT_TAG.getBytes(StandardCharsets.US_ASCII);
        byte[] preimage = new byte[tag.length + signedEnvelopeBytes.length];
        System.arraycopy(tag, 0, preimage, 0, tag.length);
        System.arraycopy(signedEnvelopeBytes, 0, preimage, tag.length, signedEnvelopeBytes.length);
        return Hash32.of(Blake2bUtil.blake2bHash256(preimage));
    }

    /** The escape nullifier of one exit leaf (D8g §4). */
    public static Hash32 escapeNullifier(Hash32 vaultId, byte[] holderId) {
        requireLength(holderId, 1, MAX_HOLDER_ID_BYTES, "holder id");
        Array fields = new Array();
        fields.add(AssetCbor.bstr(vaultId.bytes()));
        fields.add(AssetCbor.bstr(holderId));
        return digest(ESCAPE_TAG, fields);
    }

    static Hash32 digest(String tag, Array fields) {
        byte[] tagBytes = tag.getBytes(StandardCharsets.US_ASCII);
        byte[] body = AssetCbor.encode(fields);
        byte[] preimage = new byte[tagBytes.length + body.length];
        System.arraycopy(tagBytes, 0, preimage, 0, tagBytes.length);
        System.arraycopy(body, 0, preimage, tagBytes.length, body.length);
        return Hash32.of(Blake2bUtil.blake2bHash256(preimage));
    }

    private static UnicodeString text(String value, String name) {
        if (value == null || value.isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(name + " must be 1-64 bytes of UTF-8");
        }
        return new UnicodeString(value);
    }

    private static void requireLength(byte[] value, int min, int max, String name) {
        if (value == null || value.length < min || value.length > max) {
            throw new IllegalArgumentException(name + " must be " + min + "-" + max + " bytes");
        }
    }
}
