package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.contracts.AssetValue;
import org.yanoproject.x.assets.contracts.DepositAdmission;
import org.yanoproject.x.assets.contracts.DepositRecordV1;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.SweepCommitment;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-061 §10 P1, I40: the julc and Aiken backends of the custody script {@code h}, the exit delegate {@code e} and
 * the shard binding variant accept and reject exactly the same script contexts.
 *
 * <p>Each script learns its own hash from its purpose, so every transaction is built once in the julc world (the julc
 * scripts' hashes, {@link FullTx}) and each Aiken script runs on exactly the script context of its julc twin: the
 * Aiken {@code h} is applied with the julc {@code e}'s hash and the Aiken shard with the julc {@code h}'s, as their
 * julc twins are. A case is one script run; every case must get the same verdict from both backends.
 *
 * <ul>
 *   <li>Every action, honest, and alternative honest field values (claim kinds, domain statuses, boundaries).</li>
 *   <li>Every negative of {@code ProgrammableVaultNegativeTest}.</li>
 *   <li>The state-proof verifier: honest inclusion at many trie sizes, absence at every terminal kind and cursor
 *       parity, systematic and seeded random changes of inclusion and absence proofs.</li>
 *   <li>Systematic (every node of the Data tree) and random changes of every action's redeemer, of every datum the
 *       scripts read, and of the transaction: inputs, outputs, values, addresses, reference inputs, signers, validity
 *       range, withdrawals, mint, certificates and the shard's redeemer.</li>
 *   <li>Lenient encodings both scripts accept: constructor tags they do not check and extra fields.</li>
 * </ul>
 *
 * <p>{@code DIFF|group|cases|accepted|rejected|disagreements} lines report the counts and {@code UNITS|} lines the
 * custody scripts' units of the honest transactions under both backends.
 */
class ProgrammableVaultBackendDifferentialTest extends ProgrammableTestBase {
    private static final Map<String, int[]> TALLY = new TreeMap<>();
    private static final List<String> DISAGREEMENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> TERMINALS = new TreeMap<>();
    private static final List<String> UNITS = new ArrayList<>();

    private final ProgrammableVaultCompositionTest composition = new ProgrammableVaultCompositionTest();

    // ------------------------------------------------------------------ the comparison

    /** A julc script run of a transaction and its Aiken twin, identified by its script purpose. */
    record Twin(String name, Program julc, Program aiken, PlutusData purpose) {
    }

    /** A transaction as Data, and the custody runs to compare in it. */
    record World(PlutusData info, List<Twin> twins) {
        World with(PlutusData newInfo) {
            return new World(newInfo, twins);
        }
    }

    /** The Aiken twin of a julc script, by its hash; null for a script with no twin. */
    private static Program twin(byte[] hash) {
        if (Arrays.equals(hash, custodyHash())) {
            return aikenCustody();
        }
        if (Arrays.equals(hash, exitDelegateHash())) {
            return aikenExitDelegate();
        }
        if (Arrays.equals(hash, programmableShardHash())) {
            return aikenProgrammableShard();
        }
        return null;
    }

    static World world(FullTx tx) {
        List<Twin> twins = new ArrayList<>();
        PlutusData info = null;
        for (ScriptRun run : tx.finish().runs()) {
            Program aiken = twin(run.hash());
            if (aiken == null) {
                continue;
            }
            List<PlutusData> f = fields(run.context());
            info = f.get(0);
            twins.add(new Twin(run.name().replaceAll("#.*", ""), run.program(), aiken, purposeOf(f.get(2))));
        }
        return new World(info == null ? tx.txInfo() : info, twins);
    }

    private static PlutusData purposeOf(PlutusData scriptInfo) {
        PlutusData.ConstrData info = (PlutusData.ConstrData) scriptInfo;
        return info.tag() == 1 ? PlutusData.constr(1, info.fields().getFirst()) : scriptInfo;
    }

    /** The script context of {@code purpose} in {@code info}, or null when the ledger would not run it. */
    static PlutusData context(PlutusData info, PlutusData purpose) {
        List<PlutusData> f = fields(info);
        PlutusData redeemer = lookup(f.get(9), purpose);
        if (redeemer == null) {
            return null;
        }
        PlutusData.ConstrData p = (PlutusData.ConstrData) purpose;
        PlutusData scriptInfo = purpose;
        if (p.tag() == 1) {
            PlutusData input = null;
            for (PlutusData candidate : items(f.get(0))) {
                if (fields(candidate).getFirst().equals(p.fields().getFirst())) {
                    input = candidate;
                }
            }
            if (input == null) {
                return null;
            }
            PlutusData.ConstrData datum = (PlutusData.ConstrData) fields(fields(input).get(1)).get(2);
            scriptInfo = PlutusData.constr(1, p.fields().getFirst(),
                    datum.tag() == 2 ? PlutusData.constr(0, datum.fields().getFirst()) : PlutusData.constr(1));
        } else if (p.tag() == 2 && lookup(f.get(6), p.fields().getFirst()) == null) {
            return null;
        } else if (p.tag() == 3) {
            int index = ((PlutusData.IntData) p.fields().getFirst()).value().intValueExact();
            List<PlutusData> certificates = items(f.get(5));
            if (index >= certificates.size() || !certificates.get(index).equals(p.fields().get(1))) {
                return null;
            }
        }
        return PlutusData.constr(0, info, redeemer, scriptInfo);
    }

    private static final Predicate<Twin> ALL = twin -> true;

    /** Runs both backends of the selected twins of {@code world}; returns the julc verdicts by twin name. */
    Map<String, Boolean> agree(String group, String label, World world, Predicate<Twin> which) {
        Map<String, Boolean> verdicts = new LinkedHashMap<>();
        for (Twin twin : world.twins()) {
            if (!which.test(twin)) {
                continue;
            }
            PlutusData context = context(world.info(), twin.purpose());
            if (context == null) {
                continue;
            }
            EvalResult j = evaluate(twin.julc(), context);
            EvalResult a = evaluate(twin.aiken(), context);
            record(group, label + " " + twin.name(), j, a);
            verdicts.merge(twin.name(), j.isSuccess(), Boolean::logicalAnd);
        }
        return verdicts;
    }

    Map<String, Boolean> agree(String group, String label, FullTx tx) {
        return agree(group, label, world(tx), ALL);
    }

    /** Runs every case of {@code cases} in parallel. */
    void agreeAll(String group, Map<String, World> cases, Predicate<Twin> which) {
        cases.entrySet().parallelStream().forEach(c -> agree(group, c.getKey(), c.getValue(), which));
    }

    private static void record(String group, String label, EvalResult julc, EvalResult aiken) {
        boolean j = julc.isSuccess();
        boolean a = aiken.isSuccess();
        synchronized (TALLY) {
            int[] counts = TALLY.computeIfAbsent(group, ignored -> new int[3]);
            counts[j ? 0 : 1]++;
            if (j != a) {
                counts[2]++;
                DISAGREEMENTS.add(group + " / " + label + ": julc " + (j ? "accepts" : "rejects " + julc)
                        + ", aiken " + (a ? "accepts" : "rejects " + aiken));
            }
        }
    }

    @AfterAll
    static void summary() {
        int accepted = 0;
        int rejected = 0;
        int disagreed = 0;
        for (Map.Entry<String, int[]> entry : TALLY.entrySet()) {
            int[] c = entry.getValue();
            System.out.printf("DIFF|%s|%d|%d|%d|disagreements=%d%n", entry.getKey(), c[0] + c[1], c[0], c[1], c[2]);
            accepted += c[0];
            rejected += c[1];
            disagreed += c[2];
        }
        System.out.printf("DIFF|total|%d|%d|%d|disagreements=%d%n", accepted + rejected, accepted, rejected,
                disagreed);
        TERMINALS.forEach((kind, count) -> System.out.printf("DIFF|absence terminal|%s|%d%n", kind, count));
        UNITS.forEach(System.out::println);
        DISAGREEMENTS.stream().limit(200).forEach(line -> System.out.println("DIFF|DISAGREEMENT|" + line));
    }

    private static void assertNoDisagreement() {
        assertThat(DISAGREEMENTS).as("the julc and Aiken custody backends disagree").isEmpty();
    }

    // ------------------------------------------------------------------ Data helpers

    static List<PlutusData> fields(PlutusData data) {
        return ((PlutusData.ConstrData) data).fields();
    }

    static List<PlutusData> items(PlutusData data) {
        return ((PlutusData.ListData) data).items();
    }

    static PlutusData lookup(PlutusData map, PlutusData key) {
        for (PlutusData.Pair pair : ((PlutusData.MapData) map).entries()) {
            if (pair.key().equals(key)) {
                return pair.value();
            }
        }
        return null;
    }

    static PlutusData withField(PlutusData constr, int index, PlutusData value) {
        List<PlutusData> f = new ArrayList<>(fields(constr));
        f.set(index, value);
        return new PlutusData.ConstrData(((PlutusData.ConstrData) constr).tag(), f);
    }

    static PlutusData withEntry(PlutusData map, PlutusData key, PlutusData value) {
        List<PlutusData.Pair> entries = new ArrayList<>();
        for (PlutusData.Pair pair : ((PlutusData.MapData) map).entries()) {
            entries.add(pair.key().equals(key) ? new PlutusData.Pair(key, value) : pair);
        }
        return new PlutusData.MapData(entries);
    }

    static PlutusData withoutEntry(PlutusData map, PlutusData key) {
        List<PlutusData.Pair> entries = new ArrayList<>(((PlutusData.MapData) map).entries());
        entries.removeIf(pair -> pair.key().equals(key));
        return new PlutusData.MapData(entries);
    }

    static PlutusData list(List<PlutusData> items) {
        return new PlutusData.ListData(items);
    }

    /** {@code info} with TxInfo field {@code index} replaced. */
    static PlutusData info(PlutusData info, int index, PlutusData value) {
        return withField(info, index, value);
    }

    static PlutusData withdrawPurpose(byte[] hash) {
        return PlutusData.constr(2, Cip113Blueprint.scriptCredential(hash));
    }

    /** {@code info} with the redeemer of {@code purpose} replaced. */
    static PlutusData withRedeemer(PlutusData info, PlutusData purpose, PlutusData redeemer) {
        return info(info, 9, withEntry(fields(info).get(9), purpose, redeemer));
    }

    static PlutusData redeemerOf(PlutusData info, PlutusData purpose) {
        return lookup(fields(info).get(9), purpose);
    }

    // ------------------------------------------------------------------ Data changes

    /** Every single-node change of {@code data}: each constructor, list, integer and byte string, changed. */
    static Map<String, PlutusData> nodeChanges(PlutusData data) {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        List<int[]> paths = new ArrayList<>();
        collect(data, new int[0], paths);
        for (int[] path : paths) {
            PlutusData node = at(data, path, 0);
            String at = "at " + Arrays.toString(path) + " ";
            for (Map.Entry<String, PlutusData> variant : variants(node).entrySet()) {
                out.put(at + variant.getKey(), replace(data, path, 0, variant.getValue()));
            }
        }
        return out;
    }

    private static Map<String, PlutusData> variants(PlutusData node) {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        PlutusData zero = PlutusData.integer(0);
        PlutusData empty = PlutusData.bytes(new byte[0]);
        if (node instanceof PlutusData.ConstrData constr) {
            List<PlutusData> f = constr.fields();
            out.put("tag+1", new PlutusData.ConstrData(constr.tag() + 1, f));
            out.put("tag 7", new PlutusData.ConstrData(constr.tag() == 7 ? 0 : 7, f));
            List<PlutusData> more = new ArrayList<>(f);
            more.add(zero);
            out.put("extra field", new PlutusData.ConstrData(constr.tag(), more));
            if (!f.isEmpty()) {
                out.put("last field dropped", new PlutusData.ConstrData(constr.tag(), f.subList(0, f.size() - 1)));
            }
            out.put("as int", zero);
            out.put("as list", list(f));
        } else if (node instanceof PlutusData.ListData l) {
            List<PlutusData> i = l.items();
            if (!i.isEmpty()) {
                out.put("last dropped", list(i.subList(0, i.size() - 1)));
                List<PlutusData> doubled = new ArrayList<>(i);
                doubled.addFirst(i.getFirst());
                out.put("first doubled", list(doubled));
                out.put("emptied", list(List.of()));
            }
            out.put("as int", zero);
            out.put("as constr", new PlutusData.ConstrData(0, i));
        } else if (node instanceof PlutusData.IntData n) {
            out.put("+1", PlutusData.integer(n.value().add(BigInteger.ONE)));
            out.put("-1", PlutusData.integer(n.value().subtract(BigInteger.ONE)));
            out.put("negated", PlutusData.integer(n.value().negate().subtract(BigInteger.ONE)));
            out.put("2^64", PlutusData.integer(BigInteger.TWO.pow(64)));
            out.put("as bytes", PlutusData.bytes(new byte[] {1}));
        } else if (node instanceof PlutusData.BytesData b) {
            byte[] v = b.value();
            if (v.length > 0) {
                byte[] flipped = v.clone();
                flipped[v.length - 1] ^= 0x01;
                out.put("last byte flipped", PlutusData.bytes(flipped));
                out.put("last byte dropped", PlutusData.bytes(Arrays.copyOf(v, v.length - 1)));
                out.put("emptied", empty);
            }
            out.put("byte appended", PlutusData.bytes(Arrays.copyOf(v, v.length + 1)));
            out.put("as int", zero);
            out.put("as list", list(List.of()));
        } else {
            out.put("as int", zero);
        }
        return out;
    }

    private static void collect(PlutusData data, int[] path, List<int[]> out) {
        out.add(path);
        if (data instanceof PlutusData.ConstrData constr) {
            for (int i = 0; i < constr.fields().size(); i++) {
                collect(constr.fields().get(i), append(path, i), out);
            }
        } else if (data instanceof PlutusData.ListData l) {
            for (int i = 0; i < l.items().size(); i++) {
                collect(l.items().get(i), append(path, i), out);
            }
        }
    }

    private static int[] append(int[] path, int index) {
        int[] out = Arrays.copyOf(path, path.length + 1);
        out[path.length] = index;
        return out;
    }

    private static PlutusData at(PlutusData data, int[] path, int depth) {
        if (depth == path.length) {
            return data;
        }
        return at(data instanceof PlutusData.ConstrData c ? c.fields().get(path[depth])
                : items(data).get(path[depth]), path, depth + 1);
    }

    static PlutusData replace(PlutusData data, int[] path, int depth, PlutusData value) {
        if (depth == path.length) {
            return value;
        }
        if (data instanceof PlutusData.ConstrData constr) {
            List<PlutusData> f = new ArrayList<>(constr.fields());
            f.set(path[depth], replace(f.get(path[depth]), path, depth + 1, value));
            return new PlutusData.ConstrData(constr.tag(), f);
        }
        List<PlutusData> i = new ArrayList<>(items(data));
        i.set(path[depth], replace(i.get(path[depth]), path, depth + 1, value));
        return list(i);
    }

    /** One to three random changes of a bytes or integer leaf, or of a node's kind, constructor or fields. */
    static PlutusData mutate(PlutusData data, Random random) {
        PlutusData out = data;
        int changes = 1 + random.nextInt(3);
        for (int n = 0; n < changes; n++) {
            List<int[]> paths = new ArrayList<>();
            collect(out, new int[0], paths);
            int[] path = paths.get(random.nextInt(paths.size()));
            PlutusData node = at(out, path, 0);
            PlutusData changed;
            if (random.nextInt(4) == 0) {
                List<PlutusData> options = new ArrayList<>(variants(node).values());
                changed = options.get(random.nextInt(options.size()));
            } else if (node instanceof PlutusData.BytesData b) {
                byte[] value = b.value().clone();
                if (value.length == 0 || random.nextInt(8) == 0) {
                    changed = PlutusData.bytes(Arrays.copyOf(value, Math.max(0, value.length
                            + (random.nextBoolean() ? 1 : -1))));
                } else {
                    int index = random.nextInt(value.length);
                    value[index] = (byte) (random.nextBoolean() ? value[index] ^ (1 << random.nextInt(8))
                            : random.nextInt(16));
                    changed = PlutusData.bytes(value);
                }
            } else if (node instanceof PlutusData.IntData i) {
                changed = PlutusData.integer(i.value().add(BigInteger.valueOf(random.nextInt(5) - 2)));
            } else {
                List<PlutusData> options = new ArrayList<>(variants(node).values());
                changed = options.get(random.nextInt(options.size()));
            }
            out = replace(out, path, 0, changed);
        }
        return out;
    }

    // ------------------------------------------------------------------ honest transactions

    static List<ClaimFx> claims(int shard, int seed, int k) {
        List<ClaimFx> claims = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            claims.add(walletClaim(shard, seed + i));
        }
        return claims;
    }

    /** {@code Exit} of {@code claims} under a state with {@code status}, in NORMAL or EXIT, at {@code lowerBound}. */
    FullTx exitWith(List<ClaimFx> claims, int shard, byte[] status, boolean exitMode, long lowerBound) {
        StateTrie state = stateWith(64, status, VAULT_ID, ASSET, claims);
        PlutusData action = exitRedeemer(claims, claimProofs(state, claims), state.proof(STATUS_KEY), shard);
        FullTx tx = payout(claims, shard, action, exitMode ? exitMode(state.root()) : normalMode());
        if (!exitMode) {
            tx.reference(anchorInput(anchorDatum(2, state.root(), 77, MEMBERS, THRESHOLD, NOW)));
        }
        tx.range = range(lowerBound, lowerBound + 600_000);
        return tx;
    }

    FullTx sweepByOutpoint() {
        Value junk = assetValue(3_000_000L, 7).merge(token(UNREGISTERED.policyId(), UNREGISTERED.assetName(), 9));
        TxInInfo input = walletInput(W1, junk, PlutusData.integer(5));
        SweepCommitment commitment = SweepCommitment.of(ASSET, Hash32.of(W1.txId().hash()), 0,
                BigInteger.valueOf(3_000_000L), Map.of(ASSET, BigInteger.valueOf(7), UNREGISTERED,
                        BigInteger.valueOf(9)));
        return composition.sweep(input, commitment, false);
    }

    FullTx consolidate(int k) {
        List<TxInInfo> inputs = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            inputs.add(i % 2 == 0
                    ? walletInput(ref(0x61, i), assetValue(3_000_000L, 100 + i), continuation(List.of()))
                    : walletInput(ref(0x61, i), lovelace(FLOOR), floorDatum()));
        }
        return delegated(merge(inputs, consolidate(), exitMode(filled(0x47, 32))), consolidate());
    }

    /** The honest transaction of every action the composition test runs, by label. */
    Map<String, Supplier<FullTx>> honestTransactions() {
        Map<String, Supplier<FullTx>> txs = new LinkedHashMap<>();
        for (int k : new int[] {1, 4, 8, 16}) {
            txs.put("Settle k=" + k, () -> settle(claims(4, 10, k), 4));
        }
        for (int k : new int[] {1, 4, 8}) {
            txs.put("Exit k=" + k, () -> exit(claims(6, 30, k), 6, false));
        }
        txs.put("Exit(EXIT) k=2", () -> exit(claims(6, 40, 2), 6, true));
        for (int k : new int[] {1, 4, 8}) {
            txs.put("AcceptCosigned k=" + k, () -> acceptCosigned(composition.deposits(k)));
        }
        for (int k : new int[] {1, 4, 8}) {
            txs.put("AcceptProved k=" + k, () -> acceptProved(composition.deposits(k), false));
        }
        txs.put("AcceptProved(EXIT) k=2", () -> acceptProved(composition.deposits(2), true));
        txs.put("SweepExit(outpoint)", this::sweepByOutpoint);
        txs.put("SweepExit(DepositId)", composition::sweepOfDeposit);
        txs.put("RefundUncredited", () -> composition.refundUncredited(
                ProgrammableVaultCompositionTest.mixedDeposit(), 0));
        txs.put("RefundAbsent", () -> composition.refundAbsent(ProgrammableVaultCompositionTest.mixedDeposit()));
        txs.put("BalanceExit k=1", () -> composition.balanceExit(1, 0));
        txs.put("BalanceExit k=4", () -> composition.balanceExit(4, 0));
        for (int k : new int[] {2, 4, 8}) {
            txs.put("Consolidate k=" + k, () -> consolidate(k));
        }
        return txs;
    }

    @Test
    void everyHonestActionIsAcceptedByBothBackends() {
        honestTransactions().forEach((label, supplier) -> {
            FullTx tx = supplier.get();
            World world = world(tx);
            assertThat(world.twins()).as(label).isNotEmpty();
            for (Twin twin : world.twins()) {
                PlutusData context = context(world.info(), twin.purpose());
                EvalResult j = evaluate(twin.julc(), context);
                EvalResult a = evaluate(twin.aiken(), context);
                record("honest", label + " " + twin.name(), j, a);
                assertThat(j.isSuccess()).as("%s %s julc", label, twin.name()).isTrue();
                synchronized (UNITS) {
                    UNITS.add(String.format("UNITS|%s|%s|julc cpu=%d mem=%d|aiken cpu=%d mem=%d", label,
                            twin.name().replace("withdraw:", "").replace("spend:", ""),
                            j.budgetConsumed().cpuSteps(), j.budgetConsumed().memoryUnits(),
                            a.budgetConsumed().cpuSteps(), a.budgetConsumed().memoryUnits()));
                }
            }
        });
        assertNoDisagreement();
    }

    /** Alternative honest values: claim kinds, every domain status, maturity boundaries, signers, uint bounds. */
    @Test
    void alternativeHonestValuesGetTheSameVerdict() {
        Map<String, Supplier<FullTx>> txs = new LinkedHashMap<>();
        List<ClaimFx> mixed = List.of(walletClaim(6, 50), walletClaim(6, 51).withKind(2));
        long matured = NOW - 2 * DAY + EXIT_DELAY;
        for (DomainStatusV1.Status status : DomainStatusV1.Status.values()) {
            byte[] leaf = statusLeaf(status, 0, EXIT_DELAY);
            txs.put("Exit user+operational " + status, () -> exitWith(mixed, 6, leaf, false, NOW));
            txs.put("Exit user " + status + " just past the max age", () -> exitWith(
                    List.of(walletClaim(6, 52)), 6, leaf, false, matured + MAX_PAUSE));
            txs.put("Exit user " + status + " 1 ms before the max age", () -> exitWith(
                    List.of(walletClaim(6, 53)), 6, leaf, false, matured + MAX_PAUSE - 1));
        }
        byte[] active = statusLeaf(DomainStatusV1.Status.ACTIVE, 0, EXIT_DELAY);
        txs.put("Exit matured exactly", () -> exitWith(List.of(walletClaim(6, 54)), 6, active, false, matured));
        txs.put("Exit 1 ms before maturity", () -> exitWith(List.of(walletClaim(6, 55)), 6, active, false,
                matured - 1));
        txs.put("Exit delay at the floor", () -> exitWith(List.of(walletClaim(6, 56)), 6,
                statusLeaf(DomainStatusV1.Status.ACTIVE, 0, DELAY_FLOOR), false, NOW));
        txs.put("Exit delay below the floor", () -> exitWith(List.of(walletClaim(6, 57)), 6,
                statusLeaf(DomainStatusV1.Status.ACTIVE, 0, DELAY_FLOOR - 1), false, NOW));
        txs.put("Exit delay at the ceiling, immature", () -> exitWith(List.of(walletClaim(6, 58)), 6,
                statusLeaf(DomainStatusV1.Status.ACTIVE, 0, DELAY_CEILING), false, NOW));
        txs.put("Exit(EXIT) operational claim", () -> exitWith(mixed, 6, active, true, NOW));
        txs.put("Exit(EXIT) without a range", () -> {
            FullTx tx = exitWith(List.of(walletClaim(6, 59)), 6, active, true, NOW);
            tx.range = new org.julclang.ledger.Interval(new org.julclang.ledger.IntervalBound(
                    new org.julclang.ledger.IntervalBoundType.NegInf(), true), new org.julclang.ledger.IntervalBound(
                    new org.julclang.ledger.IntervalBoundType.PosInf(), true));
            return tx;
        });
        txs.put("Consolidate of one input", () -> consolidate(1));
        txs.put("Settle lovelace-only claim to a key address", () -> settle(List.of(walletClaim(4, 1)
                .withQuantity(0).withRecipient(GatewayFixtures.enterpriseKeyAddress(filled(0x98, 28)))), 4));
        txs.put("Settle asset-only claim", () -> settle(List.of(new ClaimFx(shardId(4, 60), 0, 5, 0,
                smartWallet(filled(0x81, 28)), 1, NOW - DAY)), 4));
        txs.put("Settle operational claim", () -> settle(List.of(walletClaim(4, 61).withKind(2)), 4));
        txs.put("Settle all members sign", () -> {
            FullTx tx = settle(claims(4, 62, 2), 4);
            tx.signers.add(keyHash(MEMBERS.get(2)));
            return tx;
        });
        txs.put("Settle below the threshold", () -> {
            FullTx tx = settle(claims(4, 63, 2), 4);
            tx.signers.removeLast();
            return tx;
        });
        txs.put("Settle anchor v1", () -> {
            FullTx tx = settle(claims(4, 64, 1), 4);
            tx.references.removeIf(r -> hasPolicy(r.resolved().value(), ANCHOR_POLICY));
            tx.reference(anchorInput(anchorDatum(1, filled(0x44, 32), 77, MEMBERS, THRESHOLD, NOW)));
            return tx;
        });
        txs.put("Settle claim of another shard", () -> settle(List.of(walletClaim(5, 65)), 4));
        txs.put("AcceptCosigned origin indexes 2^64-1", () -> acceptCosigned(List.of(walletInput(W1,
                assetValue(2_000_000L, 500), programmableDepositBig(BigInteger.TWO.pow(64).subtract(BigInteger.ONE))))));
        txs.put("AcceptCosigned lovelace-only deposit", () -> acceptCosigned(List.of(depositInput(W1,
                filled(0x48, 32), 1, lovelace(3_000_000L)))));
        txs.put("SweepExit(DepositId) lovelace-only", () -> {
            TxInInfo input = depositInput(W1, filled(0x49, 32), 2, lovelace(2_500_000L));
            return composition.sweep(input, new SweepCommitment(depositIdOf(input).bytes(), AssetValue.EMPTY,
                    BigInteger.valueOf(2_000_000L)), true);
        });
        txs.put("SweepExit(outpoint) lovelace-only, no datum", () -> {
            TxInInfo input = walletInput(W1, lovelace(2_700_000L), null);
            return composition.sweep(input, SweepCommitment.of(ASSET, Hash32.of(W1.txId().hash()), 0,
                    BigInteger.valueOf(2_700_000L), Map.of()), false);
        });
        txs.put("RefundAbsent of a lovelace-only deposit", () -> composition.refundAbsent(depositInput(W1,
                filled(0x4a, 32), 0, lovelace(2_000_000L))));
        for (Map.Entry<String, Supplier<FullTx>> entry : txs.entrySet()) {
            agree("alternative honest values", entry.getKey(), entry.getValue().get());
        }
        assertNoDisagreement();
    }

    /** A deposit-shaped datum whose origin index and output index are both {@code index}. */
    static PlutusData programmableDepositBig(BigInteger index) {
        return PlutusData.constr(0, PlutusData.bytes(filled(0xa1, 28)), PlutusData.bytes(smartWallet(REFUND_KEY)),
                PlutusData.bytes(REFUND_KEY), PlutusData.constr(0, PlutusData.bytes(filled(0x4b, 32)),
                        PlutusData.integer(index), PlutusData.integer(index)));
    }

    @Test
    void theCustodyCredentialsPublishOnlyARegistration() {
        PlutusData pool = PlutusData.constr(0, PlutusData.bytes(filled(0x99, 28)));
        for (byte[] hash : List.of(custodyHash(), exitDelegateHash())) {
            PlutusData credential = Cip113Blueprint.scriptCredential(hash);
            for (PlutusData cert : List.of(
                    PlutusData.constr(0, credential, PlutusData.constr(1)),
                    PlutusData.constr(0, credential, PlutusData.constr(0, PlutusData.integer(2_000_000))),
                    PlutusData.constr(1, credential, PlutusData.constr(1)),
                    PlutusData.constr(2, credential, pool),
                    PlutusData.constr(3, credential, pool, PlutusData.integer(2_000_000)),
                    PlutusData.constr(4, credential, pool, PlutusData.integer(2_000_000)))) {
                FullTx tx = frame(normalMode());
                tx.certificates.add(cert);
                agree("publish", "certificate " + cert, tx);
            }
        }
        FullTx both = frame(normalMode());
        both.certificates.add(PlutusData.constr(0, Cip113Blueprint.scriptCredential(custodyHash()),
                PlutusData.constr(1)));
        both.certificates.add(PlutusData.constr(0, Cip113Blueprint.scriptCredential(exitDelegateHash()),
                PlutusData.constr(1)));
        assertThat(agree("publish", "both registrations", both).values()).allMatch(v -> v);
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ the negatives

    /** Every transaction of {@code ProgrammableVaultNegativeTest}, by label. */
    Map<String, Supplier<FullTx>> negativeTransactions() {
        ProgrammableVaultCompositionTest honest = composition;
        Map<String, Supplier<FullTx>> txs = new LinkedHashMap<>();
        txs.put("a second vault input", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.spend(walletInput(W2, lovelace(FLOOR), floorDatum()), null);
            return tx;
        });
        txs.put("another owner's PLB input", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            byte[] owner = filled(0x91, 28);
            tx.spend(new TxInInfo(ref(0x59, 0), new TxOut(smartWalletOf(new Credential.PubKeyCredential(
                    new PubKeyHash(owner))), lovelace(3_000_000), new OutputDatum.NoOutputDatum(),
                    Optional.empty())), null);
            tx.signers.add(new PubKeyHash(owner));
            tx.output(payTo(smartWallet(owner), lovelace(3_000_000)));
            return tx;
        });
        txs.put("no vault input", () -> {
            FullTx tx = frame(normalMode());
            tx.withdrawScript(custodyHash(), 0, PlutusData.constr(3));
            tx.output(out(vaultWallet(), assetValue(2_000_000, 1), continuation(List.of())));
            return tx;
        });
        txs.put("a second output at the vault smart wallet", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.output(out(vaultWallet(), lovelace(2_000_000), floorDatum()));
            return tx;
        });
        txs.put("an output at h's payment address", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.output(out(GatewayFixtures.scriptAddress(custodyHash()), lovelace(2_000_000), null));
            return tx;
        });
        txs.put("an output at e's payment address", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.output(out(GatewayFixtures.scriptAddress(exitDelegateHash()), lovelace(2_000_000), null));
            return tx;
        });
        txs.put("a key payment address with h's stake part", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            Address keyWithCustodyStake = new Address(new Credential.PubKeyCredential(new PubKeyHash(
                    filled(0x92, 28))), Optional.of(new StakingCredential.StakingHash(new Credential.ScriptCredential(
                    new ScriptHash(custodyHash())))));
            tx.outputs.add(tx.outputs.size() - 1, out(keyWithCustodyStake, lovelace(2_000_000), null));
            return tx;
        });
        txs.put("a certificate in a vault transaction", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.certificates.add(PlutusData.constr(0, Cip113Blueprint.keyCredential(filled(0x93, 28)),
                    PlutusData.constr(1)));
            return tx;
        });
        txs.put("a mint in a vault transaction", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.mint = token(filled(0x94, 28), "x".getBytes(), 1);
            return tx;
        });
        txs.put("an EXIT-only action without the delegate", () -> {
            FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
            tx.removeWithdrawal(exitDelegateHash());
            return tx;
        });
        txs.put("a mirror naming another custody script", () -> {
            FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
            tx.withdrawScript(exitDelegateHash(), 0, PlutusData.constr(0, PlutusData.bytes(filled(0x95, 28)),
                    tx.redeemerOf(custodyHash())));
            return tx;
        });
        txs.put("a mirror naming another action", () -> {
            FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
            tx.withdrawScript(exitDelegateHash(), 0, PlutusData.constr(0, PlutusData.bytes(custodyHash()),
                    consolidate()));
            return tx;
        });
        txs.put("the delegate without the custody withdrawal", () -> {
            FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
            tx.removeWithdrawal(custodyHash());
            return tx;
        });
        txs.put("the delegate in NORMAL", () -> {
            FullTx tx = honest.refundUncredited(ProgrammableVaultCompositionTest.mixedDeposit(), 0);
            tx.references.removeIf(r -> hasPolicy(r.resolved().value(), EXIT_POLICY));
            tx.reference(exitModeInput(normalMode()));
            return tx;
        });
        txs.put("Follow is not an action", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.withdrawScript(custodyHash(), 0, follow());
            return tx;
        });
        TxInInfo moved = depositInput(ref(0x5f, 3), filled(0x40, 32), 1, assetValue(2_000_000L, 500));
        txs.put("AcceptProved of a re-created deposit", () -> acceptProved(List.of(moved), false));
        txs.put("AcceptCosigned of a re-created deposit", () -> acceptCosigned(List.of(moved)));
        txs.put("accept of a copied datum carrying a foreign token", () -> {
            Value mixed = assetValue(2_000_000L, 500).merge(token(ASSET_B.policyId(), ASSET_B.assetName(), 1));
            FullTx tx = acceptCosigned(List.of(depositInput(W1, filled(0x40, 32), 1, mixed)));
            tx.outputs.set(1, out(vaultWallet(), mixed, continuation(List.of())));
            return tx;
        });
        List<PlutusData> malformed = ProgrammableVaultNegativeTest.malformedDeposits();
        for (int i = 0; i < malformed.size(); i++) {
            PlutusData datum = malformed.get(i);
            String at = "malformed deposit " + i + " ";
            txs.put(at + "in AcceptCosigned", () -> acceptCosigned(List.of(walletInput(W1,
                    assetValue(2_000_000L, 500), datum))));
            txs.put(at + "in AcceptProved(EXIT)", () -> {
                StateTrie state = new StateTrie().fill("ap/account/", 32);
                PlutusData action = PlutusData.constr(4, PlutusData.list(state.proof(ascii("ap/account/1"))));
                return merge(List.of(walletInput(W1, assetValue(2_000_000L, 500), datum)), action,
                        exitMode(state.root()));
            });
            TxInInfo refundable = walletInput(W1, ProgrammableVaultCompositionTest.mixedDeposit().resolved().value(),
                    datum);
            txs.put(at + "in RefundUncredited", () -> {
                StateTrie state = new StateTrie().fill("ap/account/", 32);
                PlutusData uncredited = PlutusData.constr(6, state.proof(ascii("ap/account/1")));
                return delegated(honest.refund(refundable, uncredited, state.root()), uncredited);
            });
            txs.put(at + "in RefundAbsent", () -> {
                StateTrie state = new StateTrie().fill("ap/account/", 32);
                PlutusData absent = PlutusData.constr(5, state.absence(ascii("ap/deposit/x")));
                return delegated(honest.refund(refundable, absent, state.root()), absent);
            });
            txs.put(at + "swept by outpoint", () -> honest.sweep(walletInput(W1, assetValue(2_500_000L, 300),
                    datum), SweepCommitment.of(ASSET, Hash32.of(W1.txId().hash()), 0, BigInteger.valueOf(2_500_000L),
                    Map.of(ASSET, BigInteger.valueOf(300))), false));
        }
        TxInInfo deposit = depositInput(W1, filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        txs.put("sweep committing lovelace above the input's", () -> honest.sweep(deposit, new SweepCommitment(
                depositIdOf(deposit).bytes(), AssetValue.single(ASSET, BigInteger.valueOf(300)),
                BigInteger.valueOf(2_600_000L)), true));
        txs.put("sweep committing other native assets", () -> honest.sweep(deposit, new SweepCommitment(
                depositIdOf(deposit).bytes(), AssetValue.single(ASSET, BigInteger.valueOf(299)),
                BigInteger.valueOf(2_000_000L)), true));
        txs.put("deposit-shaped sweep keyed by its outpoint", () -> honest.sweep(deposit, SweepCommitment.of(ASSET,
                Hash32.of(W1.txId().hash()), 0, BigInteger.valueOf(2_500_000L), Map.of(ASSET,
                        BigInteger.valueOf(300))), false));
        TxInInfo movedDeposit = depositInput(ref(0x5e, 1), filled(0x45, 32), 2, assetValue(2_500_000L, 300));
        txs.put("sweep of a re-created deposit", () -> honest.sweep(movedDeposit, new SweepCommitment(
                depositIdOf(movedDeposit).bytes(), AssetValue.single(ASSET, BigInteger.valueOf(300)),
                BigInteger.valueOf(2_000_000L)), true));
        TxInInfo credited = ProgrammableVaultCompositionTest.mixedDeposit();
        txs.put("RefundUncredited of a credited origin", () -> {
            StateTrie state = new StateTrie().fill("ap/account/", 32).put(depositKey(credited),
                    creditedRecord(credited).encode());
            PlutusData uncredited = PlutusData.constr(6, state.proof(depositKey(credited)));
            return delegated(honest.refund(credited, uncredited, state.root()), uncredited);
        });
        txs.put("RefundAbsent of a present origin", () -> {
            StateTrie state = new StateTrie().fill("ap/account/", 32).put(depositKey(credited),
                    creditedRecord(credited).encode());
            PlutusData absent = PlutusData.constr(5, state.absenceOfPresentKey(depositKey(credited), 3));
            return delegated(honest.refund(credited, absent, state.root()), absent);
        });
        txs.put("a refund paid to another wallet", () -> {
            StateTrie state = new StateTrie().fill("ap/account/", 32).put(depositKey(credited),
                    record(credited, DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS)
                            .encode());
            PlutusData refund = PlutusData.constr(6, state.proof(depositKey(credited)));
            FullTx stolen = delegated(honest.refund(credited, refund, state.root()), refund);
            stolen.outputs.set(0, out(ledger(smartWallet(filled(0x96, 28))), credited.resolved().value(), null));
            return stolen;
        });
        byte[] key = filled(0x97, 28);
        byte[] scriptOwned = smartWallet(key);
        scriptOwned[0] = 0x30;
        byte[] pointer = GatewayFixtures.concat(GatewayFixtures.concat(new byte[] {0x50}, PLB), new byte[] {1, 2, 3});
        byte[] enterprise = GatewayFixtures.concat(new byte[] {0x70}, PLB);
        List<byte[]> recipients = List.of(scriptOwned, pointer, enterprise, GatewayFixtures.enterpriseKeyAddress(key),
                GatewayFixtures.baseKeyAddress(key, key));
        for (int i = 0; i < recipients.size(); i++) {
            byte[] recipient = recipients.get(i);
            txs.put("a token payout to recipient " + i, () -> settle(List.of(walletClaim(4, 1)
                    .withRecipient(recipient)), 4));
        }
        txs.put("the shard variant without the custody withdrawal", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.removeWithdrawal(custodyHash());
            return tx;
        });
        for (byte[] hash : List.of(custodyHash(), exitDelegateHash())) {
            txs.put("spending " + (Arrays.equals(hash, custodyHash()) ? "h" : "e") + "'s own address", () -> {
                FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
                tx.spend(new TxInInfo(ref(0x58, 0), new TxOut(GatewayFixtures.scriptAddress(hash),
                        lovelace(5_000_000), new OutputDatum.OutputDatumInline(PlutusData.constr(0)),
                        Optional.empty())), PlutusData.constr(0));
                return tx;
            });
        }
        txs.put("an issuer freeze of the vault", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            Program frozen = hook(1, custodyHash());
            tx.references.removeIf(r -> nodeKeys(r).isPresent());
            registry(frozen, hookB()).forEach(tx::reference);
            tx.script(Cip113Blueprint.hash(frozen), frozen);
            tx.removeWithdrawal(Cip113Blueprint.hash(hookA()));
            tx.withdrawScript(Cip113Blueprint.hash(frozen), 0, PlutusData.constr(0));
            return tx;
        });
        txs.put("positive reward balances on Settle", () -> {
            FullTx tx = settle(List.of(walletClaim(4, 1)), 4);
            tx.withdrawScript(custodyHash(), 3_000_000, tx.redeemerOf(custodyHash()));
            tx.withdrawScript(CIP.dispatcherHash(), 1_000_000, transferAct());
            return tx;
        });
        txs.put("a positive delegate balance on RefundUncredited", () -> honest.refundUncredited(
                ProgrammableVaultCompositionTest.mixedDeposit(), 2_000_000));
        txs.put("a positive delegate balance on BalanceExit", () -> honest.balanceExit(1, 2_500_000));
        txs.put("a positive custody balance on SweepExit", () -> {
            FullTx tx = honest.sweepOfDeposit();
            tx.withdrawScript(custodyHash(), 4_000_000, tx.redeemerOf(custodyHash()));
            return tx;
        });
        return txs;
    }

    @Test
    void everyNegativeGetsTheSameVerdict() {
        negativeTransactions().forEach((label, tx) -> agree("negatives", label, tx.get()));
        // V16: 29 ids are accepted and 30 are not, with the Settle cap raised to reach the id cap.
        int saved = settleCap;
        settleCap = 64;
        try {
            assertThat(agree("negatives", "29 ids", settle(claims(4, 100, 29), 4)).get("withdraw:h")).isTrue();
            assertThat(agree("negatives", "30 ids", settle(claims(4, 100, 30), 4)).get("withdraw:h")).isFalse();
        } finally {
            settleCap = saved;
        }
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ actions

    private static final Predicate<Twin> CUSTODY = twin -> twin.name().startsWith("withdraw:");

    static PlutusData custodyPurpose() {
        return withdrawPurpose(custodyHash());
    }

    static PlutusData delegatePurpose() {
        return withdrawPurpose(exitDelegateHash());
    }

    static PlutusData actionOf(World world) {
        return redeemerOf(world.info(), custodyPurpose());
    }

    /** The world with {@code h}'s action replaced, and {@code e}'s mirror with it when {@code e} runs. */
    static World withAction(World world, PlutusData action) {
        PlutusData info = withRedeemer(world.info(), custodyPurpose(), action);
        if (redeemerOf(info, delegatePurpose()) != null) {
            info = withRedeemer(info, delegatePurpose(), PlutusData.constr(0, PlutusData.bytes(custodyHash()), action));
        }
        return world.with(info);
    }

    /** The world with field {@code index} of {@code h}'s action (a proof) replaced. */
    static World withActionField(World world, int[] path, PlutusData value) {
        return withAction(world, replace(actionOf(world), path, 0, value));
    }

    // ------------------------------------------------------------------ the state-proof verifier

    /** A deposit-shaped vault input whose origin is the {@code seed}-th test transaction. */
    static TxInInfo depositOf(int seed) {
        return depositInput(W1, GatewayFixtures.blake2b256(ascii("origin-" + seed)), seed % 3,
                assetValue(2_000_000L, 500));
    }

    /** {@code AcceptProved} in EXIT of one deposit whose CREDITED record sits in a trie of {@code size} fillers. */
    FullTx acceptProvedAt(TxInInfo deposit, int size) {
        StateTrie state = new StateTrie().fill("ap/account/", size).put(depositKey(deposit),
                creditedRecord(deposit).encode());
        PlutusData action = PlutusData.constr(4, PlutusData.list(state.proof(depositKey(deposit))));
        return merge(List.of(deposit), action, exitMode(state.root()));
    }

    /** {@code RefundUncredited} of one deposit whose UNCREDITED record sits in a trie of {@code size} fillers. */
    FullTx refundUncreditedAt(TxInInfo deposit, int size) {
        StateTrie state = new StateTrie().fill("ap/account/", size).put(depositKey(deposit),
                record(deposit, DepositRecordV1.Status.UNCREDITED, DepositAdmission.Reason.MIXED_ASSETS).encode());
        PlutusData action = PlutusData.constr(6, state.proof(depositKey(deposit)));
        return delegated(composition.refund(deposit, action, state.root()), action);
    }

    /** {@code RefundAbsent} of one deposit under {@code state}. */
    FullTx refundAbsentUnder(TxInInfo deposit, StateTrie state) {
        PlutusData action = PlutusData.constr(5, state.absence(depositKey(deposit)));
        return delegated(composition.refund(deposit, action, state.root()), action);
    }

    private static String terminal(PlutusData absence) {
        List<PlutusData> f = fields(absence);
        long cursor = ((PlutusData.IntData) f.get(4)).value().longValueExact();
        String kind = ((PlutusData.BytesData) f.get(2)).value().length == 0 ? "empty slot"
                : ((PlutusData.BytesData) f.get(5)).value().length == 32 ? "leaf" : "branch";
        return kind + "@" + (cursor % 2 == 0 ? "even" : "odd") + "-cursor";
    }

    /** A trie whose root is a branch with a one-nibble prefix that {@code query}'s path leaves at nibble 0. */
    static StateTrie rootBranchTrie(byte[] query) {
        int first = (GatewayFixtures.blake2b256(query)[0] & 0xff) >> 4;
        int shared = -1;
        int second = -1;
        StateTrie state = new StateTrie();
        int added = 0;
        for (int i = 0; added < 3; i++) {
            byte[] key = ascii("ap/account/" + i);
            byte[] hash = GatewayFixtures.blake2b256(key);
            int a = (hash[0] & 0xff) >> 4;
            int b = hash[0] & 0x0f;
            if (a == first || (shared >= 0 && a != shared) || b == second) {
                continue;
            }
            shared = a;
            second = added == 0 ? b : second;
            state.put(key, hash);
            added++;
        }
        return state;
    }

    private static final int[] TRIE_SIZES = {0, 1, 2, 3, 4, 5, 7, 8, 12, 16, 24, 32, 48, 64, 96, 128, 200, 300, 500,
            800, 1_200, 2_000, 4_000};

    @Test
    void honestInclusionProofsAtEveryTrieSizeGetTheSameVerdict() {
        int seed = 0;
        for (int size : TRIE_SIZES) {
            for (int round = 0; round < 2; round++) {
                TxInInfo deposit = depositOf(seed++);
                assertThat(agree("inclusion proofs", "AcceptProved size " + size, world(acceptProvedAt(deposit,
                        size)), CUSTODY).get("withdraw:h")).as("AcceptProved size %d", size).isTrue();
                assertThat(agree("inclusion proofs", "RefundUncredited size " + size, world(refundUncreditedAt(
                        deposit, size)), CUSTODY).get("withdraw:e")).as("RefundUncredited size %d", size).isTrue();
            }
        }
        assertNoDisagreement();
    }

    @Test
    void absenceProofsOfEveryTerminalKindGetTheSameVerdict() {
        Map<String, World> cases = new LinkedHashMap<>();
        int seed = 1_000;
        for (int size : TRIE_SIZES) {
            if (size == 0) {
                continue;
            }
            StateTrie state = new StateTrie().fill("ap/account/", size);
            for (int round = 0; round < 6; round++) {
                TxInInfo deposit = depositOf(seed++);
                World world = world(refundAbsentUnder(deposit, state));
                String kind = terminal(fields(actionOf(world)).getFirst());
                TERMINALS.merge(kind, 1, Integer::sum);
                cases.put("size " + size + " round " + round + " " + kind, world);
            }
        }
        for (int i = 0; i < 6; i++) {
            TxInInfo deposit = depositOf(seed++);
            byte[] query = depositKey(deposit);
            for (StateTrie state : List.of(branchPrefixTrie(query), rootBranchTrie(query))) {
                World world = world(refundAbsentUnder(deposit, state));
                String kind = terminal(fields(actionOf(world)).getFirst());
                TERMINALS.merge(kind, 1, Integer::sum);
                cases.put("branch trie " + i + " " + kind, world);
            }
        }
        cases.forEach((label, world) -> assertThat(agree("absence proofs", label, world, CUSTODY).values())
                .as(label).allMatch(v -> v));
        // The absence proof of a PRESENT key, its own leaf named through a conflicting key hash that differs from
        // the key's path above the leaf, at every such nibble: refused by both.
        for (int size : new int[] {1, 2, 16, 40, 300, 4_000}) {
            TxInInfo deposit = depositOf(seed++);
            StateTrie state = new StateTrie().fill("ap/account/", size).put(depositKey(deposit),
                    creditedRecord(deposit).encode());
            long cursor = leafCursor(state.inclusion(depositKey(deposit)).leafSuffix());
            for (int flip = 0; flip < cursor; flip++) {
                PlutusData action = PlutusData.constr(5, state.absenceOfPresentKey(depositKey(deposit), flip));
                World world = world(delegated(composition.refund(deposit, action, state.root()), action));
                assertThat(agree("absence proofs", "present key, size " + size + " flip " + flip, world, CUSTODY)
                        .get("withdraw:e")).as("present key size %d flip %d", size, flip).isFalse();
            }
        }
        for (String kind : List.of("empty slot@even-cursor", "empty slot@odd-cursor", "leaf@even-cursor",
                "leaf@odd-cursor", "branch@even-cursor", "branch@odd-cursor")) {
            assertThat(TERMINALS).as("honest absence proofs cover a terminal %s", kind).containsKey(kind);
        }
        assertNoDisagreement();
    }

    /** Proof-specific changes of an inclusion or absence proof, beyond the generic node changes. */
    static Map<String, PlutusData> proofChanges(PlutusData proof, boolean absence) {
        Map<String, PlutusData> out = new LinkedHashMap<>(nodeChanges(proof));
        List<PlutusData> f = fields(proof);
        List<PlutusData> folds = items(f.get(3));
        for (int i = 0; i < folds.size(); i++) {
            PlutusData fold = folds.get(i);
            List<PlutusData> ff = fields(fold);
            String at = "fold " + i + " ";
            byte[] prefix = ((PlutusData.BytesData) ff.get(1)).value();
            long nibble = ((PlutusData.IntData) ff.get(2)).value().longValueExact();
            for (long other : new long[] {-1, 16, (nibble + 1) % 16, (nibble + 9) % 16}) {
                out.put(at + "nibble " + other, withFold(proof, i, withField(fold, 2, PlutusData.integer(other))));
            }
            for (int extra : new int[] {0, 5, 15, 16}) {
                byte[] longer = Arrays.copyOf(prefix, prefix.length + 1);
                longer[prefix.length] = (byte) extra;
                out.put(at + "prefix + " + extra, withFold(proof, i, withField(fold, 1, PlutusData.bytes(longer))));
            }
            if (prefix.length > 0) {
                byte[] raised = prefix.clone();
                raised[0] = (byte) (raised[0] + 16);
                out.put(at + "prefix raw byte", withFold(proof, i, withField(fold, 1, PlutusData.bytes(raised))));
            }
            out.put(at + "value hash set", withFold(proof, i, withField(fold, 7, PlutusData.bytes(filled(0x3c, 32)))));
            out.put(at + "value hash 31 bytes", withFold(proof, i, withField(fold, 7,
                    PlutusData.bytes(filled(0x3c, 31)))));
            out.put(at + "neighbor 1 31 bytes", withFold(proof, i, withField(fold, 3,
                    PlutusData.bytes(filled(0x3d, 31)))));
            out.put(at + "neighbors 2 and 3 swapped", withFold(proof, i, withField(withField(fold, 4, ff.get(5)), 5,
                    ff.get(4))));
            if (i + 1 < folds.size()) {
                List<PlutusData> swapped = new ArrayList<>(folds);
                Collections.swap(swapped, i, i + 1);
                out.put(at + "swapped with the next", withField(proof, 3, list(swapped)));
            }
        }
        List<PlutusData> reversed = new ArrayList<>(folds);
        Collections.reverse(reversed);
        out.put("folds reversed", withField(proof, 3, list(reversed)));
        out.put("no folds", withField(proof, 3, list(List.of())));
        byte[] suffix = ((PlutusData.BytesData) f.get(2)).value();
        if (suffix.length > 0) {
            for (int marker : new int[] {0x00, 0xff, 0x01}) {
                byte[] remarked = suffix.clone();
                remarked[0] = (byte) marker;
                out.put("suffix marker " + marker, withField(proof, 2, PlutusData.bytes(remarked)));
            }
        }
        out.put("another key", withField(proof, 0, PlutusData.bytes(ascii("ap/account/2"))));
        if (absence) {
            long cursor = ((PlutusData.IntData) f.get(4)).value().longValueExact();
            for (long delta : new long[] {-2, -1, 1, 2, 64}) {
                out.put("terminal cursor " + delta, withField(proof, 4, PlutusData.integer(cursor + delta)));
            }
            out.put("as an empty slot", withField(withField(withField(proof, 2, PlutusData.bytes(new byte[0])), 5,
                    PlutusData.bytes(new byte[0])), 6, PlutusData.bytes(new byte[0])));
            out.put("conflicting key the query's path", withField(proof, 5,
                    PlutusData.bytes(GatewayFixtures.blake2b256(((PlutusData.BytesData) f.getFirst()).value()))));
            out.put("conflicting value hash zero", withField(proof, 6, PlutusData.bytes(new byte[32])));
            out.put("value field an integer", withField(proof, 1, PlutusData.integer(7)));
            out.put("value field a list", withField(proof, 1, list(List.of(PlutusData.integer(1)))));
        } else {
            out.put("value changed", withField(proof, 1, PlutusData.bytes(ascii("other value"))));
        }
        return out;
    }

    private static PlutusData withFold(PlutusData proof, int index, PlutusData fold) {
        List<PlutusData> folds = new ArrayList<>(items(fields(proof).get(3)));
        folds.set(index, fold);
        return withField(proof, 3, list(folds));
    }

    /** The proofs of the honest actions, as paths into each action, with the world they sit in. */
    Map<String, Object[]> proofSites() {
        Map<String, Object[]> sites = new LinkedHashMap<>();
        for (int size : new int[] {1, 40, 300}) {
            sites.put("AcceptProved size " + size, new Object[] {world(acceptProvedAt(depositOf(2_000 + size), size)),
                    new int[] {0, 0}, false});
            sites.put("RefundUncredited size " + size, new Object[] {world(refundUncreditedAt(depositOf(3_000 + size),
                    size)), new int[] {0}, false});
        }
        Map<String, Boolean> seen = new TreeMap<>();
        int seed = 4_000;
        for (int size : new int[] {3, 40, 300, 2_000}) {
            StateTrie state = new StateTrie().fill("ap/account/", size);
            for (int round = 0; round < 12 && seen.size() < 6; round++) {
                World world = world(refundAbsentUnder(depositOf(seed++), state));
                String kind = terminal(fields(actionOf(world)).getFirst());
                if (seen.putIfAbsent(kind, true) == null) {
                    sites.put("RefundAbsent " + kind, new Object[] {world, new int[] {0}, true});
                }
            }
        }
        TxInInfo deposit = depositOf(seed++);
        byte[] query = depositKey(deposit);
        sites.put("RefundAbsent branch@odd", new Object[] {world(refundAbsentUnder(deposit, branchPrefixTrie(query))),
                new int[] {0}, true});
        sites.put("RefundAbsent branch@even", new Object[] {world(refundAbsentUnder(deposit, rootBranchTrie(query))),
                new int[] {0}, true});
        World exit = world(exit(claims(6, 70, 1), 6, false));
        sites.put("Exit claim proof", new Object[] {exit, new int[] {0, 0, 1}, false});
        sites.put("Exit status proof", new Object[] {exit, new int[] {1}, false});
        World sweep = world(composition.sweepOfDeposit());
        sites.put("SweepExit claim proof", new Object[] {sweep, new int[] {5}, false});
        sites.put("SweepExit status proof", new Object[] {sweep, new int[] {6}, false});
        sites.put("BalanceExit exit proof", new Object[] {world(composition.balanceExit(1, 0)), new int[] {5}, false});
        return sites;
    }

    @Test
    void systematicProofChangesGetTheSameVerdict() {
        proofSites().forEach((site, value) -> {
            World world = (World) value[0];
            int[] path = (int[]) value[1];
            PlutusData proof = at(actionOf(world), path, 0);
            Map<String, World> cases = new LinkedHashMap<>();
            proofChanges(proof, (Boolean) value[2]).forEach((label, changed) -> cases.put(site + " " + label,
                    withActionField(world, path, changed)));
            agreeAll("proof changes", cases, CUSTODY);
        });
        assertNoDisagreement();
    }

    @Test
    void randomProofChangesGetTheSameVerdict() {
        Random random = new Random(0x061);
        List<Object[]> sites = new ArrayList<>(proofSites().values());
        Map<String, World> cases = new LinkedHashMap<>();
        for (int n = 0; n < 1_200; n++) {
            Object[] site = sites.get(random.nextInt(sites.size()));
            World world = (World) site[0];
            int[] path = (int[]) site[1];
            cases.put("case " + n, withActionField(world, path, mutate(at(actionOf(world), path, 0), random)));
        }
        agreeAll("random proof changes", cases, CUSTODY);
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ redeemers

    /** The honest worlds the change suites start from. */
    Map<String, World> baseWorlds() {
        Map<String, World> worlds = new LinkedHashMap<>();
        worlds.put("Settle k=1", world(settle(claims(4, 10, 1), 4)));
        worlds.put("Settle k=3", world(settle(claims(4, 20, 3), 4)));
        worlds.put("Exit k=1", world(exit(claims(6, 30, 1), 6, false)));
        worlds.put("Exit(EXIT) k=2", world(exit(claims(6, 40, 2), 6, true)));
        worlds.put("AcceptCosigned k=2", world(acceptCosigned(composition.deposits(2))));
        worlds.put("AcceptProved k=1", world(acceptProved(composition.deposits(1), false)));
        worlds.put("AcceptProved(EXIT) k=2", world(acceptProved(composition.deposits(2), true)));
        worlds.put("SweepExit(outpoint)", world(sweepByOutpoint()));
        worlds.put("SweepExit(DepositId)", world(composition.sweepOfDeposit()));
        worlds.put("RefundUncredited", world(composition.refundUncredited(
                ProgrammableVaultCompositionTest.mixedDeposit(), 0)));
        worlds.put("RefundAbsent", world(composition.refundAbsent(ProgrammableVaultCompositionTest.mixedDeposit())));
        worlds.put("BalanceExit k=1", world(composition.balanceExit(1, 0)));
        worlds.put("BalanceExit k=2", world(composition.balanceExit(2, 0)));
        worlds.put("Consolidate k=2", world(consolidate(2)));
        return worlds;
    }

    @Test
    void systematicRedeemerChangesGetTheSameVerdict() {
        baseWorlds().forEach((name, world) -> {
            Map<String, World> cases = new LinkedHashMap<>();
            PlutusData action = actionOf(world);
            // h's action (and e's mirror with it), every node.
            nodeChanges(action).forEach((label, changed) -> cases.put(name + " action " + label,
                    withAction(world, changed)));
            PlutusData mirror = redeemerOf(world.info(), delegatePurpose());
            if (mirror != null) {
                // e's redeemer alone, and h's alone: the mirror no longer matches.
                nodeChanges(mirror).forEach((label, changed) -> cases.put(name + " mirror " + label,
                        world.with(withRedeemer(world.info(), delegatePurpose(), changed))));
                int tag = ((PlutusData.ConstrData) action).tag();
                cases.put(name + " h's action alone retagged", world.with(withRedeemer(world.info(),
                        custodyPurpose(), new PlutusData.ConstrData(tag == 8 ? 5 : 8, fields(action)))));
            }
            agreeAll("redeemer changes", cases, CUSTODY);
            // The shard's redeemer: h reads the inserted ids from it.
            for (Twin twin : world.twins()) {
                if (twin.name().startsWith("spend:shard")) {
                    PlutusData shardRedeemer = redeemerOf(world.info(), twin.purpose());
                    Map<String, World> shardCases = new LinkedHashMap<>();
                    nodeChanges(shardRedeemer).forEach((label, changed) -> shardCases.put(name + " shard " + label,
                            world.with(withRedeemer(world.info(), twin.purpose(), changed))));
                    agreeAll("shard redeemer changes", shardCases, ALL);
                }
            }
        });
        assertNoDisagreement();
    }

    @Test
    void randomRedeemerChangesGetTheSameVerdict() {
        Random random = new Random(0x0611);
        List<World> worlds = new ArrayList<>(baseWorlds().values());
        Map<String, World> cases = new LinkedHashMap<>();
        for (int n = 0; n < 1_500; n++) {
            World world = worlds.get(random.nextInt(worlds.size()));
            cases.put("case " + n, withAction(world, mutate(actionOf(world), random)));
        }
        agreeAll("random redeemer changes", cases, CUSTODY);
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ datums and the transaction

    private static final int INPUTS = 0;
    private static final int REFERENCES = 1;
    private static final int OUTPUTS = 2;

    /** Each inline datum of the inputs, reference inputs and outputs the custody scripts read. */
    static Map<String, PlutusData> datumChanges(PlutusData info) {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        for (int list : new int[] {INPUTS, REFERENCES, OUTPUTS}) {
            List<PlutusData> entries = items(fields(info).get(list));
            for (int i = 0; i < entries.size(); i++) {
                PlutusData txOut = list == OUTPUTS ? entries.get(i) : fields(entries.get(i)).get(1);
                if (list == REFERENCES && !readByCustody(txOut)) {
                    continue;
                }
                String at = (list == INPUTS ? "input " : list == REFERENCES ? "reference " : "output ") + i + " ";
                PlutusData.ConstrData datum = (PlutusData.ConstrData) fields(txOut).get(2);
                Map<String, PlutusData> kinds = new LinkedHashMap<>();
                kinds.put("no datum", PlutusData.constr(0));
                kinds.put("datum hash", PlutusData.constr(1, PlutusData.bytes(filled(0x44, 32))));
                if (datum.tag() == 2) {
                    nodeChanges(datum.fields().getFirst()).forEach((label, changed) -> kinds.put("datum " + label,
                            PlutusData.constr(2, changed)));
                } else {
                    kinds.put("inline unit", PlutusData.constr(2, PlutusData.constr(0)));
                }
                for (Map.Entry<String, PlutusData> kind : kinds.entrySet()) {
                    out.put(at + kind.getKey(), withTxOut(info, list, i, withField(txOut, 2, kind.getValue())));
                }
            }
        }
        return out;
    }

    /** A reference input the custody scripts read: the anchor or the exit-mode thread. */
    private static boolean readByCustody(PlutusData txOut) {
        PlutusData value = fields(txOut).get(1);
        return lookupBytes(value, ANCHOR_POLICY) || lookupBytes(value, EXIT_POLICY);
    }

    private static boolean lookupBytes(PlutusData value, byte[] policy) {
        return lookup(value, PlutusData.bytes(policy)) != null;
    }

    /** {@code info} with the TxOut of entry {@code index} of {@code list} replaced. */
    static PlutusData withTxOut(PlutusData info, int list, int index, PlutusData txOut) {
        List<PlutusData> entries = new ArrayList<>(items(fields(info).get(list)));
        entries.set(index, list == OUTPUTS ? txOut : withField(entries.get(index), 1, txOut));
        return info(info, list, list(entries));
    }

    static PlutusData withEntries(PlutusData info, int list, List<PlutusData> entries) {
        List<PlutusData> sorted = new ArrayList<>(entries);
        if (list != OUTPUTS) {
            sorted.sort((a, b) -> {
                List<PlutusData> ra = fields(fields(a).getFirst());
                List<PlutusData> rb = fields(fields(b).getFirst());
                int c = Arrays.compareUnsigned(((PlutusData.BytesData) ra.getFirst()).value(),
                        ((PlutusData.BytesData) rb.getFirst()).value());
                return c != 0 ? c : ((PlutusData.IntData) ra.get(1)).value()
                        .compareTo(((PlutusData.IntData) rb.get(1)).value());
            });
        }
        return info(info, list, list(sorted));
    }

    /** A value as policy to name to quantity, by hex, in ledger order. */
    static TreeMap<String, TreeMap<String, BigInteger>> valueMap(PlutusData value) {
        TreeMap<String, TreeMap<String, BigInteger>> out = new TreeMap<>();
        for (PlutusData.Pair policy : ((PlutusData.MapData) value).entries()) {
            TreeMap<String, BigInteger> names = out.computeIfAbsent(hex(((PlutusData.BytesData) policy.key())
                    .value()), ignored -> new TreeMap<>());
            for (PlutusData.Pair name : ((PlutusData.MapData) policy.value()).entries()) {
                names.put(hex(((PlutusData.BytesData) name.key()).value()), ((PlutusData.IntData) name.value())
                        .value());
            }
        }
        return out;
    }

    static PlutusData valueData(TreeMap<String, TreeMap<String, BigInteger>> value) {
        List<PlutusData.Pair> policies = new ArrayList<>();
        value.forEach((policy, names) -> {
            List<PlutusData.Pair> tokens = new ArrayList<>();
            names.forEach((name, quantity) -> {
                if (quantity.signum() != 0) {
                    tokens.add(new PlutusData.Pair(PlutusData.bytes(java.util.HexFormat.of().parseHex(name)),
                            PlutusData.integer(quantity)));
                }
            });
            if (!tokens.isEmpty()) {
                policies.add(new PlutusData.Pair(PlutusData.bytes(java.util.HexFormat.of().parseHex(policy)),
                        new PlutusData.MapData(tokens)));
            }
        });
        return new PlutusData.MapData(policies);
    }

    static PlutusData adjusted(PlutusData value, byte[] policy, byte[] name, long delta) {
        TreeMap<String, TreeMap<String, BigInteger>> map = valueMap(value);
        map.computeIfAbsent(hex(policy), ignored -> new TreeMap<>()).merge(hex(name), BigInteger.valueOf(delta),
                BigInteger::add);
        return valueData(map);
    }

    /** Changes of one TxOut's value, address, reference script; null entries skipped. */
    Map<String, PlutusData> txOutChanges(PlutusData txOut) {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        PlutusData value = fields(txOut).get(1);
        TreeMap<String, TreeMap<String, BigInteger>> map = valueMap(value);
        out.put("lovelace + 1", withField(txOut, 1, adjusted(value, new byte[0], new byte[0], 1)));
        if (map.get("").get("").compareTo(BigInteger.ONE) > 0) {
            out.put("lovelace - 1", withField(txOut, 1, adjusted(value, new byte[0], new byte[0], -1)));
        }
        out.put("asset + 1", withField(txOut, 1, adjusted(value, ASSET.policyId(), ASSET.assetName(), 1)));
        if (map.containsKey(hex(ASSET.policyId()))) {
            out.put("asset - 1", withField(txOut, 1, adjusted(value, ASSET.policyId(), ASSET.assetName(), -1)));
            TreeMap<String, TreeMap<String, BigInteger>> without = valueMap(value);
            without.remove(hex(ASSET.policyId()));
            out.put("asset removed", withField(txOut, 1, valueData(without)));
            out.put("asset under another name", withField(txOut, 1, adjusted(value, ASSET.policyId(),
                    "USDX".getBytes(), 1)));
        }
        out.put("a second registered token", withField(txOut, 1, adjusted(value, ASSET_B.policyId(),
                ASSET_B.assetName(), 3)));
        out.put("an unregistered token", withField(txOut, 1, adjusted(value, UNREGISTERED.policyId(),
                UNREGISTERED.assetName(), 1)));
        out.put("a shard thread token", withField(txOut, 1, adjusted(value, SHARD_POLICY, new byte[] {4}, 1)));
        PlutusData address = fields(txOut).getFirst();
        for (Map.Entry<String, PlutusData> alternative : alternativeAddresses().entrySet()) {
            if (!alternative.getValue().equals(address)) {
                out.put("address " + alternative.getKey(), withField(txOut, 0, alternative.getValue()));
            }
        }
        out.put("a reference script", withField(txOut, 3, PlutusData.constr(0, PlutusData.bytes(filled(0x5b, 28)))));
        return out;
    }

    static Map<String, PlutusData> alternativeAddresses() {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        byte[] key = filled(0x8c, 28);
        out.put("vault smart wallet", vaultWallet().toPlutusData());
        out.put("key smart wallet", ledger(smartWallet(key)).toPlutusData());
        out.put("script-owned smart wallet", smartWalletOf(new Credential.ScriptCredential(new ScriptHash(
                filled(0x8e, 28)))).toPlutusData());
        out.put("h enterprise", GatewayFixtures.scriptAddress(custodyHash()).toPlutusData());
        out.put("e enterprise", GatewayFixtures.scriptAddress(exitDelegateHash()).toPlutusData());
        out.put("h staked", GatewayFixtures.scriptAddressWithStake(custodyHash(), key).toPlutusData());
        out.put("key enterprise", ledger(GatewayFixtures.enterpriseKeyAddress(key)).toPlutusData());
        out.put("key base", ledger(GatewayFixtures.baseKeyAddress(key, key)).toPlutusData());
        out.put("key pointer", ledger(GatewayFixtures.concat(GatewayFixtures.concat(new byte[] {0x40}, key),
                new byte[] {(byte) 0x81, 0x01, 0x02, 0x03})).toPlutusData());
        out.put("key with h's stake", new Address(new Credential.PubKeyCredential(new PubKeyHash(key)),
                Optional.of(new StakingCredential.StakingHash(new Credential.ScriptCredential(new ScriptHash(
                        custodyHash()))))).toPlutusData());
        out.put("shard", GatewayFixtures.scriptAddress(programmableShardHash()).toPlutusData());
        return out;
    }

    static PlutusData input(int fill, int index, TxOut out) {
        return canonicalInput(new TxInInfo(ref(fill, index), out).toPlutusData());
    }

    /** Changes of the transaction: inputs, outputs, reference inputs, signers, range, withdrawals, mint, certs. */
    Map<String, PlutusData> transactionChanges(PlutusData info) {
        Map<String, PlutusData> out = new LinkedHashMap<>();
        List<PlutusData> f = fields(info);
        // Outputs.
        List<PlutusData> outputs = items(f.get(OUTPUTS));
        for (int i = 0; i < outputs.size(); i++) {
            List<PlutusData> dropped = new ArrayList<>(outputs);
            dropped.remove(i);
            out.put("output " + i + " dropped", withEntries(info, OUTPUTS, dropped));
            List<PlutusData> doubled = new ArrayList<>(outputs);
            doubled.add(i, outputs.get(i));
            out.put("output " + i + " doubled", withEntries(info, OUTPUTS, doubled));
            if (i + 1 < outputs.size()) {
                List<PlutusData> swapped = new ArrayList<>(outputs);
                Collections.swap(swapped, i, i + 1);
                out.put("outputs " + i + " and " + (i + 1) + " swapped", withEntries(info, OUTPUTS, swapped));
            }
            int index = i;
            txOutChanges(outputs.get(i)).forEach((label, changed) -> out.put("output " + index + " " + label,
                    withTxOut(info, OUTPUTS, index, changed)));
        }
        PlutusData change = canonicalOutput(payTo(CHANGE, lovelace(3_000_000L)).toPlutusData());
        List<PlutusData> first = new ArrayList<>(outputs);
        first.addFirst(change);
        out.put("a key output first", withEntries(info, OUTPUTS, first));
        // Inputs.
        List<PlutusData> inputs = items(f.get(INPUTS));
        for (int i = 0; i < inputs.size(); i++) {
            List<PlutusData> dropped = new ArrayList<>(inputs);
            dropped.remove(i);
            out.put("input " + i + " dropped", withEntries(info, INPUTS, dropped));
            int index = i;
            txOutChanges(fields(inputs.get(i)).get(1)).forEach((label, changed) -> out.put("input " + index + " "
                    + label, withTxOut(info, INPUTS, index, changed)));
        }
        Map<String, PlutusData> added = new LinkedHashMap<>();
        added.put("a floor top-up vault input", input(0x5a, 0, out(vaultWallet(), lovelace(FLOOR), floorDatum())));
        added.put("an accepted vault input", input(0x5a, 1, out(vaultWallet(), assetValue(3_000_000L, 40),
                continuation(List.of()))));
        added.put("a deposit-shaped vault input", input(0x5a, 2, out(vaultWallet(), assetValue(2_000_000L, 40),
                programmableDeposit(smartWallet(REFUND_KEY), REFUND_KEY, filled(0x4c, 32), 0, 1))));
        added.put("another owner's PLB input", input(0x5a, 3, out(smartWalletOf(new Credential.PubKeyCredential(
                new PubKeyHash(filled(0x91, 28)))), lovelace(3_000_000L), null)));
        added.put("a key input", input(0x5a, 4, out(ledger(CHANGE), lovelace(3_000_000L), null)));
        added.put("another shard input", input(0x5a, 5, out(GatewayFixtures.scriptAddress(programmableShardHash()),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {9}, 1)), shardDatum(9, filled(0x4d, 32)))));
        added.put("an input at h", input(0x5a, 6, out(GatewayFixtures.scriptAddress(custodyHash()),
                lovelace(2_000_000L), null)));
        added.put("a key input with a shard token", input(0x5a, 7, out(ledger(CHANGE),
                lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {4}, 1)), null)));
        added.forEach((label, entry) -> {
            List<PlutusData> more = new ArrayList<>(inputs);
            more.add(entry);
            out.put(label + " added", withEntries(info, INPUTS, more));
        });
        // Reference inputs the custody scripts read.
        List<PlutusData> references = items(f.get(REFERENCES));
        for (int i = 0; i < references.size(); i++) {
            PlutusData txOut = fields(references.get(i)).get(1);
            if (!readByCustody(txOut)) {
                continue;
            }
            List<PlutusData> dropped = new ArrayList<>(references);
            dropped.remove(i);
            out.put("reference " + i + " dropped", withEntries(info, REFERENCES, dropped));
            List<PlutusData> doubled = new ArrayList<>(references);
            doubled.add(withField(references.get(i), 0, ref(0x5c, i).toPlutusData()));
            out.put("reference " + i + " doubled", withEntries(info, REFERENCES, doubled));
            int index = i;
            txOutChanges(txOut).forEach((label, changed) -> out.put("reference " + index + " " + label,
                    withTxOut(info, REFERENCES, index, changed)));
            if (lookupBytes(fields(txOut).get(1), EXIT_POLICY)) {
                for (PlutusData mode : List.of(normalMode(), exitMode(filled(0x44, 32)), exitMode(new byte[31]),
                        PlutusData.constr(1, PlutusData.bytes(filled(0x44, 32)), PlutusData.integer(-1),
                                PlutusData.integer(NOW)), PlutusData.constr(2))) {
                    out.put("reference " + index + " mode " + mode, withTxOut(info, REFERENCES, index,
                            withField(txOut, 2, PlutusData.constr(2, mode))));
                }
            }
        }
        // Signers, range, withdrawals, mint, certificates.
        List<PlutusData> signers = items(f.get(8));
        for (int i = 0; i < signers.size(); i++) {
            List<PlutusData> dropped = new ArrayList<>(signers);
            dropped.remove(i);
            out.put("signer " + i + " dropped", info(info, 8, list(dropped)));
        }
        List<PlutusData> more = new ArrayList<>(signers);
        more.add(PlutusData.bytes(keyHash(MEMBERS.get(2)).hash()));
        out.put("a third member signs", info(info, 8, list(more)));
        out.put("no signers", info(info, 8, list(List.of())));
        for (long lower : new long[] {NOW - DAY, NOW + DAY, NOW + 10 * DAY, 0}) {
            out.put("lower bound " + lower, info(info, 7, range(lower, NOW + 11 * DAY).toPlutusData()));
        }
        out.put("no lower bound", info(info, 7, new org.julclang.ledger.Interval(new org.julclang.ledger.IntervalBound(
                new org.julclang.ledger.IntervalBoundType.NegInf(), true), new org.julclang.ledger.IntervalBound(
                new org.julclang.ledger.IntervalBoundType.Finite(BigInteger.valueOf(NOW)), false)).toPlutusData()));
        PlutusData withdrawals = f.get(6);
        for (PlutusData.Pair pair : ((PlutusData.MapData) withdrawals).entries()) {
            String name = hex(credentialHash(pair.key())).substring(0, 6);
            out.put("withdrawal " + name + " + 1", info(info, 6, withEntry(withdrawals, pair.key(),
                    PlutusData.integer(((PlutusData.IntData) pair.value()).value().add(BigInteger.ONE)))));
            out.put("withdrawal " + name + " dropped", info(info, 6, withoutEntry(withdrawals, pair.key())));
        }
        out.put("a mint", info(info, 4, valueData(valueMap(token(filled(0x94, 28), "x".getBytes(), 1)
                .toPlutusData()))));
        out.put("a shard thread mint", info(info, 4, valueData(valueMap(token(SHARD_POLICY, new byte[] {4}, 1)
                .toPlutusData()))));
        out.put("a certificate", info(info, 5, list(List.of(PlutusData.constr(0, Cip113Blueprint.keyCredential(
                filled(0x93, 28)), PlutusData.constr(1))))));
        return out;
    }

    @Test
    void datumChangesGetTheSameVerdict() {
        baseWorlds().forEach((name, world) -> {
            Map<String, World> cases = new LinkedHashMap<>();
            datumChanges(world.info()).forEach((label, info) -> cases.put(name + " " + label, world.with(info)));
            agreeAll("datum changes", cases, ALL);
        });
        assertNoDisagreement();
    }

    @Test
    void transactionChangesGetTheSameVerdict() {
        baseWorlds().forEach((name, world) -> {
            Map<String, World> cases = new LinkedHashMap<>();
            transactionChanges(world.info()).forEach((label, info) -> cases.put(name + " " + label,
                    world.with(info)));
            agreeAll("transaction changes", cases, ALL);
        });
        assertNoDisagreement();
    }

    @Test
    void randomTransactionChangesGetTheSameVerdict() {
        Random random = new Random(0x0612);
        List<World> worlds = new ArrayList<>(baseWorlds().values());
        List<List<PlutusData>> changes = new ArrayList<>();
        for (World world : worlds) {
            List<PlutusData> options = new ArrayList<>(transactionChanges(world.info()).values());
            options.addAll(datumChanges(world.info()).values());
            changes.add(options);
        }
        Map<String, World> cases = new LinkedHashMap<>();
        for (int n = 0; n < 1_200; n++) {
            int w = random.nextInt(worlds.size());
            List<PlutusData> options = changes.get(w);
            PlutusData info = options.get(random.nextInt(options.size()));
            if (n % 4 == 0) {
                // A second change on top of the first.
                List<PlutusData> more = new ArrayList<>(transactionChanges(info).values());
                info = more.get(random.nextInt(more.size()));
            }
            World changed = worlds.get(w).with(info);
            if (random.nextBoolean()) {
                changed = withAction(changed, mutate(actionOf(changed), random));
            }
            cases.put("case " + n, changed);
        }
        agreeAll("random transaction changes", cases, ALL);
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ lenient encodings

    /** Encodings both backends read leniently: unchecked constructor tags and extra fields. Both must accept. */
    @Test
    void lenientEncodingsAreAcceptedByBoth() {
        Map<String, World> cases = new LinkedHashMap<>();
        for (Map.Entry<String, World> entry : baseWorlds().entrySet()) {
            World world = entry.getValue();
            PlutusData action = actionOf(world);
            String name = entry.getKey() + " ";
            List<int[]> paths = new ArrayList<>();
            collect(action, new int[0], paths);
            PlutusData padded = action;
            PlutusData retagged = action;
            for (int[] path : paths) {
                if (path.length == 0) {
                    continue;
                }
                PlutusData node = at(action, path, 0);
                if (node instanceof PlutusData.ConstrData constr) {
                    List<PlutusData> more = new ArrayList<>(constr.fields());
                    more.add(PlutusData.bytes(ascii("extra")));
                    padded = replace(padded, path, 0, new PlutusData.ConstrData(constr.tag(), more));
                    retagged = replace(retagged, path, 0, new PlutusData.ConstrData(constr.tag() + 3,
                            constr.fields()));
                }
            }
            List<PlutusData> topMore = new ArrayList<>(fields(action));
            topMore.add(PlutusData.integer(7));
            cases.put(name + "an extra field on the action", withAction(world, new PlutusData.ConstrData(
                    ((PlutusData.ConstrData) action).tag(), topMore)));
            if (!padded.equals(action)) {
                cases.put(name + "an extra field on every record", withAction(world, padded));
                cases.put(name + "every record's constructor + 3", withAction(world, retagged));
            }
            for (Twin twin : world.twins()) {
                if (twin.name().startsWith("spend:shard")) {
                    // h reads only each insert's id: its constructor and the proof steps' are not h's to check.
                    PlutusData shard = redeemerOf(world.info(), twin.purpose());
                    List<PlutusData> inserts = new ArrayList<>();
                    for (PlutusData insert : items(fields(shard).getFirst())) {
                        List<PlutusData> f = new ArrayList<>(fields(insert));
                        f.add(PlutusData.integer(1));
                        inserts.add(new PlutusData.ConstrData(0, f));
                    }
                    cases.put(name + "an extra field on every shard insert", world.with(withRedeemer(world.info(),
                            twin.purpose(), PlutusData.constr(0, list(inserts), PlutusData.integer(2)))));
                }
            }
        }
        cases.forEach((label, world) -> assertThat(agree("lenient encodings", label, world, ALL).values())
                .as(label).allMatch(v -> v));
        assertNoDisagreement();
    }
}
