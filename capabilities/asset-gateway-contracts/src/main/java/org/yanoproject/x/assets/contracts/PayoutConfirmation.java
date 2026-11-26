package org.yanoproject.x.assets.contracts;

import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatus;
import org.yanoproject.x.assets.contracts.SettlementRecords.ClaimStatusV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.EscapeRedemptionV1;
import org.yanoproject.x.assets.contracts.SettlementRecords.PayoutRecordV1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Payout confirmation for a consuming state machine (ADR-059 D10, I2, I12, I18). Only a finalized
 * {@code asset-vault-payout-v1} observation confirms a claim; effect results, operator commands and the executor
 * never do.
 *
 * <p>The consumer calls {@link #apply} from its own {@code apply()} for each finalized {@code ~l1/<observerId>}
 * observation, over a store that reads its own staged writes. Confirmations are keyed by
 * {@code (GatewayDomain, L1 tx id, ClaimId)}. For each claim of a {@link PayoutObservationV1}:
 * <ul>
 *   <li><b>{@code RESERVED}, script-enforced fields match</b> (the claim id, its vault, the recipient, the exact
 *       vault-asset quantity and lovelace {@code ≥ vaultLovelace}; for the ADA vault, lovelace {@code ≥} the claimed
 *       amount): the claim becomes {@code PAID} and, for a user withdrawal, {@code paid} grows by the claim's amount
 *       and {@code vaultLovelace} through {@link AssetAccounts#consumeReserved}, never by the output's lovelace;</li>
 *   <li><b>already {@code PAID}</b> (a replay of another transaction, or a re-payment after a rolled-back payout):
 *       a recorded no-op;</li>
 *   <li><b>a mismatch on a script-enforced field</b>, or a claim id this ledger never reserved: {@code PAID_MISMATCH},
 *       and the domain status leaf moves to {@code HALTED}. The incident procedure (D15) resolves the claim by
 *       governance; its reserved value stays reserved.</li>
 * </ul>
 * Fields the script does not enforce, such as lovelace a builder added, are never compared.
 *
 * <p>The whole observation is validated before anything is written: an envelope that does not match, another domain,
 * or a vault outside the consumer's registry writes nothing and returns {@link Disposition#IGNORED}; a redelivered
 * observation finds its records and returns {@link Disposition#DUPLICATE}. Nothing about L1 content makes it throw,
 * and only a thread-authenticated mismatch halts (I2).
 *
 * <p><b>Balance exits.</b> A {@link BalanceExitObservationV1} records its escape nullifier as redeemed
 * ({@code escape/<nullifier>}, write-once) and never touches a claim.
 *
 * <p><b>Rollback</b> (D10, A-F11). A rolled-back confirmed payout leaves the ledger {@code PAID}: the leaf is
 * immutable and still anchored, so anyone can pay it again, and that second confirmation is a recorded no-op, leaving
 * exactly one payout on L1. Until then reconciliation shows a surplus equal to the claim (D15). Nothing here
 * compensates.
 *
 * <p><b>Layout</b>, under the consumer's namespace {@code prefix}:
 * <pre>
 * prefix ‖ "claim/" ‖ ClaimId                           ClaimLeafV1     immutable (claimKeyPrefix = prefix ‖ "claim/")
 * prefix ‖ "claim-status/" ‖ ClaimId                    ClaimStatusV1
 * prefix ‖ "payout/" ‖ GatewayDomain ‖ txId ‖ ClaimId   PayoutRecordV1  write-once
 * prefix ‖ "escape/" ‖ escapeNullifier                  EscapeRedemptionV1
 * prefix ‖ "domain-status"                              DomainStatusV1  the vault's pinned status key
 * </pre>
 */
public final class PayoutConfirmation {
    private static final byte[] CLAIM_SEGMENT = ascii("claim/");
    private static final byte[] STATUS_SEGMENT = ascii("claim-status/");
    private static final byte[] PAYOUT_SEGMENT = ascii("payout/");
    private static final byte[] ESCAPE_SEGMENT = ascii("escape/");
    private static final byte[] DOMAIN_STATUS_SEGMENT = ascii("domain-status");
    private static final int MAX_PREFIX_BYTES = 64;

    /** What one confirmation did to its claim. The code is the wire value of {@link PayoutRecordV1}. */
    public enum Outcome {
        PAID(0),
        NO_OP(1),
        PAID_MISMATCH(2);

        private final int code;

        Outcome(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Outcome fromCode(long code) {
            for (Outcome outcome : values()) if (outcome.code == code) return outcome;
            throw new IllegalArgumentException("unknown payout outcome " + code);
        }
    }

    /** Why. The code is the wire value of {@link PayoutRecordV1}. */
    public enum Reason {
        NONE(0),
        ALREADY_PAID(1),
        ALREADY_MISMATCHED(2),
        /** The claim id has no claim record in this ledger. */
        UNKNOWN_CLAIM(3),
        /** The claim's leaf names another vault (V2 vault binding). */
        WRONG_VAULT(4),
        RECIPIENT(5),
        QUANTITY(6),
        LOVELACE(7),
        /** The claim record or leaf does not decode, or the reserved counters cannot cover the payout. */
        LEDGER_INCONSISTENT(8);

        private final int code;

        Reason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Reason fromCode(long code) {
            for (Reason reason : values()) if (reason.code == code) return reason;
            throw new IllegalArgumentException("unknown payout reason " + code);
        }
    }

    /** The framework-neutral envelope of one sequenced observation. */
    public record ObservedPayout(String observerId, byte[] anchorTxId, long eventOrdinal, long slot, byte[] blockHash,
                                 byte[] claim) {
        public ObservedPayout {
            anchorTxId = anchorTxId == null ? null : anchorTxId.clone();
            blockHash = blockHash == null ? null : blockHash.clone();
            claim = claim == null ? null : claim.clone();
        }

        @Override
        public byte[] anchorTxId() {
            return anchorTxId == null ? null : anchorTxId.clone();
        }

        @Override
        public byte[] blockHash() {
            return blockHash == null ? null : blockHash.clone();
        }

        @Override
        public byte[] claim() {
            return claim == null ? null : claim.clone();
        }
    }

    /** A vault of the consumer's registry: its id and asset. */
    public record VaultBinding(Hash32 vaultId, AssetId asset) {
        public VaultBinding {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
        }
    }

    /** The consumer's asset registry, read through its staged writer. */
    @FunctionalInterface
    public interface Registry {
        Optional<VaultBinding> vault(Hash32 vaultId);
    }

    public enum Disposition {
        /** Records were written (some claims may have been duplicates). */
        RECORDED,
        /** Every claim (or the balance exit) was already recorded for this transaction; nothing was written. */
        DUPLICATE,
        /** The envelope, the domain or the vault did not match; nothing was written. */
        IGNORED
    }

    /** One claim's result. {@code leaf} is present when the claim's leaf decoded. */
    public record ClaimResult(Hash32 claimId, Outcome outcome, Reason reason, Optional<ClaimLeafV1> leaf) {
    }

    /**
     * A governed claim that became {@code PAID}. Its reserved value sits in the consumer's separate governed counters
     * (D8d), which the consumer must settle in the same transition; user withdrawals are settled here.
     */
    public record GovernedPayout(ClaimLeafV1 leaf) {
    }

    public record Result(Disposition disposition, List<ClaimResult> claims, List<GovernedPayout> governed,
                         boolean halted, String detail) {
        public Result {
            claims = List.copyOf(claims);
            governed = List.copyOf(governed);
        }

        static Result of(Disposition disposition, String detail) {
            return new Result(disposition, List.of(), List.of(), false, detail);
        }
    }

    private final byte[] prefix;
    private final String observerId;
    private final Hash32 domainId;
    private final long defaultExitDelayMs;
    private final Registry registry;

    /**
     * @param defaultExitDelayMs the exit delay to write into a status leaf the consumer has not created yet, when a
     *                           mismatch halts the domain
     */
    public PayoutConfirmation(byte[] prefix, String observerId, Hash32 domainId, long defaultExitDelayMs,
                              Registry registry) {
        if (prefix == null || prefix.length == 0 || prefix.length > MAX_PREFIX_BYTES) {
            throw new IllegalArgumentException("prefix must be 1-64 bytes");
        }
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("observer id is required");
        if (defaultExitDelayMs < 0) throw new IllegalArgumentException("exit delay is non-negative");
        this.prefix = prefix.clone();
        this.observerId = observerId;
        this.domainId = Objects.requireNonNull(domainId, "domain id");
        this.defaultExitDelayMs = defaultExitDelayMs;
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    // ------------------------------------------------------------------ layout

    /** The claim leaf's key: the vault script's {@code claimKeyPrefix} is {@code prefix ‖ "claim/"} (V2). */
    public byte[] claimLeafKey(Hash32 claimId) {
        return concat(prefix, CLAIM_SEGMENT, claimId.bytes());
    }

    public byte[] claimKeyPrefix() {
        return concat(prefix, CLAIM_SEGMENT);
    }

    public byte[] claimStatusKey(Hash32 claimId) {
        return concat(prefix, STATUS_SEGMENT, claimId.bytes());
    }

    public byte[] payoutRecordKey(Hash32 txId, Hash32 claimId) {
        return concat(prefix, PAYOUT_SEGMENT, domainId.bytes(), txId.bytes(), claimId.bytes());
    }

    public byte[] escapeKey(Hash32 escapeNullifier) {
        return concat(prefix, ESCAPE_SEGMENT, escapeNullifier.bytes());
    }

    /** The domain status leaf's key, the vault script's pinned {@code domainStatusKey} (D8a). */
    public byte[] domainStatusKey() {
        return concat(prefix, DOMAIN_STATUS_SEGMENT);
    }

    /**
     * Writes the two records of a new reservation (D7 step 3): the immutable leaf at {@link #claimLeafKey} and a
     * {@code RESERVED} status. The reserving transition owns feasibility and the balance move.
     */
    public void writeReserved(AssetAccounts.Store store, ClaimLeafV1 leaf) {
        if (store.get(claimLeafKey(leaf.claimId())).isPresent()) {
            throw new IllegalStateException("claim " + leaf.claimId() + " already exists");
        }
        store.put(claimLeafKey(leaf.claimId()), leaf.encode());
        store.put(claimStatusKey(leaf.claimId()),
                ClaimStatusV1.reserved(leaf.claimId(), leaf.reservedHeight()).encode());
    }

    public Optional<ClaimStatusV1> claimStatus(AssetAccounts.Store store, Hash32 claimId) {
        return store.get(claimStatusKey(claimId)).map(ClaimStatusV1::decode);
    }

    public Optional<DomainStatusV1> domainStatus(AssetAccounts.Store store) {
        return store.get(domainStatusKey()).map(DomainStatusV1::decode);
    }

    // ------------------------------------------------------------------ apply

    /** Applies one finalized payout or balance-exit observation. See the class documentation. */
    public Result apply(AssetAccounts.Store store, ObservedPayout observed, long height, long blockTimeMs) {
        Objects.requireNonNull(store, "store");
        if (height < 0 || blockTimeMs < 0) throw new IllegalArgumentException("height and time are non-negative");
        if (observed == null || observed.claim() == null) return Result.of(Disposition.IGNORED, "no observation");
        if (!observerId.equals(observed.observerId())) return Result.of(Disposition.IGNORED, "another observer");
        PayoutClaimV1 claim;
        try {
            claim = PayoutClaimV1.decode(observed.claim());
        } catch (IllegalArgumentException | ArithmeticException undecodable) {
            return Result.of(Disposition.IGNORED, "claim does not decode");
        }
        if (observed.anchorTxId() == null || !Arrays.equals(observed.anchorTxId(), claim.txId().bytes())) {
            return Result.of(Disposition.IGNORED, "anchor differs from the claim's transaction");
        }
        if (observed.eventOrdinal() != claim.shardOutputIndex()) {
            return Result.of(Disposition.IGNORED, "event ordinal differs from the shard output index");
        }
        if (observed.slot() != claim.slot() || observed.blockHash() == null
                || !Arrays.equals(observed.blockHash(), claim.blockHash().bytes())) {
            return Result.of(Disposition.IGNORED, "slot or block hash differs from the claim");
        }
        if (!domainId.equals(claim.domainId())) return Result.of(Disposition.IGNORED, "another domain");
        Optional<VaultBinding> vault = registry.vault(claim.vaultId());
        if (vault.isEmpty()) return Result.of(Disposition.IGNORED, "vault is not in the registry");
        return switch (claim) {
            case PayoutObservationV1 payout -> confirm(store, payout, vault.get(), height, blockTimeMs);
            case BalanceExitObservationV1 exit -> redeem(store, exit, height);
        };
    }

    private record Planned(Hash32 claimId, Outcome outcome, Reason reason, Optional<ClaimLeafV1> leaf,
                           Optional<ClaimStatusV1> status) {
    }

    private Result confirm(AssetAccounts.Store store, PayoutObservationV1 payout, VaultBinding vault, long height,
                           long blockTimeMs) {
        // Plan every claim from the current state before writing anything.
        List<Planned> plan = new ArrayList<>();
        for (int i = 0; i < payout.claimIds().size(); i++) {
            Hash32 claimId = payout.claimIds().get(i);
            if (store.get(payoutRecordKey(payout.txId(), claimId)).isPresent()) continue; // redelivered
            plan.add(plan(store, claimId, payout.payouts().get(i), vault));
        }
        if (plan.isEmpty()) return Result.of(Disposition.DUPLICATE, "already confirmed");

        // A user withdrawal whose leaf pays nothing of this vault's asset and no lovelace cannot be settled against
        // the reserved counters (consumeReserved refuses it): the ledger is inconsistent, which halts.
        List<Planned> checked = new ArrayList<>();
        for (Planned planned : plan) {
            boolean empty = planned.outcome() == Outcome.PAID && !planned.leaf().orElseThrow().kind().governed()
                    && claimAmount(planned.leaf().get(), vault).signum() == 0
                    && planned.leaf().get().vaultLovelace().signum() == 0;
            checked.add(empty ? new Planned(planned.claimId(), Outcome.PAID_MISMATCH, Reason.LEDGER_INCONSISTENT,
                    planned.leaf(), planned.status()) : planned);
        }
        plan = checked;

        // The reserved counters must cover every user withdrawal this observation pays, and the paid counters must
        // stay within u128; anything else means the ledger is inconsistent, which halts rather than throwing midway.
        AssetAccounts accounts = new AssetAccounts(prefix, store);
        BigInteger amount = BigInteger.ZERO;
        BigInteger lovelace = BigInteger.ZERO;
        for (Planned planned : plan) {
            if (planned.outcome() == Outcome.PAID && !planned.leaf().orElseThrow().kind().governed()) {
                amount = amount.add(claimAmount(planned.leaf().get(), vault));
                lovelace = lovelace.add(planned.leaf().get().vaultLovelace());
            }
        }
        boolean covered;
        try {
            AssetAccounts.VaultCounters counters = accounts.counters(vault.vaultId());
            covered = counters.reserved().compareTo(amount) >= 0
                    && counters.lovelaceReserved().compareTo(lovelace) >= 0
                    && counters.paid().add(amount).compareTo(Quantities.MAX_U128) <= 0
                    && counters.lovelacePaid().add(lovelace).compareTo(Quantities.MAX_U128) <= 0;
        } catch (IllegalArgumentException undecodable) {
            covered = false;
        }
        if (!covered) {
            List<Planned> inconsistent = new ArrayList<>();
            for (Planned planned : plan) {
                inconsistent.add(planned.outcome() == Outcome.PAID && !planned.leaf().orElseThrow().kind().governed()
                        ? new Planned(planned.claimId(), Outcome.PAID_MISMATCH, Reason.LEDGER_INCONSISTENT,
                        planned.leaf(), planned.status())
                        : planned);
            }
            plan = inconsistent;
        }

        // Write.
        boolean halt = false;
        List<ClaimResult> results = new ArrayList<>();
        List<GovernedPayout> governed = new ArrayList<>();
        for (Planned planned : plan) {
            Hash32 claimId = planned.claimId();
            switch (planned.outcome()) {
                case PAID -> {
                    ClaimLeafV1 leaf = planned.leaf().orElseThrow();
                    store.put(claimStatusKey(claimId), planned.status().orElseThrow()
                            .resolve(ClaimStatus.PAID, payout.txId(), height).encode());
                    if (leaf.kind().governed()) {
                        governed.add(new GovernedPayout(leaf));
                    } else {
                        accounts.consumeReserved(vault.vaultId(), claimAmount(leaf, vault), leaf.vaultLovelace());
                    }
                }
                case PAID_MISMATCH -> {
                    halt = true;
                    // A RESERVED claim records the mismatch; a PAID one keeps its status (it was paid once).
                    planned.status().filter(status -> status.status() == ClaimStatus.RESERVED).ifPresent(status ->
                            store.put(claimStatusKey(claimId),
                                    status.resolve(ClaimStatus.PAID_MISMATCH, payout.txId(), height).encode()));
                }
                case NO_OP -> {
                }
            }
            store.put(payoutRecordKey(payout.txId(), claimId),
                    new PayoutRecordV1(planned.outcome(), planned.reason(), height).encode());
            results.add(new ClaimResult(claimId, planned.outcome(), planned.reason(), planned.leaf()));
        }
        if (halt) halt(store, height, blockTimeMs);
        return new Result(Disposition.RECORDED, results, governed, halt, halt ? "PAID_MISMATCH" : "confirmed");
    }

    /** One claim's outcome against the current state, without writing. */
    private Planned plan(AssetAccounts.Store store, Hash32 claimId, PayoutClaimV1.Payout payout, VaultBinding vault) {
        Optional<ClaimStatusV1> status;
        Optional<ClaimLeafV1> leaf;
        try {
            status = store.get(claimStatusKey(claimId)).map(ClaimStatusV1::decode);
            leaf = store.get(claimLeafKey(claimId)).map(ClaimLeafV1::decode);
        } catch (IllegalArgumentException undecodable) {
            return new Planned(claimId, Outcome.PAID_MISMATCH, Reason.LEDGER_INCONSISTENT, Optional.empty(),
                    Optional.empty());
        }
        if (status.isEmpty() && leaf.isEmpty()) {
            return new Planned(claimId, Outcome.PAID_MISMATCH, Reason.UNKNOWN_CLAIM, leaf, status);
        }
        if (status.isEmpty() || leaf.isEmpty() || !leaf.get().claimId().equals(claimId)
                || !status.get().claimId().equals(claimId)) {
            return new Planned(claimId, Outcome.PAID_MISMATCH, Reason.LEDGER_INCONSISTENT, leaf, status);
        }
        Reason mismatch = mismatch(leaf.get(), payout, vault);
        if (mismatch != Reason.NONE) return new Planned(claimId, Outcome.PAID_MISMATCH, mismatch, leaf, status);
        return switch (status.get().status()) {
            case RESERVED -> new Planned(claimId, Outcome.PAID, Reason.NONE, leaf, status);
            case PAID -> new Planned(claimId, Outcome.NO_OP, Reason.ALREADY_PAID, leaf, status);
            case PAID_MISMATCH -> new Planned(claimId, Outcome.NO_OP, Reason.ALREADY_MISMATCHED, leaf, status);
        };
    }

    /** The first script-enforced field on which the payout disagrees with the claim, or {@link Reason#NONE}. */
    private static Reason mismatch(ClaimLeafV1 leaf, PayoutClaimV1.Payout payout, VaultBinding vault) {
        if (!leaf.vaultId().equals(vault.vaultId())) return Reason.WRONG_VAULT;
        if (!Arrays.equals(leaf.recipient(), payout.recipient())) return Reason.RECIPIENT;
        BigInteger amount = claimAmount(leaf, vault);
        if (vault.asset().isAda()) {
            // The ADA vault's debit is exact and a builder may supplement the payout (V5): lovelace >= the claim.
            return payout.lovelace().compareTo(amount) >= 0 ? Reason.NONE : Reason.LOVELACE;
        }
        if (!payout.quantity().equals(amount)) return Reason.QUANTITY;
        return payout.lovelace().compareTo(leaf.vaultLovelace()) >= 0 ? Reason.NONE : Reason.LOVELACE;
    }

    /** The claim's amount of the vault's own asset (lovelace for the ADA vault). */
    private static BigInteger claimAmount(ClaimLeafV1 leaf, VaultBinding vault) {
        return leaf.value().quantityOf(vault.asset());
    }

    private void halt(AssetAccounts.Store store, long height, long blockTimeMs) {
        Optional<DomainStatusV1> current;
        try {
            current = domainStatus(store);
        } catch (IllegalArgumentException undecodable) {
            current = Optional.empty();
        }
        if (current.isPresent() && current.get().status() == DomainStatusV1.Status.HALTED) return;
        long exitDelay = current.map(DomainStatusV1::exitDelayMs).orElse(defaultExitDelayMs);
        // A domain already PAUSED keeps its since-point: trigger (c) of EnterExitMode measures how long the domain
        // has been out of ACTIVE (D8g §3), and a halt must not restart that clock.
        boolean stopped = current.isPresent() && current.get().status() != DomainStatusV1.Status.ACTIVE;
        long sinceHeight = stopped ? current.get().sinceHeight() : height;
        long sinceMs = stopped ? current.get().sinceMs() : blockTimeMs;
        store.put(domainStatusKey(),
                new DomainStatusV1(DomainStatusV1.Status.HALTED, sinceHeight, sinceMs, exitDelay).encode());
    }

    private Result redeem(AssetAccounts.Store store, BalanceExitObservationV1 exit, long height) {
        if (!exit.nullifierMatchesHolder()) {
            return Result.of(Disposition.IGNORED, "escape nullifier does not recompute");
        }
        byte[] key = escapeKey(exit.escapeNullifier());
        Optional<byte[]> existing = store.get(key);
        if (existing.isPresent()) {
            boolean sameTx;
            try {
                sameTx = EscapeRedemptionV1.decode(existing.get()).txId().equals(exit.txId());
            } catch (IllegalArgumentException undecodable) {
                sameTx = false;
            }
            // A re-redemption after a rolled-back exit is a no-op; the first record stays.
            return Result.of(Disposition.DUPLICATE, sameTx ? "already recorded" : "already redeemed");
        }
        store.put(key, new EscapeRedemptionV1(exit.escapeNullifier(), exit.vaultId(), exit.holderId(), exit.txId(),
                exit.payout().recipient(), exit.payout().quantity(), exit.payout().lovelace(), height).encode());
        return Result.of(Disposition.RECORDED, "balance exit recorded");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
