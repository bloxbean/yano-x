package org.yanoproject.x.assets.contracts;

import java.math.BigInteger;

/**
 * Checked integer arithmetic for ADR-059 D3. Balances, reservations and per-claim amounts are
 * unsigned 64-bit (matching Cardano's {@code positive_coin}); lifetime counters are unsigned
 * 128-bit, so recirculating a fixed supply can never overflow them. Every operation throws
 * {@link ArithmeticException} instead of wrapping.
 */
public final class Quantities {
    public static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    public static final BigInteger MAX_U128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);

    private Quantities() {
    }

    public static BigInteger requireU64(BigInteger value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_U64) > 0) {
            throw new ArithmeticException(name + " must be an unsigned 64-bit integer");
        }
        return value;
    }

    public static BigInteger requirePositiveU64(BigInteger value, String name) {
        requireU64(value, name);
        if (value.signum() == 0) throw new ArithmeticException(name + " must be positive");
        return value;
    }

    public static BigInteger requireU128(BigInteger value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_U128) > 0) {
            throw new ArithmeticException(name + " must be an unsigned 128-bit integer");
        }
        return value;
    }

    public static BigInteger addU64(BigInteger left, BigInteger right, String name) {
        return requireU64(requireU64(left, name).add(requireU64(right, name)), name);
    }

    public static BigInteger subU64(BigInteger left, BigInteger right, String name) {
        BigInteger result = requireU64(left, name).subtract(requireU64(right, name));
        if (result.signum() < 0) throw new ArithmeticException(name + " would become negative");
        return result;
    }

    public static BigInteger addU128(BigInteger left, BigInteger right, String name) {
        return requireU128(requireU128(left, name).add(requireU128(right, name)), name);
    }

    public static BigInteger subU128(BigInteger left, BigInteger right, String name) {
        BigInteger result = requireU128(left, name).subtract(requireU128(right, name));
        if (result.signum() < 0) throw new ArithmeticException(name + " would become negative");
        return result;
    }
}
