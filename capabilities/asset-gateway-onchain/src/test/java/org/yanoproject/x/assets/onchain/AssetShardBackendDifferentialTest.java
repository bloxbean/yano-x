package org.yanoproject.x.assets.onchain;

import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.Value;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assets.onchain.mpf.MerklePatriciaForestry;
import org.yanoproject.x.assets.onchain.mpf.Neighbor;
import org.yanoproject.x.assets.onchain.mpf.ProofStep;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-059 D8c, r23: the two shard backends accept and reject exactly the same transactions. Every case runs the julc
 * {@code AssetShardValidator} (compiled from source, identical to its committed artifact) and the committed Aiken
 * artifact {@code AssetShardValidatorAiken} with the same parameters, on the same script context, on the julc VM.
 *
 * <ul>
 *   <li>Honest inserts at many trie sizes and batch sizes; every terminal step kind of an exclusion proof, at odd and
 *       even cursors, is covered.</li>
 *   <li>The proof shapes of {@code ShardConformanceTest} that the shape checks reject.</li>
 *   <li>Systematic and seeded random changes of honest proofs. The continuation then carries the root the julc
 *       library computes for the changed proof, so a case is decided by the proof checks and not by a root
 *       mismatch; an off-chain run of the julc library predicts each verdict.</li>
 *   <li>Encodings the scripts read leniently (constructor tags of the redeemer, an insert and a neighbor, extra
 *       fields, a leaf step's tag) and every transaction-shape rule of {@code ShardConformanceTest}.</li>
 * </ul>
 */
class AssetShardBackendDifferentialTest extends GatewayTestBase {
    private static final int SHARD = 6;
    private static final int[] SIZES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 16, 20, 24, 32, 48, 64, 96, 128, 200, 300,
            500, 800, 1_200, 2_000, 4_000};
    private static final int[] BATCHES = {1, 2, 3, 4, 8, 16};

    private static final Map<String, int[]> TALLY = new TreeMap<>();
    private static final List<String> DISAGREEMENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Integer> TERMINALS = new TreeMap<>();
    private static final List<String> ACCEPTED_CHANGES = Collections.synchronizedList(new ArrayList<>());
    private static long[] julcMax = new long[2];
    private static long[] aikenMax = new long[2];

    // ------------------------------------------------------------------ the comparison

    /** Runs both backends on {@code context}; records the verdicts under {@code group} and returns julc's. */
    private boolean agree(String group, String label, PlutusData context) {
        boolean verdict = compare(group, label, context, shard(), aikenShard());
        // ADR-061 D5: the CIP-113 shard binding variant, on the same context with the vault input replaced by the
        // vault script's withdrawal; it must agree with its Aiken twin and with the native shard.
        boolean programmable = compare("programmable: " + group, label, programmableContext(context),
                programmableShard(), aikenProgrammableShard());
        if (programmable != verdict) {
            synchronized (TALLY) {
                DISAGREEMENTS.add(group + " / " + label + ": the native shard " + (verdict ? "accepts" : "rejects")
                        + ", the binding variant " + (programmable ? "accepts" : "rejects"));
            }
        }
        return verdict;
    }

    private static Program programmableShard() {
        return program(ProgrammableShardValidator.class, PlutusData.bytes(SHARD_POLICY), PlutusData.bytes(VAULT_HASH));
    }

    private static Program aikenProgrammableShard() {
        return PROGRAMMABLE_AIKEN.computeIfAbsent("shard", ignored -> aikenTemplate(java.nio.file.Path.of(
                "src/main/resources/META-INF/plutus/ProgrammableShardValidatorAiken.plutus.json")).applyParams(
                PlutusData.bytes(SHARD_POLICY), PlutusData.bytes(VAULT_HASH)));
    }

    private static final Map<String, Program> PROGRAMMABLE_AIKEN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code context} as a CIP-113 vault transaction: every input at the vault script (or another vault script) is
     * replaced by that script's withdrawal, as PLB spends a CIP-113 vault's inputs and {@code h} runs as a withdrawal.
     */
    static PlutusData programmableContext(PlutusData context) {
        List<PlutusData> c = ((PlutusData.ConstrData) context).fields();
        List<PlutusData> info = new ArrayList<>(((PlutusData.ConstrData) c.get(0)).fields());
        List<PlutusData> inputs = new ArrayList<>();
        List<PlutusData.Pair> withdrawals = new ArrayList<>(((PlutusData.MapData) info.get(6)).entries());
        List<PlutusData> removed = new ArrayList<>();
        for (PlutusData input : ((PlutusData.ListData) info.get(0)).items()) {
            List<PlutusData> f = ((PlutusData.ConstrData) input).fields();
            PlutusData payment = ((PlutusData.ConstrData) ((PlutusData.ConstrData) ((PlutusData.ConstrData) f.get(1))
                    .fields().get(0)).fields().get(0));
            byte[] hash = ((PlutusData.BytesData) ((PlutusData.ConstrData) payment).fields().get(0)).value();
            if (((PlutusData.ConstrData) payment).tag() == 1
                    && (Arrays.equals(hash, VAULT_HASH) || Arrays.equals(hash, OTHER_VAULT_HASH))) {
                withdrawals.add(new PlutusData.Pair(payment, PlutusData.integer(0)));
                removed.add(PlutusData.constr(1, f.get(0)));
            } else {
                inputs.add(input);
            }
        }
        List<PlutusData.Pair> redeemers = new ArrayList<>(((PlutusData.MapData) info.get(9)).entries());
        redeemers.removeIf(pair -> removed.contains(pair.key()));
        info.set(0, PlutusData.list(inputs.toArray(new PlutusData[0])));
        info.set(6, new PlutusData.MapData(withdrawals));
        info.set(9, new PlutusData.MapData(redeemers));
        return PlutusData.constr(0, new PlutusData.ConstrData(0, info), c.get(1), c.get(2));
    }

    private boolean compare(String group, String label, PlutusData context, Program julcShard, Program aikenShard) {
        EvalResult julc = evaluate(julcShard, context);
        EvalResult aiken = evaluate(aikenShard, context);
        boolean j = julc.isSuccess();
        boolean a = aiken.isSuccess();
        synchronized (TALLY) {
            int[] counts = TALLY.computeIfAbsent(group, ignored -> new int[3]);
            counts[j ? 0 : 1]++;
            if (j && group.endsWith("changes")) {
                ACCEPTED_CHANGES.add(group + " / " + label);
            }
            if (j != a) {
                counts[2]++;
                DISAGREEMENTS.add(group + " / " + label + ": julc " + (j ? "accepts" : "rejects") + ", aiken "
                        + (a ? "accepts" : "rejects"));
            }
        }
        if (j && a) {
            assertThat(julc.budgetConsumed().cpuSteps()).isLessThan(MAX_TX_CPU);
            assertThat(aiken.budgetConsumed().cpuSteps()).isLessThan(MAX_TX_CPU);
        }
        return j;
    }

    private void agreeAll(String group, Map<String, PlutusData> cases) {
        cases.forEach((label, context) -> agree(group, label, context));
    }

    @AfterAll
    static void summary() {
        int accepted = 0;
        int rejected = 0;
        int disagreed = 0;
        for (Map.Entry<String, int[]> entry : TALLY.entrySet()) {
            int[] c = entry.getValue();
            System.out.printf("DIFF|%s|accepted=%d|rejected=%d|disagreements=%d%n", entry.getKey(), c[0], c[1], c[2]);
            accepted += c[0];
            rejected += c[1];
            disagreed += c[2];
        }
        System.out.printf("DIFF|total|accepted=%d|rejected=%d|disagreements=%d%n", accepted, rejected, disagreed);
        TERMINALS.forEach((kind, count) -> System.out.printf("DIFF|terminal|%s|%d%n", kind, count));
        System.out.printf("DIFF|honest max units|julc cpu=%d mem=%d|aiken cpu=%d mem=%d%n", julcMax[0], julcMax[1],
                aikenMax[0], aikenMax[1]);
        ACCEPTED_CHANGES.forEach(line -> System.out.println("DIFF|accepted change|" + line));
        DISAGREEMENTS.forEach(line -> System.out.println("DIFF|DISAGREEMENT|" + line));
    }

    private static void assertNoDisagreement() {
        assertThat(DISAGREEMENTS).as("the julc and Aiken shard backends disagree").isEmpty();
    }

    // ------------------------------------------------------------------ transactions

    private static Value threadValue() {
        return lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {SHARD}, 1));
    }

    /** A shard spend from {@code before} to {@code after}, paired with a vault spend. */
    private static Tx spend(byte[] before, PlutusData redeemer, byte[] after) {
        Tx tx = new Tx();
        tx.spend(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(),
                new OutputDatum.OutputDatumInline(shardDatum(SHARD, before)), Optional.empty())), redeemer);
        tx.spend(new TxInInfo(VAULT_REF, new TxOut(vaultAddress(), lovelace(9_000_000),
                new OutputDatum.OutputDatumInline(continuationDatum(List.of())), Optional.empty())),
                PlutusData.constr(0));
        tx.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), threadValue(), shardDatum(SHARD, after)));
        return tx;
    }

    private static List<byte[]> freshIds(int count, int seed) {
        List<byte[]> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(shardId(SHARD, seed + i));
        }
        return ids;
    }

    private static List<PlutusData> inserts(PlutusData redeemer) {
        return ((PlutusData.ListData) ((PlutusData.ConstrData) redeemer).fields().getFirst()).items();
    }

    private static PlutusData redeemer(List<PlutusData> inserts) {
        return PlutusData.constr(0, PlutusData.list(inserts.toArray(new PlutusData[0])));
    }

    private static byte[] bytes(PlutusData data) {
        return ((PlutusData.BytesData) data).value();
    }

    private static int integer(PlutusData data) {
        return ((PlutusData.IntData) data).value().intValueExact();
    }

    // ------------------------------------------------------------------ the julc library, off chain

    /** A proof as the julc script reads it: constructor 0 a branch, 1 a fork, any other a leaf. */
    private static JulcList<ProofStep> steps(PlutusData proof) {
        List<ProofStep> out = new ArrayList<>();
        for (PlutusData step : ((PlutusData.ListData) proof).items()) {
            PlutusData.ConstrData constr = (PlutusData.ConstrData) step;
            List<PlutusData> f = constr.fields();
            int skip = integer(f.get(0));
            if (constr.tag() == 0) {
                out.add(new ProofStep.Branch(skip, bytes(f.get(1))));
            } else if (constr.tag() == 1) {
                List<PlutusData> n = ((PlutusData.ConstrData) f.get(1)).fields();
                out.add(new ProofStep.Fork(skip, new Neighbor(integer(n.get(0)), bytes(n.get(1)), bytes(n.get(2)))));
            } else {
                out.add(new ProofStep.Leaf(skip, bytes(f.get(1)), bytes(f.get(2))));
            }
        }
        return JulcList.of(out.toArray(new ProofStep[0]));
    }

    /** The root the julc fold computes for {@code inserts} without the proof checks; null when it cannot. */
    private static byte[] unchecked(byte[] root, List<PlutusData> inserts) {
        try {
            for (PlutusData insert : inserts) {
                List<PlutusData> f = ((PlutusData.ConstrData) insert).fields();
                byte[] id = bytes(f.get(0));
                root = MerklePatriciaForestry.including(id, id, steps(f.get(1)));
            }
            return root;
        } catch (RuntimeException undecodable) {
            return null;
        }
    }

    /** The julc fold's verdict, off chain: every id routes here and is absent, by a proof the checks accept. */
    private static boolean predicted(byte[] root, List<PlutusData> inserts) {
        if (inserts.isEmpty()) {
            return false;
        }
        try {
            for (PlutusData insert : inserts) {
                List<PlutusData> f = ((PlutusData.ConstrData) insert).fields();
                byte[] id = bytes(f.get(0));
                JulcList<ProofStep> proof = steps(f.get(1));
                if (id.length != 32 || (id[31] & 0x0f) != SHARD || !MerklePatriciaForestry.miss(root, id, proof)) {
                    return false;
                }
                root = MerklePatriciaForestry.including(id, id, proof);
            }
            return true;
        } catch (RuntimeException undecodable) {
            return false;
        }
    }

    /** Runs a changed batch: the continuation carries the unchecked root, and the julc library predicts the verdict. */
    private void changed(String group, String label, byte[] before, List<PlutusData> inserts, byte[] fallback) {
        byte[] after = unchecked(before, inserts);
        Tx tx = spend(before, redeemer(inserts), after == null ? fallback : after);
        boolean verdict = agree(group, label, tx.spending(shardRef(SHARD)));
        assertThat(verdict).as("%s / %s: the julc library predicts the verdict", group, label)
                .isEqualTo(after != null && predicted(before, inserts));
    }

    // ------------------------------------------------------------------ honest inserts

    private static String terminal(byte[] id, PlutusData proof) {
        List<PlutusData> items = ((PlutusData.ListData) proof).items();
        if (items.isEmpty()) {
            return "empty-proof";
        }
        int cursor = 0;
        for (int i = 0; i < items.size() - 1; i++) {
            cursor += integer(((PlutusData.ConstrData) items.get(i)).fields().getFirst()) + 1;
        }
        int tag = ((PlutusData.ConstrData) items.getLast()).tag();
        String kind = tag == 0 ? "branch" : tag == 1 ? "fork" : "leaf";
        return kind + "@" + (cursor % 2 == 0 ? "even" : "odd") + "-cursor";
    }

    @Test
    void honestInsertsAreAcceptedByBothBackendsAtEveryTrieSize() {
        int seed = 1_000_000;
        for (int size : SIZES) {
            ShardTrie trie = new ShardTrie(SHARD).seed(size);
            for (int k : BATCHES) {
                Inserts plan = trie.plan(freshIds(k, seed));
                seed += k;
                for (PlutusData insert : inserts(plan.redeemer())) {
                    List<PlutusData> f = ((PlutusData.ConstrData) insert).fields();
                    TERMINALS.merge(terminal(bytes(f.get(0)), f.get(1)), 1, Integer::sum);
                }
                PlutusData context = spend(plan.before(), plan.redeemer(), plan.after()).spending(shardRef(SHARD));
                assertThat(agree("honest", "size " + size + " k " + k, context)).as("size %d k %d", size, k)
                        .isTrue();
                EvalResult j = evaluate(shard(), context);
                EvalResult a = evaluate(aikenShard(), context);
                julcMax[0] = Math.max(julcMax[0], j.budgetConsumed().cpuSteps());
                julcMax[1] = Math.max(julcMax[1], j.budgetConsumed().memoryUnits());
                aikenMax[0] = Math.max(aikenMax[0], a.budgetConsumed().cpuSteps());
                aikenMax[1] = Math.max(aikenMax[1], a.budgetConsumed().memoryUnits());
                if (size == 4_000) {
                    System.out.printf("DIFF|shard units|4000 ids|k=%d|julc cpu=%d mem=%d|aiken cpu=%d mem=%d%n", k,
                            j.budgetConsumed().cpuSteps(), j.budgetConsumed().memoryUnits(),
                            a.budgetConsumed().cpuSteps(), a.budgetConsumed().memoryUnits());
                }
            }
        }
        for (String kind : List.of("empty-proof", "branch@even-cursor", "branch@odd-cursor", "fork@even-cursor",
                "fork@odd-cursor", "leaf@even-cursor", "leaf@odd-cursor")) {
            assertThat(TERMINALS).as("honest proofs cover a terminal %s", kind).containsKey(kind);
        }
        assertNoDisagreement();
    }

    // ------------------------------------------------------------------ the conformance proof shapes

    /** {@code ShardConformanceTest}: a terminal leaf at an odd cursor whose key bytes are all nibbles. */
    @Test
    void aTerminalLeafWithNibbleOnlyBytesAtAnOddCursorIsRejectedByBoth() {
        for (int c : new int[] {1, 3, 5, 9, 13}) {
            int length = 2 + (63 - c) / 2;
            byte[] id;
            byte[] path;
            for (int seed = 0; ; seed++) {
                id = shardId(SHARD, 70_000 + 1_000 * c + seed);
                path = GatewayFixtures.blake2b256(id);
                if (MerklePatriciaForestry.nibble(path, c) == 0 && MerklePatriciaForestry.nibble(path, c + 1) != 0) {
                    break;
                }
            }
            byte[] d1 = GatewayFixtures.blake2b256(new byte[] {1});
            byte[] d2 = GatewayFixtures.blake2b256(new byte[] {2});
            int lower = (MerklePatriciaForestry.nibble(path, c + length) + 1) % 16;
            int upper = (MerklePatriciaForestry.nibble(path, c - 1) + 1) % 16;
            byte[] leaf = MerklePatriciaForestry.combine(MerklePatriciaForestry.suffix(path, c + length + 1),
                    GatewayFixtures.blake2b256(id));
            byte[] branchMerkle = MerklePatriciaForestry.sparseMerkle16(MerklePatriciaForestry.nibble(path,
                    c + length), leaf, lower, MerklePatriciaForestry.combine(new byte[0], d1));
            byte[] prefix = MerklePatriciaForestry.nibbles(path, c, c + length);
            byte[] branch = MerklePatriciaForestry.combine(prefix, branchMerkle);
            byte[] root = MerklePatriciaForestry.doFork(path, 0, c - 1, branch, upper, new byte[0], d2);
            byte[] conflicting = new byte[32];
            System.arraycopy(path, 0, conflicting, 0, (c - 1) / 2);
            conflicting[(c - 1) / 2] = (byte) ((MerklePatriciaForestry.nibble(path, c - 1) << 4) | prefix[1]);
            System.arraycopy(prefix, 2, conflicting, (c + 1) / 2, length - 2);
            PlutusData proof = PlutusData.list(fork(c - 1, upper, new byte[0], d2),
                    PlutusData.constr(2, PlutusData.integer(0), PlutusData.bytes(conflicting),
                            PlutusData.bytes(branchMerkle)));
            changed("conformance shapes", "terminal leaf, cursor " + c, root,
                    List.of(PlutusData.constr(0, PlutusData.bytes(id), proof)), filled(0x01, 32));
            // The same leaf at the honest cursor of an inclusion is accepted by neither as an exclusion.
            PlutusData inclusion = PlutusData.list(fork(c - 1, upper, new byte[0], d2),
                    fork(length, lower, new byte[0], d1));
            changed("conformance shapes", "inclusion as exclusion, cursor " + c, root,
                    List.of(PlutusData.constr(0, PlutusData.bytes(id), inclusion)), filled(0x01, 32));
        }
        assertNoDisagreement();
    }

    /** {@code ShardConformanceTest}: a terminal fork at an odd cursor whose prefix has the odd-leaf length. */
    @Test
    void aTerminalForkOfTheOddLeafLengthIsRejectedByBoth() {
        byte[] d = GatewayFixtures.blake2b256(new byte[] {3});
        for (int c : new int[] {55, 57, 59}) {
            byte[] id;
            byte[] path;
            for (int seed = 0; ; seed++) {
                id = shardId(SHARD, 90_000 + 100_000 * c + seed);
                path = GatewayFixtures.blake2b256(id);
                if (MerklePatriciaForestry.nibble(path, c) != 0
                        && MerklePatriciaForestry.allNibbles(path, (c + 1) / 2)) {
                    break;
                }
            }
            byte[] valueHash = GatewayFixtures.blake2b256(id);
            byte[] suffix = MerklePatriciaForestry.suffix(path, c);
            int other = (MerklePatriciaForestry.nibble(path, c - 1) + 1) % 16;
            byte[] root = MerklePatriciaForestry.doFork(path, 0, c - 1,
                    MerklePatriciaForestry.combine(suffix, valueHash), other, new byte[0], d);
            byte[] neighborPrefix = Arrays.copyOfRange(suffix, 1, suffix.length);
            PlutusData proof = PlutusData.list(fork(c - 1, other, new byte[0], d),
                    fork(0, 0, neighborPrefix, valueHash));
            changed("conformance shapes", "terminal fork, cursor " + c, root,
                    List.of(PlutusData.constr(0, PlutusData.bytes(id), proof)), filled(0x01, 32));
        }
        assertNoDisagreement();
    }

    /**
     * A terminal fork whose neighbor branch ends at nibble {@code skip + 1 + length(prefix)}, below, at and past the
     * leaf level, from a shard root built to match the exclusion: the fork shape rules alone decide each case (the
     * neighbor must end above the leaf level; at an odd cursor a prefix starting with nibble 0 must not have the
     * odd-leaf length).
     */
    @Test
    void aForkNeighborEndingAtOrPastTheLeafLevelIsRejectedByBoth() {
        int accepted = 0;
        int rejected = 0;
        int seed = 2_000_000;
        for (int skip = 0; skip <= 62; skip++) {
            byte[] id = shardId(SHARD, seed++);
            byte[] path = GatewayFixtures.blake2b256(id);
            int own = MerklePatriciaForestry.nibble(path, skip);
            for (int end : new int[] {skip + 1, 61, 62, 63, 64, 65}) {
                int length = end - skip - 1;
                if (length < 0) {
                    continue;
                }
                for (int first : new int[] {0, 5}) {
                    byte[] prefix = new byte[length];
                    for (int b = 0; b < length; b++) {
                        prefix[b] = (byte) (b == 0 ? first : (b * 7) % 16);
                    }
                    int nibble = own == 0 ? 9 : 0;
                    PlutusData proof = PlutusData.list(fork(skip, nibble, prefix, GatewayFixtures.blake2b256(
                            new byte[] {(byte) skip, (byte) end})));
                    List<PlutusData> batch = List.of(PlutusData.constr(0, PlutusData.bytes(id), proof));
                    byte[] root;
                    try {
                        root = MerklePatriciaForestry.excluding(id, steps(proof));
                    } catch (RuntimeException outside) {
                        continue;
                    }
                    if (agreeChanged("fork neighbor length", "skip " + skip + " end " + end + " first " + first,
                            root, batch)) {
                        accepted++;
                    } else {
                        rejected++;
                    }
                }
            }
        }
        assertThat(accepted).isGreaterThan(50);
        assertThat(rejected).isGreaterThan(50);
        assertNoDisagreement();
    }

    private boolean agreeChanged(String group, String label, byte[] before, List<PlutusData> batch) {
        byte[] after = unchecked(before, batch);
        Tx tx = spend(before, redeemer(batch), after == null ? filled(0x01, 32) : after);
        boolean verdict = agree(group, label, tx.spending(shardRef(SHARD)));
        assertThat(verdict).as("%s / %s: the julc library predicts the verdict", group, label)
                .isEqualTo(after != null && predicted(before, batch));
        return verdict;
    }

    private static PlutusData fork(int skip, int nibble, byte[] prefix, byte[] root) {
        return PlutusData.constr(1, PlutusData.integer(skip), PlutusData.constr(0, PlutusData.integer(nibble),
                PlutusData.bytes(prefix), PlutusData.bytes(root)));
    }

    // ------------------------------------------------------------------ systematic proof changes

    @Test
    void systematicProofChangesGetTheSameVerdictFromBoth() {
        int seed = 3_000_000;
        for (int size : new int[] {0, 1, 2, 5, 16, 40, 300, 4_000}) {
            ShardTrie trie = new ShardTrie(SHARD).seed(size);
            for (int k : new int[] {1, 3}) {
                byte[] before = trie.root();
                Inserts plan = trie.plan(freshIds(k, seed));
                seed += k;
                List<PlutusData> honest = inserts(plan.redeemer());
                for (int j = 0; j < honest.size(); j++) {
                    for (Map.Entry<String, PlutusData> change : proofChanges(honest.get(j)).entrySet()) {
                        List<PlutusData> batch = new ArrayList<>(honest);
                        batch.set(j, change.getValue());
                        changed("proof changes", "size " + size + " k " + k + " insert " + j + " " + change.getKey(),
                                before, batch, plan.after());
                    }
                }
            }
        }
        assertNoDisagreement();
    }

    /** Named changes of one insert's id and proof. */
    private static Map<String, PlutusData> proofChanges(PlutusData insert) {
        Map<String, PlutusData> out = new TreeMap<>();
        List<PlutusData> f = ((PlutusData.ConstrData) insert).fields();
        byte[] id = bytes(f.get(0));
        List<PlutusData> items = ((PlutusData.ListData) f.get(1)).items();
        for (int i = 0; i < items.size(); i++) {
            PlutusData.ConstrData step = (PlutusData.ConstrData) items.get(i);
            List<PlutusData> sf = step.fields();
            int skip = integer(sf.getFirst());
            int tag = step.tag();
            String at = "step " + i + " ";
            for (int delta : new int[] {-1, 1, 2, 16, 64}) {
                if (skip + delta >= -1) {
                    out.put(at + "skip " + (delta > 0 ? "+" : "") + delta, withStep(id, items, i,
                            replaceField(step, 0, PlutusData.integer(skip + delta))));
                }
            }
            if (tag == 0) {
                byte[] neighbors = bytes(sf.get(1));
                out.put(at + "neighbors 127 bytes", withStep(id, items, i,
                        replaceField(step, 1, PlutusData.bytes(Arrays.copyOf(neighbors, 127)))));
                out.put(at + "neighbors 129 bytes", withStep(id, items, i,
                        replaceField(step, 1, PlutusData.bytes(Arrays.copyOf(neighbors, 129)))));
                for (int b : new int[] {0, 31, 64, 127}) {
                    out.put(at + "neighbors byte " + b, withStep(id, items, i,
                            replaceField(step, 1, PlutusData.bytes(flip(neighbors, b)))));
                }
                out.put(at + "as a fork", withStep(id, items, i, new PlutusData.ConstrData(1, sf)));
            } else if (tag == 1) {
                PlutusData.ConstrData neighbor = (PlutusData.ConstrData) sf.get(1);
                List<PlutusData> n = neighbor.fields();
                int nibble = integer(n.get(0));
                byte[] prefix = bytes(n.get(1));
                byte[] root = bytes(n.get(2));
                for (int other : new int[] {-1, 16, (nibble + 1) % 16, (nibble + 7) % 16}) {
                    out.put(at + "neighbor nibble " + other, withStep(id, items, i, fork(skip, other, prefix, root)));
                }
                byte[] appended = Arrays.copyOf(prefix, prefix.length + 1);
                out.put(at + "prefix + nibble", withStep(id, items, i, fork(skip, nibble, appended, root)));
                appended[prefix.length] = 0x10;
                out.put(at + "prefix + byte 16", withStep(id, items, i, fork(skip, nibble, appended, root)));
                if (prefix.length > 0) {
                    out.put(at + "prefix - nibble", withStep(id, items, i,
                            fork(skip, nibble, Arrays.copyOf(prefix, prefix.length - 1), root)));
                    byte[] raised = prefix.clone();
                    raised[0] = (byte) (raised[0] + 16);
                    out.put(at + "prefix raw byte", withStep(id, items, i, fork(skip, nibble, raised, root)));
                }
                out.put(at + "neighbor root 31 bytes", withStep(id, items, i,
                        fork(skip, nibble, prefix, Arrays.copyOf(root, 31))));
                out.put(at + "neighbor root byte", withStep(id, items, i, fork(skip, nibble, prefix, flip(root, 5))));
                out.put(at + "as a branch", withStep(id, items, i, new PlutusData.ConstrData(0, List.of(sf.getFirst(),
                        PlutusData.bytes(new byte[128])))));
            } else {
                byte[] key = bytes(sf.get(1));
                byte[] value = bytes(sf.get(2));
                for (int b : new int[] {0, 16, 31}) {
                    out.put(at + "key byte " + b, withStep(id, items, i, replaceField(step, 1,
                            PlutusData.bytes(flip(key, b)))));
                }
                out.put(at + "key 31 bytes", withStep(id, items, i,
                        replaceField(step, 1, PlutusData.bytes(Arrays.copyOf(key, 31)))));
                out.put(at + "key is the path", withStep(id, items, i,
                        replaceField(step, 1, PlutusData.bytes(GatewayFixtures.blake2b256(id)))));
                out.put(at + "value 31 bytes", withStep(id, items, i,
                        replaceField(step, 2, PlutusData.bytes(Arrays.copyOf(value, 31)))));
                out.put(at + "value byte", withStep(id, items, i,
                        replaceField(step, 2, PlutusData.bytes(flip(value, 9)))));
                out.put(at + "as a fork", withStep(id, items, i, new PlutusData.ConstrData(1, sf)));
            }
            List<PlutusData> dropped = new ArrayList<>(items);
            dropped.remove(i);
            out.put(at + "dropped", insert(id, dropped));
            List<PlutusData> doubled = new ArrayList<>(items);
            doubled.add(i, items.get(i));
            out.put(at + "doubled", insert(id, doubled));
            if (i + 1 < items.size()) {
                List<PlutusData> swapped = new ArrayList<>(items);
                Collections.swap(swapped, i, i + 1);
                out.put(at + "swapped with the next", insert(id, swapped));
            }
        }
        List<PlutusData> extended = new ArrayList<>(items);
        extended.add(PlutusData.constr(0, PlutusData.integer(0), PlutusData.bytes(new byte[128])));
        out.put("a branch appended", insert(id, extended));
        List<PlutusData> leafed = new ArrayList<>(items);
        leafed.add(PlutusData.constr(2, PlutusData.integer(0), PlutusData.bytes(GatewayFixtures.blake2b256(id)),
                PlutusData.bytes(GatewayFixtures.blake2b256(id))));
        out.put("a leaf appended", insert(id, leafed));
        if (!items.isEmpty()) {
            out.put("empty proof", insert(id, List.of()));
        }
        byte[] sameShard = flip(id, 3);
        out.put("another id of the shard", insert(sameShard, items));
        byte[] otherShard = id.clone();
        otherShard[31] = (byte) ((otherShard[31] & 0xf0) | ((SHARD + 1) % 16));
        out.put("an id of another shard", insert(otherShard, items));
        out.put("a 31-byte id", insert(Arrays.copyOf(id, 31), items));
        out.put("a 33-byte id", insert(Arrays.copyOf(id, 33), items));
        return out;
    }

    private static PlutusData insert(byte[] id, List<PlutusData> steps) {
        return PlutusData.constr(0, PlutusData.bytes(id), PlutusData.list(steps.toArray(new PlutusData[0])));
    }

    private static PlutusData withStep(byte[] id, List<PlutusData> items, int index, PlutusData step) {
        List<PlutusData> changed = new ArrayList<>(items);
        changed.set(index, step);
        return insert(id, changed);
    }

    private static PlutusData replaceField(PlutusData.ConstrData constr, int index, PlutusData value) {
        List<PlutusData> fields = new ArrayList<>(constr.fields());
        fields.set(index, value);
        return new PlutusData.ConstrData(constr.tag(), fields);
    }

    private static byte[] flip(byte[] value, int index) {
        byte[] out = value.clone();
        out[index] ^= 0x01;
        return out;
    }

    // ------------------------------------------------------------------ random proof changes

    @Test
    void randomProofChangesGetTheSameVerdictFromBoth() {
        Random random = new Random(0x059);
        int seed = 4_000_000;
        List<Object[]> worlds = new ArrayList<>();
        for (int size : new int[] {1, 3, 8, 40, 300, 4_000}) {
            ShardTrie trie = new ShardTrie(SHARD).seed(size);
            for (int round = 0; round < 3; round++) {
                byte[] before = trie.root();
                Inserts plan = trie.plan(freshIds(2, seed));
                seed += 2;
                worlds.add(new Object[] {before, plan});
            }
        }
        for (int n = 0; n < 400; n++) {
            Object[] world = worlds.get(random.nextInt(worlds.size()));
            byte[] before = (byte[]) world[0];
            Inserts plan = (Inserts) world[1];
            List<PlutusData> batch = new ArrayList<>(inserts(plan.redeemer()));
            int j = random.nextInt(batch.size());
            batch.set(j, mutate(batch.get(j), random));
            changed("random changes", "case " + n, before, batch, plan.after());
        }
        assertNoDisagreement();
    }

    /** One random change to one bytes or integer leaf of the data. */
    private static PlutusData mutate(PlutusData data, Random random) {
        List<int[]> paths = new ArrayList<>();
        collect(data, new int[0], paths);
        int[] path = paths.get(random.nextInt(paths.size()));
        return change(data, path, 0, random);
    }

    private static void collect(PlutusData data, int[] path, List<int[]> out) {
        if (data instanceof PlutusData.ConstrData constr) {
            for (int i = 0; i < constr.fields().size(); i++) {
                collect(constr.fields().get(i), append(path, i), out);
            }
        } else if (data instanceof PlutusData.ListData list) {
            for (int i = 0; i < list.items().size(); i++) {
                collect(list.items().get(i), append(path, i), out);
            }
        } else {
            out.add(path);
        }
    }

    private static int[] append(int[] path, int index) {
        int[] out = Arrays.copyOf(path, path.length + 1);
        out[path.length] = index;
        return out;
    }

    private static PlutusData change(PlutusData data, int[] path, int depth, Random random) {
        if (depth == path.length) {
            if (data instanceof PlutusData.BytesData b) {
                byte[] value = b.value().clone();
                if (value.length == 0 || random.nextInt(8) == 0) {
                    return PlutusData.bytes(Arrays.copyOf(value, value.length + 1));
                }
                int index = random.nextInt(value.length);
                value[index] = (byte) (random.nextBoolean() ? value[index] ^ (1 << random.nextInt(8))
                        : random.nextInt(16));
                return PlutusData.bytes(value);
            }
            BigInteger value = ((PlutusData.IntData) data).value();
            return PlutusData.integer(value.add(BigInteger.valueOf(random.nextInt(5) - 2)));
        }
        if (data instanceof PlutusData.ConstrData constr) {
            List<PlutusData> fields = new ArrayList<>(constr.fields());
            fields.set(path[depth], change(fields.get(path[depth]), path, depth + 1, random));
            return new PlutusData.ConstrData(constr.tag(), fields);
        }
        List<PlutusData> items = new ArrayList<>(((PlutusData.ListData) data).items());
        items.set(path[depth], change(items.get(path[depth]), path, depth + 1, random));
        return new PlutusData.ListData(items);
    }

    // ------------------------------------------------------------------ encodings and batches

    @Test
    void redeemerEncodingsGetTheSameVerdictFromBoth() {
        int seed = 5_000_000;
        for (int size : new int[] {0, 3, 40, 4_000}) {
            ShardTrie trie = new ShardTrie(SHARD).seed(size);
            byte[] before = trie.root();
            Inserts plan = trie.plan(freshIds(3, seed));
            seed += 3;
            List<PlutusData> honest = inserts(plan.redeemer());
            Map<String, PlutusData> cases = new TreeMap<>();
            String at = "size " + size + " ";
            PlutusData list = ((PlutusData.ConstrData) plan.redeemer()).fields().getFirst();
            cases.put(at + "redeemer constructor 3", context(before,
                    new PlutusData.ConstrData(3, List.of(list)), plan.after()));
            cases.put(at + "redeemer extra field", context(before,
                    new PlutusData.ConstrData(0, List.of(list, PlutusData.integer(7))), plan.after()));
            cases.put(at + "redeemer not a constructor", context(before, list, plan.after()));
            List<PlutusData> retagged = new ArrayList<>();
            List<PlutusData> padded = new ArrayList<>();
            List<PlutusData> neighbors = new ArrayList<>();
            List<PlutusData> leaves = new ArrayList<>();
            List<PlutusData> extraStep = new ArrayList<>();
            for (PlutusData insert : honest) {
                List<PlutusData> f = ((PlutusData.ConstrData) insert).fields();
                retagged.add(new PlutusData.ConstrData(5, f));
                padded.add(new PlutusData.ConstrData(0, List.of(f.get(0), f.get(1), PlutusData.bytes(new byte[3]))));
                List<PlutusData> n = new ArrayList<>();
                List<PlutusData> l = new ArrayList<>();
                List<PlutusData> e = new ArrayList<>();
                for (PlutusData step : ((PlutusData.ListData) f.get(1)).items()) {
                    PlutusData.ConstrData s = (PlutusData.ConstrData) step;
                    n.add(s.tag() == 1 ? replaceField(s, 1, new PlutusData.ConstrData(4, ((PlutusData.ConstrData)
                            s.fields().get(1)).fields())) : s);
                    l.add(s.tag() == 2 ? new PlutusData.ConstrData(7, s.fields()) : s);
                    List<PlutusData> more = new ArrayList<>(s.fields());
                    more.add(PlutusData.integer(1));
                    e.add(new PlutusData.ConstrData(s.tag(), more));
                }
                neighbors.add(insert(bytes(f.get(0)), n));
                leaves.add(insert(bytes(f.get(0)), l));
                extraStep.add(insert(bytes(f.get(0)), e));
            }
            cases.put(at + "insert constructor 5", context(before, redeemer(retagged), plan.after()));
            cases.put(at + "insert extra field", context(before, redeemer(padded), plan.after()));
            cases.put(at + "neighbor constructor 4", context(before, redeemer(neighbors), plan.after()));
            cases.put(at + "leaf step constructor 7", context(before, redeemer(leaves), plan.after()));
            cases.put(at + "step extra field", context(before, redeemer(extraStep), plan.after()));
            cases.put(at + "honest", context(before, plan.redeemer(), plan.after()));

            List<PlutusData> twice = new ArrayList<>(honest);
            twice.add(honest.getFirst());
            cases.put(at + "the first id twice", context(before, redeemer(twice), unchecked(before, twice)));
            List<PlutusData> reversed = new ArrayList<>(honest);
            Collections.reverse(reversed);
            byte[] reversedRoot = unchecked(before, reversed);
            cases.put(at + "reversed order", context(before, redeemer(reversed),
                    reversedRoot == null ? plan.after() : reversedRoot));
            cases.put(at + "the first two only, final root", context(before, redeemer(honest.subList(0, 2)),
                    plan.after()));
            agreeAll("redeemer encodings", cases);
        }
        assertNoDisagreement();
    }

    private static PlutusData context(byte[] before, PlutusData redeemer, byte[] after) {
        return spend(before, redeemer, after == null ? filled(0x01, 32) : after).spending(shardRef(SHARD));
    }

    // ------------------------------------------------------------------ the transaction-shape rules

    @Test
    void theTransactionShapeRulesGetTheSameVerdictFromBoth() {
        int seed = 6_000_000;
        for (int size : new int[] {0, 40}) {
            for (int k : new int[] {1, 4}) {
                ShardTrie trie = new ShardTrie(SHARD).seed(size);
                byte[] before = trie.root();
                Inserts plan = trie.plan(freshIds(k, seed));
                seed += k;
                agreeAll("transaction shape", shapeCases("size " + size + " k " + k + " ", before, plan));
            }
        }
        assertNoDisagreement();
    }

    private static Map<String, PlutusData> shapeCases(String at, byte[] before, Inserts plan) {
        Map<String, PlutusData> cases = new TreeMap<>();
        PlutusData redeemer = plan.redeemer();
        byte[] after = plan.after();
        cases.put(at + "honest", spend(before, redeemer, after).spending(shardRef(SHARD)));

        Tx otherVault = spend(before, redeemer, after);
        otherVault.inputs.set(1, new Spent(new TxInInfo(VAULT_REF, new TxOut(GatewayFixtures.scriptAddress(
                OTHER_VAULT_HASH), lovelace(9_000_000), new OutputDatum.OutputDatumInline(continuationDatum(
                List.of())), Optional.empty())), PlutusData.constr(0)));
        cases.put(at + "another vault", otherVault.spending(shardRef(SHARD)));
        Tx noVault = spend(before, redeemer, after);
        noVault.inputs.remove(1);
        cases.put(at + "no vault input", noVault.spending(shardRef(SHARD)));
        Tx keyVault = spend(before, redeemer, after);
        keyVault.inputs.set(1, new Spent(keyInput(VAULT_REF, 9_000_000), null));
        cases.put(at + "a key input instead of the vault", keyVault.spending(shardRef(SHARD)));

        cases.put(at + "wrong next root", spend(before, redeemer, filled(0x01, 32)).spending(shardRef(SHARD)));
        cases.put(at + "the spent root kept", spend(before, redeemer, before).spending(shardRef(SHARD)));

        TxOut next = spend(before, redeemer, after).outputs.getFirst();
        cases.put(at + "staked continuation", withOutput(before, redeemer, after,
                new TxOut(GatewayFixtures.scriptAddressWithStake(SHARD_HASH, filled(0x99, 28)), next.value(),
                        next.datum(), Optional.empty())));
        cases.put(at + "continuation at another script", withOutput(before, redeemer, after,
                new TxOut(GatewayFixtures.scriptAddress(OTHER_SCRIPT), next.value(), next.datum(),
                        Optional.empty())));
        cases.put(at + "continuation with a foreign token", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value().merge(token(filled(0x7c, 28), new byte[] {1}, 1)),
                        next.datum(), Optional.empty())));
        cases.put(at + "continuation with a second thread name", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value().merge(token(SHARD_POLICY, new byte[] {SHARD + 1}, 1)),
                        next.datum(), Optional.empty())));
        cases.put(at + "continuation with two thread tokens", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value().merge(token(SHARD_POLICY, new byte[] {SHARD}, 1)),
                        next.datum(), Optional.empty())));
        cases.put(at + "continuation without the token", withOutput(before, redeemer, after,
                new TxOut(next.address(), lovelace(2_000_000), next.datum(), Optional.empty())));
        cases.put(at + "continuation with another index's token", withOutput(before, redeemer, after,
                new TxOut(next.address(), lovelace(2_000_000).merge(token(SHARD_POLICY, new byte[] {SHARD + 1}, 1)),
                        next.datum(), Optional.empty())));
        cases.put(at + "continuation lovelace + 1", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value().merge(lovelace(1)), next.datum(), Optional.empty())));
        cases.put(at + "continuation lovelace - 1", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value().merge(lovelace(-1)), next.datum(), Optional.empty())));
        cases.put(at + "continuation with a reference script", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value(), next.datum(), Optional.of(new ScriptHash(filled(0x5b, 28))))));
        cases.put(at + "continuation datum by hash", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value(), new OutputDatum.OutputDatumHash(
                        new org.julclang.ledger.DatumHash(filled(0x44, 32))), Optional.empty())));
        cases.put(at + "continuation without a datum", withOutput(before, redeemer, after,
                new TxOut(next.address(), next.value(), new OutputDatum.NoOutputDatum(), Optional.empty())));
        cases.put(at + "continuation index changed", withOutput(before, redeemer, after,
                out(next.address(), next.value(), PlutusData.constr(0, PlutusData.integer(1),
                        PlutusData.integer(SHARD + 1), PlutusData.bytes(after)))));
        cases.put(at + "continuation version 2", withOutput(before, redeemer, after,
                out(next.address(), next.value(), PlutusData.constr(0, PlutusData.integer(2),
                        PlutusData.integer(SHARD), PlutusData.bytes(after)))));
        cases.put(at + "continuation datum extra field", withOutput(before, redeemer, after,
                out(next.address(), next.value(), PlutusData.constr(0, PlutusData.integer(1),
                        PlutusData.integer(SHARD), PlutusData.bytes(after), PlutusData.integer(0)))));
        Tx second = spend(before, redeemer, after);
        second.output(out(GatewayFixtures.scriptAddress(SHARD_HASH), lovelace(2_000_000), PlutusData.UNIT));
        cases.put(at + "a second output at the shard", second.spending(shardRef(SHARD)));
        Tx staked = spend(before, redeemer, after);
        staked.output(out(GatewayFixtures.scriptAddressWithStake(SHARD_HASH, filled(0x98, 28)), lovelace(2_000_000),
                PlutusData.UNIT));
        cases.put(at + "a second output with the shard credential", staked.spending(shardRef(SHARD)));
        Tx otherOutput = spend(before, redeemer, after);
        otherOutput.outputs.addFirst(payTo(GatewayFixtures.enterpriseKeyAddress(filled(0xee, 28)),
                lovelace(5_000_000)));
        cases.put(at + "a key output before the continuation", otherOutput.spending(shardRef(SHARD)));

        cases.put(at + "input without the thread token", withInput(before, redeemer, after, lovelace(2_000_000),
                shardDatum(SHARD, before)));
        cases.put(at + "input with two thread tokens", withInput(before, redeemer, after,
                threadValue().merge(token(SHARD_POLICY, new byte[] {SHARD}, 1)), shardDatum(SHARD, before)));
        cases.put(at + "input with a foreign token", withInput(before, redeemer, after,
                threadValue().merge(token(filled(0x7c, 28), new byte[] {2}, 5)), shardDatum(SHARD, before)));
        cases.put(at + "input datum version 2", withInput(before, redeemer, after, threadValue(),
                PlutusData.constr(0, PlutusData.integer(2), PlutusData.integer(SHARD), PlutusData.bytes(before))));
        cases.put(at + "input datum constructor 1", withInput(before, redeemer, after, threadValue(),
                PlutusData.constr(1, PlutusData.integer(1), PlutusData.integer(SHARD), PlutusData.bytes(before))));
        cases.put(at + "input datum extra field", withInput(before, redeemer, after, threadValue(),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(SHARD), PlutusData.bytes(before),
                        PlutusData.integer(0))));
        cases.put(at + "input datum index 16", withInput(before, redeemer, after, threadValue(),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(16), PlutusData.bytes(before))));
        cases.put(at + "input datum index -1", withInput(before, redeemer, after, threadValue(),
                PlutusData.constr(0, PlutusData.integer(1), PlutusData.integer(-1), PlutusData.bytes(before))));
        cases.put(at + "input datum root 31 bytes", withInput(before, redeemer, after, threadValue(),
                shardDatum(SHARD, Arrays.copyOf(before, 31))));
        cases.put(at + "input without a datum", withInput(before, redeemer, after, threadValue(), null));

        cases.put(at + "empty batch", spend(before, PlutusData.constr(0, PlutusData.list()), before)
                .spending(shardRef(SHARD)));
        Tx mint = spend(before, redeemer, after);
        mint.mint = token(SHARD_POLICY, new byte[] {9}, 1);
        cases.put(at + "a mint of the thread policy", mint.spending(shardRef(SHARD)));
        Tx burn = spend(before, redeemer, after);
        burn.mint = token(SHARD_POLICY, new byte[] {SHARD}, -1);
        cases.put(at + "a burn of the thread policy", burn.spending(shardRef(SHARD)));
        Tx otherMint = spend(before, redeemer, after);
        otherMint.mint = token(filled(0x7d, 28), new byte[] {1}, 1);
        cases.put(at + "a mint of another policy", otherMint.spending(shardRef(SHARD)));
        Tx signed = spend(before, redeemer, after).signedByMembers(2);
        signed.reference(exitModeInput(normalMode()));
        cases.put(at + "signers and a reference input", signed.spending(shardRef(SHARD)));
        return cases;
    }

    private static PlutusData withOutput(byte[] before, PlutusData redeemer, byte[] after, TxOut continuation) {
        Tx tx = spend(before, redeemer, after);
        tx.outputs.set(0, continuation);
        return tx.spending(shardRef(SHARD));
    }

    private static PlutusData withInput(byte[] before, PlutusData redeemer, byte[] after, Value value,
                                        PlutusData datum) {
        Tx tx = spend(before, redeemer, after);
        tx.inputs.set(0, new Spent(new TxInInfo(shardRef(SHARD), new TxOut(GatewayFixtures.scriptAddress(SHARD_HASH),
                value, datum == null ? new OutputDatum.NoOutputDatum() : new OutputDatum.OutputDatumInline(datum),
                Optional.empty())), redeemer));
        return tx.spending(shardRef(SHARD));
    }
}
