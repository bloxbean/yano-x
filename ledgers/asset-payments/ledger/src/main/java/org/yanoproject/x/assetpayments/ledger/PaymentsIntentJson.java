package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.fasterxml.jackson.databind.JsonNode;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.CardanoCredential;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.SessionGrantV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The JSON form of an intent, for clients that do not carry their own CBOR encoder (#109 §3.1: sign exact bytes,
 * never an ambiguous JSON rendering). The JSON only selects the fields; the bytes a wallet signs are always the
 * canonical {@link IntentEnvelopeV1} encoding returned next to it, and every action body is the exact
 * {@link PaymentsBodies} encoding. Amounts are decimal strings, because a u64 does not fit a JSON number safely.
 *
 * <pre>
 * {"action": "TRANSFER", "accountId": hex28, "authorizer": {"kind": "OWNER"} | {"kind": "SESSION", "grantId": hex32},
 *  "nonce": hex16, "notAfterMs": n, "body": {...}}
 *
 * REGISTER_ACCOUNT    {}
 * TRANSFER            {"asset": {"policyId": hex, "assetName": hex}, "toAccount": hex28, "amount": "n"}
 * AUTHORIZE_SESSION   {"sessionKey": hex32, "actions": ["TRANSFER"], "limits": [{"vaultId", "perPayment",
 *                      "cumulative"}], "recipients": [hex28], "notAfterMs": n}
 * REVOKE_SESSION      {"grantId": hex32}
 * REQUEST_WITHDRAWAL  {"vaultId": hex32, "recipient": hex | bech32, "amount": "n", "vaultLovelace": "n"}
 * ROTATE_OWNER        {"address": hex | bech32}   (the new owner's signing address)
 * </pre>
 */
final class PaymentsIntentJson {
    static final int UP_TO_32 = -1;
    static final int ANY = -2;
    private static final int MAX_HEX_BYTES = 16 * 1024;
    private static final HexFormat HEX = HexFormat.of();

    private PaymentsIntentJson() {
    }

    static IntentEnvelopeV1 envelope(JsonNode json, IntentEnvelopeV1.Domain domain) {
        String action = text(json, "action");
        if (!AssetPaymentsContract.ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action is not one of " + AssetPaymentsContract.ACTIONS);
        }
        JsonNode who = json.path("authorizer");
        IntentEnvelopeV1.Authorizer authorizer = switch (who.path("kind").asText("OWNER")) {
            case "OWNER" -> new IntentEnvelopeV1.Owner();
            case "SESSION" -> new IntentEnvelopeV1.Session(Hash32.of(hex(who, "grantId", 32)));
            default -> throw new IllegalArgumentException("authorizer.kind is OWNER or SESSION");
        };
        long notAfter = json.path("notAfterMs").asLong(-1);
        if (!json.path("notAfterMs").canConvertToLong() || notAfter < 0) {
            throw new IllegalArgumentException("notAfterMs must be a non-negative integer");
        }
        return new IntentEnvelopeV1(domain, action, hex(json, "accountId", 28), authorizer, hex(json, "nonce", 16),
                notAfter, body(action, json.path("body"), domain.addressNetworkId()));
    }

    static byte[] body(String action, JsonNode body, int networkId) {
        return switch (action) {
            case WalletAuthorizer.REGISTER_ACCOUNT -> PaymentsBodies.REGISTER_ACCOUNT.clone();
            case AssetPaymentsContract.TRANSFER -> {
                JsonNode asset = body.path("asset");
                yield new PaymentsBodies.Transfer(AssetId.of(hex(asset, "policyId", UP_TO_32), hex(asset, "assetName", UP_TO_32)),
                        hex(body, "toAccount", 28), amount(body, "amount")).encode();
            }
            case WalletAuthorizer.AUTHORIZE_SESSION -> {
                List<String> actions = new ArrayList<>();
                body.path("actions").forEach(node -> actions.add(node.asText()));
                List<SessionGrantV1.Limit> limits = new ArrayList<>();
                for (JsonNode limit : body.path("limits")) {
                    limits.add(new SessionGrantV1.Limit(Hash32.of(hex(limit, "vaultId", 32)),
                            amount(limit, "perPayment"), amount(limit, "cumulative")));
                }
                List<byte[]> recipients = new ArrayList<>();
                for (JsonNode recipient : body.path("recipients")) recipients.add(parseHex(recipient.asText(), 28));
                long notAfter = body.path("notAfterMs").asLong(-1);
                yield new PaymentsBodies.AuthorizeSession(hex(body, "sessionKey", 32), actions, limits, recipients,
                        notAfter).encode();
            }
            case WalletAuthorizer.REVOKE_SESSION ->
                    new PaymentsBodies.RevokeSession(Hash32.of(hex(body, "grantId", 32))).encode();
            case AssetPaymentsContract.REQUEST_WITHDRAWAL -> new PaymentsBodies.RequestWithdrawal(
                    Hash32.of(hex(body, "vaultId", 32)), address(text(body, "recipient")), amount(body, "amount"),
                    amount(body, "vaultLovelace")).encode();
            case WalletAuthorizer.ROTATE_OWNER -> {
                CardanoCredential target = CardanoCredential.fromAddress(address(text(body, "address")));
                if (target.addressNetworkId() != networkId) {
                    throw new IllegalArgumentException("the new owner's address is on another network");
                }
                yield WalletAuthorizer.rotationBody(target);
            }
            default -> throw new IllegalArgumentException("unsupported action");
        };
    }

    /** Raw Shelley address bytes from lowercase or uppercase hex, or from bech32. */
    static byte[] address(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 256) throw new IllegalArgumentException("address is required");
        if (trimmed.matches("(?:[0-9a-fA-F]{2})+")) return HEX.parseHex(trimmed.toLowerCase(Locale.ROOT));
        try {
            return new Address(trimmed).getBytes();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("address is neither hex nor a bech32 Shelley address");
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value.asText();
    }

    /** Exactly {@code length} bytes; {@link #UP_TO_32} for an asset name or policy id, {@link #ANY} up to 16 KiB. */
    static byte[] hex(JsonNode node, String field, int length) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be hex text");
        return parseHex(value.asText(), length);
    }

    static byte[] parseHex(String value, int length) {
        if (value == null || (value.length() & 1) != 0 || value.length() > 2 * MAX_HEX_BYTES
                || !value.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("expected even-length hex");
        }
        byte[] bytes = HEX.parseHex(value.toLowerCase(Locale.ROOT));
        boolean fits = switch (length) {
            case UP_TO_32 -> bytes.length <= 32;
            case ANY -> !value.isEmpty();
            default -> bytes.length == length;
        };
        if (!fits) {
            throw new IllegalArgumentException("expected " + switch (length) {
                case UP_TO_32 -> "at most 32 bytes";
                case ANY -> "a non-empty value";
                default -> length + " bytes";
            });
        }
        return bytes;
    }

    static BigInteger amount(JsonNode node, String field) {
        JsonNode value = node.path(field);
        String text = value.isTextual() ? value.asText() : value.isIntegralNumber() ? value.asText() : "";
        if (!text.matches("0|[1-9][0-9]{0,19}")) {
            throw new IllegalArgumentException(field + " must be a non-negative decimal integer string");
        }
        return new BigInteger(text);
    }
}
