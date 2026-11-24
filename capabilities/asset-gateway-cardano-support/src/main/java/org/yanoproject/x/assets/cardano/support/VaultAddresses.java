package org.yanoproject.x.assets.cardano.support;

import com.bloxbean.cardano.client.address.Address;

import java.util.Arrays;

/**
 * Checks a configured vault address (ADR-059 D4 step 6): the vault's <b>enterprise</b> script
 * address on the domain's network, whose payment credential is the vault script hash. Returns the
 * canonical bech32 form, which is what the L1 block model reports for an output address.
 */
public final class VaultAddresses {
    private static final int ENTERPRISE_SCRIPT_TYPE = 7;

    private VaultAddresses() {
    }

    public static String requireEnterpriseScript(String bech32, byte[] scriptHash, int addressNetworkId) {
        if (bech32 == null || bech32.isBlank()) throw new IllegalArgumentException("vault address is required");
        if (scriptHash == null || scriptHash.length != 28) {
            throw new IllegalArgumentException("vault script hash must be 28 bytes");
        }
        byte[] bytes;
        String canonical;
        try {
            Address address = new Address(bech32.trim());
            bytes = address.getBytes();
            canonical = address.toBech32();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("vault address is not a valid bech32 Shelley address", invalid);
        }
        if (bytes == null || bytes.length != 29) {
            throw new IllegalArgumentException("vault address must be an enterprise address");
        }
        int header = bytes[0] & 0xff;
        if ((header >>> 4) != ENTERPRISE_SCRIPT_TYPE) {
            throw new IllegalArgumentException("vault address must be an enterprise script address");
        }
        if ((header & 0x0f) != addressNetworkId) {
            throw new IllegalArgumentException("vault address is on another network id");
        }
        if (!Arrays.equals(Arrays.copyOfRange(bytes, 1, 29), scriptHash)) {
            throw new IllegalArgumentException("vault address payment credential differs from the script hash");
        }
        if (!canonical.equals(bech32.trim())) {
            throw new IllegalArgumentException("vault address must be given in canonical bech32 form");
        }
        return canonical;
    }
}
