package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.Address;
import org.yanoproject.x.assets.cardano.support.settlement.AcceptTxBuilder;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableBackend;
import org.yanoproject.x.assets.cardano.support.settlement.ProgrammableVaultTxAssembler;
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
 * domain.*, shard-backend, vaults.N.{asset, script-hash, address, shard-policy-id, shard-script-hash, shard-address}
 *                                   as for asset-vault-payout-v1 (one executor serves every vault, D8h §7); the
 *                                   shard-backend must be the payout observer's
 * vaults.N.script-ref               txhash#index of the output carrying the vault reference script
 * vaults.N.shard-script-ref         txhash#index of the output carrying the shard reference script
 * anchor.*                          as for asset-anchor-v1 (the pinned anchor identity, D8b)
 * exit-mode.policy-id, exit-mode.script-hash
 * continuation-floor                the vault profile's continuationFloor (lovelace)
 * max-settle-batch (16), max-exit-batch (8), max-pause-ms (604800000)
 * claim-key-prefix (ap/claim/), deposit-key-prefix (ap/deposit/), domain-status-key (ap/domain-status)
 *                                   ASCII, the vault script's parameters
 * owner (false)                     exactly one node per chain builds and submits (single-owner pinning)
 * round-timeout-ms (30000)          the fail-closed co-sign round timeout
 * slot.zero-time-ms, slot.zero-slot, slot.length-ms    the L1 slot clock: the owner's validity intervals, and every
 *                                   member's bound on the intervals it signs (D8h §1 step 0); every member's clock
 *                                   must be within about 5 minutes of the owner's, or it refuses honest bodies
 *                                   (reported as an l1-tip-bound refusal)
 * fee-wallet.key-file               owner only: a file holding the 32-byte Ed25519 seed (hex) of the fee wallet
 * validity-window-slots (600)       owner and relayer: the validity window of every Settle, Accept and relayed Exit,
 *                                   at most 10 minutes of slots; a pending transaction holds its inputs until the
 *                                   node's applied L1 tip is past its TTL
 * pending-ttl-file                  owner and relayer, optional: the base path of a node-local file (put it under the
 *                                   node's data directory), suffixed .<chain id>.<fee wallet key hash, 4 bytes hex>,
 *                                   holding the latest TTL slot this chain and wallet submitted; a restart builds
 *                                   nothing until the L1 view is past it. Without a valid file a restart waits the
 *                                   longest permitted window (10 minutes) plus 2 minutes, and until the applied tip is
 *                                   past its start slot plus 10 minutes
 * max-vault-inputs (8)              owner and relayer: vault inputs per Settle or relayed Exit
 * max-supplement-per-payout (0)     owner only: the most lovelace the fee wallet adds to one payout's min-UTxO (D11);
 *                                   a claim needing more is left out of the co-signed batch and stays RESERVED for
 *                                   Exit, where the relayer or the user supplements it
 * stability-window-ms (600000)      owner and relayer: an upper bound on the time from an L1 inclusion to its
 *                                   committed observation (the chain's l1.stability-depth in L1 time, plus finality):
 *                                   a Settle's payout observation, and a deposit's credit
 * inclusion-timeout-ms (900000)     owner only: how long a submitted Settle may take to be confirmed by the payout
 *                                   observer; must exceed stability-window-ms
 * accept.interval-ms (60000)        owner and relayer: how often the owner batches CREDITED-but-unaccepted deposits
 *                                   into an Accept (D8f), and a relaying non-owner into a proved Accept; 0 disables
 *                                   it
 * accept.max-batch (16), accept.max-proved-batch (12)   deposits per co-signed and per proved Accept, at most the
 *                                   builder's caps
 * accept.relayer-delay-ms           a relaying non-owner only: it proves an Accept only of deposits that could have
 *   (3 x accept.interval-ms)        been credited at least this long ago (L1 output time plus stability-window-ms),
 *                                   so it does not race a healthy owner
 * exit-relayer.enabled (false)      relay EXIT_ELIGIBLE claims as proof Exits (D9), permissionless; the owner relays
 *                                   with its own fee wallet and assembly, any other node with its own wallet
 * exit-relayer.interval-ms (60000)  how often the relayer looks for eligible claims
 * exit-relayer.fee-wallet.key-file  a relaying non-owner only: the 32-byte Ed25519 seed (hex) of its fee wallet
 * reconciliation.interval-ms        every node: how often it reconciles each vault's L1 custody with the committed
 *   (300000)                        liabilities (D15), read-only; 0 disables it; at least 1000 otherwise
 * reconciliation.report-file        optional: a node-local file (put it under the node's data directory) that each
 *                                   pass replaces with the full report as JSON
 * exit-key-prefix (ap/x/)           ASCII, the custody scripts' exit key prefix (a CIP-113 vault's parameter)
 * </pre>
 *
 * <p><b>CIP-113 vaults (ADR-061).</b> A vault with {@code vaults.N.custody = cip113} also takes, beside the payout
 * observer's {@code cip113.plb-hash}, {@code cip113.exit-delegate-hash} and {@code programmable-backend}:
 * <pre>
 * vaults.N.cip113.params-policy          56 hex: the deployment's params policy
 * vaults.N.cip113.registry-node-policy   56 hex: the deployment's registry node policy
 * vaults.N.cip113.max-inline-datum-bytes the deployment's bound (1024)
 * vaults.N.cip113.params-address         bech32: where the live protocol-params UTxO sits
 * vaults.N.cip113.registry-address       bech32: where the registry nodes sit
 * vaults.N.cip113.reference-scripts      comma-separated txhash#index of the reference scripts a vault transaction
 *                                        runs besides h and the shard (PLB, the dispatcher, the transfer delegate,
 *                                        the hook)
 * vaults.N.cip113.substandard            the asset's substandard, whose adapter builds the hook redeemer
 *                                        ({@code yano-x-test} in this release)
 * </pre>
 * At startup the selected backend's {@code h} and {@code e} for the vault's parameters (the domain, the asset, the
 * anchor, the shard policy, the exit-mode thread, the key prefixes, the profile and the deployment) must equal the
 * pinned {@code script-hash} and {@code cip113.exit-delegate-hash} ({@code ProgrammableBackend.requireDerived}), and
 * {@code max-settle-batch} and {@code max-exit-batch} must fit the continuation's id cap (D4, V16: 29 at 1,024 bytes),
 * or startup fails.
 */
record AssetSettlementSettings(AssetVaultShardSettings vaults, List<VaultPins> pins, Map<Hash32, ScriptRefs> scriptRefs,
                               boolean owner, Duration roundTimeout, Optional<byte[]> feeWalletSeed,
                               SlotClock slots, long validityWindowSlots, int maxVaultInputs,
                               Duration inclusionTimeout, BigInteger maxSupplementPerPayout,
                               Duration stabilityWindow, Tasks tasks, Optional<Path> pendingTtlFile) {
    /**
     * The validity window bound of every node that builds (the owner and a relayer): the members accept a TTL up to 10
     * minutes plus clock skew past their tip, and holds and the startup grace last one window.
     */
    static final long MAX_VALIDITY_WINDOW_MS = 600_000;

    private static final Set<String> VAULT_EXTRAS = Set.of("script-ref", "shard-script-ref", "cip113.params-policy",
            "cip113.registry-node-policy", "cip113.max-inline-datum-bytes", "cip113.params-address",
            "cip113.registry-address", "cip113.reference-scripts", "cip113.substandard");
    /** The custody scripts' exit delay bounds (ADR-059 D8e), fixed parameters of a CIP-113 vault's h and e. */
    static final long EXIT_DELAY_FLOOR_MS = 6L * 3_600_000;
    static final long EXIT_DELAY_CEILING_MS = 30L * 24 * 3_600_000;

    /**
     * The periodic tasks: the owner's {@code Accept} batching (D8f) and the permissionless exit relayer (D9).
     *
     * @param acceptIntervalMs  the {@code Accept} pace, 0 when disabled (the owner, or a relaying non-owner's proved
     *                          {@code Accept})
     * @param relayerSeed       a relaying non-owner's own fee-wallet seed; the owner relays with its fee wallet
     * @param relayerAcceptDelayMs how old a deposit's L1 output must be before a relaying non-owner proves it
     * @param reconciliationIntervalMs the reconciliation pace (D15), 0 when disabled
     * @param reconciliationReport     where each reconciliation pass writes its JSON report, if anywhere
     */
    record Tasks(long acceptIntervalMs, int acceptBatch, int acceptProvedBatch, boolean exitRelayer,
                 long exitRelayIntervalMs, Optional<byte[]> relayerSeed, long relayerAcceptDelayMs,
                 long reconciliationIntervalMs, Optional<Path> reconciliationReport) {
    }

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
            if (key.startsWith("domain.") || key.equals(AssetVaultShardSettings.SHARD_BACKEND)
                    || key.equals(AssetVaultShardSettings.PROGRAMMABLE_BACKEND)) {
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
        byte[] depositPrefix = optional(config, "deposit-key-prefix", "ap/deposit/")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] statusKey = optional(config, "domain-status-key", "ap/domain-status").getBytes(StandardCharsets.US_ASCII);
        byte[] exitPrefix = optional(config, "exit-key-prefix", "ap/x/").getBytes(StandardCharsets.US_ASCII);
        VaultPins.Anchor pinnedAnchor = pinnedAnchor(anchor);
        List<VaultPins> pins = new ArrayList<>();
        Map<Hash32, ScriptRefs> refs = new LinkedHashMap<>();
        int network = vaults.domain().addressNetworkId();
        for (AssetVaultShardSettings.Vault vault : vaults.vaults()) {
            Map<String, String> extra = extras.getOrDefault(vault.index(), Map.of());
            String prefix = "vaults." + vault.index() + ".";
            Optional<VaultPins.Programmable> programmable = Optional.empty();
            if (vault.programmable()) {
                ProgrammableVaultTxAssembler.Deployment deployment = new ProgrammableVaultTxAssembler.Deployment(
                        vault.plbHash().orElseThrow(), hex(required(extra, "cip113.params-policy"), 28,
                        prefix + "cip113.params-policy"), hex(required(extra, "cip113.registry-node-policy"), 28,
                        prefix + "cip113.registry-node-policy"), network,
                        (int) bounded(optional(extra, "cip113.max-inline-datum-bytes", "1024"), 40, 65_536,
                                prefix + "cip113.max-inline-datum-bytes"));
                ProgrammableBackend.Parameters parameters = new ProgrammableBackend.Parameters(
                        vaults.domain().domainId(), vault.asset(), 0, network, pinnedAnchor, vault.shardPolicyId(),
                        exitPolicy, exitHash, claimPrefix, depositPrefix, exitPrefix, statusKey, maxSettle, maxExit,
                        floor, EXIT_DELAY_FLOOR_MS, EXIT_DELAY_CEILING_MS, maxPause, deployment.plbHash(),
                        deployment.maxInlineDatumBytes());
                // D3: the selected backend's h and e for these parameters must be the pinned ones.
                try {
                    vaults.programmableBackend().requireDerived(parameters, vault.scriptHash(),
                            vault.exitDelegateHash().orElseThrow());
                } catch (IllegalArgumentException mismatch) {
                    throw new IllegalArgumentException(prefix + mismatch.getMessage()
                            + " (check programmable-backend and the vault's parameters)", mismatch);
                }
                // D4, V16: every continuation the profile's batches create fits the id cap.
                if (maxSettle > parameters.maxContinuationIds() || maxExit > parameters.maxContinuationIds()) {
                    throw new IllegalArgumentException(prefix + "max-settle-batch and max-exit-batch must be at most "
                            + parameters.maxContinuationIds() + ", the continuation id cap of a CIP-113 vault (D4)");
                }
                List<ResolvedOutput.Outpoint> scripts = new ArrayList<>();
                for (String ref : required(extra, "cip113.reference-scripts").split(",")) {
                    if (!ref.isBlank()) scripts.add(outpoint(ref.trim(), prefix + "cip113.reference-scripts"));
                }
                programmable = Optional.of(new VaultPins.Programmable(deployment,
                        vault.exitDelegateHash().orElseThrow(), vaults.programmableBackend(),
                        new Address(required(extra, "cip113.params-address")).getBytes(),
                        new Address(required(extra, "cip113.registry-address")).getBytes(), scripts,
                        required(extra, "cip113.substandard")));
            } else {
                for (String key : extra.keySet()) {
                    if (key.startsWith("cip113.")) {
                        throw new IllegalArgumentException(prefix + key + " needs " + prefix + "custody = cip113");
                    }
                }
            }
            pins.add(new VaultPins(vault.vaultId(), vault.asset(), network, vault.scriptHash(),
                    L1Tx.addressBytes(vault.address()), vault.shardPolicyId(), vault.shardScriptHash(),
                    new Address(vault.shardAddress()).getBytes(), pinnedAnchor, exitPolicy, exitHash, claimPrefix,
                    depositPrefix, statusKey, floor, maxSettle, maxExit, maxPause, vaults.shardBackend(),
                    programmable));
            refs.put(vault.vaultId(), new ScriptRefs(outpoint(required(extra, "script-ref"), prefix + "script-ref"),
                    outpoint(required(extra, "shard-script-ref"), prefix + "shard-script-ref")));
        }
        for (Integer index : extras.keySet()) {
            if (index < 0 || index >= vaults.vaults().size()) throw new IllegalArgumentException("vaults." + index);
        }
        boolean owner = Boolean.parseBoolean(optional(config, "owner", "false"));
        Optional<byte[]> seed = owner ? Optional.of(readSeed(required(config, "fee-wallet.key-file")))
                : Optional.empty();
        // Every member bounds the validity interval it signs by its own L1 time, so every node needs the clock.
        SlotClock slots = new SlotClock(
                bounded(required(config, "slot.zero-time-ms"), 0, Long.MAX_VALUE / 4, "slot.zero-time-ms"),
                bounded(required(config, "slot.zero-slot"), 0, Long.MAX_VALUE / 4, "slot.zero-slot"),
                bounded(required(config, "slot.length-ms"), 1, 3_600_000, "slot.length-ms"));
        long window = bounded(optional(config, "validity-window-slots", "600"), 10, 86_400, "validity-window-slots");
        boolean relayer = Boolean.parseBoolean(optional(config, "exit-relayer.enabled", "false"));
        // Only the owner and a relayer build: the owner's window must fit what members sign, and every builder's
        // holds last one window, so a pending transaction holds its inputs briefly.
        if ((owner || relayer) && window * slots.slotLengthMs() > MAX_VALIDITY_WINDOW_MS) {
            throw new IllegalArgumentException("validity-window-slots spans more than " + MAX_VALIDITY_WINDOW_MS
                    + " ms, past what members sign");
        }
        Duration inclusion = Duration.ofMillis(bounded(optional(config, "inclusion-timeout-ms", "900000"), 1_000,
                86_400_000L, "inclusion-timeout-ms"));
        Duration stability = Duration.ofMillis(bounded(optional(config, "stability-window-ms", "600000"), 0,
                86_400_000L, "stability-window-ms"));
        if (inclusion.compareTo(stability) <= 0) {
            // A Settle is confirmed only by its committed payout observation, which comes a stability depth after
            // inclusion; a shorter timeout would re-batch claims a stable payout already paid.
            throw new IllegalArgumentException("inclusion-timeout-ms must exceed stability-window-ms");
        }
        Optional<String> relayerKey = Optional.ofNullable(config.get("exit-relayer.fee-wallet.key-file"))
                .filter(value -> !value.isBlank());
        if (owner && relayerKey.isPresent()) {
            throw new IllegalArgumentException("the owner relays with fee-wallet.key-file; remove"
                    + " exit-relayer.fee-wallet.key-file");
        }
        Optional<byte[]> relayerSeed = !owner && relayer
                ? Optional.of(readSeed(relayerKey.orElseThrow(() -> new IllegalArgumentException(
                "a relaying non-owner needs exit-relayer.fee-wallet.key-file"))))
                : Optional.empty();
        long acceptInterval = owner || relayer
                ? bounded(optional(config, "accept.interval-ms", "60000"), 0, 86_400_000L, "accept.interval-ms") : 0;
        Tasks tasks = new Tasks(acceptInterval,
                (int) bounded(optional(config, "accept.max-batch", "16"), 1, AcceptTxBuilder.MAX_COSIGNED,
                        "accept.max-batch"),
                (int) bounded(optional(config, "accept.max-proved-batch", "12"), 1, AcceptTxBuilder.MAX_PROVED,
                        "accept.max-proved-batch"),
                relayer,
                bounded(optional(config, "exit-relayer.interval-ms", "60000"), 1_000, 86_400_000L,
                        "exit-relayer.interval-ms"),
                relayerSeed,
                bounded(optional(config, "accept.relayer-delay-ms", Long.toString(3 * acceptInterval)), 0,
                        7 * 86_400_000L, "accept.relayer-delay-ms"),
                reconciliationInterval(config),
                Optional.ofNullable(config.get("reconciliation.report-file")).map(String::trim)
                        .filter(value -> !value.isEmpty()).map(Path::of));
        Optional<Path> ttlFile = Optional.ofNullable(config.get("pending-ttl-file")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(Path::of);
        return new AssetSettlementSettings(vaults, pins, refs, owner,
                Duration.ofMillis(bounded(optional(config, "round-timeout-ms", "30000"), 100, 600_000,
                        "round-timeout-ms")), seed, slots, window,
                (int) bounded(optional(config, "max-vault-inputs", "8"), 1, 32, "max-vault-inputs"),
                inclusion,
                BigInteger.valueOf(bounded(optional(config, "max-supplement-per-payout", "0"), 0, 100_000_000L,
                        "max-supplement-per-payout")),
                stability, tasks, ttlFile);
    }

    /** The pinned anchor identity of the vault scripts' parameters (D8b), from {@code anchor.*}. */
    static VaultPins.Anchor pinnedAnchor(AssetAnchorSettings anchor) {
        return new VaultPins.Anchor(anchor.threadPolicyId(), anchor.threadAssetName(), anchor.scriptHash(),
                anchor.genesisId(), anchor.applicationId().getBytes(StandardCharsets.UTF_8),
                anchor.commitmentProfile().getBytes(StandardCharsets.US_ASCII), anchor.formatFingerprint());
    }

    private static long reconciliationInterval(Map<String, String> config) {
        long interval = bounded(optional(config, "reconciliation.interval-ms", "300000"), 0, 86_400_000L,
                "reconciliation.interval-ms");
        if (interval > 0 && interval < 1_000) {
            throw new IllegalArgumentException("reconciliation.interval-ms is 0 (off) or at least 1000");
        }
        return interval;
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
