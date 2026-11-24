package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Exactly-once deposit admission for a consuming state machine (ADR-059 D6, I1, I2).
 *
 * <p>The consumer calls {@link #admit} from its own {@code apply()} for each finalized
 * {@code ~l1/<observerId>} observation, over a store that reads its own staged writes. In one pass
 * it:
 * <ol>
 *   <li>checks the envelope: the configured observer id, the anchor equal to the claim's tx id,
 *       {@code eventOrdinal} equal to the output index, and the slot and block hash;</li>
 *   <li>recomputes {@code DepositId} from the vault and outpoint and compares it with the claim's;</li>
 *   <li>checks that {@code deposit/<DepositId>} is absent (staged reads, so two outputs of one
 *       transaction in one block are handled);</li>
 *   <li>applies the shape rule, the creditability rule and the registry status, then asks the
 *       consumer's {@link CreditCheck} whether it can apply the credit;</li>
 *   <li>writes the {@link DepositRecordV1}: {@code CREDITED}, {@code UNCREDITED(reason)},
 *       {@code UNATTRIBUTED} for outputs that are neither deposits nor vault custody, and
 *       {@code OPERATIONAL} for clean floor top-ups;</li>
 *   <li>returns a {@link CreditInstruction} that the caller must apply in the same transition.</li>
 * </ol>
 *
 * <p>Nothing about L1 content makes it throw. An envelope that fails steps 1–2 writes nothing and
 * returns {@link Disposition#IGNORED}; a replay returns {@link Disposition#DUPLICATE} and writes
 * nothing. A {@code CONTINUATION} or {@code MIGRATION_IN} output is the vault's own custody, never
 * a deposit and never unattributed (D5, I16): it returns {@link Disposition#VAULT_CUSTODY} and
 * writes nothing. The stock observer does not even report continuations. Every uncreditable deposit is recorded, never rejected. The {@link CreditCheck} must
 * refuse anything the consumer could not credit without throwing (an unknown beneficiary, a
 * balance overflow), so that the record and the credit always commit together.
 */
public final class DepositAdmission {
    private static final byte[] RECORD_SEGMENT = "deposit/".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_PREFIX_BYTES = 64;

    /** Why a recorded output was not credited. Codes are part of {@link DepositRecordV1}. */
    public enum Reason {
        NONE(0),
        NOT_A_DEPOSIT(1),
        FLOOR_TOP_UP(2),
        DATUM_MALFORMED(3),
        UNKNOWN_VAULT(4),
        DEPOSITS_PAUSED(5),
        UNREGISTERED_ASSET(6),
        MIXED_ASSETS(7),
        ZERO_AMOUNT(8),
        UNKNOWN_BENEFICIARY(9),
        BALANCE_OVERFLOW(10),
        /** The claim's ADA-vault summary is internally inconsistent (registered quantity ≠ lovelace). */
        INCONSISTENT_VALUE(11);

        private final int code;

        Reason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Reason fromCode(long code) {
            for (Reason reason : values()) if (reason.code == code) return reason;
            throw new IllegalArgumentException("unknown deposit reason " + code);
        }
    }

    /** The framework-neutral envelope of one sequenced observation. */
    public record ObservedDeposit(String observerId, byte[] anchorTxId, long eventOrdinal, long slot,
                                  byte[] blockHash, byte[] claim) {
        public ObservedDeposit {
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

    /** A vault in the consumer's registry and whether it currently admits deposits. */
    public record VaultBinding(Hash32 vaultId, AssetId asset, boolean depositsEnabled) {
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

    /**
     * What the consumer must credit: {@code amount} of the vault's asset and, for a native-token
     * vault, the output's lovelace as the beneficiary's min-ADA credit (D11).
     */
    public record CreditInstruction(Hash32 depositId, Hash32 vaultId, AssetId asset, byte[] beneficiary,
                                    BigInteger amount, BigInteger minAdaCredit) {
        public CreditInstruction {
            Objects.requireNonNull(depositId, "deposit id");
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
            if (beneficiary == null || beneficiary.length != 28) {
                throw new IllegalArgumentException("beneficiary must be 28 bytes");
            }
            Quantities.requirePositiveU64(amount, "amount");
            Quantities.requireU64(minAdaCredit, "min-ADA credit");
            beneficiary = beneficiary.clone();
        }

        @Override
        public byte[] beneficiary() {
            return beneficiary.clone();
        }
    }

    /**
     * The consumer's last word before a credit: empty to accept, or the reason to record the deposit
     * as uncredited. It must be a pure function of staged state.
     */
    @FunctionalInterface
    public interface CreditCheck {
        Optional<Reason> refuse(CreditInstruction candidate);
    }

    public enum Disposition {
        /** A new record was written; {@link Outcome#credit()} is present only for {@code CREDITED}. */
        RECORDED,
        /** The deposit id was already processed; nothing was written. */
        DUPLICATE,
        /** The envelope or claim failed steps 1–2; nothing was written. */
        IGNORED,
        /**
         * A continuation or migration-in output: vault custody, neither a deposit nor unattributed
         * value. Nothing was written, so it never inflates unattributed custody (D15).
         */
        VAULT_CUSTODY
    }

    public record Outcome(Disposition disposition, Optional<DepositRecordV1> record,
                          Optional<CreditInstruction> credit, String detail) {
        static Outcome ignored(String detail) {
            return new Outcome(Disposition.IGNORED, Optional.empty(), Optional.empty(), detail);
        }
    }

    private final byte[] prefix;
    private final String observerId;
    private final Registry registry;

    public DepositAdmission(byte[] prefix, String observerId, Registry registry) {
        if (prefix == null || prefix.length == 0 || prefix.length > MAX_PREFIX_BYTES) {
            throw new IllegalArgumentException("prefix must be 1-64 bytes");
        }
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("observer id is required");
        }
        this.prefix = prefix.clone();
        this.observerId = observerId;
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The exact state key of a deposit record. */
    public byte[] recordKey(Hash32 depositId) {
        byte[] id = depositId.bytes();
        byte[] key = new byte[prefix.length + RECORD_SEGMENT.length + id.length];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        System.arraycopy(RECORD_SEGMENT, 0, key, prefix.length, RECORD_SEGMENT.length);
        System.arraycopy(id, 0, key, prefix.length + RECORD_SEGMENT.length, id.length);
        return key;
    }

    public Optional<DepositRecordV1> record(AssetAccounts.Store store, Hash32 depositId) {
        return store.get(recordKey(depositId)).map(DepositRecordV1::decode);
    }

    /** Runs D6 steps 1–6 for one observation. See the class documentation. */
    public Outcome admit(AssetAccounts.Store store, ObservedDeposit observed, long height, CreditCheck check) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(check, "check");
        if (height < 0) throw new IllegalArgumentException("height must be non-negative");
        if (observed == null || observed.claim() == null) return Outcome.ignored("no observation");

        // Step 1: the envelope.
        if (!observerId.equals(observed.observerId())) return Outcome.ignored("another observer");
        AssetDepositClaimV1 claim;
        try {
            claim = AssetDepositClaimV1.decode(observed.claim());
        } catch (IllegalArgumentException | ArithmeticException undecodable) {
            return Outcome.ignored("claim does not decode");
        }
        if (observed.anchorTxId() == null || !Arrays.equals(observed.anchorTxId(), claim.txId().bytes())) {
            return Outcome.ignored("anchor differs from the claim's transaction");
        }
        if (observed.eventOrdinal() != claim.outputIndex()) {
            return Outcome.ignored("event ordinal differs from the output index");
        }
        if (observed.slot() != claim.slot() || observed.blockHash() == null
                || !Arrays.equals(observed.blockHash(), claim.blockHash().bytes())) {
            return Outcome.ignored("slot or block hash differs from the claim");
        }

        // Step 2: the deposit id.
        Hash32 depositId = GatewayIds.depositId(claim.vaultId(), claim.txId(), claim.outputIndex());
        if (!depositId.equals(claim.depositId())) return Outcome.ignored("deposit id does not recompute");

        // Vault custody (a Settle/Exit/Accept continuation, or a migration-in) is never a deposit and
        // never unattributed: no record, so it neither inflates D15 nor leaves a leaf per spend.
        if (claim.shape() == VaultOutputShape.CONTINUATION || claim.shape() == VaultOutputShape.MIGRATION_IN) {
            return new Outcome(Disposition.VAULT_CUSTODY, Optional.empty(), Optional.empty(), claim.shape().name());
        }

        // Step 3: exactly once, through staged reads.
        byte[] key = recordKey(depositId);
        if (store.get(key).isPresent()) {
            return new Outcome(Disposition.DUPLICATE, Optional.empty(), Optional.empty(), "already processed");
        }

        // Step 4: shape, creditability, registry status, then the consumer's check.
        Optional<VaultBinding> vault = registry.vault(claim.vaultId());
        DepositRecordV1.Status status;
        Reason reason;
        CreditInstruction instruction = null;
        byte[] beneficiary = claim.beneficiary() instanceof AssetDepositClaimV1.Account account
                ? account.accountId() : new byte[0];
        if (claim.shape() == VaultOutputShape.FLOOR_TOP_UP) {
            // Only a clean top-up (lovelace alone, into a registered vault) is operator floor; anything
            // carrying junk stays unattributed so a governed sweep can recover it (D8f, V10).
            Reason junk = floorTopUpJunk(claim, vault);
            status = junk == Reason.NONE ? DepositRecordV1.Status.OPERATIONAL : DepositRecordV1.Status.UNATTRIBUTED;
            reason = junk == Reason.NONE ? Reason.FLOOR_TOP_UP : junk;
        } else if (claim.shape() != VaultOutputShape.DEPOSIT) {
            status = DepositRecordV1.Status.UNATTRIBUTED;
            reason = Reason.NOT_A_DEPOSIT;
        } else {
            status = DepositRecordV1.Status.UNCREDITED;
            reason = creditability(claim, vault);
            if (reason == Reason.NONE) {
                VaultBinding binding = vault.orElseThrow();
                instruction = binding.asset().isAda()
                        ? new CreditInstruction(depositId, binding.vaultId(), binding.asset(), beneficiary,
                        claim.lovelace(), BigInteger.ZERO)
                        : new CreditInstruction(depositId, binding.vaultId(), binding.asset(), beneficiary,
                        claim.registeredQuantity(), claim.lovelace());
                Optional<Reason> refused = check.refuse(instruction);
                if (refused.isPresent()) {
                    if (refused.get() == Reason.NONE) {
                        throw new IllegalStateException("a credit check refusal needs a reason");
                    }
                    reason = refused.get();
                    instruction = null;
                } else {
                    status = DepositRecordV1.Status.CREDITED;
                }
            }
        }

        // Step 5: the write-once record.
        DepositRecordV1 record = new DepositRecordV1(depositId, claim.vaultId(), claim.txId(), claim.outputIndex(),
                claim.slot(), height, claim.shape(), status, reason, beneficiary, claim.registeredQuantity(),
                claim.lovelace(), claim.otherAssetCount(), claim.valueHash(),
                instruction == null ? BigInteger.ZERO : instruction.amount(),
                instruction == null ? BigInteger.ZERO : instruction.minAdaCredit());
        store.put(key, record.encode());

        // Step 6: the credit the caller applies in the same transition.
        return new Outcome(Disposition.RECORDED, Optional.of(record), Optional.ofNullable(instruction),
                status.name());
    }

    /** Why a floor top-up is not clean operator ADA, or {@link Reason#NONE}. */
    private static Reason floorTopUpJunk(AssetDepositClaimV1 claim, Optional<VaultBinding> vault) {
        if (vault.isEmpty()) return Reason.UNKNOWN_VAULT;
        if (vault.get().asset().isAda() && !claim.registeredQuantity().equals(claim.lovelace())) {
            return Reason.INCONSISTENT_VALUE;
        }
        if (claim.otherAssetCount() > 0
                || (!vault.get().asset().isAda() && claim.registeredQuantity().signum() != 0)) {
            return Reason.MIXED_ASSETS;
        }
        return Reason.NONE;
    }

    /** D5's creditable-deposit rule plus the registry status, for a deposit-shaped claim. */
    private static Reason creditability(AssetDepositClaimV1 claim, Optional<VaultBinding> vault) {
        if (vault.isEmpty()) return Reason.UNKNOWN_VAULT;
        // For the ADA vault the registered asset is lovelace: a claim whose two figures differ was not
        // produced by a correct observer, so it is never credited.
        if (vault.get().asset().isAda() && !claim.registeredQuantity().equals(claim.lovelace())) {
            return Reason.INCONSISTENT_VALUE;
        }
        if (!(claim.beneficiary() instanceof AssetDepositClaimV1.Account)) return Reason.DATUM_MALFORMED;
        if (!vault.get().depositsEnabled()) return Reason.DEPOSITS_PAUSED;
        BigInteger registered = vault.get().asset().isAda() ? claim.lovelace() : claim.registeredQuantity();
        if (claim.otherAssetCount() > 0) {
            return registered.signum() > 0 ? Reason.MIXED_ASSETS : Reason.UNREGISTERED_ASSET;
        }
        if (registered.signum() == 0) return Reason.ZERO_AMOUNT;
        return Reason.NONE;
    }
}
