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

    record Model(Map<Integer, BigInteger[]> balances, Map<Integer, Integer> keys, BigInteger[] counters) {
        static Model empty() {
            BigInteger[] zeros = new BigInteger[8];
            java.util.Arrays.fill(zeros, BigInteger.ZERO);
            return new Model(new HashMap<>(), new HashMap<>(), zeros);
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
            // Occasionally use a second key for the same holder, to exercise key-mismatch rejection.
            int keyVariant = random.nextInt(10) == 0 ? 1 : 0;
            BigInteger amount = BigInteger.valueOf(random.nextInt(1_000));
            BigInteger lovelace = BigInteger.valueOf(random.nextInt(3) == 0 ? random.nextInt(2_000_000) : 0);
            if (random.nextInt(5) == 0) {
                // Use the holder's exact balance, so leaves empty, are deleted and are re-created.
                amount = bal(model, holder)[0];
                lovelace = bal(model, holder)[1];
            }
            final BigInteger amountF = amount;
            final BigInteger lovelaceF = lovelace;
            Map<String, String> before = store.snapshot();
            int op = random.nextInt(5);
            // The model predicts the outcome BEFORE the library runs.
            boolean expectAccept;
            Runnable call;
            Runnable applyModel;
            switch (op) {
                case 0 -> {
                    boolean keyOk = !model.keys.containsKey(holder) || model.keys.get(holder) == keyVariant;
                    expectAccept = keyOk && (amountF.signum() > 0 || lovelaceF.signum() > 0);
                    int variant = keyVariant;
                    call = () -> accounts.credit(VAULT, id(holder), keyHash(holder, variant), amountF, lovelaceF);
                    applyModel = () -> {
                        model.keys.putIfAbsent(holder, variant);
                        bal(model, holder)[0] = bal(model, holder)[0].add(amountF);
                        bal(model, holder)[1] = bal(model, holder)[1].add(lovelaceF);
                        model.counters[0] = model.counters[0].add(amountF);
                        model.counters[2] = model.counters[2].add(amountF);
                        model.counters[4] = model.counters[4].add(lovelaceF);
                        model.counters[6] = model.counters[6].add(lovelaceF);
                    };
                }
                case 1 -> {
                    int to = random.nextInt(5) == 0 ? holder : (holder + 1 + random.nextInt(3)) % 4;
                    int toVariant = model.keys.getOrDefault(to, 0);
                    expectAccept = to != holder && amountF.signum() > 0
                            && bal(model, holder)[0].compareTo(amountF) >= 0
                            && model.keys.containsKey(holder);
                    call = () -> accounts.transfer(VAULT, id(holder), id(to), keyHash(to, toVariant), amountF);
                    applyModel = () -> {
                        model.keys.putIfAbsent(to, toVariant);
                        bal(model, holder)[0] = bal(model, holder)[0].subtract(amountF);
                        bal(model, to)[0] = bal(model, to)[0].add(amountF);
                    };
                }
                case 2 -> {
                    expectAccept = (amountF.signum() > 0 || lovelaceF.signum() > 0)
                            && model.keys.containsKey(holder)
                            && bal(model, holder)[0].compareTo(amountF) >= 0
                            && bal(model, holder)[1].compareTo(lovelaceF) >= 0;
                    call = () -> accounts.reserve(VAULT, id(holder), amountF, lovelaceF);
                    applyModel = () -> {
                        bal(model, holder)[0] = bal(model, holder)[0].subtract(amountF);
                        bal(model, holder)[1] = bal(model, holder)[1].subtract(lovelaceF);
                        model.counters[2] = model.counters[2].subtract(amountF);
                        model.counters[3] = model.counters[3].add(amountF);
                        model.counters[6] = model.counters[6].subtract(lovelaceF);
                        model.counters[7] = model.counters[7].add(lovelaceF);
                        openReservations.add(new BigInteger[] {amountF, lovelaceF});
                    };
                }
                case 3 -> {
                    if (openReservations.isEmpty()) continue;
                    BigInteger[] r = openReservations.get(random.nextInt(openReservations.size()));
                    expectAccept = true;
                    call = () -> accounts.consumeReserved(VAULT, r[0], r[1]);
                    applyModel = () -> {
                        openReservations.remove(r);
                        model.counters[1] = model.counters[1].add(r[0]);
                        model.counters[3] = model.counters[3].subtract(r[0]);
                        model.counters[5] = model.counters[5].add(r[1]);
                        model.counters[7] = model.counters[7].subtract(r[1]);
                    };
                }
                default -> {
                    // Over-consume: more than is reserved must be rejected.
                    BigInteger over = model.counters[3].add(BigInteger.ONE);
                    expectAccept = false;
                    call = () -> accounts.consumeReserved(VAULT, over, BigInteger.ZERO);
                    applyModel = () -> { };
                }
            }
            boolean accepted;
            try {
                call.run();
                accepted = true;
            } catch (ArithmeticException | IllegalArgumentException rejected) {
                accepted = false;
            }
            assertThat(accepted).as("op %d step %d accept/reject", op, step).isEqualTo(expectAccept);
            if (accepted) {
                applyModel.run();
                // The library deletes an emptied leaf and forgets its key; so does the model.
                model.balances.forEach((h, b) -> {
                    if (b[0].signum() == 0 && b[1].signum() == 0) model.keys.remove(h);
                });
            } else {
                assertThat(store.snapshot()).as("a rejected op leaves no partial write").isEqualTo(before);
            }
            assertModelValid(model);
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
            boolean modelEmpty = e.getValue()[0].signum() == 0 && e.getValue()[1].signum() == 0;
            // An empty balance must be deleted, never stored as a zero leaf.
            assertThat(leaf.isPresent()).as("leaf presence for holder %d", e.getKey()).isEqualTo(!modelEmpty);
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
    void creditWithADifferentKeyIsRejectedAndRotationIsExplicit() {
        MapStore store = new MapStore();
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), store);
        accounts.credit(VAULT, id(0), keyHash(0, 0), BigInteger.TEN, BigInteger.ZERO);
        Map<String, String> before = store.snapshot();
        assertThatThrownBy(() -> accounts.credit(VAULT, id(0), keyHash(0, 1), BigInteger.ONE, BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.snapshot()).isEqualTo(before);
        accounts.rotateHolderKey(VAULT, id(0), keyHash(0, 1));
        assertThat(accounts.balance(VAULT, id(0)).orElseThrow().holderKeyHash()).isEqualTo(keyHash(0, 1));
        accounts.credit(VAULT, id(0), keyHash(0, 1), BigInteger.ONE, BigInteger.ZERO);
        assertThat(accounts.balance(VAULT, id(0)).orElseThrow().amount()).isEqualTo(BigInteger.valueOf(11));
    }

    @Test
    void anEmptiedLeafForgetsItsKeySoConsumersMustSupplyTheAuthoritativeKey() {
        MapStore store = new MapStore();
        AssetAccounts accounts = new AssetAccounts("pay/".getBytes(), store);
        accounts.credit(VAULT, id(0), keyHash(0, 0), BigInteger.TEN, BigInteger.ZERO);
        accounts.reserve(VAULT, id(0), BigInteger.TEN, BigInteger.ZERO);
        assertThat(accounts.balance(VAULT, id(0))).isEmpty();
        // The library cannot tell which key is authoritative once the leaf is gone; the consumer
        // supplies it from the account record. Re-credit with the owner's key binds it again.
        accounts.credit(VAULT, id(0), keyHash(0, 0), BigInteger.ONE, BigInteger.ZERO);
        assertThat(accounts.balance(VAULT, id(0)).orElseThrow().holderKeyHash()).isEqualTo(keyHash(0, 0));
        assertThat(accounts.rotateHolderKey(VAULT, id(3), keyHash(3, 0))).isFalse();
        assertThatThrownBy(() -> accounts.rotateHolderKey(VAULT, id(0), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void u128CountersAreBoundedAndRoundTripAtTheEdges() {
        AssetAccounts.VaultCounters top = new AssetAccounts.VaultCounters(Quantities.MAX_U128,
                BigInteger.ONE.shiftLeft(127), BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        assertThat(AssetAccounts.VaultCounters.decode(top.encode())).isEqualTo(top);
        assertThatThrownBy(() -> Quantities.addU128(Quantities.MAX_U128, BigInteger.ONE, "x"))
                .isInstanceOf(ArithmeticException.class);
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
        return keyHash(holder, 0);
    }

    private static byte[] keyHash(int holder, int variant) {
        byte[] hash = new byte[28];
        hash[0] = (byte) holder;
        hash[1] = (byte) variant;
        return hash;
    }
}
