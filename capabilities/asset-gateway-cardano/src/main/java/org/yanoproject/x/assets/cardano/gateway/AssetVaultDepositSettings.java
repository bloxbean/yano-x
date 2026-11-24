package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.cardano.support.DatumPreflight;
import org.yanoproject.x.assets.cardano.support.VaultAddresses;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The consensus settings of one {@code asset-vault-deposit-v1} observer (ADR-059 D5), read from
 * {@code yano.app-chain.observers.<id>.*}:
 *
 * <pre>
 * domain.address-network-id   0..15                      (required)
 * domain.network-magic        unsigned                   (required)
 * domain.chain-id             app ledger chain id        (required)
 * domain.genesis-id           64 lowercase hex           (required)
 * domain.application-id       default asset-payments
 * domain.profile-id           default anchored-claim-vault-v1
 * domain.profile-version      default 1
 * vaults.N.asset              lovelace | &lt;policy hex&gt;.&lt;asset name hex&gt;   (N = 0, 1, ... contiguous)
 * vaults.N.script-hash        56 lowercase hex
 * vaults.N.address            canonical bech32 enterprise script address of that hash
 * vaults.N.vault-id           optional; must equal the derived VaultId
 * datum.max-bytes             default 16384
 * datum.max-depth             default 16
 * min-stability-depth         1..100000, default 1; the consuming machine checks the chain's l1.stability-depth
 * </pre>
 *
 * v1 has exactly one vault per asset (I29) and vault ordinal 0. Every value is part of the
 * observer's consensus identity; invalid settings fail startup.
 */
public record AssetVaultDepositSettings(int addressNetworkId, long networkMagic, String chainId, Hash32 genesisId,
                                        String applicationId, String profileId, long profileVersion,
                                        Hash32 domainId, List<Vault> vaults, DatumPreflight datumBounds,
                                        int minStabilityDepth) {
    public static final int MAX_VAULTS = 16;
    public static final String DEFAULT_APPLICATION_ID = "asset-payments";
    public static final String DEFAULT_PROFILE_ID = "anchored-claim-vault-v1";

    /** One configured vault: its id, asset, script hash and canonical bech32 address. */
    public record Vault(Hash32 vaultId, AssetId asset, String assetSetting, byte[] scriptHash, String address) {
        public Vault {
            scriptHash = scriptHash.clone();
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Vault that && vaultId.equals(that.vaultId) && asset.equals(that.asset)
                    && assetSetting.equals(that.assetSetting) && Arrays.equals(scriptHash, that.scriptHash)
                    && address.equals(that.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vaultId, asset, assetSetting, Arrays.hashCode(scriptHash), address);
        }

        @Override
        public String toString() {
            return "Vault[vaultId=" + vaultId + ", asset=" + asset + ", scriptHash="
                    + HexFormat.of().formatHex(scriptHash) + ", address=" + address + "]";
        }
    }

    public AssetVaultDepositSettings {
        vaults = List.copyOf(vaults);
    }

    public static AssetVaultDepositSettings parse(Map<String, String> settings) {
        int networkId = (int) bounded(required(settings, "domain.address-network-id"), 0, 15,
                "domain.address-network-id");
        long magic = bounded(required(settings, "domain.network-magic"), 0, 0xFFFF_FFFFL, "domain.network-magic");
        String chainId = required(settings, "domain.chain-id");
        Hash32 genesis = Hash32.of(hex(required(settings, "domain.genesis-id"), 32, "domain.genesis-id"));
        String applicationId = optional(settings, "domain.application-id", DEFAULT_APPLICATION_ID);
        String profileId = optional(settings, "domain.profile-id", DEFAULT_PROFILE_ID);
        long profileVersion = bounded(optional(settings, "domain.profile-version", "1"), 0, Long.MAX_VALUE,
                "domain.profile-version");
        Hash32 domainId = GatewayIds.domainId(networkId, magic, chainId, genesis, applicationId, profileId,
                profileVersion);

        List<Vault> vaults = new ArrayList<>();
        Set<AssetId> assets = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (int index = 0; settings.containsKey("vaults." + index + ".asset"); index++) {
            if (index >= MAX_VAULTS) throw new IllegalArgumentException("at most 16 vaults");
            String prefix = "vaults." + index + ".";
            String assetSetting = required(settings, prefix + "asset");
            AssetId asset = asset(assetSetting, prefix + "asset");
            if (!assets.add(asset)) throw new IllegalArgumentException("v1 allows one vault per asset (I29)");
            if (!"0".equals(optional(settings, prefix + "ordinal", "0"))) {
                throw new IllegalArgumentException(prefix + "ordinal must be 0 in v1");
            }
            byte[] scriptHash = hex(required(settings, prefix + "script-hash"), 28, prefix + "script-hash");
            String address = VaultAddresses.requireEnterpriseScript(required(settings, prefix + "address"),
                    scriptHash, networkId);
            if (!addresses.add(address)) throw new IllegalArgumentException("vault addresses must be distinct");
            Hash32 vaultId = GatewayIds.vaultId(domainId, asset, 0);
            String declared = settings.get(prefix + "vault-id");
            if (declared != null && !declared.trim().equals(vaultId.hex())) {
                throw new IllegalArgumentException(prefix + "vault-id differs from the derived VaultId " + vaultId);
            }
            vaults.add(new Vault(vaultId, asset, assetSetting, scriptHash, address));
        }
        if (vaults.isEmpty()) throw new IllegalArgumentException("at least one vault (vaults.0.*) is required");
        for (String key : settings.keySet()) {
            if (key.startsWith("vaults.")) {
                String rest = key.substring("vaults.".length());
                int dot = rest.indexOf('.');
                int index;
                try {
                    index = dot < 0 ? -1 : Integer.parseInt(rest.substring(0, dot));
                } catch (NumberFormatException invalid) {
                    index = -1;
                }
                if (index < 0 || index >= vaults.size()
                        || !Set.of("asset", "ordinal", "script-hash", "address", "vault-id")
                        .contains(rest.substring(dot + 1))) {
                    throw new IllegalArgumentException("unknown vault setting " + key);
                }
            }
        }
        DatumPreflight bounds = new DatumPreflight(
                (int) bounded(optional(settings, "datum.max-bytes", String.valueOf(DatumPreflight.DEFAULT_MAX_BYTES)),
                        1, 65_536, "datum.max-bytes"),
                (int) bounded(optional(settings, "datum.max-depth", String.valueOf(DatumPreflight.DEFAULT_MAX_DEPTH)),
                        1, 64, "datum.max-depth"));
        // Depth 0 would credit deposits from blocks that can still roll back (ADR-059 D5).
        int minDepth = (int) bounded(optional(settings, "min-stability-depth", "1"), 1, 100_000,
                "min-stability-depth");
        return new AssetVaultDepositSettings(networkId, magic, chainId, genesis, applicationId, profileId,
                profileVersion, domainId, vaults, bounds, minDepth);
    }

    /** Every consensus-relevant value in a fixed order and normalized form. */
    public List<String> identityFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("domain.address-network-id", String.valueOf(addressNetworkId));
        fields.put("domain.network-magic", String.valueOf(networkMagic));
        fields.put("domain.chain-id", chainId);
        fields.put("domain.genesis-id", genesisId.hex());
        fields.put("domain.application-id", applicationId);
        fields.put("domain.profile-id", profileId);
        fields.put("domain.profile-version", String.valueOf(profileVersion));
        fields.put("domain-id", domainId.hex());
        for (int index = 0; index < vaults.size(); index++) {
            Vault vault = vaults.get(index);
            fields.put("vaults." + index + ".vault-id", vault.vaultId().hex());
            fields.put("vaults." + index + ".asset", vault.asset().toString());
            fields.put("vaults." + index + ".script-hash", HexFormat.of().formatHex(vault.scriptHash()));
            fields.put("vaults." + index + ".address", vault.address());
        }
        fields.put("datum.max-bytes", String.valueOf(datumBounds.maxBytes()));
        fields.put("datum.max-depth", String.valueOf(datumBounds.maxDepth()));
        fields.put("min-stability-depth", String.valueOf(minStabilityDepth));
        List<String> out = new ArrayList<>();
        fields.forEach((key, value) -> out.add(key + "=" + value));
        return out;
    }

    /** {@code lovelace}, or {@code <56 hex policy>.<0..64 hex name>} in lowercase. */
    static AssetId asset(String value, String field) {
        if ("lovelace".equals(value)) return AssetId.ADA;
        int dot = value.indexOf('.');
        if (dot != 56 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be lovelace or <policy hex>.<asset name hex>");
        }
        try {
            return AssetId.of(HexFormat.of().parseHex(value.substring(0, dot)),
                    HexFormat.of().parseHex(value.substring(dot + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + ": " + invalid.getMessage(), invalid);
        }
    }

    private static String required(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value.trim();
    }

    private static String optional(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long bounded(String value, long min, long max, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(field + " is out of range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be an integer", invalid);
        }
    }

    private static byte[] hex(String value, int bytes, String field) {
        if (value.length() != bytes * 2 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(field + " must be " + bytes + "-byte lowercase hex");
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be " + bytes + "-byte lowercase hex", invalid);
        }
    }
}
