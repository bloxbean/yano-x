package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.internal.PlutusDataReader;

import java.util.Objects;
import java.util.Optional;

/**
 * The inline datum a {@code SweepExit} payout carries (ADR-059 D8d, D10): {@code SweepPaidV1(claimId)}, Plutus Data
 * constructor 4 with one 32-byte field, a constructor no vault output shape uses.
 *
 * <pre>
 * sweep_paid_v1 = #6.125([claim_id: bytes .size 32])
 * </pre>
 *
 * <p>A sweep spends one unaccepted outpoint whole and leaves no {@code VaultContinuationV1}, so the vault script
 * requires its output 0, the payout, to carry this datum. The payout observer reads the paid claim id from it, in a
 * transaction that it has already authenticated by the shard thread output (D10); the script checks the id equals the
 * one it inserted into the shard.
 */
public record SweepPaidV1(Hash32 claimId) {
    public static final int CONSTRUCTOR = 4;

    public SweepPaidV1 {
        Objects.requireNonNull(claimId, "claim id");
    }

    /** {@code d8 7d 81 58 20 <claim id>}. */
    public byte[] encode() {
        byte[] out = new byte[37];
        out[0] = (byte) 0xd8;
        out[1] = (byte) (121 + CONSTRUCTOR);
        out[2] = (byte) 0x81;
        out[3] = 0x58;
        out[4] = 0x20;
        System.arraycopy(claimId.bytes(), 0, out, 5, 32);
        return out;
    }

    /** Reads the datum from any Plutus Data serialization of it; empty for anything else. */
    public static Optional<SweepPaidV1> decode(byte[] inlineDatum) {
        if (PlutusDataReader.read(inlineDatum) instanceof PlutusDataReader.Constr constr
                && constr.index() == CONSTRUCTOR && constr.fields().size() == 1
                && constr.fields().getFirst() instanceof byte[] id && id.length == 32) {
            return Optional.of(new SweepPaidV1(Hash32.of(id)));
        }
        return Optional.empty();
    }
}
