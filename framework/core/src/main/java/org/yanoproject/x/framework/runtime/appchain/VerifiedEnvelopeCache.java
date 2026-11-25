package org.yanoproject.x.framework.runtime.appchain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU set of envelopes whose Ed25519 signature verified (ADR-060 D3), keyed by
 * {@code (messageId, authProof)}. A member sees every message once per relaying peer and again in a block; a hit
 * replaces those repeated verifications. Sound because callers check {@code hasValidMessageId()} first: the message
 * id is a content hash of every signed field, so a hit proves these exact signed bytes verified under this exact
 * signature and sender. Only successful verifications are recorded; a miss always verifies.
 */
final class VerifiedEnvelopeCache {
    private static final int STRIPES = 16;

    private final Stripe[] stripes = new Stripe[STRIPES];

    VerifiedEnvelopeCache(int capacity) {
        int perStripe = Math.max(16, capacity / STRIPES);
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Stripe(perStripe);
        }
    }

    boolean contains(byte[] messageId, byte[] authProof) {
        Key key = new Key(messageId, authProof);
        Stripe stripe = stripe(key);
        synchronized (stripe) {
            return stripe.get(key) != null;
        }
    }

    void add(byte[] messageId, byte[] authProof) {
        Key key = new Key(messageId.clone(), authProof.clone());
        Stripe stripe = stripe(key);
        synchronized (stripe) {
            stripe.put(key, Boolean.TRUE);
        }
    }

    int size() {
        int size = 0;
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                size += stripe.size();
            }
        }
        return size;
    }

    private Stripe stripe(Key key) {
        return stripes[Math.floorMod(key.hash, STRIPES)];
    }

    private static final class Stripe extends LinkedHashMap<Key, Boolean> {
        private final int capacity;

        Stripe(int capacity) {
            super(capacity * 4 / 3 + 1, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Boolean> eldest) {
            return size() > capacity;
        }
    }

    private static final class Key {
        private final byte[] messageId;
        private final byte[] authProof;
        private final int hash;

        Key(byte[] messageId, byte[] authProof) {
            this.messageId = messageId;
            this.authProof = authProof;
            this.hash = 31 * Arrays.hashCode(messageId) + Arrays.hashCode(authProof);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(messageId, key.messageId)
                    && Arrays.equals(authProof, key.authProof);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
