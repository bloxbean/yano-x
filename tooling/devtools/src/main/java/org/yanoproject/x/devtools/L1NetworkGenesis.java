package org.yanoproject.x.devtools;

import java.util.Map;

/**
 * The {@code observation.l1-network-genesis-id} a rendered project gives its L1 observers: the
 * SHA-256 of the exact Shelley genesis file its nodes load. Every member must use the same value,
 * because it enters each observer's consensus identity.
 */
final class L1NetworkGenesis {
    /** A devnet's genesis is generated at first start, so its start scripts export the digest. */
    static final String DEVNET_ENVIRONMENT = "YANO_APPCHAIN_L1_NETWORK_GENESIS_ID";

    /** SHA-256 of {@code config/network/<network>/shelley-genesis.json} in the Yano distribution. */
    private static final Map<String, String> PUBLIC_NETWORKS = Map.of(
            "preview", "c5ccb45161676718a8c08b1362ec1ef2cee516fd123aecafacf0f3e4625a746a",
            "preprod", "4b9d32c09159c2948e4386ba1f59db5a249a89b43b84dfd8368f465e650095de",
            "mainnet", "59cd3932c6dd792bc5020ca3336064a8faabde4e4a8dc7d143ff4df6eec36961");

    private L1NetworkGenesis() {
    }

    static String configValue(String network) {
        if ("devnet".equals(network)) {
            return "${" + DEVNET_ENVIRONMENT + "}";
        }
        String digest = PUBLIC_NETWORKS.get(network);
        if (digest == null) {
            throw new IllegalArgumentException("No L1 network genesis identity is known for " + network);
        }
        return digest;
    }

    static Map<String, String> publicNetworks() {
        return PUBLIC_NETWORKS;
    }
}
