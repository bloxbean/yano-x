package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRouteSet;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;
import org.yanoproject.x.framework.api.plugin.domain.DomainQueryService;
import org.yanoproject.x.framework.api.plugin.domain.L1TransactionBuilderService;
import org.yanoproject.x.framework.api.plugin.domain.LocalReadModelQueryService;
import org.yanoproject.x.framework.api.plugin.domain.PrivilegedSystemMessageService;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;
import org.yanoproject.x.walletauth.internal.WalletEd25519;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * The payments domain API over the real ledger: every view reads the state the machine wrote, through the same
 * query hook the host calls, and the unsigned deposit builder's transaction goes through the node's block parser and
 * the real deposit observer and is credited by the ledger (#109 §7, ADR-059 D5/D6).
 */
class AssetPaymentsDomainApiTest {
    static final HexFormat HEX = HexFormat.of();
    static final String OTHER = "not-payments";
    static final long EXPIRY = T0 + 60_000;
    static final byte[] SESSION_SEED = filled(32, 0x77);

    final ObjectMapper json = new ObjectMapper();
    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final L1Observer observer = new AssetVaultDepositObserverProvider().create(OBSERVER, observerSettings());
    PaymentsHarness ledger;
    AssetPaymentsDomainApi api;
    int nonce;
    long slot = 9_000;
    long blockNumber = 600;

    static Map<String, String> settings() {
        Map<String, String> settings = new LinkedHashMap<>(PaymentsHarness.settings());
        // Withdrawals reserve only when a payout observer id is configured (D10); none runs in this test.
        settings.put("machines.asset-payments.payout-observer-id", "payouts");
        return settings;
    }

    @BeforeEach
    void setUp() {
        ledger = new PaymentsHarness(settings());
        DomainQueryService queries = new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of(CHAIN, OTHER);
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                if (chainId.equals(OTHER)) {
                    throw new AppQueryException(AppQueryException.Code.UNSUPPORTED, "not this machine");
                }
                return new AppQueryResult(chainId, AssetPaymentsContract.STATE_MACHINE_ID, ledger.height,
                        filled(32, 0x3c), ledger.query(path, params));
            }
        };
        L1TransactionBuilderService l1 = new L1TransactionBuilderService() {
            @Override public long tipSlot() { return 5_000; }
            @Override public SpendableInput selectSpendableInput(String sourceAddress) {
                throw new AssertionError("the payments API never selects node UTxOs");
            }
            @Override public UnsignedTransaction buildPayment(PaymentPlan plan) {
                throw new AssertionError("the payments API builds its own deposit");
            }
        };
        api = new AssetPaymentsDomainApi(new DomainApiContext(Map.of(), queries,
                LocalReadModelQueryService.unavailable(), PrivilegedSystemMessageService.unavailable(), l1));
    }

    // ------------------------------------------------------------------ helpers

    JsonNode call(String routeId, DomainHttpMethod method, String relative, Map<String, String> path,
                  Map<String, List<String>> query, Object body, int expectedStatus) throws Exception {
        Map<String, String> parameters = new LinkedHashMap<>(path);
        parameters.putIfAbsent("chain_id", CHAIN);
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
        DomainApiResponse response = api.handle(new DomainApiRequest(routeId, method,
                "chains/" + parameters.get("chain_id") + "/payments/" + relative, parameters, query, bytes));
        JsonNode node = json.readTree(response.body());
        assertThat(response.status()).as(node.toString()).isEqualTo(expectedStatus);
        return node;
    }

    JsonNode get(String routeId, String relative, Map<String, String> path) throws Exception {
        return call(routeId, DomainHttpMethod.GET, relative, path, Map.of(), null, 200);
    }

    IntentEnvelopeV1 env(TestWallet wallet, String action, byte[] body) {
        return envelope(wallet.accountId(), action, new IntentEnvelopeV1.Owner(), ++nonce, EXPIRY, body);
    }

    Message register(TestWallet wallet) {
        return intent(owner(wallet, env(wallet, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT)));
    }

    /** A CIP-30 {@code getUtxos()} entry: {@code [[tx_id, index], [address, value]]} (legacy output form). */
    static String cip30Utxo(byte[] txId, int index, byte[] address, long lovelace, AssetId token, long quantity) {
        Array input = new Array();
        input.add(new co.nstant.in.cbor.model.ByteString(txId));
        input.add(new co.nstant.in.cbor.model.UnsignedInteger(index));
        Array output = new Array();
        output.add(new co.nstant.in.cbor.model.ByteString(address));
        if (token == null) {
            output.add(new co.nstant.in.cbor.model.UnsignedInteger(lovelace));
        } else {
            Array value = new Array();
            value.add(new co.nstant.in.cbor.model.UnsignedInteger(lovelace));
            co.nstant.in.cbor.model.Map names = new co.nstant.in.cbor.model.Map();
            names.put(new co.nstant.in.cbor.model.ByteString(token.assetName()),
                    new co.nstant.in.cbor.model.UnsignedInteger(quantity));
            co.nstant.in.cbor.model.Map policies = new co.nstant.in.cbor.model.Map();
            policies.put(new co.nstant.in.cbor.model.ByteString(token.policyId()), names);
            value.add(policies);
            output.add(value);
        }
        Array pair = new Array();
        pair.add(input);
        pair.add(output);
        try {
            return HEX.formatHex(CborSerializationUtil.serialize(pair));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Puts a built transaction into real L1 block bytes and returns the deposit observer's observations. */
    List<L1Observation> observe(byte[] transaction) throws Exception {
        Array tx = (Array) CborSerializationUtil.deserialize(transaction);
        Block block = L1BlockBytes.parse(L1BlockBytes.blockOf(++blockNumber, slot += 20,
                List.of(tx.getDataItems().get(0)), List.of(tx.getDataItems().get(1))));
        byte[] hash = HEX.parseHex(block.getHeader().getHeaderBody().getBlockHash());
        List<L1Observation> observations = new ArrayList<>(observer.observe(slot, hash, block));
        observations.sort(L1Observation.CANONICAL_ORDER);
        return observations;
    }

    JsonNode build(Map<String, Object> request, int status) throws Exception {
        return call(AssetPaymentsDomainApi.DEPOSIT_BUILD, DomainHttpMethod.POST, "deposits/build", Map.of(),
                Map.of(), request, status);
    }

    /** Builds, "submits" (observes) and credits one deposit through the API's builder. */
    JsonNode depositVia(Hash32 vault, long quantity, long lovelace, List<String> utxos) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vaultId", vault.hex());
        request.put("quantity", Long.toString(quantity));
        request.put("lovelace", Long.toString(lovelace));
        request.put("changeAddress", HEX.formatHex(alice.address()));
        request.put("utxos", utxos);
        JsonNode built = build(request, 200);
        List<L1Observation> observations = observe(HEX.parseHex(built.path("unsignedTxCborHex").asText()));
        assertThat(observations).hasSize(1);
        ledger.block(T0 + 1_000 + ledger.height, deposit(observations.getFirst()));
        return built;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void routesAreValidatedAndEveryRouteIsRead() {
        assertThat(DomainApiRouteSet.validateAndOrder(api.routes())).hasSize(12);
        assertThat(api.routes()).allSatisfy(route -> {
            assertThat(route.access()).isEqualTo(DomainApiAccess.READ);
            assertThat(route.template()).startsWith("chains/{chain_id}/payments/");
        });
        assertThat(new AssetPaymentsDomainApiProvider().id()).isEqualTo("org.yanoproject.x.asset-payments");
    }

    @Test
    void infoDescribesTheDomainAndTheRegistryAndOtherChainsAreNotFound() throws Exception {
        JsonNode info = get(AssetPaymentsDomainApi.INFO, "info", Map.of());
        assertThat(info.path("intentTopic").asText()).isEqualTo(AssetPaymentsContract.INTENT_TOPIC);
        assertThat(info.path("intentDomain").path("networkMagic").asLong()).isEqualTo(NETWORK_MAGIC);
        assertThat(info.path("intentDomain").path("chainId").asText()).isEqualTo(CHAIN);
        assertThat(info.path("intentDomain").path("genesisId").asText()).isEqualTo(GENESIS);
        assertThat(info.path("domainId").asText()).isEqualTo(DOMAIN.hex());
        assertThat(info.path("withdrawalsEnabled").asBoolean()).isTrue();
        assertThat(info.path("maxIntentTtlMs").asLong()).isEqualTo(600_000);
        assertThat(info.path("vaults")).hasSize(3);
        JsonNode usdm = info.path("vaults").get(1);
        assertThat(usdm.path("vaultId").asText()).isEqualTo(USDM_VAULT.hex());
        assertThat(usdm.path("ticker").asText()).isEqualTo("USDM");
        assertThat(usdm.path("asset").path("unit").asText()).isEqualTo(POLICY + "5553444d");
        assertThat(usdm.path("address").asText()).isEqualTo(USDM_ADDRESS);
        assertThat(usdm.path("depositsEnabled").asBoolean()).isTrue();
        assertThat(usdm.path("proof").path("keyHex").asText())
                .isEqualTo(HEX.formatHex(AssetPaymentsContract.registryKey(USDM_VAULT)));

        JsonNode other = call(AssetPaymentsDomainApi.INFO, DomainHttpMethod.GET, "info", Map.of("chain_id", OTHER),
                Map.of(), null, 404);
        assertThat(other.path("error").asText()).isEqualTo("NOT_A_PAYMENTS_CHAIN");
        JsonNode missing = call(AssetPaymentsDomainApi.INFO, DomainHttpMethod.GET, "info",
                Map.of("chain_id", "nope"), Map.of(), null, 404);
        assertThat(missing.path("error").asText()).isEqualTo("CHAIN_NOT_FOUND");
    }

    @Test
    void unsignedDepositBuilderPaysACreditableDepositThatTheObserverAndLedgerCredit() throws Exception {
        ledger.block(T0, register(alice));
        List<String> utxos = List.of(
                cip30Utxo(filled(32, 0x11), 0, alice.address(), 50_000_000, null, 0),
                cip30Utxo(filled(32, 0x12), 1, alice.address(), 3_000_000, USDM, 1_000));

        JsonNode ada = depositVia(ADA_VAULT, 10_000_000, 0, utxos);
        Transaction tx = Transaction.deserialize(HEX.parseHex(ada.path("unsignedTxCborHex").asText()));
        assertThat(tx.getBody().getTtl()).isEqualTo(5_000 + AssetPaymentsDomainApi.TTL_SLOTS);
        assertThat(tx.getBody().getFee()).isPositive();
        assertThat(tx.getBody().getOutputs().get(ada.path("outputIndex").asInt()).getAddress()).isEqualTo(ADA_ADDRESS);
        assertThat(ada.path("datumHex").asText()).isEqualTo(HEX.formatHex(
                new DepositDatumV1(alice.accountId(), alice.address(), alice.keyHash()).encode()));
        assertThat(ledger.balance(ADA_VAULT, alice)).isEqualTo(BigInteger.valueOf(10_000_000));

        // The deposit record the API reads back for the built transaction: credited exactly once.
        JsonNode record = get(AssetPaymentsDomainApi.DEPOSIT, "vaults/x/deposits/y/z", Map.of(
                "vault_id", ADA_VAULT.hex(), "tx_id", ada.path("transactionId").asText(),
                "output_index", ada.path("outputIndex").asText()));
        assertThat(record.path("found").asBoolean()).isTrue();
        assertThat(record.path("depositId").asText()).isEqualTo(ada.path("depositId").asText());
        assertThat(record.path("deposit").path("status").asText()).isEqualTo("CREDITED");
        assertThat(record.path("deposit").path("creditedAmount").asText()).isEqualTo("10000000");

        // A token deposit: the vault's token plus lovelace that becomes the min-ADA credit (D11).
        JsonNode usdm = depositVia(USDM_VAULT, 400, 1_500_000, utxos);
        assertThat(usdm.path("quantity").asText()).isEqualTo("400");
        long credit = Long.parseLong(usdm.path("outputLovelace").asText());
        assertThat(credit).isGreaterThanOrEqualTo(1_500_000);
        assertThat(ledger.balance(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(400));
        assertThat(ledger.minAda(USDM_VAULT, alice)).isEqualTo(BigInteger.valueOf(credit));
        ledger.checkInvariants();
    }

    @Test
    void depositInstructionsAndTheBuilderRefuseAnUnregisteredBeneficiary() throws Exception {
        Map<String, List<String>> query = Map.of("address", List.of(HEX.formatHex(alice.address())));
        JsonNode refused = call(AssetPaymentsDomainApi.DEPOSIT_INSTRUCTIONS, DomainHttpMethod.GET,
                "vaults/x/deposit-instructions", Map.of("vault_id", ADA_VAULT.hex()), query, null, 409);
        assertThat(refused.path("error").asText()).isEqualTo("ACCOUNT_NOT_REGISTERED");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("vaultId", ADA_VAULT.hex());
        request.put("quantity", "5000000");
        request.put("changeAddress", new Address(alice.address()).toBech32());
        request.put("utxos", List.of(cip30Utxo(filled(32, 0x11), 0, alice.address(), 50_000_000, null, 0)));
        assertThat(build(request, 409).path("error").asText()).isEqualTo("ACCOUNT_NOT_REGISTERED");

        ledger.block(T0, register(alice));
        JsonNode instructions = call(AssetPaymentsDomainApi.DEPOSIT_INSTRUCTIONS, DomainHttpMethod.GET,
                "vaults/x/deposit-instructions", Map.of("vault_id", ADA_VAULT.hex()), query, null, 200);
        assertThat(instructions.path("vaultAddress").asText()).isEqualTo(ADA_ADDRESS);
        assertThat(instructions.path("datumHex").asText()).isEqualTo(HEX.formatHex(
                new DepositDatumV1(alice.accountId(), alice.address(), alice.keyHash()).encode()));
        // An unaffordable deposit is a conflict, not a server error.
        request.put("quantity", "500000000");
        assertThat(build(request, 409).path("error").asText()).isEqualTo("CANNOT_BUILD");
    }

    @Test
    void accountReceiptsSessionsAndWithdrawalLifecycleCarryTheirStateKeys() throws Exception {
        ledger.block(T0, register(alice), register(bob));
        List<String> utxos = List.of(cip30Utxo(filled(32, 0x11), 0, alice.address(), 80_000_000, null, 0));
        depositVia(ADA_VAULT, 30_000_000, 0, utxos);

        IntentEnvelopeV1 transfer = env(alice, AssetPaymentsContract.TRANSFER, new PaymentsBodies.Transfer(AssetId.ADA,
                bob.accountId(), BigInteger.valueOf(4_000_000)).encode());
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(SESSION_SEED);
        IntentEnvelopeV1 authorize = env(alice, WalletAuthorizer.AUTHORIZE_SESSION, new PaymentsBodies.AuthorizeSession(
                sessionKey, List.of(AssetPaymentsContract.TRANSFER), List.of(new SessionGrantV1.Limit(ADA_VAULT,
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(3_000_000))), List.of(), EXPIRY).encode());
        ledger.block(T0 + 5_000, intent(owner(alice, transfer)), intent(owner(alice, authorize)));
        Hash32 grantId = intentId(authorize);
        IntentEnvelopeV1 pay = envelope(alice.accountId(), AssetPaymentsContract.TRANSFER,
                new IntentEnvelopeV1.Session(grantId), ++nonce, EXPIRY,
                new PaymentsBodies.Transfer(AssetId.ADA, bob.accountId(), BigInteger.valueOf(700_000)).encode());
        IntentEnvelopeV1 withdraw = env(alice, AssetPaymentsContract.REQUEST_WITHDRAWAL,
                new PaymentsBodies.RequestWithdrawal(ADA_VAULT, alice.address(), BigInteger.valueOf(2_500_000),
                        BigInteger.ZERO).encode());
        ledger.block(T0 + 6_000, intent(session(SESSION_SEED, pay)), intent(owner(alice, withdraw)));

        JsonNode receipt = get(AssetPaymentsDomainApi.RECEIPT, "receipts/x", Map.of("intent_id",
                intentId(transfer).hex()));
        assertThat(receipt.path("found").asBoolean()).isTrue();
        assertThat(receipt.path("receipt").path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(receipt.path("receipt").path("amount").asText()).isEqualTo("4000000");
        assertThat(receipt.path("proof").path("keyHex").asText())
                .isEqualTo(HEX.formatHex(AssetPaymentsContract.receiptKey(intentId(transfer))));
        assertThat(receipt.path("proof").path("proofPath").asText()).isEqualTo("app-chain/chains/" + CHAIN
                + "/state/proof/" + HEX.formatHex(AssetPaymentsContract.receiptKey(intentId(transfer)))
                + "?height=" + ledger.height);
        JsonNode absent = get(AssetPaymentsDomainApi.RECEIPT, "receipts/x", Map.of("intent_id", "ab".repeat(32)));
        assertThat(absent.path("found").asBoolean()).isFalse();
        assertThat(absent.path("proof").path("valueHex").isNull()).isTrue();

        JsonNode sessions = get(AssetPaymentsDomainApi.SESSIONS, "accounts/x/sessions",
                Map.of("account_id", HEX.formatHex(alice.accountId())));
        assertThat(sessions.path("grants")).hasSize(1);
        JsonNode grant = sessions.path("grants").get(0);
        assertThat(grant.path("grantId").asText()).isEqualTo(grantId.hex());
        assertThat(grant.path("sessionKey").asText()).isEqualTo(HEX.formatHex(sessionKey));
        assertThat(grant.path("limits").get(0).path("used").asText()).isEqualTo("700000");
        assertThat(grant.path("limits").get(0).path("remaining").asText()).isEqualTo("2300000");

        // The withdrawal: the receipt names its claim, and the account sees it as reserved.
        JsonNode withdrawalReceipt = get(AssetPaymentsDomainApi.RECEIPT, "receipts/x",
                Map.of("intent_id", intentId(withdraw).hex()));
        assertThat(withdrawalReceipt.path("receipt").path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(withdrawalReceipt.path("withdrawals")).hasSize(1);
        JsonNode claim = withdrawalReceipt.path("withdrawals").get(0);
        assertThat(claim.path("lifecycle").asText()).isEqualTo("RESERVED");
        assertThat(claim.path("amount").asText()).isEqualTo("2500000");
        assertThat(claim.path("recipientHex").asText()).isEqualTo(HEX.formatHex(alice.address()));
        assertThat(claim.path("l1TxId").isNull()).isTrue();
        Hash32 claimId = Hash32.ofHex(claim.path("claimId").asText());

        JsonNode account = get(AssetPaymentsDomainApi.ACCOUNT, "accounts/x",
                Map.of("account_id", HEX.formatHex(alice.accountId())));
        assertThat(account.path("registered").asBoolean()).isTrue();
        assertThat(account.path("owner").path("keyHash").asText()).isEqualTo(HEX.formatHex(alice.keyHash()));
        JsonNode adaBalance = account.path("balances").get(0);
        assertThat(adaBalance.path("available").asText()).isEqualTo(Long.toString(
                30_000_000 - 4_000_000 - 700_000 - 2_500_000));
        assertThat(adaBalance.path("reserved").asText()).isEqualTo("2500000");
        assertThat(adaBalance.path("proof").path("keyHex").asText())
                .isEqualTo(HEX.formatHex(KEYS.exitLeafKey(ADA_VAULT, alice.accountId())));
        assertThat(adaBalance.path("proof").path("valueHex").asText()).isEqualTo(
                HEX.formatHex(ledger.get(KEYS.exitLeafKey(ADA_VAULT, alice.accountId())).orElseThrow()));
        assertThat(account.path("reservedFrom").path("complete").asBoolean()).isTrue();
        JsonNode bobAccount = get(AssetPaymentsDomainApi.ACCOUNT, "accounts/x",
                Map.of("account_id", HEX.formatHex(bob.accountId())));
        assertThat(bobAccount.path("balances").get(0).path("available").asText()).isEqualTo("4700000");
        assertThat(get(AssetPaymentsDomainApi.WITHDRAWALS, "accounts/x/withdrawals",
                Map.of("account_id", HEX.formatHex(bob.accountId()))).path("withdrawals")).isEmpty();

        // PAID with the payout transaction, as the payout observer would record it (D10).
        PayoutConfirmation layout = new PayoutConfirmation(AssetPaymentsContract.PREFIX, "payouts", DOMAIN, 0,
                ignored -> Optional.empty());
        Hash32 payoutTx = Hash32.ofHex("5e".repeat(32));
        SettlementRecords.ClaimStatusV1 reserved = SettlementRecords.ClaimStatusV1.decode(
                ledger.get(layout.claimStatusKey(claimId)).orElseThrow());
        ledger.writer().put(layout.claimStatusKey(claimId), reserved.resolve(SettlementRecords.ClaimStatus.PAID,
                payoutTx, ledger.height).encode());
        JsonNode paid = get(AssetPaymentsDomainApi.WITHDRAWAL, "withdrawals/x", Map.of("claim_id", claimId.hex()));
        assertThat(paid.path("withdrawal").path("lifecycle").asText()).isEqualTo("PAID");
        assertThat(paid.path("withdrawal").path("l1TxId").asText()).isEqualTo(payoutTx.hex());
        JsonNode list = get(AssetPaymentsDomainApi.WITHDRAWALS, "accounts/x/withdrawals",
                Map.of("account_id", HEX.formatHex(alice.accountId())));
        assertThat(list.path("withdrawals")).hasSize(1);
        assertThat(list.path("withdrawals").get(0).path("status").asText()).isEqualTo("PAID");
        assertThat(list.path("withdrawals").get(0).path("sequence").asLong()).isZero();
        assertThat(list.path("nextBefore").isNull()).isTrue();
        assertThat(get(AssetPaymentsDomainApi.ACCOUNT, "accounts/x",
                Map.of("account_id", HEX.formatHex(alice.accountId()))).path("balances").get(0)
                .path("reserved").asText()).isEqualTo("0");
    }

    @Test
    void intentsEncodeExactBytesAndMessagesArePreparedOnlyWhenTheLedgerWouldAuthorizeThem() throws Exception {
        long notAfter = System.currentTimeMillis() + 120_000;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", WalletAuthorizer.REGISTER_ACCOUNT);
        request.put("accountId", HEX.formatHex(alice.accountId()));
        request.put("authorizer", Map.of("kind", "OWNER"));
        request.put("nonce", "a0".repeat(16));
        request.put("notAfterMs", notAfter);
        request.put("body", Map.of());
        JsonNode encoded = call(AssetPaymentsDomainApi.INTENT_ENCODE, DomainHttpMethod.POST, "intents/encode",
                Map.of(), Map.of(), request, 200);
        IntentEnvelopeV1 expected = new IntentEnvelopeV1(INTENT_DOMAIN, WalletAuthorizer.REGISTER_ACCOUNT,
                alice.accountId(), new IntentEnvelopeV1.Owner(), filled(16, 0xa0), notAfter,
                PaymentsBodies.REGISTER_ACCOUNT);
        assertThat(encoded.path("envelopeHex").asText()).isEqualTo(HEX.formatHex(expected.encode()));
        assertThat(encoded.path("intentId").asText()).isEqualTo(intentId(expected).hex());

        byte[][] cose = alice.signData(expected.encode());
        Map<String, Object> owner = Map.of("kind", "OWNER", "coseSign1Hex", HEX.formatHex(cose[0]),
                "coseKeyHex", HEX.formatHex(cose[1]));
        JsonNode prepared = call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message",
                Map.of(), Map.of(), owner, 200);
        assertThat(prepared.path("topic").asText()).isEqualTo(AssetPaymentsContract.INTENT_TOPIC);
        assertThat(prepared.path("bodyHex").asText())
                .isEqualTo(HEX.formatHex(new PaymentsMessageV1.Owner(cose[0], cose[1]).encode()));
        assertThat(prepared.path("submitBody").path("bodyHex").asText()).isEqualTo(prepared.path("bodyHex").asText());
        assertThat(prepared.path("submitPath").asText()).isEqualTo("app-chain/chains/" + CHAIN + "/messages");

        // Bob signing Alice's registration: the ledger would never authorize it.
        byte[][] wrong = bob.signData(expected.encode());
        assertThat(call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message", Map.of(),
                Map.of(), Map.of("kind", "OWNER", "coseSign1Hex", HEX.formatHex(wrong[0]),
                        "coseKeyHex", HEX.formatHex(wrong[1])), 422).path("error").asText())
                .isEqualTo("NOT_AUTHORIZED");
        // Another chain's domain.
        IntentEnvelopeV1 foreign = new IntentEnvelopeV1(new IntentEnvelopeV1.Domain(NETWORK_MAGIC, 0, "elsewhere",
                Hash32.ofHex(GENESIS), "asset-payments"), WalletAuthorizer.REGISTER_ACCOUNT, alice.accountId(),
                new IntentEnvelopeV1.Owner(), filled(16, 0xa1), notAfter, PaymentsBodies.REGISTER_ACCOUNT);
        byte[][] foreignCose = alice.signData(foreign.encode());
        call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message", Map.of(), Map.of(),
                Map.of("kind", "OWNER", "coseSign1Hex", HEX.formatHex(foreignCose[0]),
                        "coseKeyHex", HEX.formatHex(foreignCose[1])), 422);

        // Once executed, the same intent is refused as used.
        ledger.block(System.currentTimeMillis(), intent(new PaymentsMessageV1.Owner(cose[0], cose[1])));
        assertThat(call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message", Map.of(),
                Map.of(), owner, 409).path("error").asText()).isEqualTo("ALREADY_USED");

        // A session payment under a registered grant is prepared; a signature by another key is not.
        byte[] sessionKey = com.bloxbean.cardano.client.crypto.KeyGenUtil.getPublicKeyFromPrivateKey(SESSION_SEED);
        IntentEnvelopeV1 authorize = new IntentEnvelopeV1(INTENT_DOMAIN, WalletAuthorizer.AUTHORIZE_SESSION,
                alice.accountId(), new IntentEnvelopeV1.Owner(), filled(16, 0xa2), notAfter,
                new PaymentsBodies.AuthorizeSession(sessionKey, List.of(AssetPaymentsContract.TRANSFER),
                        List.of(new SessionGrantV1.Limit(ADA_VAULT, BigInteger.ONE, BigInteger.TEN)), List.of(),
                        notAfter).encode());
        ledger.block(System.currentTimeMillis(), intent(owner(alice, authorize)));
        IntentEnvelopeV1 pay = new IntentEnvelopeV1(INTENT_DOMAIN, AssetPaymentsContract.TRANSFER, alice.accountId(),
                new IntentEnvelopeV1.Session(intentId(authorize)), filled(16, 0xa3), notAfter,
                new PaymentsBodies.Transfer(AssetId.ADA, bob.accountId(), BigInteger.ONE).encode());
        JsonNode session = call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message",
                Map.of(), Map.of(), Map.of("kind", "SESSION", "envelopeHex", HEX.formatHex(pay.encode()),
                        "signatureHex", HEX.formatHex(WalletEd25519.sign(pay.encode(), SESSION_SEED))), 200);
        assertThat(session.path("authorizer").path("grantId").asText()).isEqualTo(intentId(authorize).hex());
        call(AssetPaymentsDomainApi.INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message", Map.of(), Map.of(),
                Map.of("kind", "SESSION", "envelopeHex", HEX.formatHex(pay.encode()),
                        "signatureHex", HEX.formatHex(WalletEd25519.sign(pay.encode(), filled(32, 0x78)))), 422);
    }

    @Test
    void addressesDeriveTheAccountTheWalletWillOwnAndBadInputIsA400() throws Exception {
        JsonNode derived = get(AssetPaymentsDomainApi.ADDRESS, "addresses/x",
                Map.of("address", HEX.formatHex(alice.address())));
        assertThat(derived.path("accountId").asText()).isEqualTo(HEX.formatHex(alice.accountId()));
        assertThat(derived.path("credentialKind").asText()).isEqualTo("PAYMENT_KEY");
        assertThat(derived.path("networkMatches").asBoolean()).isTrue();
        byte[] script = alice.address().clone();
        script[0] = 0x70;
        assertThat(call(AssetPaymentsDomainApi.ADDRESS, DomainHttpMethod.GET, "addresses/x",
                Map.of("address", HEX.formatHex(script)), Map.of(), null, 422).path("error").asText())
                .isEqualTo("UNSUPPORTED_ADDRESS");
        assertThat(call(AssetPaymentsDomainApi.ACCOUNT, DomainHttpMethod.GET, "accounts/x",
                Map.of("account_id", "abc"), Map.of(), null, 400).path("error").asText())
                .isEqualTo("INVALID_ACCOUNT_ID");
        assertThat(call(AssetPaymentsDomainApi.INTENT_ENCODE, DomainHttpMethod.POST, "intents/encode", Map.of(),
                Map.of(), Map.of("action", "MINT"), 400).path("error").asText()).isEqualTo("INVALID_REQUEST");
    }
}
