package org.yanoproject.x.dpp.client;

import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ledger-ordered record of the applied mutations that touched each product, replayed from
 * finalized map commands confirmed through their receipts (ADR-051 §2.2). It names the keys a
 * passport must answer with proofs and orders the timeline; it is a cache, never a source of
 * truth, and every key it names is answered against the chain's root before it is shown.
 */
public final class PassportProjection {
    private static final HexFormat HEX = HexFormat.of();

    /** One applied mutation as the ledger ordered it. */
    public record Applied(long height, int position, String messageIdHex, String collection,
                          byte[] key, String operation, String productId) {
        public Applied {
            key = key.clone();
        }

        @Override public byte[] key() { return key.clone(); }

        public String keyText() {
            return DppStarterProfile.text(key);
        }

        public String keyHex() {
            return HEX.formatHex(key);
        }
    }

    /** The keys a product's passport must answer at a height, by collection, in ledger order. */
    public record ProductKeys(Map<String, byte[]> versions, Map<String, byte[]> claims,
                              Map<String, byte[]> events, Map<String, byte[]> certificates) {
    }

    private final List<Applied> applied = new ArrayList<>();
    private final Map<String, String> certificateProducts = new HashMap<>();
    private long replayedHeight;
    private int commandCount;

    public synchronized long replayedHeight() {
        return replayedHeight;
    }

    public synchronized int commandCount() {
        return commandCount;
    }

    public synchronized int appliedCount() {
        return applied.size();
    }

    synchronized void countCommand() {
        commandCount++;
    }

    synchronized void markReplayed(long height) {
        replayedHeight = Math.max(replayedHeight, height);
    }

    /** Records one applied mutation; certificates are attributed to the product their value names. */
    synchronized void apply(long height, int position, String messageIdHex,
                            AuthenticatedMapContract.Mutation mutation) {
        String collection = mutation.collectionId();
        byte[] key = mutation.applicationKey();
        String productId = DppStarterProfile.productIdOf(collection, key);
        if (DppStarterProfile.CERTIFICATES.equals(collection)) {
            String certificateId = DppStarterProfile.text(key);
            if (mutation.value().length > 0) {
                try {
                    productId = DppValues.CertificateValue.decode(mutation.value()).productId();
                    certificateProducts.put(certificateId, productId);
                } catch (RuntimeException undecodable) {
                    productId = null;
                }
            } else {
                productId = certificateProducts.get(certificateId);
            }
        }
        if (productId == null) {
            return;
        }
        applied.add(new Applied(height, position, messageIdHex, collection, key,
                operationName(mutation.operation()), productId));
    }

    /** The product's timeline up to and including {@code height}, in ledger order. */
    public synchronized List<Applied> timeline(String productId, long height) {
        Objects.requireNonNull(productId, "productId");
        List<Applied> entries = new ArrayList<>();
        for (Applied entry : applied) {
            if (entry.height() <= height && entry.productId().equals(productId)) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    public synchronized ProductKeys keysAt(String productId, long height) {
        Map<String, byte[]> versions = new LinkedHashMap<>();
        Map<String, byte[]> claims = new LinkedHashMap<>();
        Map<String, byte[]> events = new LinkedHashMap<>();
        Map<String, byte[]> certificates = new LinkedHashMap<>();
        for (Applied entry : timeline(productId, height)) {
            Map<String, byte[]> target = switch (entry.collection()) {
                case DppStarterProfile.VERSIONS -> versions;
                case DppStarterProfile.CLAIMS -> claims;
                case DppStarterProfile.EVENTS -> events;
                case DppStarterProfile.CERTIFICATES -> certificates;
                default -> null;
            };
            if (target != null) {
                target.putIfAbsent(entry.keyText(), entry.key());
            }
        }
        return new ProductKeys(versions, claims, events, certificates);
    }

    public static String operationName(int operation) {
        return switch (operation) {
            case AuthenticatedMapContract.OP_PUT -> "PUT";
            case AuthenticatedMapContract.OP_PUT_IF_ABSENT -> "PUT_IF_ABSENT";
            case AuthenticatedMapContract.OP_COMPARE_AND_SET -> "COMPARE_AND_SET";
            case AuthenticatedMapContract.OP_TRANSFER_CONTROLLER -> "TRANSFER_CONTROLLER";
            case AuthenticatedMapContract.OP_REVOKE -> "REVOKE";
            case AuthenticatedMapContract.OP_RESTORE -> "RESTORE";
            default -> "OP_" + operation;
        };
    }
}
