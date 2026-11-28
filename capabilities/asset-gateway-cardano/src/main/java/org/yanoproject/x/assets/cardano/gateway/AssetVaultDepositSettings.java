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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.asset;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.bounded;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.hex;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.optional;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.required;

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
 * vaults.N.custody            native (default) | cip113 (ADR-061)
 * vaults.N.cip113.plb-hash    56 hex: the pinned deployment's base script; the address is then the vault smart
 *                             wallet base(Script(plb), Script(script-hash))
 * vaults.N.cip113.params-policy  56 hex: the deployment's params policy
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
    public record Vault(Hash32 vaultId, AssetId asset, String assetSetting, byte[] scriptHash, String address,
                        Optional<Cip113> cip113) {
        public Vault {
            scriptHash = scriptHash.clone();
            Objects.requireNonNull(cip113, "cip113");
        }

        public Vault(Hash32 vaultId, AssetId asset, String assetSetting, byte[] scriptHash, String address) {
            this(vaultId, asset, assetSetting, scriptHash, address, Optional.empty());
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Vault that && vaultId.equals(that.vaultId) && asset.equals(that.asset)
                    && assetSetting.equals(that.assetSetting) && Arrays.equals(scriptHash, that.scriptHash)
                    && address.equals(that.address) && cip113.equals(that.cip113);
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

    /** ADR-061: a CIP-113 vault's deployment pins: the base script and the params policy. */
    public record Cip113(byte[] plbHash, byte[] paramsPolicy) {
        public Cip113 {
            plbHash = plbHash.clone();
            paramsPolicy = paramsPolicy.clone();
        }

        @Override
        public byte[] plbHash() {
            return plbHash.clone();
        }

        @Override
        public byte[] paramsPolicy() {
            return paramsPolicy.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Cip113 that && Arrays.equals(plbHash, that.plbHash)
                    && Arrays.equals(paramsPolicy, that.paramsPolicy);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(plbHash) * 31 + Arrays.hashCode(paramsPolicy);
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
            String custody = optional(settings, prefix + "custody", "native");
            Optional<Cip113> cip113;
            String address;
            if (custody.equals("cip113")) {
                if (asset.isAda()) throw new IllegalArgumentException(prefix + "a CIP-113 vault holds a native asset");
                cip113 = Optional.of(new Cip113(hex(required(settings, prefix + "cip113.plb-hash"), 28,
                        prefix + "cip113.plb-hash"), hex(required(settings, prefix + "cip113.params-policy"), 28,
                        prefix + "cip113.params-policy")));
                address = required(settings, prefix + "address");
                byte[] expected = org.yanoproject.x.assets.cardano.support.settlement.ProgrammableBackend
                        .smartWallet(cip113.get().plbHash(), scriptHash, networkId);
                byte[] actual;
                try {
                    actual = new com.bloxbean.cardano.client.address.Address(address).getBytes();
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException(prefix + "address is not a bech32 Shelley address", invalid);
                }
                if (!Arrays.equals(actual, expected)) {
                    throw new IllegalArgumentException(prefix + "address must be the vault smart wallet "
                            + "base(Script(plb), Script(script-hash))");
                }
            } else if (custody.equals("native")) {
                cip113 = Optional.empty();
                address = VaultAddresses.requireEnterpriseScript(required(settings, prefix + "address"),
                        scriptHash, networkId);
            } else {
                throw new IllegalArgumentException(prefix + "custody must be native or cip113");
            }
            if (!addresses.add(address)) throw new IllegalArgumentException("vault addresses must be distinct");
            Hash32 vaultId = GatewayIds.vaultId(domainId, asset, 0);
            String declared = settings.get(prefix + "vault-id");
            if (declared != null && !declared.trim().equals(vaultId.hex())) {
                throw new IllegalArgumentException(prefix + "vault-id differs from the derived VaultId " + vaultId);
            }
            vaults.add(new Vault(vaultId, asset, assetSetting, scriptHash, address, cip113));
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
                        || !Set.of("asset", "ordinal", "script-hash", "address", "vault-id", "custody",
                        "cip113.plb-hash", "cip113.params-policy").contains(rest.substring(dot + 1))) {
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
            // A native vault's identity is unchanged; a CIP-113 vault adds its custody and deployment pins.
            vault.cip113().ifPresent(pins -> {
                fields.put("vaults." + vault.vaultId().hex() + ".custody", "cip113");
                fields.put("vaults." + vault.vaultId().hex() + ".cip113.plb-hash",
                        HexFormat.of().formatHex(pins.plbHash()));
                fields.put("vaults." + vault.vaultId().hex() + ".cip113.params-policy",
                        HexFormat.of().formatHex(pins.paramsPolicy()));
            });
        }
        fields.put("datum.max-bytes", String.valueOf(datumBounds.maxBytes()));
        fields.put("datum.max-depth", String.valueOf(datumBounds.maxDepth()));
        fields.put("min-stability-depth", String.valueOf(minStabilityDepth));
        List<String> out = new ArrayList<>();
        fields.forEach((key, value) -> out.add(key + "=" + value));
        return out;
    }
}
