package org.yanoproject.x.assets.cardano.gateway;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetDepositClaimV1;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.VaultOutputShape;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1ObserverConsensusIdentity;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-059 V5 fixtures A1–A10 through the observer, each with its stated outcome after
 * {@link DepositAdmission}: A9 and A10 credit exactly the genuine deposits, the others never
 * credit, and nothing throws or halts (I1, I2, I16).
 */
class AssetVaultDepositObserverTest {
    static final String OBSERVER = "deposits";
    static final byte[] ADA_HASH = filled(28, 0x01);
    static final byte[] USDM_HASH = filled(28, 0x02);
    static final byte[] EURC_HASH = filled(28, 0x03);
    static final String POLICY = "f0".repeat(28);
    static final AssetId USDM = AssetId.ofHex(POLICY, "5553444d");
    static final AssetId EURC = AssetId.ofHex(POLICY, "45555243");
    static final AssetId UNREGISTERED = AssetId.ofHex("ab".repeat(28), "4241444d");
    static final String ADA_VAULT = enterprise(ADA_HASH, 0);
    static final String USDM_VAULT = enterprise(USDM_HASH, 0);
    static final String EURC_VAULT = enterprise(EURC_HASH, 0);
    static final String GENESIS = "6c".repeat(32);
    static final byte[] ALICE = filled(28, 0xa1);
    static final byte[] BLOCK_HASH = filled(32, 0xb1);
    static final long SLOT = 9_000;

    static byte[] filled(int length, int value) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static String enterprise(byte[] hash, int network) {
        return AddressProvider.getEntAddress(Credential.fromScript(hash),
                network == 0 ? Networks.testnet() : Networks.mainnet()).getAddress();
    }

    static Map<String, String> settings(String chainId) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("type", AssetVaultDepositObserverProvider.TYPE);
        settings.put("domain.address-network-id", "0");
        settings.put("domain.network-magic", "42");
        settings.put("domain.chain-id", chainId);
        settings.put("domain.genesis-id", GENESIS);
        settings.put("vaults.0.asset", "lovelace");
        settings.put("vaults.0.script-hash", HexFormat.of().formatHex(ADA_HASH));
        settings.put("vaults.0.address", ADA_VAULT);
        settings.put("vaults.1.asset", POLICY + ".5553444d");
        settings.put("vaults.1.script-hash", HexFormat.of().formatHex(USDM_HASH));
        settings.put("vaults.1.address", USDM_VAULT);
        settings.put("vaults.2.asset", POLICY + ".45555243");
        settings.put("vaults.2.script-hash", HexFormat.of().formatHex(EURC_HASH));
        settings.put("vaults.2.address", EURC_VAULT);
        return settings;
    }

    static final AssetVaultDepositObserver OBSERVER_INSTANCE =
            new AssetVaultDepositObserver(OBSERVER, settings("payments-1"));
    static final Hash32 DOMAIN = OBSERVER_INSTANCE.settings().domainId();
    static final Hash32 ADA_ID = GatewayIds.vaultId(DOMAIN, AssetId.ADA, 0);
    static final Hash32 USDM_ID = GatewayIds.vaultId(DOMAIN, USDM, 0);
    static final Hash32 EURC_ID = GatewayIds.vaultId(DOMAIN, EURC, 0);

    static byte[] depositDatum(byte[] beneficiary) {
        return new DepositDatumV1(beneficiary, new byte[] {0x60, 1, 2, 3}, filled(28, 0xcc)).encode();
    }

    static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(quantity)).build();
    }

    static Amount token(AssetId asset, long quantity) {
        String policy = HexFormat.of().formatHex(asset.policyId());
        String name = HexFormat.of().formatHex(asset.assetName());
        return Amount.builder().unit(policy + "." + name).policyId(policy).assetNameBytes(asset.assetName())
                .quantity(BigInteger.valueOf(quantity)).build();
    }

    static TransactionOutput output(String address, byte[] datum, Amount... amounts) {
        return TransactionOutput.builder().address(address).amounts(List.of(amounts))
                .inlineDatum(datum == null ? null : HexFormat.of().formatHex(datum)).build();
    }

    static TransactionBody tx(int hashByte, TransactionOutput... outputs) {
        return TransactionBody.builder().txHash(HexFormat.of().formatHex(filled(32, hashByte)))
                .outputs(List.of(outputs)).build();
    }

    static Block block(List<Integer> invalid, TransactionBody... transactions) {
        return Block.builder().transactionBodies(List.of(transactions)).invalidTransactions(invalid).build();
    }

    /** The consuming machine's side: canonical order, then admission over one staged store. */
    static final class Ledger {
        final TreeMap<String, byte[]> state = new TreeMap<>();
        final Map<Hash32, DepositAdmission.VaultBinding> registry = new HashMap<>(Map.of(
                ADA_ID, new DepositAdmission.VaultBinding(ADA_ID, AssetId.ADA, true),
                USDM_ID, new DepositAdmission.VaultBinding(USDM_ID, USDM, true),
                EURC_ID, new DepositAdmission.VaultBinding(EURC_ID, EURC, true)));
        final AssetAccounts.Store store = new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) {
                return Optional.ofNullable(state.get(HexFormat.of().formatHex(key)));
            }
            @Override public void put(byte[] key, byte[] value) {
                state.put(HexFormat.of().formatHex(key), value);
            }
            @Override public void delete(byte[] key) {
                state.remove(HexFormat.of().formatHex(key));
            }
        };
        final DepositAdmission admission = new DepositAdmission("ap/".getBytes(StandardCharsets.US_ASCII),
                OBSERVER, id -> Optional.ofNullable(registry.get(id)));
        final List<DepositAdmission.CreditInstruction> credits = new ArrayList<>();

        List<DepositAdmission.Outcome> apply(List<L1Observation> observations) {
            List<L1Observation> ordered = new ArrayList<>(observations);
            ordered.sort(L1Observation.CANONICAL_ORDER);
            List<DepositAdmission.Outcome> outcomes = new ArrayList<>();
            for (L1Observation observation : ordered) {
                DepositAdmission.Outcome outcome = admission.admit(store, new DepositAdmission.ObservedDeposit(
                        observation.observerId(), observation.transactionAnchor().transactionHash(),
                        observation.eventOrdinal(), observation.slot(), observation.blockHash(),
                        observation.claim()), 1, candidate -> Optional.empty());
                outcome.credit().ifPresent(credits::add);
                outcomes.add(outcome);
            }
            return outcomes;
        }
    }

    static List<L1Observation> observe(Block block) {
        return OBSERVER_INSTANCE.observe(SLOT, BLOCK_HASH, block);
    }

    static AssetDepositClaimV1 claim(L1Observation observation) {
        return AssetDepositClaimV1.decode(observation.claim());
    }

    static DepositRecordV1 only(List<DepositAdmission.Outcome> outcomes) {
        assertThat(outcomes).hasSize(1);
        return outcomes.getFirst().record().orElseThrow();
    }

    @Test
    void a1UnsolicitedAdaIsReportedAndRecordedAsUnattributed() {
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(),
                tx(0x11, output(ADA_VAULT, null, lovelace(5_000_000)),
                        output(USDM_VAULT, null, lovelace(2_000_000)))));
        assertThat(observations).extracting(L1Observation::eventOrdinal).containsExactly(0L, 1L);
        assertThat(observations).allSatisfy(o -> assertThat(claim(o).shape()).isEqualTo(VaultOutputShape.OTHER));
        List<DepositAdmission.Outcome> outcomes = ledger.apply(observations);
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.record().orElseThrow().status()).isEqualTo(DepositRecordV1.Status.UNATTRIBUTED);
            assertThat(outcome.credit()).isEmpty();
        });
    }

    @Test
    void a2MixedAssetsInOneOutputAreNeverCredited() {
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(),
                tx(0x12, output(USDM_VAULT, depositDatum(ALICE), lovelace(1_500_000), token(USDM, 100), token(EURC, 5)),
                        output(ADA_VAULT, depositDatum(ALICE), lovelace(9_000_000), token(USDM, 1)))));
        AssetDepositClaimV1 tokenClaim = claim(observations.getFirst());
        assertThat(tokenClaim.registeredQuantity()).isEqualTo(BigInteger.valueOf(100));
        assertThat(tokenClaim.otherAssetCount()).isEqualTo(1);
        List<DepositAdmission.Outcome> outcomes = ledger.apply(observations);
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.record().orElseThrow().reason()).isEqualTo(DepositAdmission.Reason.MIXED_ASSETS);
            assertThat(outcome.credit()).isEmpty();
        });
    }

    @Test
    void a3AnUnregisteredTokenIsNeverCredited() {
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(),
                tx(0x13, output(USDM_VAULT, depositDatum(ALICE), lovelace(1_500_000), token(UNREGISTERED, 100)),
                        output(enterprise(filled(28, 0x09), 0), depositDatum(ALICE), lovelace(1_500_000),
                                token(UNREGISTERED, 100)))));
        assertThat(observations).hasSize(1); // the second output is not at a vault address
        DepositRecordV1 record = only(ledger.apply(observations));
        assertThat(record.status()).isEqualTo(DepositRecordV1.Status.UNCREDITED);
        assertThat(record.reason()).isEqualTo(DepositAdmission.Reason.UNREGISTERED_ASSET);
    }

    /**
     * C1/B7: a deposit whose refund address is a pointer the vault script could never pay (a padded natural, or a
     * transaction index of 2^16, beyond Conway's limit) is a malformed deposit: recorded, never credited.
     */
    @Test
    void aDepositWithAnUnpayablePointerRefundAddressIsMalformed() {
        String key = "1c".repeat(28);
        String account = "a1".repeat(28);
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(), tx(0x15,
                output(USDM_VAULT, HexFormat.of().parseHex("d87983581c" + account + "5822" + "40" + "01".repeat(28)
                        + "0084800000" + "581c" + key), lovelace(1_500_000), token(USDM, 1)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87983581c" + account + "5821" + "40" + "01".repeat(28)
                        + "80000000" + "581c" + key), lovelace(1_500_000), token(USDM, 1)))));
        assertThat(observations).hasSize(2);
        for (L1Observation observation : observations) {
            assertThat(claim(observation).shape()).isEqualTo(VaultOutputShape.DEPOSIT);
            assertThat(claim(observation).beneficiary()).isEqualTo(
                    new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_UNDECODABLE));
        }
        assertThat(ledger.apply(observations)).allSatisfy(outcome -> {
            assertThat(outcome.credit()).isEmpty();
            assertThat(outcome.record().orElseThrow().reason()).isEqualTo(DepositAdmission.Reason.DATUM_MALFORMED);
        });
    }

    @Test
    void a4MalformedOrOversizedDatumsAreRecordedNeverCreditedAndNeverThrow() {
        byte[] oversized = new byte[20_000];
        oversized[0] = (byte) 0xd8;
        oversized[1] = (byte) 121;
        oversized[2] = (byte) 0x5a; // byte string with a 4-byte length
        int length = oversized.length - 7;
        oversized[3] = (byte) (length >>> 24);
        oversized[4] = (byte) (length >>> 16);
        oversized[5] = (byte) (length >>> 8);
        oversized[6] = (byte) length;
        byte[] deep = new byte[12_000];
        deep[0] = (byte) 0xd8;
        deep[1] = (byte) 121;
        Arrays.fill(deep, 2, deep.length - 1, (byte) 0x81);
        byte[] deepOther = deep.clone();
        deepOther[1] = (byte) 0x7f;
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(), tx(0x14,
                output(USDM_VAULT, HexFormat.of().parseHex("d87983010203"), lovelace(1_500_000), token(USDM, 1)),
                output(USDM_VAULT, oversized, lovelace(1_500_000), token(USDM, 1)),
                output(USDM_VAULT, deep, lovelace(1_500_000), token(USDM, 1)),
                output(USDM_VAULT, deepOther, lovelace(1_500_000), token(USDM, 1)),
                output(USDM_VAULT, new byte[] {(byte) 0xd8}, lovelace(1_500_000), token(USDM, 1)))));
        assertThat(observations).hasSize(5);
        assertThat(claim(observations.get(0)).beneficiary()).isEqualTo(
                new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_UNDECODABLE));
        assertThat(claim(observations.get(1)).beneficiary()).isEqualTo(
                new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_OUT_OF_BOUNDS));
        assertThat(claim(observations.get(2)).beneficiary()).isEqualTo(
                new AssetDepositClaimV1.Malformed(AssetDepositClaimV1.MalformedReason.DATUM_OUT_OF_BOUNDS));
        assertThat(claim(observations.get(3)).shape()).isEqualTo(VaultOutputShape.OTHER);
        assertThat(claim(observations.get(4)).shape()).isEqualTo(VaultOutputShape.OTHER);
        List<DepositAdmission.Outcome> outcomes = ledger.apply(observations);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.credit()).isEmpty());
        assertThat(outcomes).filteredOn(o -> o.record().orElseThrow().shape() == VaultOutputShape.DEPOSIT)
                .allSatisfy(o -> assertThat(o.record().orElseThrow().reason())
                        .isEqualTo(DepositAdmission.Reason.DATUM_MALFORMED));
    }

    @Test
    void a5WrongNetworkIsNotObservedAndWrongChainIsNeverCredited() {
        List<L1Observation> wrongNetwork = observe(block(List.of(), tx(0x15,
                output(enterprise(USDM_HASH, 1), depositDatum(ALICE), lovelace(1_500_000), token(USDM, 100)))));
        assertThat(wrongNetwork).isEmpty();

        AssetVaultDepositObserver otherChain = new AssetVaultDepositObserver(OBSERVER, settings("payments-2"));
        List<L1Observation> foreign = otherChain.observe(SLOT, BLOCK_HASH, block(List.of(), tx(0x15,
                output(USDM_VAULT, depositDatum(ALICE), lovelace(1_500_000), token(USDM, 100)))));
        assertThat(foreign).hasSize(1);
        DepositRecordV1 record = only(new Ledger().apply(foreign));
        assertThat(record.status()).isEqualTo(DepositRecordV1.Status.UNCREDITED);
        assertThat(record.reason()).isEqualTo(DepositAdmission.Reason.UNKNOWN_VAULT);
    }

    @Test
    void a6PhaseTwoInvalidTransactionsAreNeverObserved() {
        List<L1Observation> observations = observe(block(List.of(0),
                tx(0x16, output(USDM_VAULT, depositDatum(ALICE), lovelace(1_500_000), token(USDM, 100))),
                tx(0x17, output(ADA_VAULT, depositDatum(ALICE), lovelace(3_000_000)))));
        assertThat(observations).singleElement().satisfies(o ->
                assertThat(o.transactionAnchor().transactionHash()).isEqualTo(filled(32, 0x17)));
    }

    static final String CONTINUATION = "d87a8181" + "5820" + "77".repeat(32);

    @Test
    void a7AFakeContinuationOrPayoutWithoutThreadTokenIsNeverCredited() {
        Ledger ledger = new Ledger();
        // A donated, well-formed continuation is vault custody and is not even reported; malformed
        // look-alikes and a pre-M4b migration-in look-alike are unattributed. None credits.
        List<L1Observation> observations = observe(block(List.of(), tx(0x18,
                output(USDM_VAULT, HexFormat.of().parseHex(CONTINUATION), lovelace(1_500_000), token(USDM, 1_000_000)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87a9f5820" + "77".repeat(32) + "ff"),
                        lovelace(1_500_000), token(USDM, 1_000_000)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87c80"), lovelace(1_500_000), token(USDM, 5)))));
        assertThat(observations).extracting(L1Observation::eventOrdinal).containsExactly(1L, 2L);
        List<DepositAdmission.Outcome> outcomes = ledger.apply(observations);
        assertThat(outcomes).hasSize(2).allSatisfy(outcome -> {
            assertThat(outcome.record().orElseThrow().status()).isEqualTo(DepositRecordV1.Status.UNATTRIBUTED);
            assertThat(outcome.credit()).isEmpty();
        });
        // Payout confirmation is M4 (D10); in M3 no observation of any shape can change a claim.
    }

    @Test
    void a8AContinuationOutputIsNeverCreditedAndAFloorTopUpIsOperational() {
        Ledger ledger = new Ledger();
        TransactionBody spend = tx(0x19,
                output(USDM_VAULT, HexFormat.of().parseHex(CONTINUATION), lovelace(2_000_000), token(USDM, 500)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87b80"), lovelace(5_000_000)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87b80"), lovelace(5_000_000), token(USDM, 1)));
        List<L1Observation> observations = observe(block(List.of(), spend));
        assertThat(observations).extracting(L1Observation::eventOrdinal).containsExactly(1L, 2L); // M3-F1
        List<DepositAdmission.Outcome> outcomes = ledger.apply(observations);
        assertThat(outcomes).extracting(o -> o.record().orElseThrow().status())
                .containsExactly(DepositRecordV1.Status.OPERATIONAL, DepositRecordV1.Status.UNATTRIBUTED); // M3-F2
        assertThat(ledger.credits).isEmpty();
    }

    static final AssetId NFT = AssetId.ofHex("cd".repeat(28), "4e4654");

    /** Fable P2 probe: 2 ADA + 50 USDM + 1 foreign NFT, under each custody-like datum. */
    @Test
    void junkCarriedByContinuationsAndMigrationsIsReportedAndRecordedUnattributed() {
        Ledger ledger = new Ledger();
        List<L1Observation> observations = observe(block(List.of(), tx(0x1b,
                output(USDM_VAULT, HexFormat.of().parseHex("d87a8180"), lovelace(2_000_000), token(USDM, 50),
                        token(NFT, 1)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87c82" + "5820" + "11".repeat(32) + "5820"
                        + "22".repeat(32)), lovelace(2_000_000), token(USDM, 50), token(NFT, 1)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87b80"), lovelace(2_000_000), token(USDM, 50),
                        token(NFT, 1)),
                output(ADA_VAULT, HexFormat.of().parseHex("d87a8180"), lovelace(2_000_000), token(NFT, 1)),
                output(USDM_VAULT, HexFormat.of().parseHex("d87a8180"), lovelace(2_000_000), token(USDM, 50)))));
        // The clean continuation (index 4) is the only output not reported.
        assertThat(observations).extracting(L1Observation::eventOrdinal).containsExactly(0L, 1L, 2L, 3L);
        assertThat(ledger.apply(observations)).extracting(o -> o.record().orElseThrow().status() + "/"
                + o.record().orElseThrow().reason()).containsExactly(
                "UNATTRIBUTED/MIXED_ASSETS", "UNATTRIBUTED/NOT_A_DEPOSIT", "UNATTRIBUTED/MIXED_ASSETS",
                "UNATTRIBUTED/MIXED_ASSETS");
        assertThat(ledger.credits).isEmpty();
    }

    @Test
    void a9TwoDepositsInOneTransactionAreBothCreditedExactlyOnce() {
        Ledger ledger = new Ledger();
        Block block = block(List.of(), tx(0x1a,
                output(USDM_VAULT, depositDatum(ALICE), lovelace(1_500_000), token(USDM, 100)),
                output(enterprise(filled(28, 0x55), 0), null, lovelace(9_000_000)),
                output(USDM_VAULT, depositDatum(ALICE), lovelace(1_200_000), token(USDM, 250))));
        List<L1Observation> observations = observe(block);
        assertThat(observations).extracting(L1Observation::eventOrdinal).containsExactly(0L, 2L);
        ledger.apply(observations);
        assertThat(ledger.credits).extracting(DepositAdmission.CreditInstruction::amount)
                .containsExactly(BigInteger.valueOf(100), BigInteger.valueOf(250));
        assertThat(ledger.credits).extracting(DepositAdmission.CreditInstruction::minAdaCredit)
                .containsExactly(BigInteger.valueOf(1_500_000), BigInteger.valueOf(1_200_000));
        // Rescan and re-delivery are no-ops.
        assertThat(ledger.apply(observe(block))).allSatisfy(outcome ->
                assertThat(outcome.disposition()).isEqualTo(DepositAdmission.Disposition.DUPLICATE));
        assertThat(ledger.credits).hasSize(2);
    }

    @Test
    void a10ADepositAndAVaultSpendInOneBlockCreditOnlyTheDepositInBothHashOrders() {
        for (int[] hashes : new int[][] {{0x20, 0xf0}, {0xf0, 0x20}}) {
            for (boolean spendFirst : new boolean[] {true, false}) {
                TransactionBody deposit = tx(hashes[0],
                        output(EURC_VAULT, depositDatum(ALICE), lovelace(1_400_000), token(EURC, 42)));
                TransactionBody spend = tx(hashes[1],
                        output(EURC_VAULT, HexFormat.of().parseHex(CONTINUATION), lovelace(3_000_000), token(EURC, 958)),
                        output(enterprise(filled(28, 0x66), 0), null, lovelace(1_000_000), token(EURC, 42)));
                Block block = spendFirst ? block(List.of(), spend, deposit) : block(List.of(), deposit, spend);
                Ledger ledger = new Ledger();
                List<DepositAdmission.Outcome> outcomes = ledger.apply(observe(block));
                assertThat(outcomes).hasSize(1); // the continuation is vault custody, not reported
                assertThat(ledger.credits).singleElement().satisfies(credit -> {
                    assertThat(credit.amount()).isEqualTo(BigInteger.valueOf(42));
                    assertThat(credit.vaultId()).isEqualTo(EURC_ID);
                    assertThat(credit.depositId()).isEqualTo(
                            GatewayIds.depositId(EURC_ID, Hash32.of(filled(32, hashes[0])), 0));
                });
            }
        }
    }

    @Test
    void realBlockBytesThroughTheNodeParserGiveTheSameClaims() {
        L1BlockBytes.Tx deposits = new L1BlockBytes.Tx(1, List.of(
                new L1BlockBytes.Output(addressBytes(USDM_VAULT), 1_500_000, Map.of(USDM, 100L), depositDatum(ALICE)),
                new L1BlockBytes.Output(addressBytes(ADA_VAULT), 7_000_000, Map.of(), depositDatum(ALICE)),
                new L1BlockBytes.Output(addressBytes(EURC_VAULT), 1_300_000, Map.of(EURC, 9L, USDM, 1L),
                        depositDatum(ALICE))));
        L1BlockBytes.Tx invalid = new L1BlockBytes.Tx(2, List.of(
                new L1BlockBytes.Output(addressBytes(USDM_VAULT), 1_500_000, Map.of(USDM, 999L), depositDatum(ALICE))));
        Block block = L1BlockBytes.parse(L1BlockBytes.block(77, SLOT, List.of(deposits, invalid), List.of(1)));
        byte[] blockHash = HexFormat.of().parseHex(block.getHeader().getHeaderBody().getBlockHash());
        List<L1Observation> observations = OBSERVER_INSTANCE.observe(SLOT, blockHash, block);
        assertThat(observations).hasSize(3);
        Ledger ledger = new Ledger();
        ledger.apply(observations);
        assertThat(ledger.credits).extracting(DepositAdmission.CreditInstruction::asset)
                .containsExactlyInAnyOrder(USDM, AssetId.ADA);
        AssetDepositClaimV1 mixed = claim(observations.get(2));
        assertThat(mixed.otherAssetCount()).isEqualTo(1);
        assertThat(mixed.txId().hex()).isEqualTo(block.getTransactionBodies().getFirst().getTxHash());
    }

    @Test
    void settingsAreStrictAndTheIdentityCoversEveryConsensusValue() {
        L1ObserverConsensusIdentity identity = new AssetVaultDepositObserverProvider()
                .consensusIdentity(OBSERVER, settings("payments-1"));
        assertThat(identity.claimSchema()).isEqualTo(AssetDepositClaimV1.CLAIM_SCHEMA);
        for (Map.Entry<String, String> change : Map.of(
                "domain.network-magic", "43", "domain.chain-id", "payments-3", "domain.genesis-id", "6d".repeat(32),
                "domain.profile-version", "2", "datum.max-bytes", "1024", "datum.max-depth", "8",
                "min-stability-depth", "3", "domain.application-id", "other-app").entrySet()) {
            Map<String, String> changed = new HashMap<>(settings("payments-1"));
            changed.put(change.getKey(), change.getValue());
            assertThat(new AssetVaultDepositObserverProvider().consensusIdentity(OBSERVER, changed)
                    .canonicalIdentityBytes()).as(change.getKey()).isNotEqualTo(identity.canonicalIdentityBytes());
        }
        assertRejected("vaults.3.asset", POLICY + ".5553444d"); // gap-free only, and I29
        Map<String, String> duplicate = new HashMap<>(settings("payments-1"));
        duplicate.put("vaults.2.asset", POLICY + ".5553444d");
        assertThatThrownBy(() -> new AssetVaultDepositObserver(OBSERVER, duplicate)).hasMessageContaining("I29");
        assertRejected("vaults.1.address", USDM_VAULT.replace("addr_test", "addr"));
        assertRejected("vaults.1.vault-id", "00".repeat(32));
        assertRejected("vaults.1.ordinal", "1");
        assertRejected("vaults.1.script-hash", "02".repeat(27) + "03");
        assertRejected("domain.genesis-id", "6C".repeat(32));
        assertRejected("vaults.1.asset", POLICY.toUpperCase() + ".5553444d");
        Map<String, String> declared = new HashMap<>(settings("payments-1"));
        declared.put("vaults.1.vault-id", USDM_ID.hex());
        assertThat(new AssetVaultDepositObserver(OBSERVER, declared).settings().vaults().get(1).vaultId())
                .isEqualTo(USDM_ID);
        assertThatThrownBy(() -> new AssetVaultDepositObserver("bad/id", settings("payments-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minStabilityDepthZeroIsRejected() {
        // ADR-059 D5: a deposit observer never credits from a block that can still roll back.
        assertRejected("min-stability-depth", "0");
        assertRejected("min-stability-depth", "-1");
        Map<String, String> one = new HashMap<>(settings("payments-1"));
        one.put("min-stability-depth", "1");
        assertThat(new AssetVaultDepositObserver(OBSERVER, one).settings().minStabilityDepth()).isEqualTo(1);
        assertThat(new AssetVaultDepositObserver(OBSERVER, settings("payments-1")).settings().minStabilityDepth())
                .isEqualTo(1);
    }

    @Test
    void settingsAndVaultsCompareByContent() {
        AssetVaultDepositSettings first = AssetVaultDepositSettings.parse(settings("payments-1"));
        AssetVaultDepositSettings second = AssetVaultDepositSettings.parse(settings("payments-1"));
        assertThat(first.vaults().get(1)).isEqualTo(second.vaults().get(1))
                .hasSameHashCodeAs(second.vaults().get(1));
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first.vaults().get(1)).isNotEqualTo(first.vaults().get(2));
        AssetVaultDepositSettings.Vault vault = first.vaults().get(1);
        byte[] otherHash = vault.scriptHash();
        otherHash[0] ^= 1;
        assertThat(new AssetVaultDepositSettings.Vault(vault.vaultId(), vault.asset(), vault.assetSetting(),
                otherHash, vault.address())).isNotEqualTo(vault);
        assertThat(AssetVaultDepositSettings.parse(settings("payments-2"))).isNotEqualTo(first);
    }

    @Test
    void observeNeverThrowsOnUnreadableBlocks() {
        TransactionBody noHash = TransactionBody.builder().txHash("zz").outputs(List.of(
                output(USDM_VAULT, depositDatum(ALICE), lovelace(1)))).build();
        TransactionBody nullOutputs = TransactionBody.builder().txHash("aa".repeat(32)).build();
        List<TransactionOutput> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(TransactionOutput.builder().address(USDM_VAULT).inlineDatum("zz").build());
        TransactionBody odd = TransactionBody.builder().txHash("bb".repeat(32)).outputs(withNull).build();
        List<L1Observation> observations = OBSERVER_INSTANCE.observe(SLOT, BLOCK_HASH,
                block(List.of(), noHash, nullOutputs, odd));
        assertThat(observations).singleElement().satisfies(o ->
                assertThat(claim(o).shape()).isEqualTo(VaultOutputShape.OTHER));
        assertThat(OBSERVER_INSTANCE.observe(SLOT, new byte[3], block(List.of()))).isEmpty();
        assertThat(OBSERVER_INSTANCE.observe(SLOT, BLOCK_HASH, null)).isEmpty();
    }

    private static void assertRejected(String key, String value) {
        Map<String, String> changed = new HashMap<>(settings("payments-1"));
        changed.put(key, value);
        assertThatThrownBy(() -> new AssetVaultDepositObserver(OBSERVER, changed)).as(key)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] addressBytes(String bech32) {
        return new com.bloxbean.cardano.client.address.Address(bech32).getBytes();
    }
}
