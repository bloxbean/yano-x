package org.yanoproject.x.composite.bindings;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.AppStateMachine.AdmissionResult;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.x.composite.contracts.BindingCbor;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingIrV1.AdmissionRule;
import org.yanoproject.x.composite.contracts.BindingIrV1.Read;
import org.yanoproject.x.composite.contracts.BindingIrV1.RuleAttachment;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleFailure;
import org.yanoproject.x.composite.contracts.BindingReceiptV1.RuleTrace;
import org.yanoproject.x.composite.contracts.BindingSourceV1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-031.4 Phase 3 engine behaviour: reads through the cascade overlay, the write view and its quantifiers,
 * verified coverage in the fact slot, the deciding write in receipts, budgets, atomicity, no bypass, replay, and
 * ingress details.
 */
class TypedViewsEngineTest {
    private static final byte[] VALUE = {5, 6};
    private static final RuleFact NOTE = new RuleFact("note", RuleFact.Type.TEXT);
    private static final RuleFact LENGTH = new RuleFact("valueLength", RuleFact.Type.INTEGER);
    private static final RuleFact MAX = new RuleFact("max", RuleFact.Type.INTEGER);

    /**
     * A record kernel with typed views: namespace {@code ""} decodes stored records ({@code note},
     * {@code valueLength}); namespace {@code limits} decodes a {@code {max}} map under {@code limit:<key>}. Its write
     * view reads the command's note as {@code OP:key[=max];...}, and coverage comes from the approving facts.
     */
    static class ViewKernel extends CascadeHarness.RecordKernel {
        boolean writeView = true;
        Function<List<Object>, List<Map<String, Object>>> writes = ViewKernel::fromNote;
        Function<List<Object>, List<Map<String, Object>>> coverage = command -> fromNote(command).stream()
                .map(write -> Map.<String, Object>of("coverage", "direct", "actorId", "maker-a",
                        "actorRoles", List.of("operator"))).toList();
        Map<String, Object> decodedOverride;
        /** Extra views appended to the two fixed ones. */
        List<RuleValueView> extraViews = List.of();
        /** Replaces the value-key resolution when set. */
        Function<byte[], byte[]> valueKey;
        RuntimeException fieldsFailure;
        RuntimeException writesFailure;
        boolean nullDecoding;
        int coverageCalls;
        final List<Facts> coverageFacts = new ArrayList<>();

        @Override public List<RuleValueView> ruleValueViews() {
            List<RuleValueView> views = new ArrayList<>(List.of(new RuleValueView("", List.of(NOTE, LENGTH), List.of()),
                    new RuleValueView("limits", List.of(), List.of(MAX))));
            views.addAll(extraViews);
            return views;
        }
        @Override public byte[] ruleValueKey(String namespace, byte[] key) {
            if (valueKey != null) return valueKey.apply(key);
            if (new String(key, StandardCharsets.UTF_8).equals("bad")) throw new IllegalArgumentException("bad key");
            return switch (namespace) {
                case "" -> key.clone();
                case "limits" -> concat("limit:".getBytes(StandardCharsets.UTF_8), key);
                default -> key.clone();
            };
        }
        @Override public Map<String, Object> ruleValueFields(String namespace, byte[] key, byte[] stored) {
            if (fieldsFailure != null) throw fieldsFailure;
            if (nullDecoding) return null;
            if (decodedOverride != null) return decodedOverride;
            Object decoded = BindingCbor.decode(stored, stored.length);
            if (namespace.equals("limits")) return Map.of("value.max", ((Map<?, ?>) decoded).get("max"));
            List<?> command = (List<?>) decoded;
            Map<String, Object> fields = new LinkedHashMap<>();
            if (command.size() > 2 && command.get(2) instanceof String note) fields.put("note", note);
            if (command.get(1) instanceof byte[] value) fields.put("valueLength", (long) value.length);
            return fields;
        }
        @Override public List<RuleFact> ruleWriteFields() {
            return writeView ? List.of(new RuleFact("op", RuleFact.Type.TEXT),
                    new RuleFact("keyText", RuleFact.Type.TEXT)) : List.of();
        }
        @Override public List<RuleFact> ruleWriteCoverageFields() {
            return writeView ? List.of(new RuleFact("coverage", RuleFact.Type.TEXT),
                    new RuleFact("actorId", RuleFact.Type.TEXT), new RuleFact("actorRoles", RuleFact.Type.TEXT_SET))
                    : List.of();
        }
        @Override public List<Map<String, Object>> ruleWrites(List<Object> command) {
            if (writesFailure != null) throw writesFailure;
            return writes.apply(command);
        }
        @Override public List<Map<String, Object>> ruleWriteCoverage(List<Object> command, TransitionContext context,
                                                                    Facts facts) {
            coverageCalls++;
            coverageFacts.add(facts);
            return coverage.apply(command);
        }

        static List<Map<String, Object>> fromNote(List<Object> command) {
            if (command.size() < 3 || !(command.get(2) instanceof String note) || note.isEmpty()) return List.of();
            List<Map<String, Object>> writes = new ArrayList<>();
            for (String item : note.split(";")) {
                Map<String, Object> write = new LinkedHashMap<>();
                String[] parts = item.split(":", 2);
                write.put("op", parts[0]);
                if (parts.length > 1) {
                    String[] keyed = parts[1].split("=", 2);
                    write.put("keyText", keyed[0]);
                    if (keyed.length > 1) write.put("value.max", Long.parseLong(keyed[1]));
                }
                writes.add(write);
            }
            return writes;
        }

        private static byte[] concat(byte[] left, byte[] right) {
            byte[] joined = new byte[left.length + right.length];
            System.arraycopy(left, 0, joined, 0, left.length);
            System.arraycopy(right, 0, joined, left.length, right.length);
            return joined;
        }
    }

    // ---- documents ---------------------------------------------------------------------------------------------

    /** a -> b -> c through {@code recorded.v1}; the b -> c edge maps the note {@code from-b}. */
    private static BindingIrV1 chain(List<AdmissionRule> rules, Map<String, List<RuleAttachment>> attachments,
                                     BindingIrV1.Limits limits) {
        var first = new BindingIrV1.Binding("first", "a", "recorded.v1", List.of(),
                BindingContextAndViewTest.recordTarget("b", new BindingSourceV1.Literal("PUT:from-a")));
        var second = new BindingIrV1.Binding("second", "b", "recorded.v1", List.of(),
                BindingContextAndViewTest.recordTarget("c", new BindingSourceV1.Literal("from-b")));
        return new BindingIrV1(List.of(
                BindingContextAndViewTest.component("a", attachments.getOrDefault("a", List.of())),
                BindingContextAndViewTest.component("b", attachments.getOrDefault("b", List.of())),
                BindingContextAndViewTest.component("c", attachments.getOrDefault("c", List.of()))),
                rules.stream().sorted((x, y) -> x.id().compareTo(y.id())).toList(), List.of(first, second), limits, 1);
    }

    private static BindingIrV1 chain(List<AdmissionRule> rules, Map<String, List<RuleAttachment>> attachments) {
        return chain(rules, attachments, BindingIrV1.Limits.DEFAULT);
    }

    private static AdmissionRule rule(String id, String deny, List<Read> reads, BindingExpressionV1.Node... clauses) {
        return new AdmissionRule(id, deny, null, List.of(), reads, List.of(clauses).stream()
                .map(BindingContextAndViewTest::condition).map(BindingIrV1.Clause.class::cast).toList());
    }

    private static RuleAttachment attach(String rule) { return new RuleAttachment(rule, Map.of()); }

    private static Call eq(BindingExpressionV1.Node left, Object right) {
        return new Call("eq", List.of(left, new Literal(right)));
    }

    private static Quantifier all(BindingExpressionV1.Node body) { return new Quantifier(false, body); }

    private static CascadeHarness harness(BindingIrV1 ir) { return harness(ir, id -> new ViewKernel()); }

    private static CascadeHarness harness(BindingIrV1 ir, Function<String, ViewKernel> kernels) {
        return new CascadeHarness(ir, kernels::apply);
    }

    private static AppMessage recordTo(String component, int identity, String note) {
        return CascadeHarness.message(identity, component + ".v1", CascadeHarness.record(VALUE, note));
    }

    private static ViewKernel kernel(CascadeHarness harness, String component) {
        return (ViewKernel) harness.kernels.get(component);
    }

    private static RuleTrace trace(BindingReceiptV1 receipt) { return receipt.steps().getLast().rules(); }

    // ---- reads ---------------------------------------------------------------------------------------------------

    @Test
    void readsSeeEarlierCascadeWritesAndAnAbsentReadFailsClosedUnlessGuarded() {
        var source = recordTo("a", 1, "fine");
        // The source step's own write (under its message id) is visible to a rule two steps later.
        var prior = new Read("prior", "a", "", new BindingSourceV1.Literal(source.getMessageId()));
        var sees = rule("sees-prior", "PRIOR_NOT_FINE", List.of(prior), new Call("and", List.of(
                Field.read("prior", "present"), eq(Field.read("prior", "note"), "fine"))));
        var accepted = harness(chain(List.of(sees), Map.of("c", List.of(attach("sees-prior")))));
        accepted.apply(1, List.of(source));
        assertThat(accepted.receipt(source).accepted()).isTrue();
        assertThat(trace(accepted.receipt(source))).isEqualTo(new RuleTrace(1, null));

        var missing = new Read("missing", "a", "", new BindingSourceV1.Literal(new byte[]{9}));
        var guarded = rule("guarded", "NEVER", List.of(missing), new Call("or", List.of(
                new Call("not", List.of(Field.read("missing", "present"))), eq(Field.read("missing", "note"), "x"))));
        var unguarded = rule("unguarded", "NEVER", List.of(missing), eq(Field.read("missing", "note"), "x"));
        var failing = harness(chain(List.of(guarded, unguarded), Map.of("a", List.of(attach("guarded"),
                attach("unguarded")))));
        var message = recordTo("a", 2, "n");
        failing.apply(1, List.of(message));
        assertThat(failing.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(trace(failing.receipt(message))).isEqualTo(new RuleTrace(1, new RuleFailure("unguarded", 0,
                null)));
    }

    @Test
    void aGovernedParameterInStateChangesTheLimitWithoutAProfileChange() {
        var limits = new Read("limits", "a", "limits", new BindingSourceV1.Literal("transfer"));
        var limited = new AdmissionRule("governed-limit", "LIMIT_EXCEEDED", "transfer", List.of(), List.of(limits),
                List.of(BindingContextAndViewTest.condition(new Call("le", List.of(new Field(Scope.COMMAND, "amount"),
                        Field.readValue("limits", "max"))))));
        // One component: transfers derive nothing.
        var harness = harness(new BindingIrV1(List.of(BindingContextAndViewTest.component("a",
                List.of(attach("governed-limit")))), List.of(limited), List.of(), BindingIrV1.Limits.DEFAULT, 1));
        byte[] key = "limit:transfer".getBytes(StandardCharsets.UTF_8);
        harness.states.get("a").put(key, BindingCbor.encode(Map.of("max", 10L)));
        var small = CascadeHarness.message(1, "a.v1", CascadeHarness.transfer(10, "m"));
        var large = CascadeHarness.message(2, "a.v1", CascadeHarness.transfer(20, "m"));
        harness.apply(1, List.of(small, large));
        assertThat(harness.receipt(small).accepted()).isTrue();
        assertThat(harness.receipt(large).code()).isEqualTo("ADMISSION_RULE_DENIED");
        // A governed write raises the stored limit; the committed profile is unchanged.
        harness.states.get("a").put(key, BindingCbor.encode(Map.of("max", 30L)));
        var again = CascadeHarness.message(3, "a.v1", CascadeHarness.transfer(20, "m"));
        harness.apply(2, List.of(again));
        assertThat(harness.receipt(again).accepted()).isTrue();
    }

    @Test
    void readKeysAndDecodingsFailClosedBeforeAnyClause() {
        var bad = new Read("limits", "a", "limits", new BindingSourceV1.Literal("bad"));
        var refused = rule("refused-key", "NEVER", List.of(bad), Field.read("limits", "present"));
        var keyed = harness(chain(List.of(refused), Map.of("a", List.of(attach("refused-key")))));
        var message = recordTo("a", 1, "n");
        keyed.apply(1, List.of(message));
        assertThat(keyed.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(trace(keyed.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("refused-key", -1,
                null)));

        var own = recordTo("a", 2, "fine");
        var self = new Read("self", "b", "", new BindingSourceV1.Literal(own.getMessageId()));
        var violating = rule("violating", "NEVER", List.of(self), Field.read("self", "present"));
        var decoded = harness(chain(List.of(violating), Map.of("a", List.of(attach("violating")))), id -> {
            var kernel = new ViewKernel();
            kernel.decodedOverride = Map.of("note", 7L);
            return kernel;
        });
        decoded.states.get("b").put(own.getMessageId(), BindingCbor.encode(List.of(1L, VALUE)));
        decoded.apply(1, List.of(own));
        assertThat(decoded.receipt(own).code()).isEqualTo("ADMISSION_RULE_INPUT");
        assertThat(trace(decoded.receipt(own))).isEqualTo(new RuleTrace(0, new RuleFailure("violating", -1, null)));
    }

    // ---- write view and quantifiers ------------------------------------------------------------------------------

    @Test
    void quantifiersStopInIndexOrderAndReceiptsNameTheDecidingWrite() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var noRevoke = rule("no-revoke", "REVOKED", List.of(), new Call("not", List.of(new Quantifier(true,
                eq(Field.element("op"), "REVOKE")))));
        var harness = harness(chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts")))));
        var mixed = recordTo("a", 1, "PUT:x;PUT_IF_ABSENT:y;PUT:z");
        harness.apply(1, List.of(mixed));
        assertThat(trace(harness.receipt(mixed))).isEqualTo(new RuleTrace(0, new RuleFailure("only-puts", 0,
                "NOT_PUT", 1)));

        var revoking = harness(chain(List.of(noRevoke), Map.of("a", List.of(attach("no-revoke")))));
        var message = recordTo("a", 2, "PUT:x;PUT:y;REVOKE:z;REVOKE:w");
        revoking.apply(1, List.of(message));
        assertThat(trace(revoking.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("no-revoke", 0,
                "REVOKED", 2)));

        // An element that raises an error before a false one decides the clause: the error, at its index.
        var bounded = rule("bounded", "OVER", List.of(), all(new Call("lt", List.of(Field.elementValue("max"),
                new Literal(5L)))));
        var erroring = harness(chain(List.of(bounded), Map.of("a", List.of(attach("bounded")))));
        var unset = recordTo("a", 3, "PUT:a;PUT:b=9");
        erroring.apply(1, List.of(unset));
        assertThat(erroring.receipt(unset).code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(trace(erroring.receipt(unset))).isEqualTo(new RuleTrace(0, new RuleFailure("bounded", 0, null,
                0)));
    }

    @Test
    void quantifierWorkIsChargedPerVisitedElementOnBothCounters() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var ir = chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts"))));
        long early = work(ir, "REVOKE:a;PUT:b;PUT:c;PUT:d");
        long late = work(ir, "PUT:a;PUT:b;PUT:c;REVOKE:d");
        // The same batch size costs more when the deciding element comes later.
        assertThat(late).isGreaterThan(early);
    }

    private static long work(BindingIrV1 ir, String note) {
        var harness = harness(ir);
        harness.apply(1, List.of(recordTo("a", 1, note)));
        return harness.evaluationWork();
    }

    @Test
    void quantifierWorkIsLinearInTheElementsVisited() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var ir = chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts"))));
        // The same writes and command bytes; only the position of the deciding element moves.
        long first = work(ir, "REVOKE:a;PUT:b;PUT:c;PUT:d");
        long second = work(ir, "PUT:a;REVOKE:b;PUT:c;PUT:d");
        long third = work(ir, "PUT:a;PUT:b;REVOKE:c;PUT:d");
        assertThat(second - first).isPositive().isEqualTo(third - second);
    }

    @Test
    void aReadChargesTheStoredValueAndItsDecodingOnBothCounters() {
        var target = new Read("target", "b", "", new BindingSourceV1.Literal("k"));
        var guarded = rule("guarded", "NEVER", List.of(target), new Call("or", List.of(
                new Call("not", List.of(Field.read("target", "present"))), eq(Field.read("target", "note"), "fine"))));
        var ir = chain(List.of(guarded), Map.of("a", List.of(attach("guarded"))));
        byte[] stored = BindingCbor.encode(List.of(1L, VALUE, "fine"));
        var absent = harness(ir);
        var message = recordTo("a", 1, "n");
        absent.apply(1, List.of(message));
        var present = harness(ir);
        present.states.get("b").put("k".getBytes(StandardCharsets.UTF_8), stored);
        present.apply(1, List.of(message));
        assertThat(absent.receipt(message).accepted()).isTrue();
        assertThat(present.receipt(message).accepted()).isTrue();
        // Present: + stored bytes + the decoded fields' encoding, then one more clause operand.
        long decoded = BindingWork.encoding(Map.of("note", "fine", "valueLength", (long) VALUE.length));
        assertThat(present.evaluationWork() - absent.evaluationWork()).isBetween(stored.length + decoded,
                stored.length + decoded + 32);
        // The per-cascade budget is charged too: room for the whole cascade, but not for reading a large record.
        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536,
                (int) absent.evaluationWork() + 100, 1_000_000, 4);
        var exhausted = harness(chain(List.of(guarded), Map.of("a", List.of(attach("guarded"))), tight));
        exhausted.states.get("b").put("k".getBytes(StandardCharsets.UTF_8), BindingCbor.encode(List.of(1L,
                new byte[1_000], "fine")));
        exhausted.apply(1, List.of(message));
        assertThat(exhausted.receipt(message).code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
        assertThat(trace(exhausted.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("guarded", -1,
                null)));
        assertThat(exhausted.states.get("a").values).isEmpty();
    }

    @Test
    void aFullBatchIsWithinBudgetWhileOversizedViewsAndExhaustedWorkFailClosed() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        StringBuilder batch = new StringBuilder();
        for (int index = 0; index < RuleValueView.MAX_WRITES; index++) {
            if (index > 0) batch.append(';');
            batch.append("PUT:k").append(index);
        }
        var full = harness(chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts")))));
        var message = recordTo("a", 1, batch.toString());
        full.apply(1, List.of(message));
        assertThat(full.receipt(message).accepted()).isTrue();

        var oversized = harness(chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts")))));
        var extra = recordTo("a", 2, batch + ";PUT:one-more");
        oversized.apply(1, List.of(extra));
        assertThat(oversized.receipt(extra).code()).isEqualTo("ADMISSION_RULE_INPUT");
        assertThat(trace(oversized.receipt(extra))).isEqualTo(new RuleTrace(0, new RuleFailure("only-puts", -1,
                null)));

        // Enough for the command's mandatory work, not for materializing and checking 128 writes.
        var tight = new BindingIrV1.Limits(8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 3_000, 1_000_000, 4);
        var exhausted = harness(chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts"))), tight));
        var costly = recordTo("a", 3, batch.toString());
        exhausted.apply(1, List.of(costly));
        assertThat(exhausted.receipt(costly).code()).isEqualTo("EXPRESSION_CAPACITY_EXCEEDED");
    }

    @Test
    void derivedCommandsCannotBypassAWriteRuleAtDepthTwo() {
        var noFromB = rule("no-from-b", "FROM_B", List.of(), all(new Call("ne", List.of(Field.element("op"),
                new Literal("from-b")))));
        var ir = chain(List.of(noFromB), Map.of("c", List.of(attach("no-from-b"))));
        var derived = harness(ir);
        var source = recordTo("a", 1, "fine");
        derived.apply(1, List.of(source));
        var receipt = derived.receipt(source);
        assertThat(receipt.failedStepOrdinal()).isEqualTo(2);
        assertThat(trace(receipt)).isEqualTo(new RuleTrace(0, new RuleFailure("no-from-b", 0, "FROM_B", 0)));
        // Nothing from the cascade committed: no step's record exists.
        assertThat(derived.states.values()).allSatisfy(state -> assertThat(state.values).isEmpty());

        var direct = harness(ir);
        var submitted = recordTo("c", 2, "from-b");
        direct.apply(1, List.of(submitted));
        assertThat(trace(direct.receipt(submitted))).isEqualTo(new RuleTrace(0, new RuleFailure("no-from-b", 0,
                "FROM_B", 0)));
    }

    @Test
    void invalidWriteViewsAreInputFailures() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var ir = chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts"))));
        List<Function<List<Object>, List<Map<String, Object>>>> invalid = List.of(
                command -> List.of(Map.of("op", "PUT", "owner", "x")),
                command -> List.of(Map.of("op", 1L)),
                command -> List.of(Map.of("op", "PUT", "index", 0L)),
                command -> null);
        int identity = 1;
        for (var writes : invalid) {
            var harness = harness(ir, id -> {
                var kernel = new ViewKernel();
                kernel.writes = writes;
                return kernel;
            });
            var message = recordTo("a", identity++, "PUT:x");
            harness.apply(1, List.of(message));
            assertThat(harness.receipt(message).code()).isEqualTo("ADMISSION_RULE_INPUT");
            assertThat(trace(harness.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("only-puts", -1,
                    null)));
        }
    }

    @Test
    void oversizedElementsForeignKeysAndMalformedDecodingsAreInputFailures() {
        // Two views of 32 distinct value fields each declare 66 write names with op and keyText.
        List<RuleFact> left = new ArrayList<>();
        List<RuleFact> right = new ArrayList<>();
        for (int index = 0; index < RuleValueView.MAX_FIELDS; index++) {
            left.add(new RuleFact(String.format("l%02d", index), RuleFact.Type.INTEGER));
            right.add(new RuleFact(String.format("r%02d", index), RuleFact.Type.INTEGER));
        }
        var views = List.of(new RuleValueView("left", List.of(), left), new RuleValueView("right", List.of(), right));
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var ir = chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts"))));
        Map<String, Object> full = new LinkedHashMap<>(Map.of("op", "PUT", "keyText", "k"));
        for (int index = 0; full.size() < 2 * RuleValueView.MAX_FIELDS; index++) {
            full.put("value." + (index < 32 ? left : right).get(index % 32).name(), 1L);
        }
        Map<String, Object> over = new LinkedHashMap<>(full);
        over.put("value.r31", 1L);
        Map<Object, Object> foreign = new TreeMap<>(Map.of(1, "PUT"));
        List<Function<List<Object>, List<Map<String, Object>>>> writes = List.of(command -> List.of(full),
                command -> List.of(over), command -> castElements(List.of(foreign)),
                command -> castElements(Arrays.asList("PUT", null)));
        List<String> codes = new ArrayList<>();
        int identity = 1;
        for (var view : writes) {
            var harness = harness(ir, id -> {
                var kernel = new ViewKernel();
                kernel.extraViews = views;
                kernel.writes = view;
                return kernel;
            });
            var message = recordTo("a", identity++, "PUT:x");
            harness.apply(1, List.of(message));
            codes.add(harness.receipt(message).accepted() ? "ACCEPTED" : harness.receipt(message).code());
        }
        assertThat(codes).containsExactly("ACCEPTED", "ADMISSION_RULE_INPUT", "ADMISSION_RULE_INPUT",
                "ADMISSION_RULE_INPUT");

        // Decoded reads: too many entries, malformed text, and no decoding at all are declaration violations.
        var target = new Read("target", "b", "", new BindingSourceV1.Literal("k"));
        var reading = rule("reading", "NEVER", List.of(target), Field.read("target", "present"));
        Map<String, Object> many = new LinkedHashMap<>();
        for (int index = 0; index <= 2 * RuleValueView.MAX_FIELDS; index++) many.put("note" + index, "x");
        List<Map<String, Object>> decodings = new ArrayList<>(List.of(many, Map.of("note", "\ud800")));
        decodings.add(null);
        for (var decoding : decodings) {
            var harness = harness(chain(List.of(reading), Map.of("a", List.of(attach("reading")))), id -> {
                var kernel = new ViewKernel();
                kernel.decodedOverride = decoding;
                kernel.nullDecoding = decoding == null;
                return kernel;
            });
            harness.states.get("b").put("k".getBytes(StandardCharsets.UTF_8), BindingCbor.encode(List.of(1L, VALUE)));
            var message = recordTo("a", identity++, "n");
            harness.apply(1, List.of(message));
            assertThat(harness.receipt(message).code()).isEqualTo("ADMISSION_RULE_INPUT");
            assertThat(trace(harness.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("reading", -1,
                    null)));
            assertThat(harness.states.get("a").values).isEmpty();
        }
    }

    @Test
    void emptyValueKeysAreErrorsAndOtherKernelExceptionsPropagate() {
        var target = new Read("target", "b", "", new BindingSourceV1.Literal("k"));
        var reading = rule("reading", "NEVER", List.of(target), Field.read("target", "present"));
        var ir = chain(List.of(reading), Map.of("a", List.of(attach("reading"))));
        int identity = 1;
        for (Function<byte[], byte[]> key : List.<Function<byte[], byte[]>>of(ignored -> null,
                ignored -> new byte[0])) {
            var harness = harness(ir, id -> {
                var kernel = new ViewKernel();
                kernel.valueKey = key;
                return kernel;
            });
            var message = recordTo("a", identity++, "n");
            harness.apply(1, List.of(message));
            assertThat(harness.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR");
            assertThat(trace(harness.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("reading", -1,
                    null)));
        }
        var failing = harness(ir, id -> {
            var kernel = new ViewKernel();
            kernel.fieldsFailure = new IllegalStateException("kernel bug");
            return kernel;
        });
        failing.states.get("b").put("k".getBytes(StandardCharsets.UTF_8), BindingCbor.encode(List.of(1L, VALUE)));
        assertThatThrownBy(() -> failing.apply(1, List.of(recordTo("a", 10, "n"))))
                .satisfies(thrown -> assertThat(root(thrown)).isInstanceOf(IllegalStateException.class)
                        .hasMessage("kernel bug"));

        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var writing = harness(chain(List.of(onlyPuts), Map.of("a", List.of(attach("only-puts")))), id -> {
            var kernel = new ViewKernel();
            kernel.writesFailure = new IllegalArgumentException("kernel bug");
            return kernel;
        });
        assertThatThrownBy(() -> writing.apply(1, List.of(recordTo("a", 11, "PUT:x"))))
                .satisfies(thrown -> assertThat(root(thrown)).isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("kernel bug"));
    }

    private static Throwable root(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        return root;
    }

    @Test
    void factRulesMayKeyReadsByVerifiedFacts() {
        var target = new Read("target", "b", "", new BindingSourceV1.Field(Scope.FACTS, "target"));
        var matches = rule("matches", "MISMATCH", List.of(target), new Call("and", List.of(
                Field.read("target", "present"), eq(Field.read("target", "note"), "fine"))));
        var ir = chain(List.of(matches), Map.of("a", List.of(attach("matches"))));
        List<String> codes = new ArrayList<>();
        for (String note : List.of("fine", "other")) {
            var harness = harness(ir, id -> {
                var kernel = new ViewKernel();
                kernel.declared = List.of(new RuleFact("target", RuleFact.Type.TEXT));
                kernel.factValues = command -> Map.of("target", "t1");
                return kernel;
            });
            harness.states.get("b").put("t1".getBytes(StandardCharsets.UTF_8), BindingCbor.encode(List.of(1L, VALUE,
                    note)));
            var message = recordTo("a", 1, "n");
            harness.apply(1, List.of(message));
            codes.add(harness.receipt(message).accepted() ? "ACCEPTED" : harness.receipt(message).code());
            assertThat(harness.program.rules().component("a").facts()).extracting(rule -> rule.rule().id())
                    .containsExactly("matches");
        }
        assertThat(codes).containsExactly("ACCEPTED", "ADMISSION_RULE_DENIED");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castElements(List<?> elements) {
        return (List<Map<String, Object>>) (List<?>) elements;
    }

    // ---- coverage ------------------------------------------------------------------------------------------------

    @Test
    void coverageRulesRunInTheFactSlotWithTheApprovingFactsAndFailClosedWithoutCoverage() {
        var operatorOnly = rule("operator-only", "ROLE_REQUIRED", List.of(), all(new Call("and", List.of(
                eq(Field.element("coverage"), "direct"), new Call("in", List.of(new Literal("operator"),
                        Field.element("actorRoles")))))));
        var ir = chain(List.of(operatorOnly), Map.of("a", List.of(attach("operator-only"))));
        var rejected = harness(ir, id -> {
            var kernel = new ViewKernel();
            kernel.rejectCode = "KERNEL_SAYS_NO";
            return kernel;
        });
        var message = recordTo("a", 1, "PUT:x;PUT:y");
        rejected.apply(1, List.of(message));
        assertThat(rejected.receipt(message).code()).isEqualTo("KERNEL_SAYS_NO");
        assertThat(kernel(rejected, "a").coverageCalls).isZero();

        var approved = harness(ir);
        approved.apply(1, List.of(message));
        assertThat(approved.receipt(message).accepted()).isTrue();
        var kernel = kernel(approved, "a");
        assertThat(kernel.coverageCalls).isEqualTo(1);
        assertThat(kernel.coverageFacts.getFirst()).isSameAs(kernel.factsProduced.getFirst());

        // A replay establishes no coverage: the element has no coverage fields, so the rule fails closed.
        var replay = harness(ir, id -> {
            var view = new ViewKernel();
            view.coverage = command -> fromNoteEmpty(command);
            return view;
        });
        replay.apply(1, List.of(message));
        assertThat(replay.receipt(message).code()).isEqualTo("ADMISSION_RULE_ERROR");
        assertThat(trace(replay.receipt(message))).isEqualTo(new RuleTrace(0, new RuleFailure("operator-only", 0,
                null, 0)));

        // Coverage that does not describe exactly the writes is a declaration violation.
        var short1 = harness(ir, id -> {
            var view = new ViewKernel();
            view.coverage = command -> List.of();
            return view;
        });
        short1.apply(1, List.of(message));
        assertThat(short1.receipt(message).code()).isEqualTo("ADMISSION_RULE_INPUT");
    }

    private static List<Map<String, Object>> fromNoteEmpty(List<Object> command) {
        return ViewKernel.fromNote(command).stream().map(write -> Map.<String, Object>of()).toList();
    }

    // ---- ingress and construction --------------------------------------------------------------------------------

    @Test
    void ingressRefusesStaticWriteRulesWithStructuredDetailsAndNeverRunsReadsOrCoverage() {
        var onlyPuts = rule("only-puts", "NOT_PUT", List.of(), all(eq(Field.element("op"), "PUT")));
        var missing = new Read("missing", "a", "", new BindingSourceV1.Literal(new byte[]{9}));
        var reading = rule("reading", "NEVER", List.of(missing), Field.read("missing", "present"));
        var covered = rule("covered", "NEVER", List.of(), all(eq(Field.element("coverage"), "approval")));
        var ir = chain(List.of(covered, onlyPuts, reading), Map.of("a", List.of(attach("only-puts"),
                attach("reading"), attach("covered"))));
        var harness = harness(ir);
        AdmissionResult refused = harness.engine.validate(recordTo("a", 1, "PUT:x;REVOKE:y"));
        assertThat(refused.isAccepted()).isFalse();
        assertThat(refused.reason()).isEqualTo("ADMISSION_RULE_DENIED");
        assertThat(refused.details()).containsExactly(Map.entry("rule", "only-puts"), Map.entry("deny", "NOT_PUT"),
                Map.entry("write", 1L));
        // The read and coverage rules would deny at block time; ingress never runs them.
        assertThat(harness.engine.validate(recordTo("a", 2, "PUT:x")).isAccepted()).isTrue();
        assertThat(kernel(harness, "a").coverageCalls).isZero();
    }

    @Test
    void constructionTypeChecksReadsAndWriteViews() {
        var unknownComponent = rule("r", "D", List.of(new Read("x", "zz", "", new BindingSourceV1.Literal("k"))),
                Field.read("x", "present"));
        var unknownNamespace = rule("r", "D", List.of(new Read("x", "a", "other", new BindingSourceV1.Literal("k"))),
                Field.read("x", "present"));
        var unknownField = rule("r", "D", List.of(new Read("x", "a", "", new BindingSourceV1.Literal("k"))),
                eq(Field.read("x", "owner"), "o"));
        var badKey = rule("r", "D", List.of(new Read("x", "a", "", new BindingSourceV1.Literal(7L))),
                Field.read("x", "present"));
        var unknownElement = rule("r", "D", List.of(), all(eq(Field.element("owner"), "o")));
        for (var entry : Map.of(unknownComponent, "RULE_READ_UNKNOWN_COMPONENT",
                unknownNamespace, "RULE_READ_UNKNOWN_NAMESPACE", unknownField, "RULE_READ_UNKNOWN_FIELD",
                badKey, "RULE_READ_KEY_TYPE", unknownElement, "RULE_FIELD_UNKNOWN").entrySet()) {
            assertThatThrownBy(() -> harness(chain(List.of(entry.getKey()), Map.of("a", List.of(attach("r"))))))
                    .as(entry.getValue()).isInstanceOfSatisfying(BindingValidationException.class,
                            invalid -> assertThat(invalid.code()).isEqualTo(entry.getValue()));
        }
        var writes = rule("r", "D", List.of(), all(eq(Field.element("op"), "PUT")));
        assertThatThrownBy(() -> harness(chain(List.of(writes), Map.of("a", List.of(attach("r")))), id -> {
            var kernel = new ViewKernel();
            kernel.writeView = false;
            return kernel;
        })).isInstanceOfSatisfying(BindingValidationException.class,
                invalid -> assertThat(invalid.code()).isEqualTo("RULE_WRITES_UNSUPPORTED"));
    }

    @Test
    void coverageMakesARuleAFactRuleAndReadsMakeItNonStatic() {
        var content = rule("content", "D", List.of(), all(eq(Field.element("op"), "PUT")));
        var covered = rule("covered", "D", List.of(), all(eq(Field.element("coverage"), "direct")));
        var reading = rule("reading", "D", List.of(new Read("x", "a", "", new BindingSourceV1.Literal("k"))),
                new Call("not", List.of(Field.read("x", "present"))));
        var harness = harness(chain(List.of(content, covered, reading), Map.of("a", List.of(attach("covered"),
                attach("reading"), attach("content")))));
        var rules = harness.program.rules().component("a");
        assertThat(rules.admission()).extracting(attached -> attached.rule().id()).containsExactly("reading",
                "content");
        assertThat(rules.facts()).extracting(attached -> attached.rule().id()).containsExactly("covered");
        assertThat(rules.admission()).extracting(BindingRules.Attached::isStatic).containsExactly(false, true);
        assertThat(rules.facts().getFirst().isStatic()).isFalse();
        // The manifest's slot and static-ness come from this resolution, not from the IR alone.
        assertThat(harness.engine.ruleAttachmentSlots()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "a/0", List.of("fact", "false"), "a/1", List.of("admission", "false"),
                "a/2", List.of("admission", "true")));
        assertThat(covered.readsFacts()).isFalse();
    }
}
