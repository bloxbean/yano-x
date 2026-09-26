package org.yanoproject.x.composite.bindings;

import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Consensus interpreter for the canonical restricted-CEL intermediate representation.
 *
 * <p>This is not a general CEL runtime: it accepts only the versioned operators and scalar types in
 * {@link BindingExpressionV1}. Arithmetic is checked signed 64-bit arithmetic, conditional branches are
 * lazy, and boolean operators implement the dialect's error-masking rules. Resource exhaustion is never
 * masked. Evaluation uses deterministic work counters, not elapsed time, and cannot invoke host callbacks.
 */
public final class BindingExpressionEvaluator {
    private BindingExpressionEvaluator() { }

    /**
     * Mutable, single-execution work counter. A cascade owns one counter and shares a second counter with
     * other cascades in its block. Failed evaluations do not refund work; do not reuse a counter across blocks.
     */
    public static final class Budget {
        private final long maximum;
        private long used;
        public Budget(long maximum) {
            if (maximum < 0) throw new IllegalArgumentException("negative expression budget");
            this.maximum = maximum;
        }
        public long used() { return used; }
        /**
         * Charges work without overflowing the counter, saturating it when the request cannot be satisfied.
         *
         * @throws BindingFailure with {@code EXPRESSION_CAPACITY_EXCEEDED} when the charge is invalid or too large
         */
        public void charge(long work) {
            if (work < 0 || work > maximum - used) {
                used = maximum;
                throw new BindingFailure("EXPRESSION_CAPACITY_EXCEEDED");
            }
            used += work;
        }
    }

    /**
     * Checks every branch's types and structure, including branches that might never execute.
     *
     * @param fields declared event-field types; runtime presence is checked separately
     * @throws IllegalArgumentException if any type, field, literal, depth, or node-count constraint is invalid
     */
    public static void validate(BindingExpressionV1 expression, Map<String, Type> fields, Limits limits) {
        int[] count = {0};
        Type result = validate(expression.root(), fields, limits, 1, count);
        if (result != expression.resultType()) throw new IllegalArgumentException("expression result type mismatch");
    }

    private static Type validate(Node node, Map<String, Type> fields, Limits limits, int depth, int[] count) {
        if (++count[0] > limits.maxExpressionNodes() || depth > limits.maxExpressionDepth()) {
            throw new IllegalArgumentException("expression structure exceeds profile limits");
        }
        if (node instanceof Literal literal) {
            if (size(literal.value()) > limits.maxExpressionValueBytes()) {
                throw new IllegalArgumentException("expression literal exceeds value limit");
            }
            return type(literal.value());
        }
        if (node instanceof Field field) {
            Type type = fields.get(field.name());
            if (type == null) throw new IllegalArgumentException("unknown expression field: " + field.name());
            return type;
        }
        Call call = (Call) node;
        List<Type> args = call.arguments().stream().map(arg -> validate(arg, fields, limits, depth + 1,
                count)).toList();
        Type first = args.getFirst();
        return switch (call.operator()) {
            case "eq", "ne" -> { same(args); yield Type.BOOLEAN; }
            case "lt", "le", "gt", "ge" -> { require(args, Type.INTEGER); yield Type.BOOLEAN; }
            case "and", "or", "not" -> { require(args, Type.BOOLEAN); yield Type.BOOLEAN; }
            case "if" -> {
                if (first != Type.BOOLEAN || args.get(1) != args.get(2)) throw invalidType();
                yield args.get(1);
            }
            case "concat" -> {
                same(args);
                if (first != Type.TEXT && first != Type.BYTES) throw invalidType();
                yield first;
            }
            default -> { require(args, Type.INTEGER); yield Type.INTEGER; }
        };
    }

    /**
     * Evaluates an already validated expression while charging both caller-owned budgets.
     * Each visited node costs one work unit; byte-sensitive comparisons and concatenations additionally
     * charge for their operands. Only the selected conditional branch is visited.
     *
     * @return a {@link Long}, {@link String}, {@code byte[]}, or {@link Boolean} matching the declared result type
     * @throws BindingFailure for missing data, arithmetic errors, type errors, or exhausted limits
     */
    public static Object evaluate(BindingExpressionV1 expression, Map<String, Object> event,
                                  Limits limits, Budget cascade, Budget block) {
        Object result = evaluate(expression.root(), event, limits, cascade, block);
        if (type(result) != expression.resultType()) throw new BindingFailure("EXPRESSION_TYPE_ERROR");
        return result;
    }

    private static Object evaluate(Node node, Map<String, Object> event, Limits limits, Budget cascade, Budget block) {
        charge(1, cascade, block);
        if (node instanceof Literal literal) return bounded(literal.value(), limits);
        if (node instanceof Field field) {
            if (!event.containsKey(field.name())) throw new BindingFailure("EXPRESSION_MISSING_FIELD");
            return bounded(event.get(field.name()), limits);
        }
        Call call = (Call) node;
        List<Node> args = call.arguments();
        String op = call.operator();
        if (op.equals("and") || op.equals("or")) {
            boolean decisive = op.equals("or");
            BindingFailure error = null;
            Object left = null;
            try { left = evaluate(args.get(0), event, limits, cascade, block); }
            catch (BindingFailure failure) {
                if (failure.code().equals("EXPRESSION_CAPACITY_EXCEEDED")) throw failure;
                error = failure;
            }
            if (Boolean.valueOf(decisive).equals(left)) return decisive;
            Object right;
            try { right = evaluate(args.get(1), event, limits, cascade, block); }
            catch (BindingFailure failure) {
                if (failure.code().equals("EXPRESSION_CAPACITY_EXCEEDED") || error == null) throw failure;
                throw error;
            }
            if (Boolean.valueOf(decisive).equals(right)) return decisive;
            if (error != null) throw error;
            if (!(left instanceof Boolean) || !(right instanceof Boolean))
                    throw new BindingFailure("EXPRESSION_TYPE_ERROR");
            return !decisive;
        }
        Object a = evaluate(args.getFirst(), event, limits, cascade, block);
        if (op.equals("if")) {
            if (!(a instanceof Boolean condition)) throw new BindingFailure("EXPRESSION_TYPE_ERROR");
            return evaluate(args.get(condition ? 1 : 2), event, limits, cascade, block);
        }
        try {
            if (op.equals("not")) return !(Boolean) a;
            if (op.equals("neg")) return Math.negateExact((Long) a);
            Object b = evaluate(args.get(1), event, limits, cascade, block);
            if (op.equals("eq") || op.equals("ne")) {
                if (type(a) != type(b)) throw new BindingFailure("EXPRESSION_TYPE_ERROR");
                if (a instanceof byte[] || a instanceof String) charge((long) size(a) + size(b), cascade, block);
                boolean equal = a instanceof byte[] bytes ? Arrays.equals(bytes, (byte[]) b) : a.equals(b);
                return op.equals("eq") == equal;
            }
            if (op.equals("concat")) {
                long length = (long) size(a) + size(b);
                charge(length, cascade, block);
                if (length > limits.maxExpressionValueBytes()) throw new BindingFailure("EXPRESSION_VALUE_LIMIT");
                if (a instanceof String text && b instanceof String other) return text + other;
                if (a instanceof byte[] bytes && b instanceof byte[] other) {
                    byte[] result = Arrays.copyOf(bytes, (int) length);
                    System.arraycopy(other, 0, result, bytes.length, other.length);
                    return result;
                }
                throw new BindingFailure("EXPRESSION_TYPE_ERROR");
            }
            long left = (Long) a;
            long right = (Long) b;
            return switch (op) {
                case "add" -> Math.addExact(left, right);
                case "sub" -> Math.subtractExact(left, right);
                case "mul" -> Math.multiplyExact(left, right);
                case "div", "mod" -> {
                    if (right == 0) throw new BindingFailure("EXPRESSION_DIVISION_BY_ZERO");
                    if (op.equals("div") && left == Long.MIN_VALUE && right == -1) {
                        throw new BindingFailure("EXPRESSION_OVERFLOW");
                    }
                    yield op.equals("div") ? left / right : left % right;
                }
                case "lt" -> left < right;
                case "le" -> left <= right;
                case "gt" -> left > right;
                case "ge" -> left >= right;
                default -> throw new IllegalStateException("unvalidated expression operator");
            };
        } catch (ArithmeticException overflow) {
            throw new BindingFailure("EXPRESSION_OVERFLOW");
        } catch (ClassCastException invalid) {
            throw new BindingFailure("EXPRESSION_TYPE_ERROR");
        }
    }

    private static void charge(long work, Budget cascade, Budget block) {
        BindingWork.charge(work, cascade, block);
    }
    private static Object bounded(Object value, Limits limits) {
        type(value);
        if (size(value) > limits.maxExpressionValueBytes()) throw new BindingFailure("EXPRESSION_VALUE_LIMIT");
        return value instanceof byte[] bytes ? bytes.clone() : value;
    }
    public static Type type(Object value) {
        if (value instanceof Long) return Type.INTEGER;
        if (value instanceof Boolean) return Type.BOOLEAN;
        if (value instanceof String) return Type.TEXT;
        if (value instanceof byte[]) return Type.BYTES;
        throw new BindingFailure("EXPRESSION_TYPE_ERROR");
    }
    private static int size(Object value) {
        return value instanceof byte[] || value instanceof String ? Math.toIntExact(BindingWork.size(value)) : 0;
    }
    private static void same(List<Type> types) {
        if (types.stream().distinct().count() != 1) throw invalidType();
    }
    private static void require(List<Type> types, Type required) {
        if (types.stream().anyMatch(type -> type != required)) throw invalidType();
    }
    private static IllegalArgumentException invalidType() {
        return new IllegalArgumentException("expression operand type mismatch");
    }
}
