package org.yanoproject.x.execution.abi.v1;

/**
 * The chain's effect settings that bound {@code effect_emit}. They are consensus settings in Yano
 * ({@code effects.*}), so checking them in the host keeps a guest from failing later inside the
 * framework's emitter, where its writes could no longer be discarded cleanly.
 *
 * @param remainingInBlock effects the block can still take before {@code effects.max-per-block}
 */
public record EffectLimits(boolean enabled, int maxPayloadBytes, long maxExpiryBlocks,
                           long resultWindowBlocks, int remainingInBlock) {

    public static EffectLimits disabled() {
        return new EffectLimits(false, 0, 0, 0, 0);
    }
}
