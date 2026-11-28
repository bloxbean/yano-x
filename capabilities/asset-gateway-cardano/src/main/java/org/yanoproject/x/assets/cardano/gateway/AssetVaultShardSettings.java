package org.yanoproject.x.assets.cardano.gateway;

import org.yanoproject.x.assets.cardano.support.VaultAddresses;
import org.yanoproject.x.assets.cardano.support.settlement.ShardBackend;
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
import java.util.Set;

import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.asset;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.bounded;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.hex;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.optional;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.required;

/**
 * The consensus settings of an {@code asset-vault-payout-v1} or {@code asset-shard-bootstrap-v1} observer
 * (ADR-059 D8c, D10), read from {@code yano.app-chain.observers.<id>.*}:
 *
 * <pre>
 * domain.*                     as for asset-vault-deposit-v1 (the GatewayDomain)
 * vaults.N.asset               lovelace | &lt;policy hex&gt;.&lt;asset name hex&gt;   (N = 0, 1, ... contiguous)
 * vaults.N.script-hash         56 lowercase hex
 * vaults.N.address             canonical bech32 enterprise script address of that hash
 * vaults.N.vault-id            optional; must equal the derived VaultId
 * shard-backend                julc | aiken, default julc: the nullifier-shard validator of every vault (r23)
 * vaults.N.shard-policy-id     56 lowercase hex: ShardThreadPolicy(seed) of this vault
 * vaults.N.shard-script-hash   56 lowercase hex: the shard-backend's shard validator(shardPolicyId, vaultScriptHash)
 * vaults.N.shard-address       canonical bech32 enterprise script address of the shard hash
 * min-stability-depth          1..100000, default 1
 * </pre>
 *
 * The vault set is fixed at genesis (D5). Every value is part of the observer's consensus identity; invalid settings
 * fail startup. The vault script hash is pinned (deriving it needs the whole vault profile). The shard script hash
 * is pinned too, and must be the selected backend's shard of the vault's shard policy and script hash
 * ({@link ShardBackend#derive}), so a member whose shard pin and {@code shard-backend} disagree fails startup, and
 * members with different backends have different consensus identities.
 */
record AssetVaultShardSettings(DomainSettings domain, ShardBackend shardBackend, List<Vault> vaults,
                               int minStabilityDepth) {
    /** The nullifier-shard backend setting (ADR-059 r23). */
    static final String SHARD_BACKEND = "shard-backend";
    private static final Set<String> VAULT_KEYS = Set.of("asset", "ordinal", "script-hash", "address", "vault-id",
            "shard-policy-id", "shard-script-hash", "shard-address");

    /** One vault and its nullifier shard thread. */
    record Vault(int index, Hash32 vaultId, AssetId asset, byte[] scriptHash, String address, byte[] shardPolicyId,
                 byte[] shardScriptHash, String shardAddress) {
        Vault {
            scriptHash = scriptHash.clone();
            shardPolicyId = shardPolicyId.clone();
            shardScriptHash = shardScriptHash.clone();
        }

        @Override
        public byte[] scriptHash() {
            return scriptHash.clone();
        }

        @Override
        public byte[] shardPolicyId() {
            return shardPolicyId.clone();
        }

        String shardPolicyHex() {
            return HexFormat.of().formatHex(shardPolicyId);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Vault that && index == that.index && vaultId.equals(that.vaultId)
                    && asset.equals(that.asset) && Arrays.equals(scriptHash, that.scriptHash)
                    && address.equals(that.address) && Arrays.equals(shardPolicyId, that.shardPolicyId)
                    && Arrays.equals(shardScriptHash, that.shardScriptHash) && shardAddress.equals(that.shardAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, vaultId, address, shardAddress);
        }
    }

    AssetVaultShardSettings {
        vaults = List.copyOf(vaults);
    }

    static AssetVaultShardSettings parse(Map<String, String> settings) {
        DomainSettings domain = DomainSettings.parse(settings);
        ShardBackend backend = ShardBackend.parse(settings.get(SHARD_BACKEND));
        List<Vault> vaults = new ArrayList<>();
        Set<AssetId> assets = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        Set<String> policies = new HashSet<>();
        for (int index = 0; settings.containsKey("vaults." + index + ".asset"); index++) {
            if (index >= AssetVaultDepositSettings.MAX_VAULTS) throw new IllegalArgumentException("at most 16 vaults");
            String prefix = "vaults." + index + ".";
            AssetId asset = asset(required(settings, prefix + "asset"), prefix + "asset");
            if (!assets.add(asset)) throw new IllegalArgumentException("v1 allows one vault per asset (I29)");
            if (!"0".equals(optional(settings, prefix + "ordinal", "0"))) {
                throw new IllegalArgumentException(prefix + "ordinal must be 0 in v1");
            }
            byte[] scriptHash = hex(required(settings, prefix + "script-hash"), 28, prefix + "script-hash");
            String address = VaultAddresses.requireEnterpriseScript(required(settings, prefix + "address"),
                    scriptHash, domain.addressNetworkId());
            byte[] shardPolicy = hex(required(settings, prefix + "shard-policy-id"), 28, prefix + "shard-policy-id");
            byte[] shardHash = hex(required(settings, prefix + "shard-script-hash"), 28, prefix + "shard-script-hash");
            String shardAddress = VaultAddresses.requireEnterpriseScript(required(settings, prefix + "shard-address"),
                    shardHash, domain.addressNetworkId());
            byte[] derived = backend.derive(shardPolicy, scriptHash, domain.addressNetworkId()).scriptHash();
            if (!Arrays.equals(derived, shardHash)) {
                throw new IllegalArgumentException(prefix + "shard-script-hash is not the " + backend.id()
                        + " shard of this vault's shard-policy-id and script-hash (expected "
                        + HexFormat.of().formatHex(derived) + "; check shard-backend)");
            }
            if (!addresses.add(address) || !addresses.add(shardAddress)) {
                throw new IllegalArgumentException("vault and shard addresses must be distinct");
            }
            if (!policies.add(HexFormat.of().formatHex(shardPolicy))) {
                throw new IllegalArgumentException("each vault has its own shard thread policy (D8c)");
            }
            Hash32 vaultId = GatewayIds.vaultId(domain.domainId(), asset, 0);
            String declared = settings.get(prefix + "vault-id");
            if (declared != null && !declared.trim().equals(vaultId.hex())) {
                throw new IllegalArgumentException(prefix + "vault-id differs from the derived VaultId " + vaultId);
            }
            vaults.add(new Vault(index, vaultId, asset, scriptHash, address, shardPolicy, shardHash, shardAddress));
        }
        if (vaults.isEmpty()) throw new IllegalArgumentException("at least one vault (vaults.0.*) is required");
        for (String key : settings.keySet()) {
            if (!key.startsWith("vaults.")) continue;
            String rest = key.substring("vaults.".length());
            int dot = rest.indexOf('.');
            int index;
            try {
                index = dot < 0 ? -1 : Integer.parseInt(rest.substring(0, dot));
            } catch (NumberFormatException invalid) {
                index = -1;
            }
            if (index < 0 || index >= vaults.size() || !VAULT_KEYS.contains(rest.substring(dot + 1))) {
                throw new IllegalArgumentException("unknown vault setting " + key);
            }
        }
        int minDepth = (int) bounded(optional(settings, "min-stability-depth", "1"), 1, 100_000,
                "min-stability-depth");
        return new AssetVaultShardSettings(domain, backend, vaults, minDepth);
    }

    /** Every consensus-relevant value in a fixed order and normalized form. */
    List<String> identityFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        domain.identityFields(fields);
        fields.put(SHARD_BACKEND, shardBackend.id());
        for (Vault vault : vaults) {
            String prefix = "vaults." + vault.index() + ".";
            fields.put(prefix + "vault-id", vault.vaultId().hex());
            fields.put(prefix + "asset", vault.asset().toString());
            fields.put(prefix + "script-hash", HexFormat.of().formatHex(vault.scriptHash()));
            fields.put(prefix + "address", vault.address());
            fields.put(prefix + "shard-policy-id", vault.shardPolicyHex());
            fields.put(prefix + "shard-script-hash", HexFormat.of().formatHex(vault.shardScriptHash()));
            fields.put(prefix + "shard-address", vault.shardAddress());
        }
        fields.put("min-stability-depth", String.valueOf(minStabilityDepth));
        List<String> out = new ArrayList<>();
        fields.forEach((key, value) -> out.add(key + "=" + value));
        return out;
    }
}
