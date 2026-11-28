package org.yanoproject.x.assetpayments.ledger;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsMessageV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AccountRecordV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.ReceiptV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.ClaimLeafV1;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.assets.contracts.GatewayIds;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.PayoutConfirmation;
import org.yanoproject.x.assets.contracts.SettlementQueries;
import org.yanoproject.x.assets.contracts.SettlementRecords;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.appchain.AppQueryResult;
import org.yanoproject.x.framework.api.plugin.domain.DomainApi;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiContext;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiException;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiMediaType;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRequest;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiResponse;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRoute;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiRouteSet;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthException;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The payments domain API of {@code asset-payments} (#109 §7, ADR-059 D9/D13/D14): asset discovery, deposit
 * instructions and an unsigned deposit builder, account balances, session grants, receipts and withdrawal status,
 * each with the exact state keys a client fetches proofs for, plus intent encoding and preparation.
 *
 * <p><b>Read-only and node-local.</b> Every route is {@link DomainApiAccess#READ}: it reads committed state through the
 * host's root-fixed query facade, or computes bytes, and changes nothing. It cannot submit: the host gives a domain
 * API no message submission (by design), so a prepared intent message is submitted by the client through the
 * host's ordinary {@code SUBMIT} route, {@code POST /api/v1/app-chain/chains/{chain}/messages}, where the ledger's own
 * admission authorizes it again (D13 "ingress of user intents"). Nothing here is consensus state: two nodes may answer
 * at different committed heights, and each answer names its height and state root.
 *
 * <p><b>Proofs.</b> Each fact names its state key and value. {@code proofPath} is the host's native proof route
 * relative to {@code /api/v1/}, pinned to the committed height the answer was read at; a client verifies the value
 * there (or with the proof verifier SDK) instead of trusting this API.
 */
final class AssetPaymentsDomainApi implements DomainApi {
    static final String BASE = "chains/{chain_id}/payments/";
    static final String INFO = "payments.info";
    static final String ACCOUNT = "payments.account";
    static final String SESSIONS = "payments.sessions";
    static final String WITHDRAWALS = "payments.withdrawals";
    static final String ADDRESS = "payments.address";
    static final String RECEIPT = "payments.receipt";
    static final String WITHDRAWAL = "payments.withdrawal";
    static final String DEPOSIT_INSTRUCTIONS = "payments.deposit-instructions";
    static final String DEPOSIT = "payments.deposit";
    static final String DEPOSIT_BUILD = "payments.deposit-build";
    static final String INTENT_ENCODE = "payments.intent-encode";
    static final String INTENT_MESSAGE = "payments.intent-message";
    static final List<DomainApiRoute> ROUTES = DomainApiRouteSet.validateAndOrder(List.of(
            route(INFO, DomainHttpMethod.GET, "info"),
            route(ACCOUNT, DomainHttpMethod.GET, "accounts/{account_id}"),
            route(SESSIONS, DomainHttpMethod.GET, "accounts/{account_id}/sessions"),
            route(WITHDRAWALS, DomainHttpMethod.GET, "accounts/{account_id}/withdrawals"),
            route(ADDRESS, DomainHttpMethod.GET, "addresses/{address}"),
            route(RECEIPT, DomainHttpMethod.GET, "receipts/{intent_id}"),
            route(WITHDRAWAL, DomainHttpMethod.GET, "withdrawals/{claim_id}"),
            route(DEPOSIT_INSTRUCTIONS, DomainHttpMethod.GET, "vaults/{vault_id}/deposit-instructions"),
            route(DEPOSIT, DomainHttpMethod.GET, "vaults/{vault_id}/deposits/{tx_id}/{output_index}"),
            route(DEPOSIT_BUILD, DomainHttpMethod.POST, "deposits/build"),
            route(INTENT_ENCODE, DomainHttpMethod.POST, "intents/encode"),
            route(INTENT_MESSAGE, DomainHttpMethod.POST, "intents/message")));
    /** Pages of {@code claims-of} an account view reads to total its reserved claims. */
    static final int RESERVED_PAGES = 8;
    /** The validity a built deposit gets past the node's L1 tip: two hours of one-second slots. */
    static final long TTL_SLOTS = 7_200;
    /**
     * The CBOR nesting depth of a canonical {@link DepositDatumV1} as the deposit observer's datum preflight counts it:
     * the constructor tag, then its field array.
     */
    static final int DEPOSIT_DATUM_DEPTH = 2;
    private static final HexFormat HEX = HexFormat.of();

    private final DomainApiContext context;
    private final ObjectMapper json = new ObjectMapper();
    private final DepositTransactionBuilder deposits;

    AssetPaymentsDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.deposits = new DepositTransactionBuilder();
    }

    @Override
    public List<DomainApiRoute> routes() {
        return ROUTES;
    }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        Objects.requireNonNull(request, "request");
        String chain = request.pathParameters().get("chain_id");
        if (chain == null || !context.queryService().chainIds().contains(chain)) {
            return error(404, "CHAIN_NOT_FOUND", "no app chain with this id is hosted here");
        }
        Optional<Chain> info = chain(chain);
        if (info.isEmpty()) return error(404, "NOT_A_PAYMENTS_CHAIN", "this chain does not run asset-payments");
        try {
            return switch (request.routeId()) {
                case INFO -> info(info.get());
                case ACCOUNT -> account(info.get(), accountId(request));
                case SESSIONS -> sessions(info.get(), accountId(request));
                case WITHDRAWALS -> withdrawals(info.get(), accountId(request), request.queryParameters());
                case ADDRESS -> address(info.get(), request.pathParameters().get("address"));
                case RECEIPT -> receipt(info.get(), hash(request, "intent_id"));
                case WITHDRAWAL -> withdrawal(info.get(), hash(request, "claim_id"));
                case DEPOSIT_INSTRUCTIONS -> depositInstructions(info.get(), hash(request, "vault_id"),
                        request.queryParameters());
                case DEPOSIT -> deposit(info.get(), request);
                case DEPOSIT_BUILD -> depositBuild(info.get(), body(request));
                case INTENT_ENCODE -> intentEncode(info.get(), body(request));
                case INTENT_MESSAGE -> intentMessage(info.get(), body(request));
                default -> error(404, "UNKNOWN_ROUTE", "unknown payments route");
            };
        } catch (BadRequest invalid) {
            return error(400, invalid.code, invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", invalid.getMessage());
        }
    }

    // ------------------------------------------------------------------ routes

    private DomainApiResponse info(Chain chain) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("committed", committed(chain.result()));
        out.put("intentTopic", AssetPaymentsContract.INTENT_TOPIC);
        out.put("submitPath", "app-chain/chains/" + encode(chain.id()) + "/messages");
        Map<String, Object> domain = new LinkedHashMap<>();
        domain.put("tag", IntentEnvelopeV1.TAG);
        domain.put("networkMagic", chain.domain().networkMagic());
        domain.put("addressNetworkId", chain.domain().addressNetworkId());
        domain.put("chainId", chain.domain().chainId());
        domain.put("genesisId", chain.domain().genesisId().hex());
        domain.put("applicationId", chain.domain().applicationId());
        out.put("intentDomain", domain);
        out.put("domainId", chain.domainId().hex());
        out.put("profileId", chain.profileId());
        out.put("profileVersion", chain.profileVersion());
        out.put("maxIntentTtlMs", chain.info().maxIntentTtlMs());
        out.put("actions", AssetPaymentsContract.ACTIONS.stream().sorted().toList());
        out.put("sessionActions", AssetPaymentsContract.SESSION_ACTIONS.stream().sorted().toList());
        out.put("depositObserverId", chain.info().depositObserverId());
        out.put("withdrawalsEnabled", !chain.info().payoutObserverId().isEmpty());
        out.put("minAdaPayout", chain.info().minAdaPayout().toString());
        out.put("payoutLovelaceCap", chain.info().payoutLovelaceCap().toString());
        out.put("depositDatumBounds", Map.of("maxBytes", chain.info().depositDatumBounds().maxBytes(),
                "maxDepth", chain.info().depositDatumBounds().maxDepth()));
        out.put("domainStatus", chain.info().domainStatus().map(bytes -> {
            DomainStatusV1 status = DomainStatusV1.decode(bytes);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("status", status.status().name());
            view.put("sinceHeight", status.sinceHeight());
            view.put("sinceMs", status.sinceMs());
            view.put("exitDelayMs", status.exitDelayMs());
            view.put("proof", proof(chain, AssetPaymentsContract.domainStatusKey(), bytes));
            return view;
        }).orElse(null));
        List<Object> vaults = new ArrayList<>();
        for (RegistryEntryV1 vault : chain.info().vaults()) {
            Map<String, Object> view = vaultView(vault);
            view.put("address", vault.vaultAddress());
            view.put("scriptHash", HEX.formatHex(vault.scriptHash()));
            view.put("activeFromSlot", vault.activeFromSlot());
            view.put("addedAtHeight", vault.addedAtHeight());
            view.put("depositsEnabled", vault.status().depositsEnabled());
            view.put("transfersEnabled", vault.status().transfersEnabled());
            view.put("proof", proof(chain, AssetPaymentsContract.registryKey(vault.vaultId()), vault.encode()));
            vaults.add(view);
        }
        out.put("vaults", vaults);
        return ok(out);
    }

    private DomainApiResponse account(Chain chain, byte[] accountId) {
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.ACCOUNT, accountId);
        PaymentsViewQueries.Account view = PaymentsViewQueries.Account.decode(result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("accountId", HEX.formatHex(accountId));
        out.put("committed", committed(result));
        out.put("registered", view.record().isPresent());
        byte[] accountKey = AssetPaymentsContract.accountKey(accountId);
        view.record().ifPresent(bytes -> {
            AccountRecordV1 record = AccountRecordV1.decode(bytes);
            out.put("owner", Map.of("credentialKind", CardanoCredential.Kind.values()[record.credentialKind()].name(),
                    "keyHash", HEX.formatHex(record.keyHash())));
            out.put("createdHeight", record.createdHeight());
            out.put("rotations", record.rotations());
            out.put("grantCount", record.grants().size());
        });
        out.put("proof", proof(result, chain.id(), accountKey, view.record().orElse(null)));
        Reserved reserved = reserved(chain, accountId);
        AssetAccounts keys = keys();
        List<Object> balances = new ArrayList<>();
        for (PaymentsViewQueries.Leaf leaf : view.leaves()) {
            RegistryEntryV1 vault = chain.vault(leaf.vaultId()).orElseThrow();
            Optional<ExitLeafV1> exit = leaf.exitLeaf().map(ExitLeafV1::decode);
            Map<String, Object> balance = vaultView(vault);
            balance.put("available", exit.map(ExitLeafV1::amount).orElse(BigInteger.ZERO).toString());
            balance.put("minAdaCredit", exit.map(ExitLeafV1::vaultLovelace).orElse(BigInteger.ZERO).toString());
            balance.put("reserved", reserved.amount(leaf.vaultId()).toString());
            balance.put("reservedMinAda", reserved.lovelace(leaf.vaultId()).toString());
            balance.put("proof", proof(result, chain.id(), keys.exitLeafKey(leaf.vaultId(), accountId),
                    leaf.exitLeaf().orElse(null)));
            balances.add(balance);
        }
        out.put("balances", balances);
        out.put("reservedFrom", Map.of("committedHeight", reserved.height(), "complete", reserved.complete(),
                "claimsScanned", reserved.scanned()));
        return ok(out);
    }

    private DomainApiResponse sessions(Chain chain, byte[] accountId) {
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.ACCOUNT, accountId);
        PaymentsViewQueries.Account view = PaymentsViewQueries.Account.decode(result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("accountId", HEX.formatHex(accountId));
        out.put("committed", committed(result));
        out.put("registered", view.record().isPresent());
        List<Object> grants = new ArrayList<>();
        for (PaymentsViewQueries.Grant entry : view.grants()) {
            SessionGrantV1 grant = entry.grant();
            Map<String, Object> grantView = new LinkedHashMap<>();
            grantView.put("grantId", grant.grantId().hex());
            grantView.put("sessionKey", HEX.formatHex(grant.sessionKey()));
            grantView.put("actions", grant.actions());
            grantView.put("notAfterMs", grant.notAfterMs());
            List<Object> limits = new ArrayList<>();
            for (SessionGrantV1.Limit limit : grant.limits()) {
                BigInteger used = entry.usage().stream().filter(usage -> usage.vaultId().equals(limit.vaultId()))
                        .map(PaymentsViewQueries.Usage::used).findFirst().orElse(BigInteger.ZERO);
                Map<String, Object> limitView = new LinkedHashMap<>();
                limitView.put("vaultId", limit.vaultId().hex());
                chain.vault(limit.vaultId()).ifPresent(vault -> limitView.put("asset", assetView(vault.asset())));
                limitView.put("perPayment", limit.perPayment().toString());
                limitView.put("cumulative", limit.cumulative().toString());
                limitView.put("used", used.toString());
                limitView.put("remaining", limit.cumulative().subtract(used).max(BigInteger.ZERO).toString());
                limits.add(limitView);
            }
            grantView.put("limits", limits);
            grantView.put("recipients", grant.recipients().stream().map(HEX::formatHex).toList());
            grantView.put("proof", proof(result, chain.id(), AssetPaymentsContract.grantKey(grant.grantId()),
                    entry.encoded()));
            grants.add(grantView);
        }
        out.put("grants", grants);
        return ok(out);
    }

    private DomainApiResponse withdrawals(Chain chain, byte[] accountId, Map<String, List<String>> query) {
        long before = longParameter(query, "before", 0, Long.MAX_VALUE);
        int limit = (int) longParameter(query, "limit", 20, PaymentsViewQueries.MAX_CLAIMS_PER_PAGE);
        if (limit < 1) throw new BadRequest("INVALID_LIMIT", "limit is 1..64");
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.CLAIMS_OF,
                PaymentsViewQueries.claimsOfRequest(accountId, before, limit));
        PaymentsViewQueries.ClaimsPage page = PaymentsViewQueries.ClaimsPage.decode(result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("accountId", HEX.formatHex(accountId));
        out.put("committed", committed(result));
        List<Object> claims = new ArrayList<>();
        for (PaymentsViewQueries.Claim claim : page.claims()) claims.add(claimView(chain, result, claim));
        out.put("withdrawals", claims);
        out.put("nextBefore", page.nextBefore() == 0 ? null : page.nextBefore());
        out.put("claimsScanned", page.scanned());
        return ok(out);
    }

    private DomainApiResponse address(Chain chain, String addressHex) {
        byte[] raw;
        try {
            raw = PaymentsIntentJson.parseHex(addressHex, PaymentsIntentJson.ANY);
        } catch (IllegalArgumentException invalid) {
            throw new BadRequest("INVALID_ADDRESS", "address must be the raw address bytes in hex");
        }
        CardanoCredential credential;
        try {
            credential = CardanoCredential.fromAddress(raw);
        } catch (IllegalArgumentException unsupported) {
            return error(422, "UNSUPPORTED_ADDRESS", "this address has no key credential a CIP-30 wallet signs with: "
                    + unsupported.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addressHex", HEX.formatHex(raw));
        out.put("credentialKind", credential.kind().name());
        out.put("keyHash", HEX.formatHex(credential.keyHash()));
        out.put("addressNetworkId", credential.addressNetworkId());
        out.put("networkMatches", credential.addressNetworkId() == chain.domain().addressNetworkId());
        out.put("accountId", HEX.formatHex(WalletAuthorizer.accountId(credential)));
        return ok(out);
    }

    private DomainApiResponse receipt(Chain chain, Hash32 intentId) {
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.RECEIPT, intentId.bytes());
        Optional<byte[]> bytes = PaymentsViewQueries.decodeSingle(result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("intentId", intentId.hex());
        out.put("committed", committed(result));
        out.put("found", bytes.isPresent());
        out.put("proof", proof(result, chain.id(), AssetPaymentsContract.receiptKey(intentId), bytes.orElse(null)));
        if (bytes.isEmpty()) return ok(out);
        ReceiptV1 receipt = ReceiptV1.decode(bytes.get());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("accountId", HEX.formatHex(receipt.accountId()));
        view.put("action", receipt.action());
        view.put("asset", receipt.asset().map(AssetPaymentsDomainApi::assetView).orElse(null));
        view.put("amount", receipt.amount().toString());
        view.put("outcome", receipt.outcome().name());
        view.put("reason", receipt.reason());
        view.put("height", receipt.height());
        out.put("receipt", view);
        if (AssetPaymentsContract.REQUEST_WITHDRAWAL.equals(receipt.action())
                && receipt.outcome() == PaymentsRecords.Outcome.ACCEPTED) {
            out.put("withdrawals", claimOf(chain, intentId));
        }
        return ok(out);
    }

    private DomainApiResponse withdrawal(Chain chain, Hash32 claimId) {
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.CLAIM, claimId.bytes());
        Optional<PaymentsViewQueries.Claim> claim = PaymentsViewQueries.Claim.decodeSingle(result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("claimId", claimId.hex());
        out.put("committed", committed(result));
        out.put("found", claim.isPresent());
        claim.ifPresent(value -> out.put("withdrawal", claimView(chain, result, value)));
        return ok(out);
    }

    private DomainApiResponse depositInstructions(Chain chain, Hash32 vaultId, Map<String, List<String>> query) {
        RegistryEntryV1 vault = chain.vault(vaultId).orElse(null);
        if (vault == null) return error(404, "UNKNOWN_VAULT", "no vault with this id is registered");
        byte[] depositor = PaymentsIntentJson.address(single(query, "address")
                .orElseThrow(() -> new BadRequest("ADDRESS_REQUIRED", "address (the depositing wallet) is required")));
        Beneficiary beneficiary = beneficiary(chain, depositor, single(query, "account"));
        if (beneficiary.failure() != null) return beneficiary.failure();
        if (!vault.status().depositsEnabled()) {
            return error(409, "DEPOSITS_PAUSED", "this vault does not credit deposits now");
        }
        DomainApiResponse inactive = notActive(vault, tipSlot());
        if (inactive != null) return inactive;
        Map<String, Object> out = vaultView(vault);
        out.put("chainId", chain.id());
        out.put("committed", committed(chain.result()));
        out.put("vaultAddress", vault.vaultAddress());
        out.put("beneficiaryAccount", HEX.formatHex(beneficiary.datum().beneficiaryAccount()));
        out.put("refundAddress", HEX.formatHex(beneficiary.datum().refundAddress()));
        out.put("refundKeyHash", HEX.formatHex(beneficiary.datum().refundKeyHash()));
        out.put("datumHex", HEX.formatHex(beneficiary.datum().encode()));
        out.put("depositIdRule", "GatewayIds.depositId(vaultId, txId, outputIndex)");
        out.put("rules", List.of(
                "one output at vaultAddress with this exact inline datum",
                vault.asset().isAda() ? "lovelace only; the whole output is credited"
                        : "the vault's token and lovelace only; the lovelace becomes your min-ADA credit (D11)",
                "credited after the chain's L1 stability depth and the next app-chain block"));
        return ok(out);
    }

    private DomainApiResponse deposit(Chain chain, DomainApiRequest request) {
        Hash32 vaultId = hash(request, "vault_id");
        Hash32 txId = hash(request, "tx_id");
        long index = parseLong(request.pathParameters().get("output_index"), "output_index", 0, 65_535);
        if (chain.vault(vaultId).isEmpty()) return error(404, "UNKNOWN_VAULT", "no vault with this id is registered");
        Hash32 depositId = GatewayIds.depositId(vaultId, txId, index);
        AppQueryResult result = query(chain.id(), SettlementQueries.DEPOSIT, depositId.bytes());
        Optional<DepositRecordV1> record = SettlementQueries.decodeDeposit(depositId, result.payload());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("depositId", depositId.hex());
        out.put("committed", committed(result));
        out.put("found", record.isPresent());
        byte[] key = new DepositAdmission(AssetPaymentsContract.PREFIX, chain.info().depositObserverId(),
                ignored -> Optional.empty()).recordKey(depositId);
        out.put("proof", proof(result, chain.id(), key, record.map(DepositRecordV1::encode).orElse(null)));
        record.ifPresent(value -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("status", value.status().name());
            view.put("reason", value.reason().name());
            view.put("shape", value.shape().name());
            view.put("beneficiary", HEX.formatHex(value.beneficiary()));
            view.put("slot", value.slot());
            view.put("height", value.height());
            view.put("registeredQuantity", value.registeredQuantity().toString());
            view.put("lovelace", value.lovelace().toString());
            view.put("creditedAmount", value.creditedAmount().toString());
            view.put("creditedLovelace", value.creditedLovelace().toString());
            out.put("deposit", view);
        });
        return ok(out);
    }

    private DomainApiResponse depositBuild(Chain chain, JsonNode body) {
        Hash32 vaultId = Hash32.of(PaymentsIntentJson.hex(body, "vaultId", 32));
        RegistryEntryV1 vault = chain.vault(vaultId).orElse(null);
        if (vault == null) return error(404, "UNKNOWN_VAULT", "no vault with this id is registered");
        if (!vault.status().depositsEnabled()) {
            return error(409, "DEPOSITS_PAUSED", "this vault does not credit deposits now");
        }
        BigInteger quantity = PaymentsIntentJson.amount(body, "quantity");
        BigInteger lovelace = body.has("lovelace") ? PaymentsIntentJson.amount(body, "lovelace") : BigInteger.ZERO;
        if (quantity.signum() <= 0) throw new BadRequest("INVALID_QUANTITY", "quantity must be positive");
        byte[] change = PaymentsIntentJson.address(PaymentsIntentJson.text(body, "changeAddress"));
        Optional<String> account = body.hasNonNull("beneficiaryAccount")
                ? Optional.of(PaymentsIntentJson.text(body, "beneficiaryAccount")) : Optional.empty();
        Beneficiary beneficiary = beneficiary(chain, change, account);
        if (beneficiary.failure() != null) return beneficiary.failure();
        List<String> hexUtxos = new ArrayList<>();
        body.path("utxos").forEach(node -> hexUtxos.add(node.asText()));
        List<Utxo> utxos = DepositTransactionBuilder.decodeCip30(hexUtxos);
        // A deposit is built only with a TTL past a known tip, and only once the observer would credit it.
        OptionalLong tip = tipSlot();
        if (tip.isEmpty()) {
            return error(409, "L1_TIP_UNKNOWN", "this node does not know its L1 tip, so it cannot bound the deposit's "
                    + "validity; try again when it follows L1");
        }
        DomainApiResponse inactive = notActive(vault, tip);
        if (inactive != null) return inactive;
        DepositTransactionBuilder.FeeParameters fees;
        try {
            fees = DepositTransactionBuilder.FeeParameters.from(context.l1Transactions().pricingParameters());
        } catch (RuntimeException unavailable) {
            return error(409, "L1_PARAMETERS_UNAVAILABLE",
                    "this node has no current L1 protocol parameters to price the deposit with ("
                            + unavailable.getMessage() + ")");
        }
        DepositTransactionBuilder.Built built;
        try {
            built = deposits.build(new DepositTransactionBuilder.Request(vault, beneficiary.datum(), quantity,
                    lovelace, new Address(change).toBech32(), utxos, Math.addExact(tip.getAsLong(), TTL_SLOTS), fees));
        } catch (IllegalStateException cannotPay) {
            return error(409, "CANNOT_BUILD", cannotPay.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("unsignedTxCborHex", HEX.formatHex(built.cbor()));
        out.put("transactionId", built.transactionId());
        out.put("fee", Long.toString(built.fee()));
        out.put("outputIndex", built.outputIndex());
        out.put("depositId", GatewayIds.depositId(vaultId, Hash32.ofHex(built.transactionId()), built.outputIndex())
                .hex());
        out.put("vault", vaultView(vault));
        out.put("vaultAddress", vault.vaultAddress());
        out.put("quantity", built.outputQuantity().toString());
        out.put("outputLovelace", built.outputLovelace().toString());
        out.put("datumHex", HEX.formatHex(beneficiary.datum().encode()));
        out.put("beneficiaryAccount", HEX.formatHex(beneficiary.datum().beneficiaryAccount()));
        out.put("inputs", built.inputs());
        out.put("validToSlot", built.validToSlot());
        out.put("next", "sign with CIP-30 signTx(unsignedTxCborHex, false), add the witness set, and submit");
        return ok(out);
    }

    private DomainApiResponse intentEncode(Chain chain, JsonNode body) {
        IntentEnvelopeV1 envelope = PaymentsIntentJson.envelope(body, chain.domain());
        byte[] bytes = envelope.encode();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("action", envelope.action());
        out.put("accountId", HEX.formatHex(envelope.accountId()));
        out.put("envelopeHex", HEX.formatHex(bytes));
        out.put("bodyHex", HEX.formatHex(envelope.body()));
        out.put("intentId", GatewayIds.intentId(bytes).hex());
        out.put("signWith", envelope.authorizer() instanceof IntentEnvelopeV1.Owner
                ? "CIP-30 signData(address, envelopeHex)" : "Ed25519 over the envelope bytes with the session key");
        return ok(out);
    }

    /**
     * Wraps a CIP-30 {@code signData} result (or a session signature) as the exact {@link PaymentsMessageV1} body and
     * pre-checks it the way the ledger's admission will, against the committed state this node holds now and its
     * own clock. It never submits; the verdict is advisory and the ledger decides again at block time.
     */
    private DomainApiResponse intentMessage(Chain chain, JsonNode body) {
        PaymentsMessageV1 message = switch (PaymentsIntentJson.text(body, "kind")) {
            case "OWNER" -> new PaymentsMessageV1.Owner(PaymentsIntentJson.parseHex(text(body, "coseSign1Hex"), PaymentsIntentJson.ANY),
                    PaymentsIntentJson.parseHex(text(body, "coseKeyHex"), PaymentsIntentJson.ANY));
            case "SESSION" -> new PaymentsMessageV1.Session(
                    PaymentsIntentJson.parseHex(text(body, "envelopeHex"), PaymentsIntentJson.ANY),
                    PaymentsIntentJson.parseHex(text(body, "signatureHex"), 64));
            case "ROTATION" -> new PaymentsMessageV1.Rotation(
                    PaymentsIntentJson.parseHex(text(body, "currentSign1Hex"), PaymentsIntentJson.ANY),
                    PaymentsIntentJson.parseHex(text(body, "currentKeyHex"), PaymentsIntentJson.ANY),
                    PaymentsIntentJson.parseHex(text(body, "newSign1Hex"), PaymentsIntentJson.ANY),
                    PaymentsIntentJson.parseHex(text(body, "newKeyHex"), PaymentsIntentJson.ANY));
            default -> throw new BadRequest("INVALID_KIND", "kind is OWNER, SESSION or ROTATION");
        };
        byte[] encoded = message.encode();
        WalletAuthorizer authorizer = new WalletAuthorizer(chain.domain(), chain.info().maxIntentTtlMs());
        Map<String, Optional<PaymentsViewQueries.Account>> accounts = new LinkedHashMap<>();
        WalletAuthorizer.OwnerLookup owners = accountId -> committedAccount(chain, accountId, accounts)
                .flatMap(PaymentsViewQueries.Account::record).map(AccountRecordV1::decode)
                .map(record -> new CardanoCredential(CardanoCredential.Kind.values()[record.credentialKind()],
                        record.keyHash(), chain.domain().addressNetworkId()));
        WalletAuthorizer.GrantLookup grants = grantId -> accounts.values().stream()
                .flatMap(Optional::stream).flatMap(account -> account.grants().stream())
                .map(PaymentsViewQueries.Grant::grant).filter(grant -> grant.grantId().equals(grantId)).findFirst();
        long now = System.currentTimeMillis();
        WalletAuthorizer.Authorized authorized;
        try {
            authorized = switch (message) {
                case PaymentsMessageV1.Owner owner ->
                        authorizer.authorizeOwner(owner.coseSign1(), owner.coseKey(), owners, now);
                case PaymentsMessageV1.Session session -> {
                    // The grant lookup reads the account the envelope names.
                    committedAccount(chain, IntentEnvelopeV1.decode(session.envelope()).accountId(), accounts);
                    yield authorizer.authorizeSession(session.envelope(), session.signature(), grants, now);
                }
                case PaymentsMessageV1.Rotation rotation -> authorizer.authorizeRotation(rotation.currentSign1(),
                        rotation.currentKey(), rotation.newSign1(), rotation.newKey(), owners, now);
            };
        } catch (WalletAuthException | IllegalArgumentException refused) {
            return error(422, "NOT_AUTHORIZED", refused.getMessage());
        }
        if (!AssetPaymentsContract.ACTIONS.contains(authorized.envelope().action())) {
            return error(422, "UNSUPPORTED_ACTION", "this ledger does not execute " + authorized.envelope().action());
        }
        AppQueryResult receipt = query(chain.id(), PaymentsViewQueries.RECEIPT, authorized.intentId().bytes());
        if (PaymentsViewQueries.decodeSingle(receipt.payload()).isPresent()) {
            return error(409, "ALREADY_USED", "this intent already has a receipt");
        }
        IntentEnvelopeV1 envelope = authorized.envelope();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chainId", chain.id());
        out.put("topic", AssetPaymentsContract.INTENT_TOPIC);
        out.put("bodyHex", HEX.formatHex(encoded));
        out.put("intentId", authorized.intentId().hex());
        out.put("action", envelope.action());
        out.put("accountId", HEX.formatHex(envelope.accountId()));
        out.put("authorizer", envelope.authorizer() instanceof IntentEnvelopeV1.Session session
                ? Map.of("kind", "SESSION", "grantId", session.grantId().hex()) : Map.of("kind", "OWNER"));
        out.put("notAfterMs", envelope.notAfterMs());
        out.put("checkedAtMs", now);
        out.put("submitPath", "app-chain/chains/" + encode(chain.id()) + "/messages");
        out.put("submitBody", Map.of("topic", AssetPaymentsContract.INTENT_TOPIC, "bodyHex", HEX.formatHex(encoded)));
        return ok(out);
    }

    // ------------------------------------------------------------------ claims

    private Map<String, Object> claimView(Chain chain, AppQueryResult result, PaymentsViewQueries.Claim claim) {
        ClaimLeafV1 leaf = ClaimLeafV1.decode(claim.leaf());
        SettlementRecords.ClaimStatusV1 status = SettlementRecords.ClaimStatusV1.decode(claim.status());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("claimId", leaf.claimId().hex());
        view.put("sequence", claim.sequence() < 0 ? null : claim.sequence());
        view.put("vaultId", leaf.vaultId().hex());
        Optional<RegistryEntryV1> vault = chain.vault(leaf.vaultId());
        vault.ifPresent(entry -> view.put("asset", assetView(entry.asset())));
        view.put("kind", leaf.kind().name());
        BigInteger amount = vault.map(entry -> leaf.value().quantityOf(entry.asset())).orElse(BigInteger.ZERO);
        view.put("amount", amount.toString());
        view.put("vaultLovelace", leaf.vaultLovelace().toString());
        view.put("recipientHex", HEX.formatHex(leaf.recipient()));
        view.put("recipientAddress", bech32(leaf.recipient()));
        view.put("reservedHeight", leaf.reservedHeight());
        view.put("reservedAtMs", leaf.reservedAtMs());
        view.put("status", status.status().name());
        view.put("lifecycle", switch (status.status()) {
            case RESERVED -> claim.inOpenBatch() ? "SETTLING" : "RESERVED";
            case PAID -> "PAID";
            case PAID_MISMATCH -> "PAID_MISMATCH";
        });
        view.put("l1TxId", status.resolvedTxId().map(Hash32::hex).orElse(null));
        view.put("resolvedHeight", status.status() == SettlementRecords.ClaimStatus.RESERVED ? null
                : status.resolvedHeight());
        PayoutConfirmation layout = layout(chain);
        view.put("proofs", List.of(proof(result, chain.id(), layout.claimLeafKey(leaf.claimId()), claim.leaf()),
                proof(result, chain.id(), layout.claimStatusKey(leaf.claimId()), claim.status())));
        return view;
    }

    /**
     * The claim an accepted withdrawal intent reserved, through the ledger's {@code intent-claim/<IntentId>} link,
     * with the link's own proof next to the claim's leaf and status proofs.
     */
    private List<Object> claimOf(Chain chain, Hash32 intentId) {
        AppQueryResult link = query(chain.id(), PaymentsViewQueries.INTENT_CLAIM, intentId.bytes());
        Optional<byte[]> claimId = PaymentsViewQueries.decodeSingle(link.payload());
        if (claimId.isEmpty()) return List.of();
        AppQueryResult result = query(chain.id(), PaymentsViewQueries.CLAIM, claimId.get());
        Optional<PaymentsViewQueries.Claim> claim = PaymentsViewQueries.Claim.decodeSingle(result.payload());
        if (claim.isEmpty()) return List.of();
        Map<String, Object> view = claimView(chain, result, claim.get());
        view.put("intentLinkProof", proof(link, chain.id(), AssetPaymentsContract.intentClaimKey(intentId),
                claimId.get()));
        return List.of(view);
    }

    private Reserved reserved(Chain chain, byte[] accountId) {
        Map<Hash32, BigInteger> amounts = new LinkedHashMap<>();
        Map<Hash32, BigInteger> lovelace = new LinkedHashMap<>();
        long before = 0;
        long scanned = 0;
        long height = -1;
        boolean complete = false;
        for (int page = 0; page < RESERVED_PAGES; page++) {
            AppQueryResult result = query(chain.id(), PaymentsViewQueries.CLAIMS_OF, PaymentsViewQueries
                    .claimsOfRequest(accountId, before, PaymentsViewQueries.MAX_CLAIMS_PER_PAGE));
            if (height < 0) height = result.committedHeight();
            PaymentsViewQueries.ClaimsPage claims = PaymentsViewQueries.ClaimsPage.decode(result.payload());
            scanned += claims.scanned();
            for (PaymentsViewQueries.Claim claim : claims.claims()) {
                if (SettlementRecords.ClaimStatusV1.decode(claim.status()).status()
                        != SettlementRecords.ClaimStatus.RESERVED) {
                    continue;
                }
                ClaimLeafV1 leaf = ClaimLeafV1.decode(claim.leaf());
                chain.vault(leaf.vaultId()).ifPresent(vault -> amounts.merge(leaf.vaultId(),
                        leaf.value().quantityOf(vault.asset()), BigInteger::add));
                lovelace.merge(leaf.vaultId(), leaf.vaultLovelace(), BigInteger::add);
            }
            if (claims.nextBefore() == 0) {
                complete = true;
                break;
            }
            before = claims.nextBefore();
        }
        return new Reserved(amounts, lovelace, height, complete, scanned);
    }

    private record Reserved(Map<Hash32, BigInteger> amounts, Map<Hash32, BigInteger> lovelaces, long height,
                            boolean complete, long scanned) {
        BigInteger amount(Hash32 vaultId) {
            return amounts.getOrDefault(vaultId, BigInteger.ZERO);
        }

        BigInteger lovelace(Hash32 vaultId) {
            return lovelaces.getOrDefault(vaultId, BigInteger.ZERO);
        }
    }

    // ------------------------------------------------------------------ chain facts

    /** The chain's committed payments info, decoded once per request. */
    private record Chain(String id, AppQueryResult result, PaymentsViewQueries.Info info, IntentEnvelopeV1.Domain domain,
                         Hash32 domainId, String profileId, long profileVersion) {
        Optional<RegistryEntryV1> vault(Hash32 vaultId) {
            return info.vaults().stream().filter(vault -> vault.vaultId().equals(vaultId)).findFirst();
        }
    }

    private Optional<Chain> chain(String chainId) {
        AppQueryResult result;
        try {
            result = context.queryService().query(chainId, PaymentsViewQueries.INFO, new byte[0]);
        } catch (AppQueryException failure) {
            if (failure.code() == AppQueryException.Code.UNSUPPORTED) return Optional.empty();
            throw translate(failure);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return Optional.empty();
        }
        if (!AssetPaymentsContract.STATE_MACHINE_ID.equals(result.stateMachineId())) return Optional.empty();
        PaymentsViewQueries.Info info = PaymentsViewQueries.Info.decode(result.payload());
        // [1, domain_id, address_network_id, network_magic, chain_id, genesis_id, application_id, profile_id,
        //  profile_version, settings_digest] (PaymentsRecords.DomainRecordV1)
        List<DataItem> items = AssetCbor.decodeArray(info.domainRecord(), 10).getDataItems();
        IntentEnvelopeV1.Domain domain = new IntentEnvelopeV1.Domain(AssetCbor.uintLong(items.get(3)),
                (int) AssetCbor.uintLong(items.get(2)), AssetCbor.text(items.get(4), 64),
                Hash32.of(AssetCbor.bytes(items.get(5), 32, 32)), AssetCbor.text(items.get(6), 64));
        return Optional.of(new Chain(chainId, result, info, domain, Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                AssetCbor.text(items.get(7), 64), AssetCbor.uintLong(items.get(8))));
    }

    private Optional<PaymentsViewQueries.Account> committedAccount(Chain chain, byte[] accountId,
                                                                   Map<String, Optional<PaymentsViewQueries.Account>>
                                                                           cache) {
        return cache.computeIfAbsent(HEX.formatHex(accountId), ignored -> Optional.of(PaymentsViewQueries.Account
                .decode(query(chain.id(), PaymentsViewQueries.ACCOUNT, accountId.clone()).payload())));
    }

    private record Beneficiary(DepositDatumV1 datum, DomainApiResponse failure) { }

    /**
     * The deposit datum for a depositing wallet: the refund address and key are the depositor's; the beneficiary is
     * the depositor's own account unless another registered account is named. An unregistered beneficiary is refused,
     * because its deposit would be recorded uncredited and leave only through a governed sweep (D6, D8d).
     */
    private Beneficiary beneficiary(Chain chain, byte[] depositor, Optional<String> account) {
        CardanoCredential credential;
        try {
            credential = CardanoCredential.fromAddress(depositor);
        } catch (IllegalArgumentException unsupported) {
            return new Beneficiary(null, error(422, "UNSUPPORTED_ADDRESS",
                    "the depositing address must have a key payment credential"));
        }
        if (credential.kind() != CardanoCredential.Kind.PAYMENT_KEY) {
            return new Beneficiary(null, error(422, "UNSUPPORTED_ADDRESS",
                    "the depositing address must be a payment address, not a reward address"));
        }
        if (credential.addressNetworkId() != chain.domain().addressNetworkId()) {
            return new Beneficiary(null, error(422, "WRONG_NETWORK", "the address is on another Cardano network"));
        }
        byte[] beneficiary = account.map(value -> PaymentsIntentJson.parseHex(value, 28))
                .orElseGet(() -> WalletAuthorizer.accountId(credential));
        PaymentsViewQueries.Account view = PaymentsViewQueries.Account.decode(
                query(chain.id(), PaymentsViewQueries.ACCOUNT, beneficiary).payload());
        if (view.record().isEmpty()) {
            return new Beneficiary(null, error(409, "ACCOUNT_NOT_REGISTERED",
                    "register the beneficiary account first: a deposit to an unregistered account is not credited"));
        }
        if (depositor.length > DepositDatumV1.MAX_REFUND_ADDRESS_BYTES) {
            return new Beneficiary(null, error(422, "UNSUPPORTED_ADDRESS", "the refund address is too long"));
        }
        DepositDatumV1 datum = new DepositDatumV1(beneficiary, depositor, credential.keyHash());
        // The deposit observer drops a deposit whose datum is outside its bounds; never hand out such a datum.
        AssetPaymentsSettings.DatumBounds bounds = chain.info().depositDatumBounds();
        if (!bounds.admits(datum.encode(), DEPOSIT_DATUM_DEPTH)) {
            return new Beneficiary(null, error(422, "DATUM_TOO_LARGE", "the deposit datum for this refund address is "
                    + datum.encode().length + " bytes; the deposit observer reads at most " + bounds.maxBytes()
                    + " bytes and depth " + bounds.maxDepth() + ". Deposit from a shorter address"));
        }
        return new Beneficiary(datum, null);
    }

    private PayoutConfirmation layout(Chain chain) {
        String observer = chain.info().payoutObserverId().isEmpty() ? "payouts" : chain.info().payoutObserverId();
        return new PayoutConfirmation(AssetPaymentsContract.PREFIX, observer, chain.domainId(), 0,
                ignored -> Optional.empty());
    }

    /**
     * D5: the observer credits a vault's deposit only from its {@code activeFromSlot}. A vault active from slot 0 is
     * always active; any later activation needs a known tip at or past it.
     */
    private DomainApiResponse notActive(RegistryEntryV1 vault, OptionalLong tip) {
        if (vault.activeFromSlot() == 0) return null;
        if (tip.isPresent() && tip.getAsLong() >= vault.activeFromSlot()) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VAULT_NOT_ACTIVE");
        body.put("message", tip.isEmpty()
                ? "this node does not know its L1 tip, so it cannot tell whether the vault is active yet"
                : "the vault credits deposits from L1 slot " + vault.activeFromSlot() + "; the tip is "
                + tip.getAsLong());
        body.put("activeFromSlot", vault.activeFromSlot());
        return respond(409, body);
    }

    private OptionalLong tipSlot() {
        try {
            return OptionalLong.of(context.l1Transactions().tipSlot());
        } catch (RuntimeException unavailable) {
            return OptionalLong.empty();
        }
    }

    private AppQueryResult query(String chainId, String path, byte[] params) {
        try {
            return context.queryService().query(chainId, path, params);
        } catch (AppQueryException failure) {
            throw translate(failure);
        }
    }

    static DomainApiException translate(AppQueryException failure) {
        DomainApiException.Code code = switch (failure.code()) {
            case INVALID_REQUEST, REQUEST_TOO_LARGE -> DomainApiException.Code.INVALID_REQUEST;
            case UNSUPPORTED -> DomainApiException.Code.NOT_FOUND;
            case BUSY -> DomainApiException.Code.BUSY;
            case TIMEOUT -> DomainApiException.Code.TIMEOUT;
            case RESULT_TOO_LARGE -> DomainApiException.Code.RESULT_TOO_LARGE;
            case UNAVAILABLE -> DomainApiException.Code.UNAVAILABLE;
            case FAILED -> DomainApiException.Code.FAILED;
        };
        return new DomainApiException(code, "payments query failed", failure);
    }

    // ------------------------------------------------------------------ JSON

    private static Map<String, Object> vaultView(RegistryEntryV1 vault) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("vaultId", vault.vaultId().hex());
        view.put("asset", assetView(vault.asset()));
        view.put("ticker", vault.ticker());
        view.put("decimals", vault.decimals());
        view.put("status", vault.status().name());
        return view;
    }

    static Map<String, Object> assetView(AssetId asset) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("policyId", HEX.formatHex(asset.policyId()));
        view.put("assetName", HEX.formatHex(asset.assetName()));
        view.put("unit", asset.isAda() ? "lovelace" : HEX.formatHex(asset.policyId()) + HEX.formatHex(asset.assetName()));
        view.put("ada", asset.isAda());
        return view;
    }

    private static Map<String, Object> committed(AppQueryResult result) {
        return Map.of("height", result.committedHeight(), "stateRoot", HEX.formatHex(result.stateRoot()));
    }

    private static Map<String, Object> proof(Chain chain, byte[] key, byte[] value) {
        return proof(chain.result(), chain.id(), key, value);
    }

    /** One state fact: its key, its value (null when absent) and the host route that proves it at this height. */
    private static Map<String, Object> proof(AppQueryResult result, String chainId, byte[] key, byte[] value) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("keyHex", HEX.formatHex(key));
        view.put("valueHex", value == null ? null : HEX.formatHex(value));
        view.put("height", result.committedHeight());
        view.put("proofPath", "app-chain/chains/" + encode(chainId) + "/state/proof/" + HEX.formatHex(key)
                + "?height=" + result.committedHeight());
        return view;
    }

    private static String bech32(byte[] raw) {
        try {
            return new Address(raw).toBech32();
        } catch (RuntimeException notShelley) {
            return null;
        }
    }

    private static AssetAccounts keys() {
        return new AssetAccounts(AssetPaymentsContract.PREFIX, new AssetAccounts.Store() {
            @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
            @Override public void put(byte[] key, byte[] value) { throw new UnsupportedOperationException(); }
            @Override public void delete(byte[] key) { throw new UnsupportedOperationException(); }
        });
    }

    private DomainApiResponse ok(Object value) {
        return respond(200, value);
    }

    private DomainApiResponse error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message == null ? code : message);
        return respond(status, body);
    }

    private DomainApiResponse respond(int status, Object value) {
        try {
            return new DomainApiResponse(status, DomainApiMediaType.JSON, json.writeValueAsBytes(value));
        } catch (DomainApiException tooLarge) {
            throw tooLarge;
        } catch (Exception failure) {
            throw new DomainApiException(DomainApiException.Code.FAILED, "cannot encode payments response", failure);
        }
    }

    private JsonNode body(DomainApiRequest request) {
        if (request.method() != DomainHttpMethod.POST) throw new BadRequest("METHOD", "POST is required");
        try {
            JsonNode node = json.readTree(request.body());
            if (node == null || !node.isObject()) throw new BadRequest("INVALID_JSON", "a JSON object is required");
            return node;
        } catch (BadRequest invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new BadRequest("INVALID_JSON", "the request body is not valid JSON");
        }
    }

    // ------------------------------------------------------------------ parameters

    private static byte[] accountId(DomainApiRequest request) {
        try {
            return PaymentsIntentJson.parseHex(request.pathParameters().get("account_id"), 28);
        } catch (IllegalArgumentException invalid) {
            throw new BadRequest("INVALID_ACCOUNT_ID", "account id must be 28 bytes of hex");
        }
    }

    private static Hash32 hash(DomainApiRequest request, String name) {
        try {
            return Hash32.of(PaymentsIntentJson.parseHex(request.pathParameters().get(name), 32));
        } catch (IllegalArgumentException invalid) {
            throw new BadRequest("INVALID_" + name.toUpperCase(Locale.ROOT), name + " must be 32 bytes of hex");
        }
    }

    private static String text(JsonNode body, String field) {
        return PaymentsIntentJson.text(body, field);
    }

    private static Optional<String> single(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) return Optional.empty();
        if (values.size() != 1) throw new BadRequest("INVALID_" + name.toUpperCase(Locale.ROOT), name + " is single");
        return Optional.of(values.getFirst());
    }

    private static long longParameter(Map<String, List<String>> query, String name, long fallback, long max) {
        return single(query, name).map(value -> parseLong(value, name, 0, max)).orElse(fallback);
    }

    private static long parseLong(String value, String name, long min, long max) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException | NullPointerException invalid) {
            throw new BadRequest("INVALID_" + name.toUpperCase(Locale.ROOT), name + " must be an integer in range");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static DomainApiRoute route(String id, DomainHttpMethod method, String suffix) {
        return new DomainApiRoute(id, method, BASE + suffix, DomainApiAccess.READ);
    }

    /** A request error with a stable code, rendered as a 400 JSON body. */
    private static final class BadRequest extends IllegalArgumentException {
        final String code;

        BadRequest(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
