package org.yanoproject.x.framework.api.appchain;

/**
 * Reference to a finalized message's position in the chain (ADR app-layer/006
 * E3.3): resolve the full message via the block at {@code height}.
 */
public record MessageRef(long height, int index, String messageIdHex) {
}
