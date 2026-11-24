package org.yanoproject.x.framework.cardano;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.config.YanoPropertyKeys;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The node's configured address filter on its L1 UTxO store ({@code yano.filters.utxo.enabled}),
 * as far as this node's configuration shows it. With the filter on, the UTxO store keeps only
 * outputs whose address is listed in {@code yano.filters.utxo.addresses} or whose payment
 * credential is listed in {@code yano.filters.utxo.payment-credentials}; with both lists empty
 * it keeps everything.
 *
 * <p>Script-anchor co-signing resolves every input of the leader's body in the member's own
 * UTxO view (ADR-059 framework addition 6), so a filtered view must admit the anchor thread
 * and the leader's fee and collateral wallet. Storage filters that plugins register
 * programmatically are not visible here; operators running such a filter must apply the same
 * rule themselves.</p>
 */
record L1UtxoViewFilter(boolean enabled, Set<String> addresses, Set<String> paymentCredentials) {
    static final String ENABLED = YanoPropertyKeys.UtxoFilter.ENABLED;
    static final String ADDRESSES = YanoPropertyKeys.UtxoFilter.ADDRESSES;
    static final String PAYMENT_CREDENTIALS = YanoPropertyKeys.UtxoFilter.PAYMENT_CREDENTIALS;

    static final L1UtxoViewFilter NONE = new L1UtxoViewFilter(false, Set.of(), Set.of());

    L1UtxoViewFilter {
        addresses = Set.copyOf(Objects.requireNonNull(addresses, "addresses"));
        paymentCredentials = Set.copyOf(Objects.requireNonNull(paymentCredentials, "paymentCredentials"));
    }

    /** Read the filter from the node's flattened global configuration. */
    static L1UtxoViewFilter fromGlobals(Map<String, ?> globals) {
        if (globals == null) {
            return NONE;
        }
        Object enabled = globals.get(ENABLED);
        if (enabled == null || !Boolean.parseBoolean(String.valueOf(enabled).trim())) {
            return NONE;
        }
        return new L1UtxoViewFilter(true, values(globals.get(ADDRESSES), false),
                values(globals.get(PAYMENT_CREDENTIALS), true));
    }

    /** Does the filter drop anything at all? (Enabled with both lists empty keeps everything.) */
    boolean filtering() {
        return enabled && !(addresses.isEmpty() && paymentCredentials.isEmpty());
    }

    /** Would an output at {@code address} (payment credential {@code paymentCredential}) be kept? */
    boolean admits(String address, byte[] paymentCredential) {
        if (!filtering()) {
            return true;
        }
        if (address != null && addresses.contains(address)) {
            return true;
        }
        return paymentCredential != null
                && paymentCredentials.contains(HexUtil.encodeHexString(paymentCredential).toLowerCase(Locale.ROOT));
    }

    private static Set<String> values(Object raw, boolean lowercase) {
        Set<String> result = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            collection.forEach(item -> add(result, item, lowercase));
        } else if (raw instanceof Object[] array) {
            for (Object item : array) {
                add(result, item, lowercase);
            }
        } else if (raw instanceof Map<?, ?> indexed) {
            indexed.values().forEach(item -> add(result, item, lowercase));
        } else if (raw != null) {
            add(result, raw, lowercase);
        }
        return result;
    }

    private static void add(Set<String> result, Object item, boolean lowercase) {
        if (item == null) {
            return;
        }
        for (String part : String.valueOf(item).split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                result.add(lowercase ? value.toLowerCase(Locale.ROOT) : value);
            }
        }
    }
}
