package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Model-based property test (V4): random sequences of credit, transfer, reserve and payout run
 * against both {@link AssetAccounts} and a trivial reference model. After every step the two
 * must agree, I3 (only credits raise liabilities, transfers preserve totals) and I4
 * ({@code credited − paid = available + reserved}) must hold, and a rejected operation must leave
 * state unchanged.
 */
class AssetAccountsPropertyTest {
    private static final Hash32 VAULT = Hash32.of(new byte[32]);

    static final class MapStore implements AssetAccounts.Store {
        final Map<String, byte[]> data = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(data.get(HexFormat.of().formatHex(key))).map(byte[]::clone);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            data.put(HexFormat.of().formatHex(key), value.clone());
        }

        @Override
        public void delete(byte[] key) {
            data.remove(HexFormat.of().formatHex(key));
        }

        Map<String, String> snapshot() {
            Map<String, String> out = new HashMap<>();
            data.forEach((k, v) -> out.put(k, HexFormat.of().formatHex(v)));
            return out;
        }
    }

    record Model(Map<Integer, BigInteger[]> balances, BigInteger[] counters) {
        static Model empty() {
            BigInteger[] zeros = new BigInteger[8];
            java.util.Arrays.fill(zeros, BigInteger.ZERO);
            return new Model(new HashMap<>(), zeros);
        }
    }

    @Test
    void randomSequencesMatchTheModelAndKeepTheInvariants() {
        for (long seed = 1; seed <= 200; seed++) {
            runSequence(new Random(seed), 300);
        }
    }

    private void runSequence(Random random, int steps) {
        MapStore store = new MapStore();
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), store);
        Model model = Model.empty();
        List<BigInteger[]> openReservations = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            int holder = random.nextInt(4);
            BigInteger amount = BigInteger.valueOf(random.nextInt(1_000));
            BigInteger lovelace = BigInteger.valueOf(random.nextInt(3) == 0 ? random.nextInt(2_000_000) : 0);
            Map<String, String> before = store.snapshot();
            try {
                switch (random.nextInt(4)) {
                    case 0 -> {
                        accounts.credit(VAULT, id(holder), keyHash(holder), amount, lovelace);
                        bal(model, holder)[0] = bal(model, holder)[0].add(amount);
                        bal(model, holder)[1] = bal(model, holder)[1].add(lovelace);
                        model.counters[0] = model.counters[0].add(amount);
                        model.counters[2] = model.counters[2].add(amount);
                        model.counters[4] = model.counters[4].add(lovelace);
                        model.counters[6] = model.counters[6].add(lovelace);
                    }
                    case 1 -> {
                        int to = (holder + 1 + random.nextInt(3)) % 4;
                        accounts.transfer(VAULT, id(holder), id(to), keyHash(to), amount);
                        bal(model, holder)[0] = bal(model, holder)[0].subtract(amount);
                        bal(model, to)[0] = bal(model, to)[0].add(amount);
                    }
                    case 2 -> {
                        accounts.reserve(VAULT, id(holder), amount, lovelace);
                        bal(model, holder)[0] = bal(model, holder)[0].subtract(amount);
                        bal(model, holder)[1] = bal(model, holder)[1].subtract(lovelace);
                        model.counters[2] = model.counters[2].subtract(amount);
                        model.counters[3] = model.counters[3].add(amount);
                        model.counters[6] = model.counters[6].subtract(lovelace);
                        model.counters[7] = model.counters[7].add(lovelace);
                        openReservations.add(new BigInteger[] {amount, lovelace});
                    }
                    default -> {
                        if (openReservations.isEmpty()) continue;
                        BigInteger[] r = openReservations.remove(random.nextInt(openReservations.size()));
                        accounts.consumeReserved(VAULT, r[0], r[1]);
                        model.counters[1] = model.counters[1].add(r[0]);
                        model.counters[3] = model.counters[3].subtract(r[0]);
                        model.counters[5] = model.counters[5].add(r[1]);
                        model.counters[7] = model.counters[7].subtract(r[1]);
                    }
                }
                assertModelValid(model);
            } catch (ArithmeticException | IllegalArgumentException rejected) {
                // Each case calls the library before touching the model, so a rejected operation
                // leaves both unchanged; the store must be byte-for-byte as before.
                assertThat(store.snapshot()).isEqualTo(before);
                continue;
            }
            assertMatches(accounts, model);
            accounts.checkInvariant(VAULT);
        }
    }

    private static void assertModelValid(Model model) {
        for (BigInteger[] b : model.balances.values()) {
            assertThat(b[0].signum()).isNotNegative();
            assertThat(b[1].signum()).isNotNegative();
        }
    }

    private static void assertMatches(AssetAccounts accounts, Model model) {
        AssetAccounts.VaultCounters c = accounts.counters(VAULT);
        assertThat(List.of(c.credited(), c.paid(), c.available(), c.reserved(), c.lovelaceCredited(),
                c.lovelacePaid(), c.lovelaceAvailable(), c.lovelaceReserved()))
                .containsExactly(model.counters);
        BigInteger totalAvailable = BigInteger.ZERO;
        BigInteger totalLovelace = BigInteger.ZERO;
        for (Map.Entry<Integer, BigInteger[]> e : model.balances.entrySet()) {
            Optional<ExitLeafV1> leaf = accounts.balance(VAULT, id(e.getKey()));
            BigInteger amount = leaf.map(ExitLeafV1::amount).orElse(BigInteger.ZERO);
            BigInteger credit = leaf.map(ExitLeafV1::vaultLovelace).orElse(BigInteger.ZERO);
            assertThat(amount).isEqualTo(e.getValue()[0]);
            assertThat(credit).isEqualTo(e.getValue()[1]);
            totalAvailable = totalAvailable.add(amount);
            totalLovelace = totalLovelace.add(credit);
        }
        // I3: the sum of leaves equals the available counters, so transfers preserve totals.
        assertThat(totalAvailable).isEqualTo(c.available());
        assertThat(totalLovelace).isEqualTo(c.lovelaceAvailable());
    }

    @Test
    void lifetimeCountersSurviveTurnoverBeyondU64() {
        MapStore store = new MapStore();
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), store);
        BigInteger big = Quantities.MAX_U64;
        for (int i = 0; i < 3; i++) {
            accounts.credit(VAULT, id(0), keyHash(0), big, BigInteger.ZERO);
            accounts.reserve(VAULT, id(0), big, BigInteger.ZERO);
            accounts.consumeReserved(VAULT, big, BigInteger.ZERO);
        }
        AssetAccounts.VaultCounters c = accounts.counters(VAULT);
        assertThat(c.credited()).isEqualTo(big.multiply(BigInteger.valueOf(3)));
        assertThat(c.paid()).isEqualTo(c.credited());
        accounts.checkInvariant(VAULT);
    }

    @Test
    void aBalanceCannotOverflowU64() {
        MapStore store = new MapStore();
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), store);
        accounts.credit(VAULT, id(0), keyHash(0), Quantities.MAX_U64, BigInteger.ZERO);
        Map<String, String> before = store.snapshot();
        assertThatThrownBy(() -> accounts.credit(VAULT, id(0), keyHash(0), BigInteger.ONE, BigInteger.ZERO))
                .isInstanceOf(ArithmeticException.class);
        assertThat(store.snapshot()).isEqualTo(before);
    }

    @Test
    void exitLeafKeyIsPrefixVaultHolder() {
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), new MapStore());
        byte[] key = accounts.exitLeafKey(VAULT, new byte[] {7});
        assertThat(HexFormat.of().formatHex(key))
                .isEqualTo(HexFormat.of().formatHex("pay/x/".getBytes()) + "00".repeat(32) + "07");
    }

    private static BigInteger[] bal(Model model, int holder) {
        return model.balances.computeIfAbsent(holder, ignored -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
    }

    private static byte[] id(int holder) {
        return new byte[] {(byte) holder, 0x55};
    }

    private static byte[] keyHash(int holder) {
        byte[] hash = new byte[28];
        hash[0] = (byte) holder;
        return hash;
    }
}
