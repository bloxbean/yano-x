package org.yanoproject.x.assets.cardano.support.settlement;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The vault and shard datums and redeemers of {@code asset-gateway-onchain-v1.cddl}, as cardano-client-lib Plutus
 * Data, and their decoding for the co-sign body check. Every claim field comes from a committed {@link ClaimLeafV1};
 * nothing here invents one.
 */
public final class GatewayPlutus {
    public static final int SETTLE = 0;
    public static final int EXIT = 1;
    public static final int ACCEPT_COSIGNED = 3;
    public static final int ACCEPT_PROVED = 4;
    public static final int FOLLOW = 9;

    private GatewayPlutus() {
    }

    /** {@code claim = Constr 0 [claim_id, kind, quantity, vault_lovelace, recipient, reserved_height, reserved_at_ms]}. */
    public static PlutusData claim(ClaimLeafV1 leaf, AssetId vaultAsset) {
        return ConstrPlutusData.of(0, BytesPlutusData.of(leaf.claimId().bytes()), BigIntPlutusData.of(leaf.kind().code()),
                BigIntPlutusData.of(leaf.value().quantityOf(vaultAsset)), BigIntPlutusData.of(leaf.vaultLovelace()),
                BytesPlutusData.of(leaf.recipient()), BigIntPlutusData.of(leaf.reservedHeight()),
                BigIntPlutusData.of(leaf.reservedAtMs()));
    }

    /** {@code Settle(claims, shard_index)}: constructor 0. */
    public static PlutusData settle(List<ClaimLeafV1> claims, AssetId vaultAsset, int shardIndex) {
        List<PlutusData> items = new ArrayList<>();
        for (ClaimLeafV1 leaf : claims) items.add(claim(leaf, vaultAsset));
        return ConstrPlutusData.of(SETTLE, list(items), BigIntPlutusData.of(shardIndex));
    }

    /** {@code Exit([Constr 0 [claim, proof]], status_proof, shard_index)}: constructor 1. */
    public static PlutusData exit(List<ClaimLeafV1> claims, List<StateProof> proofs, StateProof statusProof,
                                  AssetId vaultAsset, int shardIndex) {
        if (claims.size() != proofs.size()) throw new IllegalArgumentException("one proof per claim");
        List<PlutusData> items = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            items.add(ConstrPlutusData.of(0, claim(claims.get(i), vaultAsset), proofs.get(i).toPlutusData()));
        }
        return ConstrPlutusData.of(EXIT, list(items), statusProof.toPlutusData(), BigIntPlutusData.of(shardIndex));
    }

    /** {@code AcceptCosigned}: constructor 3, no fields (D8f; the members' threshold signs, V9). */
    public static PlutusData acceptCosigned() {
        return ConstrPlutusData.of(ACCEPT_COSIGNED);
    }

    /**
     * {@code AcceptProved(proofs)}: constructor 4 with one inclusion proof per deposit input, in the ledger order of
     * the vault inputs; proof {@code i} shows {@code deposit/<DepositId of vault input i> = CREDITED} (D8f).
     */
    public static PlutusData acceptProved(List<StateProof> proofs) {
        List<PlutusData> items = new ArrayList<>();
        for (StateProof proof : proofs) items.add(proof.toPlutusData());
        return ConstrPlutusData.of(ACCEPT_PROVED, list(items));
    }

    /** True for exactly {@code AcceptCosigned} ({@code Constr 3 []}). */
    public static boolean isAcceptCosigned(PlutusData data) {
        return data instanceof ConstrPlutusData constr && constr.getAlternative() == ACCEPT_COSIGNED
                && constr.getData().getPlutusDataList().isEmpty();
    }

    /** The marker a non-lead vault input carries (V13). */
    public static PlutusData follow() {
        return ConstrPlutusData.of(FOLLOW);
    }

    /** {@code VaultContinuationV1(ids)}: constructor 1 with the ordered id list. */
    public static PlutusData continuation(List<Hash32> ids) {
        List<PlutusData> items = new ArrayList<>();
        for (Hash32 id : ids) items.add(BytesPlutusData.of(id.bytes()));
        return ConstrPlutusData.of(1, list(items));
    }

    /** {@code ShardDatumV1(1, shard_index, nullifier_root)}. */
    public static PlutusData shardDatum(int shardIndex, byte[] root) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(1), BigIntPlutusData.of(shardIndex), BytesPlutusData.of(root));
    }

    /** {@code shard_redeemer = Constr 0 [[Constr 0 [id, proof_steps]]]}, ids in payment order. */
    public static PlutusData shardRedeemer(List<ShardMirror.Insert> inserts) {
        List<PlutusData> items = new ArrayList<>();
        for (ShardMirror.Insert insert : inserts) {
            items.add(ConstrPlutusData.of(0, BytesPlutusData.of(insert.id().bytes()), insert.proof()));
        }
        return ConstrPlutusData.of(0, list(items));
    }

    /** The exit-mode thread's {@code NORMAL} datum. */
    public static PlutusData normalMode() {
        return ConstrPlutusData.of(0);
    }

    static ListPlutusData list(List<PlutusData> items) {
        return ListPlutusData.builder().plutusDataList(new ArrayList<>(items)).build();
    }

    // ------------------------------------------------------------------ decoding (co-sign body check)

    /** The fields of one decoded redeemer claim. */
    public record RedeemerClaim(Hash32 claimId, long kind, BigInteger quantity, BigInteger vaultLovelace,
                                byte[] recipient, long reservedHeight, long reservedAtMs) {
    }

    /** A decoded {@code Settle}: its claims and shard index. */
    public record DecodedSettle(List<RedeemerClaim> claims, int shardIndex) {
    }

    /** Decodes a {@code Settle} redeemer exactly (constructor 0, two fields), or throws. */
    public static DecodedSettle decodeSettle(PlutusData data) {
        ConstrPlutusData constr = constr(data, SETTLE, 2);
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        List<RedeemerClaim> claims = new ArrayList<>();
        for (PlutusData item : listOf(fields.get(0))) {
            List<PlutusData> f = constr(item, 0, 7).getData().getPlutusDataList();
            byte[] id = bytes(f.get(0));
            if (id.length != Hash32.BYTES) throw new IllegalArgumentException("claim id is 32 bytes");
            claims.add(new RedeemerClaim(Hash32.of(id), integer(f.get(1)).longValueExact(), integer(f.get(2)),
                    integer(f.get(3)), bytes(f.get(4)), integer(f.get(5)).longValueExact(),
                    integer(f.get(6)).longValueExact()));
        }
        return new DecodedSettle(claims, integer(fields.get(1)).intValueExact());
    }

    /** The ordered ids of a shard redeemer, or throws. */
    public static List<Hash32> decodeShardInserts(PlutusData data) {
        List<PlutusData> fields = constr(data, 0, 1).getData().getPlutusDataList();
        List<Hash32> ids = new ArrayList<>();
        for (PlutusData insert : listOf(fields.getFirst())) {
            byte[] id = bytes(constr(insert, 0, 2).getData().getPlutusDataList().getFirst());
            if (id.length != Hash32.BYTES) throw new IllegalArgumentException("insert id is 32 bytes");
            ids.add(Hash32.of(id));
        }
        return ids;
    }

    /** The ids of a {@code VaultContinuationV1} datum, or throws. */
    public static List<Hash32> decodeContinuation(PlutusData data) {
        List<Hash32> ids = new ArrayList<>();
        for (PlutusData item : listOf(constr(data, 1, 1).getData().getPlutusDataList().getFirst())) {
            byte[] id = bytes(item);
            if (id.length != Hash32.BYTES) throw new IllegalArgumentException("continuation id is 32 bytes");
            ids.add(Hash32.of(id));
        }
        return ids;
    }

    public static boolean isFollow(PlutusData data) {
        return data instanceof ConstrPlutusData constr && constr.getAlternative() == FOLLOW
                && constr.getData().getPlutusDataList().isEmpty();
    }

    public static boolean isNormalMode(PlutusData data) {
        return data instanceof ConstrPlutusData constr && constr.getAlternative() == 0
                && constr.getData().getPlutusDataList().isEmpty();
    }

    static ConstrPlutusData constr(PlutusData data, int alternative, int arity) {
        if (!(data instanceof ConstrPlutusData constr) || constr.getAlternative() != alternative
                || constr.getData() == null || constr.getData().getPlutusDataList().size() != arity) {
            throw new IllegalArgumentException("expected constructor " + alternative + " with " + arity + " fields");
        }
        return constr;
    }

    static List<PlutusData> listOf(PlutusData data) {
        if (!(data instanceof ListPlutusData list)) throw new IllegalArgumentException("expected a list");
        return list.getPlutusDataList();
    }

    static byte[] bytes(PlutusData data) {
        if (!(data instanceof BytesPlutusData bytes)) throw new IllegalArgumentException("expected bytes");
        return bytes.getValue();
    }

    static BigInteger integer(PlutusData data) {
        if (!(data instanceof BigIntPlutusData value)) throw new IllegalArgumentException("expected an integer");
        return Objects.requireNonNull(value.getValue());
    }
}
