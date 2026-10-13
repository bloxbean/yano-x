package org.yanoproject.x.eutxo.bridge.cardano;

import org.yanoproject.api.appchain.codec.internal.CborStructurePreflight;

/**
 * Bounds an inline datum read from L1 before it is decoded. Anyone can attach any datum to an output at the
 * public vault address, and the recursive Plutus data decoder overflows the stack on a datum nested a few
 * thousand levels deep, which fits in one transaction. A {@link StackOverflowError} escapes every observer's
 * exception handling and stops L1 observation for the chain, so each observer checks the structure first,
 * without recursion, and skips a datum outside these bounds. Every genuine bridge datum is far inside them.
 */
final class BridgeDatumPreflight {
    private static final CborStructurePreflight.Limits LIMITS =
            new CborStructurePreflight.Limits(16_384, 16, 8_192, 4_096, 16_384);

    private BridgeDatumPreflight() {
    }

    static boolean bounded(byte[] datumCbor) {
        return CborStructurePreflight.accepts(datumCbor, LIMITS);
    }
}
