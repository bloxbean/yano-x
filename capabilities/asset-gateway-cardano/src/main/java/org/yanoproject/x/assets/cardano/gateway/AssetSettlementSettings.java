package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import org.yanoproject.x.assets.cardano.support.settlement.ResolvedOutput;
import org.yanoproject.x.assets.cardano.support.settlement.VaultPins;
import org.yanoproject.x.assets.contracts.Hash32;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.bounded;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.hex;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.optional;
import static org.yanoproject.x.assets.cardano.gateway.SettingsValues.required;

/**
 * The node-local settings of the {@code asset-settlement} executor (ADR-059 D9), read from
 * {@code effects.executors.asset-settlement.*}. They are not consensus settings: they say how this node builds,
 * co-signs and submits. The vault and anchor pins must equal the scripts' parameters, or the built transactions fail
 * on L1 and the members' body checks refuse them.
 *
 * <pre>
 * domain.*, vaults.N.{asset, script-hash, address, shard-policy-id, shard-script-hash, shard-address}
 *                                   as for asset-vault-payout-v1 (one executor serves every vault, D8h §7)
 * vaults.N.script-ref               txhash#index of the output carrying the vault reference script
 * vaults.N.shard-script-ref         txhash#index of the output carrying the shard reference script
 * anchor.*                          as for asset-anchor-v1 (the pinned anchor identity, D8b)
 * exit-mode.policy-id, exit-mode.script-hash
 * continuation-floor                the vault profile's continuationFloor (lovelace)
 * max-settle-batch (16), max-exit-batch (8), max-pause-ms (604800000)
 * claim-key-prefix (ap/claim/), domain-status-key (ap/domain-status)   ASCII, the vault script's parameters
 * owner (false)                     exactly one node per chain builds and submits (single-owner pinning)
 * round-timeout-ms (30000)          the fail-closed co-sign round timeout
 * fee-wallet.key-file               owner only: a file holding the 32-byte Ed25519 seed (hex) of the fee wallet
 * slot.zero-time-ms, slot.zero-slot, slot.length-ms    owner only: the L1 slot clock
 * validity-window-slots (600), max-vault-inputs (8), inclusion-timeout-ms (900000)
 * </pre>
 */
record AssetSettlementSettings(AssetVaultShardSettings vaults, List<VaultPins> pins, Map<Hash32, ScriptRefs> scriptRefs,
                               boolean owner, Duration roundTimeout, Optional<byte[]> feeWalletSeed,
                               Optional<SlotClock> slots, long validityWindowSlots, int maxVaultInputs,
                               Duration inclusionTimeout) {
    private static final Set<String> VAULT_EXTRAS = Set.of("script-ref", "shard-script-ref");

    /** The reference-script outputs of one vault. */
    record ScriptRefs(ResolvedOutput.Outpoint vault, ResolvedOutput.Outpoint shard) {
    }

    /** The L1 slot clock: wall-clock time to slot, for validity intervals. */
    record SlotClock(long zeroTimeMs, long zeroSlot, long slotLengthMs) {
        long slotAt(long timeMs) {
            return zeroSlot + Math.max(0, timeMs - zeroTimeMs) / slotLengthMs;
        }

        long timeOf(long slot) {
            return zeroTimeMs + (slot - zeroSlot) * slotLengthMs;
        }
    }

    static AssetSettlementSettings parse(Map<String, String> config) {
        Map<String, String> shardSettings = new LinkedHashMap<>();
        Map<String, String> anchorSettings = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> extras = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if (key.startsWith("domain.")) {
                shardSettings.put(key, value);
            } else if (key.startsWith("anchor.")) {
                anchorSettings.put(key, value);
            } else if (key.startsWith("vaults.")) {
                String rest = key.substring("vaults.".length());
                int dot = rest.indexOf('.');
                if (dot > 0 && VAULT_EXTRAS.contains(rest.substring(dot + 1))) {
                    extras.computeIfAbsent(Integer.parseInt(rest.substring(0, dot)), ignored -> new LinkedHashMap<>())
                            .put(rest.substring(dot + 1), value);
                } else {
                    shardSettings.put(key, value);
                }
            }
        });
        AssetVaultShardSettings vaults = AssetVaultShardSettings.parse(shardSettings);
        AssetAnchorSettings anchor = AssetAnchorSettings.parse(anchorSettings);
        byte[] exitPolicy = hex(required(config, "exit-mode.policy-id"), 28, "exit-mode.policy-id");
        byte[] exitHash = hex(required(config, "exit-mode.script-hash"), 28, "exit-mode.script-hash");
        BigInteger floor = BigInteger.valueOf(bounded(required(config, "continuation-floor"), 1, 1_000_000_000_000L,
                "continuation-floor"));
        int maxSettle = (int) bounded(optional(config, "max-settle-batch", "16"), 1, 64, "max-settle-batch");
        int maxExit = (int) bounded(optional(config, "max-exit-batch", "8"), 1, 64, "max-exit-batch");
        long maxPause = bounded(optional(config, "max-pause-ms", "604800000"), 0, Long.MAX_VALUE / 4, "max-pause-ms");
        byte[] claimPrefix = optional(config, "claim-key-prefix", "ap/claim/").getBytes(StandardCharsets.US_ASCII);
        byte[] statusKey = optional(config, "domain-status-key", "ap/domain-status").getBytes(StandardCharsets.US_ASCII);
        VaultPins.Anchor pinnedAnchor = new VaultPins.Anchor(anchor.threadPolicyId(), anchor.threadAssetName(),
                anchor.scriptHash(), anchor.genesisId(), anchor.applicationId().getBytes(StandardCharsets.UTF_8),
                anchor.commitmentProfile().getBytes(StandardCharsets.US_ASCII), anchor.formatFingerprint());
        List<VaultPins> pins = new ArrayList<>();
        Map<Hash32, ScriptRefs> refs = new LinkedHashMap<>();
        int network = vaults.domain().addressNetworkId();
        for (AssetVaultShardSettings.Vault vault : vaults.vaults()) {
            pins.add(new VaultPins(vault.vaultId(), vault.asset(), network, vault.scriptHash(),
                    new Address(vault.address()).getBytes(), vault.shardPolicyId(), vault.shardScriptHash(),
                    new Address(vault.shardAddress()).getBytes(), pinnedAnchor, exitPolicy, exitHash, claimPrefix,
                    statusKey, floor, maxSettle, maxExit, maxPause));
            Map<String, String> extra = extras.getOrDefault(vault.index(), Map.of());
            String prefix = "vaults." + vault.index() + ".";
            refs.put(vault.vaultId(), new ScriptRefs(outpoint(required(extra, "script-ref"), prefix + "script-ref"),
                    outpoint(required(extra, "shard-script-ref"), prefix + "shard-script-ref")));
        }
        for (Integer index : extras.keySet()) {
            if (index < 0 || index >= vaults.vaults().size()) throw new IllegalArgumentException("vaults." + index);
        }
        boolean owner = Boolean.parseBoolean(optional(config, "owner", "false"));
        Optional<byte[]> seed = Optional.empty();
        Optional<SlotClock> slots = Optional.empty();
        if (owner) {
            seed = Optional.of(readSeed(required(config, "fee-wallet.key-file")));
            slots = Optional.of(new SlotClock(
                    bounded(required(config, "slot.zero-time-ms"), 0, Long.MAX_VALUE / 4, "slot.zero-time-ms"),
                    bounded(required(config, "slot.zero-slot"), 0, Long.MAX_VALUE / 4, "slot.zero-slot"),
                    bounded(required(config, "slot.length-ms"), 1, 3_600_000, "slot.length-ms")));
        }
        return new AssetSettlementSettings(vaults, pins, refs, owner,
                Duration.ofMillis(bounded(optional(config, "round-timeout-ms", "30000"), 100, 600_000,
                        "round-timeout-ms")), seed, slots,
                bounded(optional(config, "validity-window-slots", "600"), 10, 86_400, "validity-window-slots"),
                (int) bounded(optional(config, "max-vault-inputs", "8"), 1, 32, "max-vault-inputs"),
                Duration.ofMillis(bounded(optional(config, "inclusion-timeout-ms", "900000"), 1_000, 86_400_000L,
                        "inclusion-timeout-ms")));
    }

    Optional<VaultPins> vault(Hash32 vaultId) {
        return pins.stream().filter(pin -> pin.vaultId().equals(vaultId)).findFirst();
    }

    private static ResolvedOutput.Outpoint outpoint(String value, String field) {
        int hash = value.indexOf('#');
        if (hash != 64) throw new IllegalArgumentException(field + " must be <tx hash>#<index>");
        return new ResolvedOutput.Outpoint(Hash32.of(hex(value.substring(0, hash), 32, field)),
                (int) bounded(value.substring(hash + 1), 0, 65_535, field));
    }

    private static byte[] readSeed(String file) {
        try {
            String text = Files.readString(Path.of(file), StandardCharsets.US_ASCII).trim();
            return HexFormat.of().parseHex(text.length() == 64 ? text : "");
        } catch (Exception unreadable) {
            // The seed is a secret: never echo its content or parse error.
            throw new IllegalArgumentException("fee-wallet.key-file must hold a 32-byte hex Ed25519 seed");
        }
    }
}
