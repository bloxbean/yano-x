package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Readers of the on-chain datums and redeemers the settlement observers rely on (ADR-059 D8b, D8c, D10;
 * {@code asset-gateway-onchain-v1.cddl}). Each judges the <b>decoded</b> Plutus Data, in any serialization the ledger
 * accepts, which is all a validator ever sees, and returns empty for anything else. All reads are bounded
 * ({@link PlutusDataReader}), so they are safe on any public L1 content and never throw.
 */
public final class GatewayDatums {
    /** The commitment profile every anchor of this custody profile pins (D8b). */
    public static final String COMMITMENT_PROFILE = "mpf-blake2b256-v1";
    private static final int BALANCE_EXIT_CONSTRUCTOR = 7;

    private GatewayDatums() {
    }

    /**
     * The ordered ids of a {@code VaultContinuationV1(ids)} datum (constructor 1, one field: a list of at most 64
     * 32-byte ids), the same shape {@link VaultOutputShape#classify} calls {@code CONTINUATION}.
     */
    public static Optional<List<Hash32>> continuationIds(byte[] inlineDatum) {
        if (!(PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr) || constr.index() != 1
                || constr.fields().size() != 1 || !(constr.fields().getFirst() instanceof List<?> ids)
                || ids.size() > VaultOutputShape.MAX_CONTINUATION_CLAIMS) {
            return Optional.empty();
        }
        List<Hash32> out = new ArrayList<>(ids.size());
        for (Object id : ids) {
            if (!(id instanceof byte[] bytes) || bytes.length != Hash32.BYTES) return Optional.empty();
            out.add(Hash32.of(bytes));
        }
        return Optional.of(List.copyOf(out));
    }

    /**
     * The holder id named by a {@code BalanceExit} vault redeemer: constructor 7 with its seven fields
     * {@code [holder_id: 1..64 bytes, holder_key_hash: 28 bytes, amount, vault_lovelace, recipient: 1..128 bytes,
     * proof: constructor, shard_index: 0..15]}. This is the D10 discriminator; no other vault redeemer and no shard
     * redeemer has this shape.
     */
    public static Optional<byte[]> balanceExitHolderId(byte[] redeemer) {
        if (!(PlutusDataReader.read(redeemer) instanceof PlutusDataReader.Constr constr)
                || constr.index() != BALANCE_EXIT_CONSTRUCTOR || constr.fields().size() != 7) {
            return Optional.empty();
        }
        List<Object> fields = constr.fields();
        boolean shaped = fields.get(0) instanceof byte[] holder && holder.length >= 1
                && holder.length <= GatewayIds.MAX_HOLDER_ID_BYTES
                && fields.get(1) instanceof byte[] keyHash && keyHash.length == ExitLeafV1.KEY_HASH_BYTES
                && nonNegative(fields.get(2)) && nonNegative(fields.get(3))
                && fields.get(4) instanceof byte[] recipient && recipient.length >= 1
                && recipient.length <= GatewayIds.MAX_ADDRESS_BYTES
                && fields.get(5) instanceof PlutusDataReader.Constr
                && fields.get(6) instanceof BigInteger shard && shard.signum() >= 0
                && shard.compareTo(BigInteger.valueOf(16)) < 0;
        return shaped ? Optional.of(((byte[]) fields.get(0)).clone()) : Optional.empty();
    }

    /** A shard datum {@code ShardDatumV1 = Constr 0 [1, shard_index, nullifier_root]}. */
    public record ShardDatum(int shardIndex, Hash32 nullifierRoot) {
        public boolean emptyRoot() {
            return Arrays.equals(nullifierRoot.bytes(), new byte[Hash32.BYTES]);
        }
    }

    public static Optional<ShardDatum> shardDatum(byte[] inlineDatum) {
        if (PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr && constr.index() == 0
                && constr.fields().size() == 3 && BigInteger.ONE.equals(constr.fields().get(0))
                && constr.fields().get(1) instanceof BigInteger index && index.signum() >= 0
                && index.compareTo(BigInteger.valueOf(16)) < 0
                && constr.fields().get(2) instanceof byte[] root && root.length == Hash32.BYTES) {
            return Optional.of(new ShardDatum(index.intValueExact(), Hash32.of(root)));
        }
        return Optional.empty();
    }

    /**
     * The anchored state of an anchor datum v2 that the vault accepts ({@code MpfAnchorLib.acceptedAnchorState}
     * with version 2): {@code Constr 0 [2, chain_id: 1..128 bytes, genesis_id, application_id, commitment_profile,
     * format_fingerprint, height, block_hash: 32, state_root: 32, member_keys, threshold, updated_at_ms]}, the four
     * identity fields equal to the pinned ones, the member keys 32 bytes each and strictly ascending (at most
     * {@link AnchorObservationV1#MAX_MEMBERS}), {@code 1 <= threshold <= count}, and nothing after
     * {@code updated_at_ms}.
     */
    public static Optional<AnchorObservationV1.Anchored> acceptedAnchorV2(byte[] inlineDatum, byte[] genesisId,
                                                                          byte[] applicationId,
                                                                          byte[] commitmentProfile,
                                                                          byte[] formatFingerprint) {
        if (!(PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr) || constr.index() != 0
                || constr.fields().size() != 12) {
            return Optional.empty();
        }
        List<Object> f = constr.fields();
        if (!BigInteger.TWO.equals(f.get(0))
                || !(f.get(1) instanceof byte[] chainId) || chainId.length < 1 || chainId.length > 128
                || !(f.get(2) instanceof byte[] genesis) || !Arrays.equals(genesis, genesisId)
                || !(f.get(3) instanceof byte[] application) || !Arrays.equals(application, applicationId)
                || !(f.get(4) instanceof byte[] profile) || !Arrays.equals(profile, commitmentProfile)
                || !(f.get(5) instanceof byte[] fingerprint) || !Arrays.equals(fingerprint, formatFingerprint)
                || !(f.get(6) instanceof BigInteger height) || height.signum() < 0 || height.bitLength() > 63
                || !(f.get(7) instanceof byte[] blockHash) || blockHash.length != Hash32.BYTES
                || !(f.get(8) instanceof byte[] stateRoot) || stateRoot.length != Hash32.BYTES
                || !(f.get(9) instanceof List<?> members) || members.isEmpty()
                || members.size() > AnchorObservationV1.MAX_MEMBERS
                || !(f.get(10) instanceof BigInteger threshold) || threshold.signum() <= 0
                || threshold.compareTo(BigInteger.valueOf(members.size())) > 0
                || !(f.get(11) instanceof BigInteger updatedAt) || updatedAt.signum() < 0
                || updatedAt.bitLength() > 63) {
            return Optional.empty();
        }
        List<Hash32> keys = new ArrayList<>(members.size());
        for (Object member : members) {
            if (!(member instanceof byte[] key) || key.length != Hash32.BYTES) return Optional.empty();
            Hash32 next = Hash32.of(key);
            if (!keys.isEmpty() && keys.getLast().compareTo(next) >= 0) return Optional.empty();
            keys.add(next);
        }
        return Optional.of(new AnchorObservationV1.Anchored(height.longValueExact(), Hash32.of(blockHash),
                Hash32.of(stateRoot), updatedAt.longValueExact(), threshold.intValueExact(), keys));
    }

    private static boolean nonNegative(Object value) {
        return value instanceof BigInteger number && number.signum() >= 0;
    }
}
