package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Genesis configuration of an {@code asset-payments} chain, read from
 * {@code yano.app-chain.machines.asset-payments.*} (ADR-059 D14). It is consensus configuration:
 * every member must use identical values, and {@link #digest()} is written into state at genesis
 * so a restart with different settings fails at startup instead of diverging (I13).
 *
 * <pre>
 * domain.address-network-id, domain.network-magic, domain.genesis-id     required
 * domain.application-id (asset-payments), domain.profile-id (anchored-claim-vault-v1),
 * domain.profile-version (1)                                              GatewayDomain; chain id is the chain's
 * deposit-observer-id                                                     required; the ~l1/&lt;id&gt; topic credited
 * payout-observer-id                                                      optional; the ~l1/&lt;id&gt; payout topic (D10)
 *                                                                         that confirms claims; not the deposit one
 * exit-delay-ms (86400000)                                                the status leaf's delay, [6 h, 30 d] (D8e)
 * max-intent-ttl-ms (600000)                                              must exceed the forward clock skew
 * min-stability-depth (1, at least 1)                                    the chain's l1.stability-depth must be ≥
 * min-ada-payout (1000000)                                               D7/D11: the smallest lovelace claim (ADA vault,
 *                                                                         or a token vault's credit-only claim)
 * payout-lovelace-cap (2000000)                                          D11: the most min-ADA credit a token claim
 *                                                                         spends as its payout's vaultLovelace
 * settlement.batch-size (16, 1..16)                                      D7: the soft cap that emits a shard's batch
 *                                                                         (the profile's maxSettleBatch is 16), also
 *                                                                         bounded by effects.max-payload-bytes
 * settlement.batch-wait-ms (0)                                           D7: the age that emits a smaller batch; 0
 *                                                                         batches a claim in its reserving block, and
 *                                                                         a non-zero wait needs regular traffic
 * settlement.expiry-blocks (0 = the chain default)                       the batch effect's CHAIN expiry, within
 *                                                                         effects.max-expiry-blocks and the result
 *                                                                         window
 * settlement.min-queued-vault-lovelace (1300000)                         D11: a token claim whose vaultLovelace is
 *                                                                         below this is never batched and stays
 *                                                                         payable by Exit only; the default covers
 *                                                                         the min-UTxO of any single-asset payout
 *                                                                         (base address, 32-byte asset name, u64
 *                                                                         quantity) at 4310 lovelace per byte, the
 *                                                                         payout an executor with the default
 *                                                                         max-supplement-per-payout of 0 can pay
 * retire-after-wind-down-ms (max(1 h, depth x 60 s))                     D14: how long a vault stays in WIND_DOWN, in
 *                                                                         block time, before RETIRED is accepted; it
 *                                                                         covers the L1 stability window (depth x 3
 *                                                                         mean L1 block intervals of 20 s) and app
 *                                                                         finality, so a deposit already on L1 when
 *                                                                         the wind-down began is resolved first; at
 *                                                                         least depth x 20 s, at most 30 d
 * vaults.N.asset, vaults.N.script-hash, vaults.N.address                  the fixed vault set (I29), N = 0, 1, ...
 *                                                                         contiguous; distinct addresses, no other keys
 * vaults.N.status (ACTIVE), vaults.N.ticker (""), vaults.N.decimals (0), vaults.N.active-from-slot (0)
 * </pre>
 *
 * When the chain also configures {@code observers.<deposit-observer-id>.*}, its type must be
 * {@code asset-vault-deposit-v1} and its domain, vault and {@code min-stability-depth} settings must
 * equal these, so the observer and the ledger can never disagree about the domain, the vault set or
 * the stability depth they assume. {@code observers.<payout-observer-id>.*}, when configured, must be
 * {@code asset-vault-payout-v1} with the same domain, vaults and depth.
 */
record AssetPaymentsSettings(String chainId, int addressNetworkId, long networkMagic, Hash32 genesisId,
                             String applicationId, String profileId, long profileVersion, Hash32 domainId,
                             String depositObserverId, Optional<String> payoutObserverId, long maxIntentTtlMs,
                             int minStabilityDepth, long exitDelayMs, List<PaymentsRecords.RegistryEntryV1> vaults,
                             long minAdaPayout, long payoutLovelaceCap, Settlement settlement,
                             long retireAfterWindDownMs, DatumBounds depositDatumBounds,
                             Optional<String> wiringObserverId) {
    /**
     * The deposit observer's inline-datum bounds ({@code observers.<deposit-observer-id>.datum.max-bytes} and
     * {@code datum.max-depth}, defaults 16384 and 16). The observer drops a deposit whose datum exceeds them, so the
     * payments API never hands out a datum outside them. Node-local: not part of the domain or settings digest.
     */
    record DatumBounds(int maxBytes, int maxDepth) {
        static final DatumBounds DEFAULTS = new DatumBounds(16_384, 16);

        DatumBounds {
            if (maxBytes < 1 || maxBytes > 65_536 || maxDepth < 1 || maxDepth > 64) {
                throw new IllegalArgumentException("datum bounds must be 1..65536 bytes and depth 1..64");
            }
        }

        boolean admits(byte[] datum, int depth) {
            return datum.length <= maxBytes && depth <= maxDepth;
        }
    }

    /** D7 step 4: how reserved claims are batched into {@code asset.settlement.batch} effects. */
    record Settlement(int batchSize, long batchWaitMs, long expiryBlocks, long minQueuedVaultLovelace) {
        Settlement {
            if (batchSize < 1 || batchSize > MAX_SETTLE_BATCH || batchWaitMs < 0 || expiryBlocks < 0
                    || minQueuedVaultLovelace < 0) {
                throw new IllegalArgumentException("settlement batching is out of range");
            }
        }
    }

    /** The profile's {@code maxSettleBatch} (BUDGETS.md): a ledger batch never exceeds what one Settle can pay. */
    static final int MAX_SETTLE_BATCH = 16;
    static final String PREFIX = "machines." + AssetPaymentsContract.STATE_MACHINE_ID + ".";
    static final String OBSERVER_TYPE = "asset-vault-deposit-v1";
    static final String PAYOUT_OBSERVER_TYPE = "asset-vault-payout-v1";
    /** D8e: the exit delay's on-chain bounds, [6 h, 30 d]; the default is 24 h. */
    static final long MIN_EXIT_DELAY_MS = 6L * 3_600_000;
    static final long MAX_EXIT_DELAY_MS = 30L * 24 * 3_600_000;
    static final long DEFAULT_EXIT_DELAY_MS = 24L * 3_600_000;
    static final String WIRING_OBSERVER_TYPE = "asset-cip113-wiring-v1";
    /** The mean Cardano L1 block interval; the retirement window is measured in multiples of it per depth. */
    static final long L1_BLOCK_INTERVAL_MS = 20_000;

    /** D14: the default retirement window, three mean L1 block intervals per depth and never under an hour. */
    static long defaultRetireAfterWindDownMs(int minStabilityDepth) {
        return Math.max(3_600_000L, minStabilityDepth * 3 * L1_BLOCK_INTERVAL_MS);
    }

    private static final Set<String> VAULT_SETTINGS = Set.of("asset", "script-hash", "address", "status", "ticker",
            "decimals", "active-from-slot", "custody", "cip113.bootstrap-tx", "cip113.params-policy", "cip113.plb-hash",
            "cip113.registry-node-policy", "cip113.max-inline-datum-bytes", "cip113.backend",
            "cip113.exit-delegate-hash", "cip113.substandard", "cip113.substandard-version", "cip113.params-outpoint",
            "cip113.dispatcher", "cip113.issuance-logic", "cip113.transfer", "cip113.third-party",
            "cip113.transfer-delegate", "cip113.third-party-delegate", "cip113.unfracking-delegate",
            "cip113.baseline-transfer-logic", "cip113.baseline-global-state", "cip113.issuance-logic-kind");

    AssetPaymentsSettings {
        vaults = List.copyOf(vaults);
    }

    static AssetPaymentsSettings parse(String chainId, Map<String, String> all) {
        Map<String, String> settings = new LinkedHashMap<>();
        all.forEach((key, value) -> {
            if (key.startsWith(PREFIX)) settings.put(key.substring(PREFIX.length()), value);
        });
        int networkId = (int) bounded(required(settings, "domain.address-network-id"), 0, 15,
                "domain.address-network-id");
        long magic = bounded(required(settings, "domain.network-magic"), 0, 0xFFFF_FFFFL, "domain.network-magic");
        Hash32 genesis = Hash32.of(hex(required(settings, "domain.genesis-id"), 32, "domain.genesis-id"));
        String applicationId = optional(settings, "domain.application-id", "asset-payments");
        String profileId = optional(settings, "domain.profile-id", "anchored-claim-vault-v1");
        long profileVersion = bounded(optional(settings, "domain.profile-version", "1"), 0, Long.MAX_VALUE,
                "domain.profile-version");
        Hash32 domainId = GatewayIds.domainId(networkId, magic, chainId, genesis, applicationId, profileId,
                profileVersion);
        String observerId = required(settings, "deposit-observer-id");
        String payoutId = optional(settings, "payout-observer-id", "");
        if (payoutId.equals(observerId)) {
            throw new IllegalArgumentException(PREFIX + "payout-observer-id must differ from deposit-observer-id");
        }
        long exitDelay = bounded(optional(settings, "exit-delay-ms", String.valueOf(DEFAULT_EXIT_DELAY_MS)),
                MIN_EXIT_DELAY_MS, MAX_EXIT_DELAY_MS, "exit-delay-ms");
        long ttl = bounded(optional(settings, "max-intent-ttl-ms", "600000"), 1, 7L * 24 * 3_600_000,
                "max-intent-ttl-ms");
        // Depth 0 would credit deposits from blocks that can still roll back (ADR-059 D5).
        int minDepth = (int) bounded(optional(settings, "min-stability-depth", "1"), 1, 100_000,
                "min-stability-depth");
        long retireAfter = bounded(optional(settings, "retire-after-wind-down-ms",
                        String.valueOf(defaultRetireAfterWindDownMs(minDepth))),
                minDepth * L1_BLOCK_INTERVAL_MS, MAX_EXIT_DELAY_MS, "retire-after-wind-down-ms");
        long minAdaPayout = bounded(optional(settings, "min-ada-payout", "1000000"), 1, 1_000_000_000_000L,
                "min-ada-payout");
        long payoutLovelaceCap = bounded(optional(settings, "payout-lovelace-cap", "2000000"), 0,
                1_000_000_000_000L, "payout-lovelace-cap");
        Settlement settlement = new Settlement(
                (int) bounded(optional(settings, "settlement.batch-size", "16"), 1, MAX_SETTLE_BATCH,
                        "settlement.batch-size"),
                bounded(optional(settings, "settlement.batch-wait-ms", "0"), 0, 7L * 24 * 3_600_000,
                        "settlement.batch-wait-ms"),
                bounded(optional(settings, "settlement.expiry-blocks", "0"), 0, 1_000_000, "settlement.expiry-blocks"),
                bounded(optional(settings, "settlement.min-queued-vault-lovelace", "1300000"), 0, 1_000_000_000_000L,
                        "settlement.min-queued-vault-lovelace"));

        List<PaymentsRecords.RegistryEntryV1> vaults = new ArrayList<>();
        Set<AssetId> assets = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        Set<String> scriptHashes = new HashSet<>();
        for (int index = 0; settings.containsKey("vaults." + index + ".asset"); index++) {
            if (index >= AssetPaymentsContract.MAX_VAULTS) throw new IllegalArgumentException("at most 16 vaults");
            String prefix = "vaults." + index + ".";
            AssetId asset = asset(required(settings, prefix + "asset"), prefix + "asset");
            if (!assets.add(asset)) throw new IllegalArgumentException("v1 allows one vault per asset (I29)");
            String address = required(settings, prefix + "address");
            if (address.length() > 128 || !address.startsWith("addr")) {
                throw new IllegalArgumentException(prefix + "address must be a bech32 Shelley address");
            }
            if (!addresses.add(address)) throw new IllegalArgumentException("vault addresses must be distinct");
            byte[] scriptHash = hex(required(settings, prefix + "script-hash"), 28, prefix + "script-hash");
            if (!scriptHashes.add(HexFormat.of().formatHex(scriptHash))) {
                throw new IllegalArgumentException("vault script hashes must be distinct");
            }
            PaymentsRecords.AssetStatus status;
            try {
                status = PaymentsRecords.AssetStatus.valueOf(optional(settings, prefix + "status", "ACTIVE"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(prefix + "status is not a registry status", invalid);
            }
            Optional<PaymentsRecords.Cip113Custody> custody = custody(settings, prefix, asset);
            vaults.add(new PaymentsRecords.RegistryEntryV1(GatewayIds.vaultId(domainId, asset, 0), asset,
                    scriptHash, address, status,
                    bounded(optional(settings, prefix + "active-from-slot", "0"), 0, Long.MAX_VALUE,
                            prefix + "active-from-slot"),
                    0, optional(settings, prefix + "ticker", ""),
                    (int) bounded(optional(settings, prefix + "decimals", "0"), 0, 19, prefix + "decimals"),
                    custody));
        }
        if (vaults.isEmpty()) throw new IllegalArgumentException(PREFIX + "vaults.0.* is required");
        // A gapped index (vaults.0, vaults.2) or a misspelt suffix would otherwise be silently ignored.
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
            if (index < 0 || index >= vaults.size() || !VAULT_SETTINGS.contains(rest.substring(dot + 1))) {
                throw new IllegalArgumentException("unknown vault setting " + PREFIX + key);
            }
        }
        boolean programmable = vaults.stream().anyMatch(vault -> vault.custody().isPresent());
        requireOneDeployment(vaults);
        Optional<String> wiringId = programmable
                ? Optional.of(required(settings, "cip113.wiring-observer-id")) : Optional.empty();
        // I48: every wiring observation of a block is recorded before its deposits are admitted, which the
        // canonical order of observations by observer id gives only when the wiring observer sorts first.
        if (wiringId.isPresent() && wiringId.get().compareTo(observerId) >= 0) {
            throw new IllegalArgumentException(PREFIX + "cip113.wiring-observer-id must sort before "
                    + "deposit-observer-id (ADR-061 I48)");
        }
        AssetPaymentsSettings parsed = new AssetPaymentsSettings(chainId, networkId, magic, genesis, applicationId,
                profileId, profileVersion, domainId, observerId,
                payoutId.isEmpty() ? Optional.empty() : Optional.of(payoutId), ttl, minDepth, exitDelay, vaults,
                minAdaPayout, payoutLovelaceCap, settlement, retireAfter, datumBounds(all, observerId), wiringId);
        parsed.checkObserver(all, observerId, OBSERVER_TYPE);
        wiringId.ifPresent(id -> parsed.checkObserver(all, id, WIRING_OBSERVER_TYPE));
        parsed.payoutObserverId.ifPresent(id -> parsed.checkObserver(all, id, PAYOUT_OBSERVER_TYPE));
        return parsed;
    }

    /**
     * ADR-061 D1, D12: a vault's custody, {@code native} (the default) or {@code cip113} with the genesis-fixed pins
     * of the deployment, the custody, the substandard, wiring version 0 and the registry-node baseline.
     */
    private static Optional<PaymentsRecords.Cip113Custody> custody(Map<String, String> settings, String prefix,
                                                                   AssetId asset) {
        String kind = optional(settings, prefix + "custody", "native");
        if (kind.equals("native")) {
            for (String key : settings.keySet()) {
                if (key.startsWith(prefix + "cip113.")) {
                    throw new IllegalArgumentException(PREFIX + key + " needs " + prefix + "custody = cip113");
                }
            }
            return Optional.empty();
        }
        if (!kind.equals("cip113")) throw new IllegalArgumentException(prefix + "custody must be native or cip113");
        if (asset.isAda()) throw new IllegalArgumentException(prefix + "a CIP-113 vault holds a native asset");
        String c = prefix + "cip113.";
        String outpoint = required(settings, c + "params-outpoint");
        int hash = outpoint.indexOf('#');
        if (hash != 64) throw new IllegalArgumentException(c + "params-outpoint must be <tx id hex>#<index>");
        return Optional.of(new PaymentsRecords.Cip113Custody(hex(required(settings, c + "bootstrap-tx"), 32,
                c + "bootstrap-tx"), hex(required(settings, c + "params-policy"), 28, c + "params-policy"),
                hex(required(settings, c + "plb-hash"), 28, c + "plb-hash"),
                hex(required(settings, c + "registry-node-policy"), 28, c + "registry-node-policy"),
                (int) bounded(optional(settings, c + "max-inline-datum-bytes", "1024"), 40, 65_536,
                        c + "max-inline-datum-bytes"),
                optional(settings, c + "backend", "julc"),
                hex(required(settings, c + "exit-delegate-hash"), 28, c + "exit-delegate-hash"),
                required(settings, c + "substandard"),
                (int) bounded(optional(settings, c + "substandard-version", "1"), 0, Integer.MAX_VALUE,
                        c + "substandard-version"),
                Hash32.of(hex(outpoint.substring(0, 64), 32, c + "params-outpoint")),
                (int) bounded(outpoint.substring(65), 0, 65_535, c + "params-outpoint"),
                hex(required(settings, c + "dispatcher"), 28, c + "dispatcher"),
                hex(required(settings, c + "issuance-logic"), 28, c + "issuance-logic"),
                hex(required(settings, c + "transfer"), 28, c + "transfer"),
                hex(required(settings, c + "third-party"), 28, c + "third-party"),
                hex(required(settings, c + "transfer-delegate"), 28, c + "transfer-delegate"),
                hex(required(settings, c + "third-party-delegate"), 28, c + "third-party-delegate"),
                hex(optional(settings, c + "unfracking-delegate", "00".repeat(28)), 28, c + "unfracking-delegate"),
                hex(required(settings, c + "baseline-transfer-logic"), 28, c + "baseline-transfer-logic"),
                HexFormat.of().parseHex(optional(settings, c + "baseline-global-state", "")),
                switch (optional(settings, c + "issuance-logic-kind", "script")) {
                    case "script" -> 0;
                    // The pinned params validator accepts a key credential in field 1 (ADR-061 r5).
                    case "key" -> 0b0010;
                    default -> throw new IllegalArgumentException(c + "issuance-logic-kind must be script or key");
                }));
    }

    /**
     * ADR-061 D6, D12 (r5): every CIP-113 vault of a domain is on one deployment, so the one wiring observer's
     * {@code params-policy} records the wiring of exactly that deployment, and the vaults agree on its wiring version
     * 0 and delegates, and on one custody backend (the observers' {@code programmable-backend}).
     */
    private static void requireOneDeployment(List<PaymentsRecords.RegistryEntryV1> vaults) {
        PaymentsRecords.Cip113Custody first = null;
        for (PaymentsRecords.RegistryEntryV1 vault : vaults) {
            if (vault.custody().isEmpty()) continue;
            PaymentsRecords.Cip113Custody custody = vault.custody().get();
            if (first == null) {
                first = custody;
                continue;
            }
            if (!Arrays.equals(first.paramsPolicy(), custody.paramsPolicy())
                    || !Arrays.equals(first.plbHash(), custody.plbHash())) {
                throw new IllegalArgumentException(PREFIX + "every CIP-113 vault of a domain is on one deployment"
                        + " (one params policy and PLB hash)");
            }
            if (!first.backend().equals(custody.backend())) {
                throw new IllegalArgumentException(PREFIX + "every CIP-113 vault of a domain has one custody backend");
            }
            boolean sameWiring0 = first.params0TxId().equals(custody.params0TxId())
                    && first.params0Index() == custody.params0Index() && first.wiring0().equals(custody.wiring0())
                    && Arrays.equals(first.transferDelegate(), custody.transferDelegate())
                    && Arrays.equals(first.thirdPartyDelegate(), custody.thirdPartyDelegate())
                    && Arrays.equals(first.unfrackingDelegate(), custody.unfrackingDelegate());
            if (!sameWiring0) {
                throw new IllegalArgumentException(PREFIX + "the CIP-113 vaults of one deployment pin different"
                        + " wiring version 0 or delegate hashes");
            }
        }
    }

    /** ADR-061: the params policy of the domain's one CIP-113 deployment, when it has CIP-113 vaults. */
    Optional<byte[]> cip113ParamsPolicy() {
        return vaults.stream().flatMap(vault -> vault.custody().stream()).findFirst()
                .map(PaymentsRecords.Cip113Custody::paramsPolicy);
    }

    /** The CIP-113 wiring topic, when a CIP-113 vault is registered. */
    Optional<String> wiringTopic() {
        return wiringObserverId.map(id -> "~l1/" + id);
    }

    IntentEnvelopeV1.Domain intentDomain() {
        return new IntentEnvelopeV1.Domain(networkMagic, addressNetworkId, chainId, genesisId, applicationId);
    }

    String depositTopic() {
        return "~l1/" + depositObserverId;
    }

    /** The payout confirmation topic, or empty when no payout observer is configured. */
    Optional<String> payoutTopic() {
        return payoutObserverId.map(id -> "~l1/" + id);
    }

    PaymentsRecords.DomainRecordV1 domainRecord() {
        return new PaymentsRecords.DomainRecordV1(domainId, addressNetworkId, networkMagic, chainId, genesisId,
                applicationId, profileId, profileVersion, digest());
    }

    /** {@code blake2b-256} of every consensus value, length-delimited, in a fixed order. */
    Hash32 digest() {
        List<String> fields = new ArrayList<>(List.of(
                "chain-id=" + chainId, "domain-id=" + domainId.hex(), "deposit-observer-id=" + depositObserverId,
                "max-intent-ttl-ms=" + maxIntentTtlMs, "min-stability-depth=" + minStabilityDepth,
                "payout-observer-id=" + payoutObserverId.orElse(""), "exit-delay-ms=" + exitDelayMs,
                "min-ada-payout=" + minAdaPayout, "payout-lovelace-cap=" + payoutLovelaceCap,
                "settlement.batch-size=" + settlement.batchSize(),
                "settlement.batch-wait-ms=" + settlement.batchWaitMs(),
                "settlement.expiry-blocks=" + settlement.expiryBlocks(),
                "settlement.min-queued-vault-lovelace=" + settlement.minQueuedVaultLovelace(),
                "retire-after-wind-down-ms=" + retireAfterWindDownMs));
        // ADR-061 (r5): the wiring observer whose observations the ledger records is part of a CIP-113 domain's
        // configuration; a native domain's digest is unchanged.
        wiringObserverId.ifPresent(id -> fields.add("cip113.wiring-observer-id=" + id));
        for (PaymentsRecords.RegistryEntryV1 vault : vaults) fields.add("vault=" + HexFormat.of().formatHex(vault.encode()));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(fields.size());
                for (String field : fields) {
                    byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                }
            }
            return Hash32.of(Blake2bUtil.blake2bHash256(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** The deposit observer's datum bounds, as {@code AssetVaultDepositSettings} reads them, or the defaults. */
    static DatumBounds datumBounds(Map<String, String> all, String observerId) {
        String observer = "observers." + observerId + ".";
        String bytes = all.get(observer + "datum.max-bytes");
        String depth = all.get(observer + "datum.max-depth");
        return new DatumBounds(
                (int) bounded(bytes == null || bytes.isBlank() ? String.valueOf(DatumBounds.DEFAULTS.maxBytes())
                        : bytes.trim(), 1, 65_536, observer + "datum.max-bytes"),
                (int) bounded(depth == null || depth.isBlank() ? String.valueOf(DatumBounds.DEFAULTS.maxDepth())
                        : depth.trim(), 1, 64, observer + "datum.max-depth"));
    }

    /** An observer this ledger reads and this ledger must describe the same domain and vault set. */
    private void checkObserver(Map<String, String> all, String observerId, String type) {
        String observer = "observers." + observerId + ".";
        if (!all.containsKey(observer + "type")) return;
        if (!type.equals(all.get(observer + "type").trim())) {
            throw new IllegalArgumentException(observer + "type must be " + type);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("domain.address-network-id", String.valueOf(addressNetworkId));
        expected.put("domain.network-magic", String.valueOf(networkMagic));
        expected.put("domain.chain-id", chainId);
        expected.put("domain.genesis-id", genesisId.hex());
        expected.put("domain.application-id", applicationId);
        expected.put("domain.profile-id", profileId);
        expected.put("domain.profile-version", String.valueOf(profileVersion));
        // The observer's depth is part of its consensus identity; it must be the depth this ledger enforces.
        expected.put("min-stability-depth", String.valueOf(minStabilityDepth));
        // ADR-061 D6: the wiring observer pins only the deployment's params policy, never the vaults or the wiring.
        boolean wiring = type.equals(WIRING_OBSERVER_TYPE);
        if (wiring) {
            // r5: the wiring observer reports the outputs of exactly this domain's deployment.
            cip113ParamsPolicy().ifPresent(policy -> expected.put("params-policy", HexFormat.of().formatHex(policy)));
        }
        for (int index = 0; !wiring && index < vaults.size(); index++) {
            PaymentsRecords.RegistryEntryV1 vault = vaults.get(index);
            expected.put("vaults." + index + ".asset", vault.asset().toString());
            expected.put("vaults." + index + ".script-hash", HexFormat.of().formatHex(vault.scriptHash()));
            expected.put("vaults." + index + ".address", vault.vaultAddress());
            String v = "vaults." + index + ".";
            String c = v + "cip113.";
            vault.custody().ifPresent(custody -> {
                // ADR-061 D3: the observers pin the same custody: deployment, e and backend.
                expected.put(v + "custody", "cip113");
                expected.put(c + "plb-hash", HexFormat.of().formatHex(custody.plbHash()));
                expected.put(c + "exit-delegate-hash", HexFormat.of().formatHex(custody.exitDelegateHash()));
                if (type.equals(OBSERVER_TYPE)) {
                    expected.put(c + "params-policy", HexFormat.of().formatHex(custody.paramsPolicy()));
                }
                expected.put("programmable-backend", custody.backend());
            });
        }
        Map<String, String> defaults = Map.of("domain.application-id", "asset-payments",
                "domain.profile-id", "anchored-claim-vault-v1", "domain.profile-version", "1",
                "min-stability-depth", "1", "programmable-backend", "julc");
        expected.forEach((key, value) -> {
            String actual = all.get(observer + key);
            actual = actual == null || actual.isBlank() ? defaults.get(key) : actual.trim();
            if (!value.equals(actual)) {
                throw new IllegalArgumentException(observer + key + " differs from " + PREFIX + key);
            }
        });
        if (!wiring && all.containsKey(observer + "vaults." + vaults.size() + ".asset")) {
            throw new IllegalArgumentException(observer + "vaults lists more vaults than the ledger registry");
        }
    }

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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(PREFIX + key + " is required");
        return value.trim();
    }

    private static String optional(Map<String, String> settings, String key, String fallback) {
        String value = settings.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long bounded(String value, long min, long max, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(PREFIX + field + " is out of range");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(PREFIX + field + " must be an integer", invalid);
        }
    }

    private static byte[] hex(String value, int bytes, String field) {
        if (value.length() != bytes * 2 || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(PREFIX + field + " must be " + bytes + "-byte lowercase hex");
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(PREFIX + field + " must be " + bytes + "-byte lowercase hex", invalid);
        }
    }
}
