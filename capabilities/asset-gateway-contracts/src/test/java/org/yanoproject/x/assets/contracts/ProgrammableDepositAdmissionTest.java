package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 §10 P4: CIP-113 deposit admission (origin binding, action classes, versioned wiring, outpoint-first
 * records) and its same-block determinism (I35, I44, I45, I48).
 */
class ProgrammableDepositAdmissionTest {
    static final byte[] PREFIX = "ap/".getBytes(StandardCharsets.US_ASCII);
    static final String DEPOSITS = "asset-vault-deposit-v1";
    static final String WIRING = "asset-cip113-wiring-v1";
    static final byte[] PLB = filled(0x3a, 28);
    static final AssetId TOKEN = AssetId.of(filled(0x7a, 28), "USDM".getBytes(StandardCharsets.US_ASCII));
    static final Hash32 VAULT = GatewayIds.vaultId(Hash32.of(filled(0x01, 32)), TOKEN, 0);
    static final byte[] D = filled(0xd0, 28);
    static final byte[] ISSUANCE = filled(0xd1, 28);
    static final byte[] T = filled(0xd2, 28);
    static final byte[] TP = filled(0xd3, 28);
    static final Cip113WiringClaimV1.Fields V0 = new Cip113WiringClaimV1.Fields(D, ISSUANCE, T, TP);
    static final ProgrammableDepositDatumV1.Outpoint PARAMS0 = outpoint(0xa0, 0);
    static final byte[] ALICE = filled(0xa1, 28);
    static final byte[] BLOCK = filled(0xbb, 32);

    /** A sorted in-memory store reading its own writes. */
    static final class MapStore implements AssetAccounts.Store {
        final TreeMap<String, byte[]> values = new TreeMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(values.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            values.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            values.remove(HexFormat.of().formatHex(key));
        }

        String snapshot() {
            StringBuilder out = new StringBuilder();
            values.forEach((k, v) -> out.append(k).append('=').append(HexFormat.of().formatHex(v)).append('\n'));
            return out.toString();
        }
    }

    final MapStore store = new MapStore();
    final Cip113Wiring wiring = new Cip113Wiring(cat(PREFIX, "cip113/".getBytes()), WIRING);
    boolean held;
    final ProgrammableDepositAdmission admission = new ProgrammableDepositAdmission(PREFIX, DEPOSITS,
            vaultId -> vaultId.equals(VAULT) ? Optional.of(new ProgrammableDepositAdmission.VaultBinding(VAULT, TOKEN,
                    true, held)) : Optional.empty(), wiring);

    ProgrammableDepositAdmissionTest() {
        wiring.genesis(store, new Cip113Wiring.Version(PARAMS0, 100, 0, Optional.of(V0)),
                new Cip113Wiring.ApprovedTuple(V0, T, TP, new byte[28]));
    }

    // ------------------------------------------------------------------ the datum predicate

    @Test
    void theDepositShapedPredicateIsTotalAndEncodingIndependent() {
        ProgrammableDepositDatumV1 datum = datum(outpoint(0x11, 0), 0);
        byte[] definite = datum.encode();
        assertThat(ProgrammableDepositDatumV1.depositShaped(definite, PLB, 0)).contains(datum);
        // The same value with indefinite lists, and in the general constructor form 102 [0, fields].
        byte[] indefinite = indefinite(definite);
        assertThat(ProgrammableDepositDatumV1.depositShaped(indefinite, PLB, 0)).contains(datum);
        byte[] general = general(definite);
        assertThat(ProgrammableDepositDatumV1.depositShaped(general, PLB, 0)).contains(datum);
        // Not deposit-shaped, never throwing: 3 fields (059), wrong types, a refund address that is not a key-owned
        // smart wallet of this PLB, junk.
        byte[] threeField = new DepositDatumV1(ALICE, cat(new byte[] {0x60}, ALICE), ALICE).encode();
        assertThat(ProgrammableDepositClaimV1.Shape.of(threeField, PLB, 0))
                .isEqualTo(ProgrammableDepositClaimV1.Shape.MALFORMED);
        byte[] scriptOwned = smartWallet(ALICE);
        scriptOwned[0] = 0x30;
        for (byte[] refund : List.of(scriptOwned, cat(new byte[] {0x60}, ALICE), otherPlb())) {
            byte[] bad = new ProgrammableDepositDatumV1(ALICE, refund, ALICE, Hash32.of(filled(0x11, 32)), 0, 0)
                    .encode();
            assertThat(ProgrammableDepositDatumV1.depositShaped(bad, PLB, 0)).isEmpty();
            assertThat(ProgrammableDepositClaimV1.Shape.of(bad, PLB, 0))
                    .isEqualTo(ProgrammableDepositClaimV1.Shape.MALFORMED);
        }
        for (byte[] junk : List.of(new byte[0], new byte[] {(byte) 0xff}, new byte[] {0x05},
                new byte[] {(byte) 0xd8, 0x79, (byte) 0x80}, Arrays.copyOf(definite, definite.length - 3))) {
            assertThat(ProgrammableDepositDatumV1.depositShaped(junk, PLB, 0)).isEmpty();
        }
        assertThat(ProgrammableDepositClaimV1.Shape.of(new byte[] {(byte) 0xd8, 0x7a, (byte) 0x81, (byte) 0x80}, PLB,
                0)).isEqualTo(ProgrammableDepositClaimV1.Shape.CONTINUATION);
        assertThat(ProgrammableDepositClaimV1.Shape.of(VaultOutputShape.floorTopUpDatum(), PLB, 0))
                .isEqualTo(ProgrammableDepositClaimV1.Shape.TOP_UP);
    }

    @Test
    void theSmartWalletRecipientRule() {
        assertThat(CardanoAddresses.payableSmartWallet(smartWallet(ALICE), PLB, 0)).isTrue();
        assertThat(CardanoAddresses.payableSmartWallet(smartWallet(ALICE), PLB, 1)).isFalse();
        assertThat(CardanoAddresses.payableSmartWallet(otherPlb(), PLB, 0)).isFalse();
        assertThat(CardanoAddresses.payableProgrammableRecipient(cat(new byte[] {0x60}, ALICE), PLB,
                0, false)).isTrue();
        assertThat(CardanoAddresses.payableProgrammableRecipient(cat(new byte[] {0x60}, ALICE), PLB,
                0, true)).isFalse();
    }

    // ------------------------------------------------------------------ origin binding and records

    @Test
    void aDepositWithAValidOriginIsCreditedOnce() {
        Tx tx = transfer(0x21, List.of(outpoint(0x11, 0)));
        ProgrammableDepositAdmission.Outcome outcome = admit(tx.deposit(datum(outpoint(0x11, 0), 0), 0, 500));
        assertThat(outcome.output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.DEPOSIT);
        assertThat(outcome.deposit().orElseThrow().status()).isEqualTo(DepositRecordV1.Status.CREDITED);
        assertThat(outcome.credit()).isPresent();
        assertThat(outcome.actionClass()).isEqualTo(ProgrammableDepositAdmission.ActionClass.TRANSFER);
        assertThat(admit(tx.deposit(datum(outpoint(0x11, 0), 0), 0, 500)).result())
                .isEqualTo(ProgrammableDepositAdmission.Result.DUPLICATE);
    }

    @Test
    void aNoOpThirdPartyReSpendIsRecreatedInAnyOrder() {
        ProgrammableDepositDatumV1 datum = datum(outpoint(0x11, 0), 0);
        Tx deposit = transfer(0x21, List.of(outpoint(0x11, 0)));
        Tx respend = thirdParty(0x22, List.of(outpoint(0x21, 0)));
        List<byte[]> claims = List.of(deposit.deposit(datum, 0, 500), respend.deposit(datum, 0, 500));
        String first = null;
        for (List<byte[]> order : List.of(claims, List.of(claims.get(1), claims.get(0)))) {
            ProgrammableDepositAdmissionTest fresh = new ProgrammableDepositAdmissionTest();
            List<ProgrammableDepositAdmission.Outcome> outcomes = new ArrayList<>();
            for (byte[] claim : order) outcomes.add(fresh.admit(claim));
            long credits = outcomes.stream().filter(o -> o.credit().isPresent()).count();
            assertThat(credits).isEqualTo(1);
            assertThat(fresh.admission.output(fresh.store, outpoint(0x22, 0)).orElseThrow().disposition())
                    .isEqualTo(ProgrammableDepositAdmission.Disposition.RECREATED);
            if (first == null) first = fresh.store.snapshot();
            else assertThat(fresh.store.snapshot()).isEqualTo(first);
        }
    }

    @Test
    void aCopyNamingAnUnspentInputNeverCredits() {
        // Finding 7: R names an unspent J (RECREATED); a third-party re-spend spends J and R and re-creates R at the
        // same index. The copy is origin-valid but third-party dispatched: uncredited, never credited.
        ProgrammableDepositDatumV1 datum = datum(outpoint(0x31, 0), 0);
        ProgrammableDepositAdmission.Outcome r = admit(transfer(0x41, List.of(outpoint(0x12, 0)))
                .deposit(datum, 0, 500));
        assertThat(r.output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.RECREATED);
        ProgrammableDepositAdmission.Outcome copy = admit(thirdParty(0x42, List.of(outpoint(0x31, 0),
                outpoint(0x41, 0))).deposit(datum, 0, 900));
        assertThat(copy.credit()).isEmpty();
        assertThat(copy.deposit().orElseThrow().reason()).isEqualTo(DepositAdmission.Reason.INELIGIBLE_ACTION);
    }

    @Test
    void everyCreatingActionClass() {
        ProgrammableDepositDatumV1 d1 = datum(outpoint(0x51, 0), 0);
        assertThat(admit(issuance(0x61, List.of(outpoint(0x51, 0)), true, Optional.empty()).deposit(d1, 0, 7))
                .actionClass()).isEqualTo(ProgrammableDepositAdmission.ActionClass.ISSUANCE);
        ProgrammableDepositDatumV1 d2 = datum(outpoint(0x52, 0), 0);
        ProgrammableDepositAdmission.Outcome noMint = admit(issuance(0x62, List.of(outpoint(0x52, 0)), false,
                Optional.empty()).deposit(d2, 0, 7));
        assertThat(noMint.actionClass()).isEqualTo(ProgrammableDepositAdmission.ActionClass.UNKNOWN);
        assertThat(noMint.deposit().orElseThrow().reason()).isEqualTo(DepositAdmission.Reason.UNKNOWN_ACTION);
        ProgrammableDepositDatumV1 d3 = datum(outpoint(0x53, 0), 0);
        Tx unfrack = new Tx(0x63, List.of(outpoint(0x53, 0)), List.of(withdrawal(D, 2L)), false,
                List.of(PARAMS0));
        assertThat(admit(unfrack.deposit(d3, 0, 7)).deposit().orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.INELIGIBLE_ACTION);
        ProgrammableDepositDatumV1 d4 = datum(outpoint(0x54, 0), 0);
        Tx overflow = new Tx(0x64, List.of(outpoint(0x54, 0)), null, false, List.of(PARAMS0));
        assertThat(admit(overflow.deposit(d4, 0, 7)).deposit().orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.UNKNOWN_ACTION);
        ProgrammableDepositDatumV1 d5 = datum(outpoint(0x55, 0), 0);
        Tx noParams = new Tx(0x65, List.of(outpoint(0x55, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(outpoint(0x99, 0)));
        assertThat(admit(noParams.deposit(d5, 0, 7)).deposit().orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.UNKNOWN_ACTION);
        ProgrammableDepositDatumV1 d6 = datum(outpoint(0x56, 0), 0);
        Tx refsOverflow = new Tx(0x66, List.of(outpoint(0x56, 0)), List.of(withdrawal(D, 0L)), false, null);
        assertThat(admit(refsOverflow.deposit(d6, 0, 7)).deposit().orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.UNKNOWN_ACTION);
    }

    @Test
    void anIssuanceWhoseRedeemerIsAMapIsReportedWithoutAConstructorAndClassifiedIssuance() {
        ProgrammableDepositDatumV1 datum = datum(outpoint(0x57, 0), 0);
        ProgrammableDepositAdmission.Outcome outcome = admit(issuance(0x67, List.of(outpoint(0x57, 0)), true,
                Optional.empty()).deposit(datum, 0, 9));
        assertThat(outcome.actionClass()).isEqualTo(ProgrammableDepositAdmission.ActionClass.ISSUANCE);
        assertThat(outcome.credit()).isPresent();
    }

    @Test
    void malformedAndOtherOutputsGetOneRecordEach() {
        Tx tx = transfer(0x71, List.of(outpoint(0x13, 0)));
        byte[] threeField = new DepositDatumV1(ALICE, cat(new byte[] {0x60}, ALICE), ALICE).encode();
        assertThat(admit(tx.claim(0, ProgrammableDepositClaimV1.Shape.of(threeField, PLB, 0), Optional.empty(), 5, 0))
                .output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.MALFORMED);
        assertThat(admit(tx.claim(1, ProgrammableDepositClaimV1.Shape.CONTINUATION, Optional.empty(), 5, 0))
                .output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.CONTINUATION);
        assertThat(admit(tx.claim(2, ProgrammableDepositClaimV1.Shape.TOP_UP, Optional.empty(), 0, 0))
                .output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.TOP_UP);
        assertThat(admit(tx.claim(3, ProgrammableDepositClaimV1.Shape.CONTINUATION, Optional.empty(), 5, 1))
                .output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.UNATTRIBUTED);
        assertThat(admit(tx.claim(4, ProgrammableDepositClaimV1.Shape.OTHER, Optional.empty(), 5, 0))
                .output().orElseThrow().disposition()).isEqualTo(ProgrammableDepositAdmission.Disposition.UNATTRIBUTED);
    }

    @Test
    void anIssuerHoldRecordsDepositsAsHeld() {
        held = true;
        ProgrammableDepositAdmission.Outcome outcome = admit(transfer(0x72, List.of(outpoint(0x14, 0)))
                .deposit(datum(outpoint(0x14, 0), 0), 0, 5));
        assertThat(outcome.deposit().orElseThrow().reason()).isEqualTo(DepositAdmission.Reason.ASSET_HELD);
    }

    // ------------------------------------------------------------------ versioned wiring

    @Test
    void anAuthorityOnlyRolloverIsApprovedAtOnce() {
        recordWiring(outpoint(0xa1, 0), 200, 0, Optional.of(V0));
        Cip113Wiring.Version head = wiring.head(store).orElseThrow();
        assertThat(head.outpoint()).isEqualTo(outpoint(0xa1, 0));
        assertThat(wiring.approved(store, head)).isPresent();
        Tx tx = new Tx(0x81, List.of(outpoint(0x15, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(outpoint(0xa1, 0)));
        assertThat(admit(tx.deposit(datum(outpoint(0x15, 0), 0), 0, 5)).credit()).isPresent();
    }

    @Test
    void changedFieldsSuspendDepositsUntilRewireNamesTheHead() {
        Cip113WiringClaimV1.Fields v1 = new Cip113WiringClaimV1.Fields(D, filled(0xe1, 28), T, TP);
        recordWiring(outpoint(0xa2, 0), 300, 4, Optional.of(v1));
        Tx tx = new Tx(0x82, List.of(outpoint(0x16, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(outpoint(0xa2, 0)));
        assertThat(admit(tx.deposit(datum(outpoint(0x16, 0), 0), 0, 5)).deposit().orElseThrow().reason())
                .isEqualTo(DepositAdmission.Reason.WIRING_UNPINNED);
        // A rewire that names the older version is rejected; one that names the head approves its fields.
        assertThat(wiring.rewire(store, PARAMS0, T, TP, new byte[28])).isEqualTo(Cip113Wiring.Result.REJECTED_NOT_HEAD);
        assertThat(wiring.rewire(store, outpoint(0xa2, 0), T, TP, new byte[28])).isEqualTo(Cip113Wiring.Result.APPROVED);
        Tx later = new Tx(0x83, List.of(outpoint(0x17, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(outpoint(0xa2, 0)));
        assertThat(admit(later.deposit(datum(outpoint(0x17, 0), 0), 0, 5)).credit()).isPresent();
        // The suspended deposit stays uncredited: suspension is a reason, not a state.
        assertThat(admission.deposit(store, datum(outpoint(0x16, 0), 0).depositId(VAULT)).orElseThrow().status())
                .isEqualTo(DepositRecordV1.Status.UNCREDITED);
    }

    @Test
    void anUndecodableVersionIsUnapprovedAndNeverThrows() {
        recordWiring(outpoint(0xa3, 0), 400, 0, Optional.empty());
        assertThat(wiring.approved(store, wiring.head(store).orElseThrow())).isEmpty();
        assertThat(wiring.rewire(store, outpoint(0xa3, 0), T, TP, new byte[28]))
                .isEqualTo(Cip113Wiring.Result.REJECTED_UNDECODABLE);
        assertThat(Cip113WiringClaimV1.Fields.ofDatum(new byte[] {(byte) 0xd8, 0x79, (byte) 0x80})).isEmpty();
        assertThat(Cip113WiringClaimV1.Fields.ofDatum(new byte[] {0x01, 0x02})).isEmpty();
    }

    @Test
    void twoWiringChangesInOneBlockMakeTheHigherTransactionIndexTheHead() {
        Cip113WiringClaimV1.Fields v1 = new Cip113WiringClaimV1.Fields(D, filled(0xe2, 28), T, TP);
        Cip113WiringClaimV1.Fields v2 = new Cip113WiringClaimV1.Fields(D, filled(0xe3, 28), T, TP);
        recordWiring(outpoint(0xa5, 0), 500, 7, Optional.of(v2));
        recordWiring(outpoint(0xa4, 0), 500, 3, Optional.of(v1));
        assertThat(wiring.head(store).orElseThrow().outpoint()).isEqualTo(outpoint(0xa5, 0));
    }

    @Test
    void aSameBlockWiringChangeClassifiesEachDepositWithTheVersionItReferenced() {
        // Upgrade U (tx index 5) spends PARAMS0 and creates P1 with a new issuance logic. A deposit before U in the
        // block referenced PARAMS0; one after U referenced P1. Wiring observations are recorded first.
        Cip113WiringClaimV1.Fields v1 = new Cip113WiringClaimV1.Fields(D, filled(0xe4, 28), T, TP);
        byte[] before = new Tx(0x91, List.of(outpoint(0x18, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(PARAMS0)).deposit(datum(outpoint(0x18, 0), 0), 0, 5);
        byte[] after = new Tx(0x92, List.of(outpoint(0x19, 0)), List.of(withdrawal(D, 0L), withdrawal(T, 0L)), false,
                List.of(outpoint(0xa6, 0))).deposit(datum(outpoint(0x19, 0), 0), 0, 5);
        String first = null;
        for (List<byte[]> order : List.of(List.of(before, after), List.of(after, before))) {
            ProgrammableDepositAdmissionTest fresh = new ProgrammableDepositAdmissionTest();
            fresh.recordWiring(outpoint(0xa6, 0), 600, 5, Optional.of(v1));
            for (byte[] claim : order) fresh.admit(claim);
            assertThat(fresh.admission.deposit(fresh.store, datum(outpoint(0x18, 0), 0).depositId(VAULT)).orElseThrow()
                    .status()).isEqualTo(DepositRecordV1.Status.CREDITED);
            assertThat(fresh.admission.deposit(fresh.store, datum(outpoint(0x19, 0), 0).depositId(VAULT)).orElseThrow()
                    .reason()).isEqualTo(DepositAdmission.Reason.WIRING_UNPINNED);
            if (first == null) first = fresh.store.snapshot();
            else assertThat(fresh.store.snapshot()).isEqualTo(first);
        }
    }

    @Test
    void everySameBlockOrderGivesTheSameRecords() {
        // A mixed block: genuine deposits, re-spends, copies, malformed and other outputs (I45, I48).
        ProgrammableDepositDatumV1 a = datum(outpoint(0x1a, 0), 0);
        ProgrammableDepositDatumV1 b = datum(outpoint(0x1b, 1), 1);
        List<byte[]> claims = List.of(
                transfer(0xb1, List.of(outpoint(0x1a, 0))).deposit(a, 0, 10),
                thirdParty(0xb2, List.of(outpoint(0xb1, 0))).deposit(a, 0, 10),
                transfer(0xb3, List.of(outpoint(0x1b, 1))).deposit(b, 1, 20),
                transfer(0xb4, List.of(outpoint(0x77, 0))).deposit(b, 0, 99),
                transfer(0xb5, List.of(outpoint(0x78, 0))).claim(0, ProgrammableDepositClaimV1.Shape.MALFORMED,
                        Optional.empty(), 1, 0),
                transfer(0xb6, List.of(outpoint(0x79, 0))).claim(0, ProgrammableDepositClaimV1.Shape.OTHER,
                        Optional.empty(), 0, 0));
        String expected = null;
        for (List<byte[]> order : permutations(claims)) {
            ProgrammableDepositAdmissionTest fresh = new ProgrammableDepositAdmissionTest();
            long credits = 0;
            for (byte[] claim : order) {
                if (fresh.admit(claim).credit().isPresent()) credits++;
            }
            assertThat(credits).isEqualTo(2);
            String snapshot = fresh.store.snapshot();
            if (expected == null) expected = snapshot;
            else assertThat(snapshot).isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------ reconciliation (D14 a)

    @Test
    void reconciliationCountsOnlySpendableBacking() {
        ProgrammableDepositDatumV1 credited = datum(outpoint(0x1c, 0), 0);
        admit(transfer(0xc1, List.of(outpoint(0x1c, 0))).deposit(credited, 0, 500));
        Hash32 id = credited.depositId(VAULT);
        List<ProgrammableReconciliation.LiveOutput> live = List.of(
                new ProgrammableReconciliation.LiveOutput(outpoint(0xc1, 0), ProgrammableDepositClaimV1.Shape.DEPOSIT,
                        Optional.of(id), BigInteger.valueOf(500), BigInteger.valueOf(2_000_000), 0),
                // A copy of the credited datum carrying a foreign token: unavailable, never backing.
                new ProgrammableReconciliation.LiveOutput(outpoint(0xc2, 0), ProgrammableDepositClaimV1.Shape.DEPOSIT,
                        Optional.of(id), BigInteger.valueOf(300), BigInteger.valueOf(2_000_000), 1),
                new ProgrammableReconciliation.LiveOutput(outpoint(0xc3, 0),
                        ProgrammableDepositClaimV1.Shape.CONTINUATION, Optional.empty(), BigInteger.valueOf(1_000),
                        BigInteger.valueOf(5_000_000), 0),
                new ProgrammableReconciliation.LiveOutput(outpoint(0xc4, 0), ProgrammableDepositClaimV1.Shape.DEPOSIT,
                        Optional.of(Hash32.of(filled(0xee, 32))), BigInteger.valueOf(9), BigInteger.ONE, 0));
        ProgrammableReconciliation.Report report = ProgrammableReconciliation.reconcile(live,
                depositId -> admission.deposit(store, depositId), BigInteger.valueOf(1_800));
        assertThat(report.backing()).isEqualTo(BigInteger.valueOf(1_500));
        assertThat(report.unavailable()).isEqualTo(BigInteger.valueOf(300));
        assertThat(report.donorDust()).isEqualTo(BigInteger.valueOf(9));
        assertThat(report.shortfall()).isEqualTo(BigInteger.valueOf(300));
        assertThat(report.shortfallFlagged()).isTrue();
    }

    // ------------------------------------------------------------------ fixtures

    /** One creating transaction: its inputs, withdrawals (null = OVERFLOW), mint flag and reference inputs. */
    record Tx(int fill, List<ProgrammableDepositDatumV1.Outpoint> inputs,
              List<ProgrammableDepositClaimV1.Withdrawal> withdrawals, boolean positiveMint,
              List<ProgrammableDepositDatumV1.Outpoint> references) {
        byte[] deposit(ProgrammableDepositDatumV1 datum, int index, long quantity) {
            boolean spent = datum.originSpent(inputs, index);
            return claim(index, ProgrammableDepositClaimV1.Shape.DEPOSIT, Optional.of(
                    new ProgrammableDepositClaimV1.Deposit(datum, spent)), quantity, 0);
        }

        byte[] claim(int index, ProgrammableDepositClaimV1.Shape shape,
                     Optional<ProgrammableDepositClaimV1.Deposit> deposit, long quantity, long others) {
            return new ProgrammableDepositClaimV1(VAULT, Hash32.of(filled(fill, 32)), index, 1_000, Hash32.of(BLOCK),
                    shape, deposit, BigInteger.valueOf(quantity), BigInteger.valueOf(2_000_000), others,
                    Hash32.of(filled(0x5e, 32)), Optional.ofNullable(withdrawals), positiveMint,
                    Optional.ofNullable(references)).encode();
        }
    }

    static Tx transfer(int fill, List<ProgrammableDepositDatumV1.Outpoint> inputs) {
        return new Tx(fill, inputs, List.of(withdrawal(D, 0L), withdrawal(T, 0L), withdrawal(filled(0x5d, 28), null)),
                false, List.of(PARAMS0, outpoint(0x6b, 1)));
    }

    static Tx thirdParty(int fill, List<ProgrammableDepositDatumV1.Outpoint> inputs) {
        return new Tx(fill, inputs, List.of(withdrawal(D, 1L), withdrawal(TP, 0L)), false, List.of(PARAMS0));
    }

    static Tx issuance(int fill, List<ProgrammableDepositDatumV1.Outpoint> inputs, boolean mint,
                       Optional<Long> ignored) {
        return new Tx(fill, inputs, List.of(withdrawal(ISSUANCE, null), withdrawal(filled(0x5d, 28), 0L)), mint,
                List.of(PARAMS0));
    }

    static ProgrammableDepositClaimV1.Withdrawal withdrawal(byte[] hash, Long ctor) {
        return new ProgrammableDepositClaimV1.Withdrawal(hash, Optional.ofNullable(ctor));
    }

    ProgrammableDepositAdmission.Outcome admit(byte[] claim) {
        ProgrammableDepositClaimV1 decoded = ProgrammableDepositClaimV1.decode(claim);
        return admission.admit(store, new DepositAdmission.ObservedDeposit(DEPOSITS, decoded.txId().bytes(),
                decoded.outputIndex(), decoded.slot(), BLOCK, claim), 77, candidate -> Optional.empty());
    }

    void recordWiring(ProgrammableDepositDatumV1.Outpoint at, long slot, long txIndex,
                      Optional<Cip113WiringClaimV1.Fields> fields) {
        byte[] claim = new Cip113WiringClaimV1(at.txId(), at.index(), slot, txIndex, Hash32.of(BLOCK), fields).encode();
        assertThat(wiring.record(store, new DepositAdmission.ObservedDeposit(WIRING, at.txId().bytes(), at.index(),
                slot, BLOCK, claim))).isEqualTo(Cip113Wiring.Result.RECORDED);
    }

    static ProgrammableDepositDatumV1 datum(ProgrammableDepositDatumV1.Outpoint origin, long outputIndex) {
        return new ProgrammableDepositDatumV1(ALICE, smartWallet(ALICE), ALICE, origin.txId(), origin.index(),
                outputIndex);
    }

    static byte[] cat(byte[] left, byte[] right) {
        byte[] out = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    static byte[] smartWallet(byte[] key) {
        return cat(cat(new byte[] {0x10}, PLB), key);
    }

    static byte[] otherPlb() {
        byte[] other = smartWallet(ALICE);
        other[1] ^= 1;
        return other;
    }

    static ProgrammableDepositDatumV1.Outpoint outpoint(int fill, long index) {
        return new ProgrammableDepositDatumV1.Outpoint(Hash32.of(filled(fill, 32)), index);
    }

    static byte[] filled(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    /** {@code definite} with every definite array head ({@code 0x80..0x97}) turned indefinite ({@code 9f .. ff}). */
    static byte[] indefinite(byte[] definite) {
        // The datum is d879 84 <b28> <b57> <b28> d879 83 <b32> <uint> <uint>.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xd8, 0x79, (byte) 0x9f});
        int at = 3;
        int fieldsEnd = definite.length;
        int originStart = indexOf(definite, new byte[] {(byte) 0xd8, 0x79, (byte) 0x83}, 3);
        out.write(definite, at, originStart - at);
        out.writeBytes(new byte[] {(byte) 0xd8, 0x79, (byte) 0x9f});
        out.write(definite, originStart + 3, fieldsEnd - originStart - 3);
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xff});
        return out.toByteArray();
    }

    /** The general constructor form {@code d866 82 00 [fields]} of the outer constructor. */
    static byte[] general(byte[] definite) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xd8, 0x66, (byte) 0x82, 0x00});
        out.write(definite, 2, definite.length - 2);
        return out.toByteArray();
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) return i;
        }
        throw new IllegalArgumentException("not found");
    }

    static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) return List.of(List.of());
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> one = new ArrayList<>();
                one.add(head);
                one.addAll(tail);
                out.add(one);
            }
        }
        return out;
    }
}
