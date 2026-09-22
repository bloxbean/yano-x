package org.yanoproject.x.composite.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Closed expression tree emitted by the restricted CEL compiler, never an executable Java callback.
 * The wire envelope is {@code [1, resultType, root]}; type ordinals and node tags are consensus constants.
 * Node tags are literal=0, event-field=1, call=2. Construction enforces implementation-wide structural
 * maxima; the runtime also checks the tighter limits selected by the enclosing binding profile.
 *
 * @param resultType declared scalar result, checked against every branch during program validation
 * @param root immutable expression tree; no variables other than declared event fields are supported
 */
public record BindingExpressionV1(Type resultType, Node root) {
    public static final String DIALECT = "yano-x-cel-v1";
    /** Frozen wire ordinals: signed int64=0, UTF-8 text=1, opaque bytes=2, boolean=3. Do not reorder. */
    public enum Type { INTEGER, TEXT, BYTES, BOOLEAN }
    /** Data-only expression node; execution semantics belong to the versioned runtime interpreter. */
    public sealed interface Node permits Literal, Field, Call { Object wire(); }
    /** A non-null supported scalar; byte-array inputs and accessor results are defensively copied. */
    public record Literal(Object value) implements Node {
        public Literal {
            requireScalar(value);
            if (value instanceof byte[] bytes) value = bytes.clone();
        }
        @Override public Object value() { return value instanceof byte[] bytes ? bytes.clone() : value; }
        @Override public Object wire() { return List.of(0, value()); }
    }
    /** A top-level event-field reference; no reflection, arbitrary property traversal, or Java objects. */
    public record Field(String name) implements Node {
        public Field { requireName(name); }
        @Override public Object wire() { return List.of(1, name); }
    }
    /** An allowlisted fixed-arity operator whose argument order is semantically significant. */
    public record Call(String operator, List<Node> arguments) implements Node {
        public Call {
            if (!List.of("eq", "ne", "lt", "le", "gt", "ge", "and", "or", "not", "if",
                    "add", "sub", "mul", "div", "mod", "neg", "concat").contains(operator)) {
                throw new IllegalArgumentException("unsupported expression operator");
            }
            arguments = List.copyOf(arguments);
            int arity = switch (operator) { case "not", "neg" -> 1; case "if" -> 3; default -> 2; };
            if (arguments.size() != arity) throw new IllegalArgumentException("incorrect expression arity");
        }
        @Override public Object wire() {
            return List.of(2, operator, arguments.stream().map(Node::wire).toList());
        }
    }

    public BindingExpressionV1 {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(root, "root");
        if (count(root, 0) > 512) throw new IllegalArgumentException("expression exceeds node limit");
    }
    /** Returns the data-only canonical-encoder input; callers serialize it with {@link BindingCbor}. */
    public Object wire() { return List.of(1, resultType.ordinal(), root.wire()); }
    /**
     * Decodes a supported expression envelope; static field/operator type validation follows at program construction.
     */
    public static BindingExpressionV1 fromWire(Object value) {
        List<?> fields = BindingCbor.array(value, 3);
        if (BindingCbor.integer(fields.get(0)) != 1) throw new IllegalArgumentException("expression version");
        int type = Math.toIntExact(BindingCbor.integer(fields.get(1)));
        if (type < 0 || type >= Type.values().length) throw new IllegalArgumentException("expression type");
        return new BindingExpressionV1(Type.values()[type], node(fields.get(2), 0));
    }
    private static Node node(Object value, int depth) {
        if (depth > 32 || !(value instanceof List<?> fields) || fields.size() < 2) {
            throw new IllegalArgumentException("invalid expression node");
        }
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> new Literal(BindingCbor.array(fields, 2).get(1));
            case 1 -> new Field(BindingCbor.text(BindingCbor.array(fields, 2).get(1)));
            case 2 -> {
                BindingCbor.array(fields, 3);
                if (!(fields.get(2) instanceof List<?> arguments)) throw new IllegalArgumentException("arguments");
                yield new Call(BindingCbor.text(fields.get(1)), arguments.stream()
                        .map(argument -> node(argument, depth + 1)).toList());
            }
            default -> throw new IllegalArgumentException("unknown expression node");
        };
    }
    private static int count(Node node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("expression exceeds depth limit");
        int total = 1;
        if (node instanceof Call call) {
            for (Node argument : call.arguments()) {
                total += count(argument, depth + 1);
                if (total > 512) return total;
            }
        }
        return total;
    }
    static void requireName(String name) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,126}")) {
            throw new IllegalArgumentException("invalid binding field name");
        }
    }
    static void requireScalar(Object value) {
        if (!(value instanceof Long || value instanceof String || value instanceof byte[]
                || value instanceof Boolean)) {
            throw new IllegalArgumentException("expected scalar");
        }
        if (value instanceof byte[] bytes && bytes.length > 65_536
                || value instanceof String text && text.length() > 65_536) {
            throw new IllegalArgumentException("scalar exceeds byte limit");
        }
    }
}
