package org.yanoproject.x.eutxo.bridge.onchain.mpf;

import org.julclang.stdlib.annotation.OnchainLibrary;

/**
 * A neighbor node used in a Merkle Patricia Forestry proof.
 */
@OnchainLibrary
public record Neighbor(int nibble, byte[] prefix, byte[] root) {}
