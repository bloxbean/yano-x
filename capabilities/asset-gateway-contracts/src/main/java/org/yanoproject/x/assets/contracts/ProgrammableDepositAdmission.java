package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR-061 D6: exactly-once deposit admission for a CIP-113 vault, outpoint first.
 *
 * <p>For each finalized {@link ProgrammableDepositClaimV1} the consumer calls {@link #admit} from its own
 * {@code apply()}, over a store that reads its own staged writes, after it has recorded every wiring observation of
 * the same block ({@link Cip113Wiring}). In one pass it:
 * <ol>
 *   <li>checks the envelope (observer id, anchor, event ordinal, slot, block hash), as 059's admission does;</li>
 *   <li>writes the one write-once record {@code vault-output/<outpoint>} of the output, whatever its shape, with its
 *       {@link Disposition};</li>
 *   <li>for the one origin-valid attempt of a {@code DepositId} ({@code DEPOSIT}: deposit-shaped and
 *       {@code originSpent}), writes {@code deposit/<DepositId>} once: {@code CREDITED}, or
 *       {@code UNCREDITED(reason)};</li>
 *   <li>returns the credit the caller applies in the same transition.</li>
 * </ol>
 *
 * <p>A deposit-shaped output whose origin is not spent by its own transaction is {@code RECREATED} and never writes
 * {@code deposit/<DepositId>}: a front-runner could name a victim's future origin, so a record there would block the
 * genuine deposit (I35, I45). The creditability of a {@code DEPOSIT} is classified with the recorded fields of the
 * one wiring version its transaction referenced ({@link ActionClass}), so the result never depends on the order of
 * the deposit observations of a block (I48). Nothing about L1 content makes this throw.
 */
public final class ProgrammableDepositAdmission {
    private static final byte[] OUTPUT_SEGMENT = "vault-output/".getBytes(StandardCharsets.US_ASCII);

    /** The disposition of {@code vault-output/<outpoint>}. */
    public enum Disposition {
        DEPOSIT(0), RECREATED(1), MALFORMED(2), CONTINUATION(3), TOP_UP(4), UNATTRIBUTED(5);

        private final int code;

        Disposition(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Disposition fromCode(long code) {
            for (Disposition disposition : values()) if (disposition.code == code) return disposition;
            throw AssetCbor.malformed("unknown vault-output disposition");
        }
    }

    /** The creating action, classified against the referenced wiring version (D6 table). */
    public enum ActionClass { TRANSFER, ISSUANCE, THIRD_PARTY, UNFRACKING, UNKNOWN }

    /** A CIP-113 vault in the consumer's registry and its current registry status. */
    public record VaultBinding(Hash32 vaultId, AssetId asset, boolean depositsEnabled, boolean held) {
        public VaultBinding {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(asset, "asset");
            if (asset.isAda()) throw new IllegalArgumentException("a CIP-113 vault holds a native asset");
        }
    }

    @FunctionalInterface
    public interface Registry {
        Optional<VaultBinding> vault(Hash32 vaultId);
    }

    /**
     * {@code vault-output/<outpoint>}: {@code [1, tx_id, output_index, vault_id, disposition, deposit_id / h'',
     * reason, slot, height]}.
     */
    public record VaultOutputRecordV1(ProgrammableDepositDatumV1.Outpoint outpoint, Hash32 vaultId,
                                      Disposition disposition, Optional<Hash32> depositId, DepositAdmission.Reason reason,
                                      long slot, long height) {
        public VaultOutputRecordV1 {
            Objects.requireNonNull(outpoint, "outpoint");
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(depositId, "deposit id");
            Objects.requireNonNull(reason, "reason");
            boolean keyed = disposition == Disposition.DEPOSIT || disposition == Disposition.RECREATED;
            if (keyed != depositId.isPresent()) {
                throw new IllegalArgumentException("only DEPOSIT and RECREATED name a deposit id");
            }
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.bstr(outpoint.txId().bytes()));
            array.add(AssetCbor.uint(outpoint.index()));
            array.add(AssetCbor.bstr(vaultId.bytes()));
            array.add(AssetCbor.uint(disposition.code()));
            array.add(AssetCbor.bstr(depositId.map(Hash32::bytes).orElse(new byte[0])));
            array.add(AssetCbor.uint(reason.code()));
            array.add(AssetCbor.uint(slot));
            array.add(AssetCbor.uint(height));
            return AssetCbor.encode(array);
        }

        public static VaultOutputRecordV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 9).getDataItems();
            byte[] id = AssetCbor.bytes(items.get(5), 0, 32);
            return new VaultOutputRecordV1(new ProgrammableDepositDatumV1.Outpoint(
                    Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)), AssetCbor.uintLong(items.get(2))),
                    Hash32.of(AssetCbor.bytes(items.get(3), 32, 32)),
                    Disposition.fromCode(AssetCbor.uintLong(items.get(4))),
                    id.length == 0 ? Optional.empty() : Optional.of(Hash32.of(id)),
                    DepositAdmission.Reason.fromCode(AssetCbor.uintLong(items.get(6))),
                    AssetCbor.uintLong(items.get(7)), AssetCbor.uintLong(items.get(8)));
        }
    }

    public enum Result { RECORDED, DUPLICATE, IGNORED }

    public record Outcome(Result result, Optional<VaultOutputRecordV1> output, Optional<DepositRecordV1> deposit,
                          Optional<DepositAdmission.CreditInstruction> credit, ActionClass actionClass) {
        static Outcome ignored() {
            return new Outcome(Result.IGNORED, Optional.empty(), Optional.empty(), Optional.empty(),
                    ActionClass.UNKNOWN);
        }
    }

    private final byte[] prefix;
    private final String observerId;
    private final Registry registry;
    private final Cip113Wiring wiring;
    private final DepositAdmission records;

    public ProgrammableDepositAdmission(byte[] prefix, String observerId, Registry registry, Cip113Wiring wiring) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").clone();
        this.observerId = Objects.requireNonNull(observerId, "observer id");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.wiring = Objects.requireNonNull(wiring, "wiring");
        // deposit/<DepositId> uses the 059 record key and layout.
        this.records = new DepositAdmission(prefix, observerId, vaultId -> Optional.empty());
    }

    public byte[] outputKey(ProgrammableDepositDatumV1.Outpoint outpoint) {
        byte[] cbor = GatewayIds.outpointCbor(outpoint.txId(), outpoint.index());
        byte[] key = new byte[prefix.length + OUTPUT_SEGMENT.length + cbor.length];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        System.arraycopy(OUTPUT_SEGMENT, 0, key, prefix.length, OUTPUT_SEGMENT.length);
        System.arraycopy(cbor, 0, key, prefix.length + OUTPUT_SEGMENT.length, cbor.length);
        return key;
    }

    public byte[] depositKey(Hash32 depositId) {
        return records.recordKey(depositId);
    }

    public Optional<VaultOutputRecordV1> output(AssetAccounts.Store store, ProgrammableDepositDatumV1.Outpoint at) {
        return store.get(outputKey(at)).map(VaultOutputRecordV1::decode);
    }

    public Optional<DepositRecordV1> deposit(AssetAccounts.Store store, Hash32 depositId) {
        return records.record(store, depositId);
    }

    /** Admits one observation. See the class documentation. */
    public Outcome admit(AssetAccounts.Store store, DepositAdmission.ObservedDeposit observed, long height,
                         DepositAdmission.CreditCheck check) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(check, "check");
        if (observed == null || observed.claim() == null || !observerId.equals(observed.observerId())) {
            return Outcome.ignored();
        }
        ProgrammableDepositClaimV1 claim;
        try {
            claim = ProgrammableDepositClaimV1.decode(observed.claim());
        } catch (IllegalArgumentException | ArithmeticException undecodable) {
            return Outcome.ignored();
        }
        if (observed.anchorTxId() == null || !Arrays.equals(observed.anchorTxId(), claim.txId().bytes())
                || observed.eventOrdinal() != claim.outputIndex() || observed.slot() != claim.slot()
                || observed.blockHash() == null || !Arrays.equals(observed.blockHash(), claim.blockHash().bytes())) {
            return Outcome.ignored();
        }
        byte[] outputKey = outputKey(claim.outpoint());
        if (store.get(outputKey).isPresent()) {
            return new Outcome(Result.DUPLICATE, Optional.empty(), Optional.empty(), Optional.empty(),
                    ActionClass.UNKNOWN);
        }
        Optional<VaultBinding> vault = registry.vault(claim.vaultId());
        Disposition disposition;
        DepositAdmission.Reason reason = DepositAdmission.Reason.NONE;
        Optional<Hash32> depositId = Optional.empty();
        DepositRecordV1 depositRecord = null;
        DepositAdmission.CreditInstruction credit = null;
        ActionClass actionClass = ActionClass.UNKNOWN;
        switch (claim.shape()) {
            case DEPOSIT -> {
                ProgrammableDepositClaimV1.Deposit deposit = claim.deposit().orElseThrow();
                Hash32 id = deposit.datum().depositId(claim.vaultId());
                depositId = Optional.of(id);
                if (!deposit.originSpent() || store.get(depositKey(id)).isPresent()) {
                    // At most one output per DepositId can have originSpent, so a present record means this
                    // claim is not the origin-valid attempt either; it never writes the record.
                    disposition = Disposition.RECREATED;
                } else {
                    disposition = Disposition.DEPOSIT;
                    actionClass = classify(store, claim);
                    reason = creditability(store, claim, vault, actionClass);
                    DepositRecordV1.Status status = DepositRecordV1.Status.UNCREDITED;
                    if (reason == DepositAdmission.Reason.NONE) {
                        VaultBinding binding = vault.orElseThrow();
                        credit = new DepositAdmission.CreditInstruction(id, binding.vaultId(), binding.asset(),
                                deposit.datum().beneficiary(), claim.registeredQuantity(), claim.lovelace());
                        Optional<DepositAdmission.Reason> refused = check.refuse(credit);
                        if (refused.isPresent()) {
                            if (refused.get() == DepositAdmission.Reason.NONE) {
                                throw new IllegalStateException("a credit check refusal needs a reason");
                            }
                            reason = refused.get();
                            credit = null;
                        } else {
                            status = DepositRecordV1.Status.CREDITED;
                        }
                    }
                    depositRecord = new DepositRecordV1(id, claim.vaultId(), claim.txId(), claim.outputIndex(),
                            claim.slot(), height, VaultOutputShape.DEPOSIT, status, reason,
                            deposit.datum().beneficiary(), claim.registeredQuantity(), claim.lovelace(),
                            claim.otherAssetCount(), claim.valueHash(),
                            credit == null ? BigInteger.ZERO : credit.amount(),
                            credit == null ? BigInteger.ZERO : credit.minAdaCredit());
                }
            }
            case MALFORMED -> {
                disposition = Disposition.MALFORMED;
                reason = DepositAdmission.Reason.DATUM_MALFORMED;
            }
            case CONTINUATION -> {
                boolean clean = vault.isPresent() && claim.otherAssetCount() == 0;
                disposition = clean ? Disposition.CONTINUATION : Disposition.UNATTRIBUTED;
                reason = vault.isEmpty() ? DepositAdmission.Reason.UNKNOWN_VAULT
                        : clean ? DepositAdmission.Reason.NONE : DepositAdmission.Reason.MIXED_ASSETS;
            }
            case TOP_UP -> {
                boolean clean = vault.isPresent() && claim.otherAssetCount() == 0
                        && claim.registeredQuantity().signum() == 0;
                disposition = clean ? Disposition.TOP_UP : Disposition.UNATTRIBUTED;
                reason = vault.isEmpty() ? DepositAdmission.Reason.UNKNOWN_VAULT
                        : clean ? DepositAdmission.Reason.FLOOR_TOP_UP : DepositAdmission.Reason.MIXED_ASSETS;
            }
            default -> {
                disposition = Disposition.UNATTRIBUTED;
                reason = DepositAdmission.Reason.NOT_A_DEPOSIT;
            }
        }
        VaultOutputRecordV1 output = new VaultOutputRecordV1(claim.outpoint(), claim.vaultId(), disposition,
                depositId, reason, claim.slot(), height);
        store.put(outputKey, output.encode());
        if (depositRecord != null) {
            store.put(depositKey(depositRecord.depositId()), depositRecord.encode());
        }
        return new Outcome(Result.RECORDED, Optional.of(output), Optional.ofNullable(depositRecord),
                Optional.ofNullable(credit), actionClass);
    }

    /**
     * The creating action, against the one recorded wiring version among the transaction's reference inputs (D6):
     * {@code THIRD_PARTY} or {@code UNFRACKING} when the version's third-party or unfracking delegate is withdrawn, or
     * its dispatcher's redeemer has constructor 1 or 2; {@code TRANSFER} for its dispatcher with constructor 0;
     * {@code ISSUANCE} for its issuance logic with no dispatcher and a positive mint of the vault asset's policy;
     * {@code UNKNOWN} otherwise, including every list over its cap and a transaction that referenced no recorded
     * version. The delegate hashes come from the approved tuple; an unapproved version is classified by its fields
     * alone and never credits anyway.
     */
    public ActionClass classify(AssetAccounts.Store store, ProgrammableDepositClaimV1 claim) {
        if (claim.withdrawals().isEmpty() || claim.referenceInputs().isEmpty()) return ActionClass.UNKNOWN;
        Optional<Cip113Wiring.Version> version = wiring.referenced(store, claim.referenceInputs().get());
        if (version.isEmpty() || version.get().fields().isEmpty()) return ActionClass.UNKNOWN;
        Cip113WiringClaimV1.Fields fields = version.get().fields().get();
        Optional<Cip113Wiring.ApprovedTuple> tuple = wiring.approved(store, fields);
        // The observer reports script withdrawals only, so a field that is a key credential never matches one: a key
        // issuance logic, for example, never classifies a transaction as ISSUANCE (it is UNKNOWN, never credited).
        byte[] thirdPartyField = fields.scriptHash(3).orElse(null);
        byte[] thirdParty = tuple.map(Cip113Wiring.ApprovedTuple::thirdPartyDelegate).orElse(thirdPartyField);
        byte[] unfracking = tuple.map(Cip113Wiring.ApprovedTuple::unfrackingDelegate).orElse(null);
        byte[] dispatcherField = fields.scriptHash(0).orElse(null);
        byte[] issuanceField = fields.scriptHash(1).orElse(null);
        Optional<Long> dispatcher = Optional.empty();
        boolean dispatched = false;
        boolean issuance = false;
        for (ProgrammableDepositClaimV1.Withdrawal w : claim.withdrawals().get()) {
            byte[] hash = w.scriptHash();
            if (Arrays.equals(hash, thirdParty) || Arrays.equals(hash, thirdPartyField)) {
                return ActionClass.THIRD_PARTY;
            }
            if (unfracking != null && Arrays.equals(hash, unfracking)) return ActionClass.UNFRACKING;
            if (Arrays.equals(hash, dispatcherField)) {
                dispatched = true;
                dispatcher = w.redeemerConstructor();
            }
            if (Arrays.equals(hash, issuanceField)) issuance = true;
        }
        if (dispatched) {
            if (dispatcher.isEmpty()) return ActionClass.UNKNOWN;
            long ctor = dispatcher.get();
            return ctor == 0 ? ActionClass.TRANSFER : ctor == 1 ? ActionClass.THIRD_PARTY
                    : ctor == 2 ? ActionClass.UNFRACKING : ActionClass.UNKNOWN;
        }
        return issuance && claim.positiveMint() ? ActionClass.ISSUANCE : ActionClass.UNKNOWN;
    }

    private DepositAdmission.Reason creditability(AssetAccounts.Store store, ProgrammableDepositClaimV1 claim,
                                                  Optional<VaultBinding> vault, ActionClass actionClass) {
        if (vault.isEmpty()) return DepositAdmission.Reason.UNKNOWN_VAULT;
        if (actionClass == ActionClass.THIRD_PARTY || actionClass == ActionClass.UNFRACKING) {
            return DepositAdmission.Reason.INELIGIBLE_ACTION;
        }
        Optional<Cip113Wiring.Version> version = claim.referenceInputs()
                .flatMap(refs -> wiring.referenced(store, refs));
        if (version.isPresent() && wiring.approved(store, version.get()).isEmpty()) {
            return DepositAdmission.Reason.WIRING_UNPINNED;
        }
        if (actionClass == ActionClass.UNKNOWN) return DepositAdmission.Reason.UNKNOWN_ACTION;
        if (vault.get().held()) return DepositAdmission.Reason.ASSET_HELD;
        if (!vault.get().depositsEnabled()) return DepositAdmission.Reason.DEPOSITS_PAUSED;
        if (claim.otherAssetCount() > 0) {
            return claim.registeredQuantity().signum() > 0 ? DepositAdmission.Reason.MIXED_ASSETS
                    : DepositAdmission.Reason.UNREGISTERED_ASSET;
        }
        if (claim.registeredQuantity().signum() == 0) return DepositAdmission.Reason.ZERO_AMOUNT;
        return DepositAdmission.Reason.NONE;
    }
}
