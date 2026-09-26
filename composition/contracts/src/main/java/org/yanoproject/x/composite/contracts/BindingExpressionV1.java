package org.yanoproject.x.composite.contracts;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Closed expression tree emitted by the restricted CEL compiler, never an executable Java callback.
 * The wire envelope is {@code [1, resultType, root]}; type ordinals, scope ordinals, and node tags are consensus
 * constants. Node tags are literal=0, scoped field=1 ({@code [1, scope, name]}), call=2. Construction enforces
 * implementation-wide structural maxima; the runtime also checks the tighter limits selected by the enclosing
 * binding profile.
 *
 * <p>ADR-031.3 amended this dialect in place: field references carry a {@link Scope}, the {@link Type#TEXT_SET}
 * field type exists for kernel-declared facts, and {@code in} tests text membership in a text set. Which scopes
 * are legal depends on where the expression is used ({@link #BINDING_SCOPES}, {@link #RULE_SCOPES}); the enclosing
 * IR structure enforces that at construction and decode.
 *
 * @param resultType declared scalar result, checked against every branch during program validation
 * @param root immutable expression tree; no variables other than declared scoped fields are supported
 */
public record BindingExpressionV1(Type resultType, Node root) {
    public static final String DIALECT = "yano-x-cel-v1";
    /**
     * Frozen wire ordinals: signed int64=0, UTF-8 text=1, opaque bytes=2, boolean=3, text set=4. Do not reorder.
     * {@link #TEXT_SET} is a field type only (a sorted, duplicate-free text list); no expression returns it.
     */
    public enum Type { INTEGER, TEXT, BYTES, BOOLEAN, TEXT_SET }
    /**
     * Frozen field-scope ordinals: event=0, command=1, params=2, config=3, context=4, facts=5. Do not reorder.
     * The ADR-031.3 scope-by-use-site table decides where each scope is legal.
     */
    public enum Scope {
        EVENT, COMMAND, PARAMS, CONFIG, CONTEXT, FACTS;

        /** Lower-case authoring name, identical to the CEL variable prefix. */
        public String label() { return name().toLowerCase(Locale.ROOT); }
    }
    /** Scopes legal in binding conditions, lookups, and mappings. */
    public static final Set<Scope> BINDING_SCOPES = Set.copyOf(EnumSet.of(Scope.EVENT, Scope.CONTEXT));
    /** Scopes legal in admission-rule clauses; {@link Scope#COMMAND} additionally requires a command selector. */
    public static final Set<Scope> RULE_SCOPES = Set.copyOf(EnumSet.of(Scope.COMMAND, Scope.PARAMS, Scope.CONFIG,
            Scope.CONTEXT, Scope.FACTS));
    /** The five {@code context.*} fields (ADR-031.3 §5.3); no other context name exists. */
    public static final Set<String> CONTEXT_FIELDS = Set.of("height", "sender", "derived", "depth", "binding");
    private static final List<String> OPERATORS = List.of("eq", "ne", "lt", "le", "gt", "ge", "and", "or", "not",
            "if", "add", "sub", "mul", "div", "mod", "neg", "concat", "in");

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
    /** A top-level field of one scope; no reflection, arbitrary property traversal, or Java objects. */
    public record Field(Scope scope, String name) implements Node {
        public Field {
            Objects.requireNonNull(scope, "scope");
            requireName(name);
            requireContextField(scope, name);
        }
        /** An event field, the only scope that existed before ADR-031.3. */
        public Field(String name) { this(Scope.EVENT, name); }
        @Override public Object wire() { return List.of(1, scope.ordinal(), name); }
    }
    /** An allowlisted fixed-arity operator whose argument order is semantically significant. */
    public record Call(String operator, List<Node> arguments) implements Node {
        public Call {
            if (!OPERATORS.contains(operator)) throw new IllegalArgumentException("unsupported expression operator");
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
        if (resultType == Type.TEXT_SET) throw new IllegalArgumentException("text set is a field type only");
        if (count(root, 0) > 512) throw new IllegalArgumentException("expression exceeds node limit");
    }
    /** Returns the data-only canonical-encoder input; callers serialize it with {@link BindingCbor}. */
    public Object wire() { return List.of(1, resultType.ordinal(), root.wire()); }

    /** Whether any node of this expression applies {@code operator}. */
    public boolean usesOperator(String operator) { return uses(root, operator); }

    /** Returns every scope this expression reads, in scope order, for use-site validation. */
    public Set<Scope> scopes() {
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        collect(root, scopes);
        return Collections.unmodifiableSet(scopes);
    }

    /**
     * Rejects an expression that reads a scope outside {@code allowed}.
     *
     * @param useSite diagnostic name of the enclosing use site
     * @throws IllegalArgumentException naming the first illegal scope
     */
    public void requireScopes(Set<Scope> allowed, String useSite) {
        for (Scope scope : scopes()) {
            if (!allowed.contains(scope)) {
                throw new IllegalArgumentException(scope.label() + " scope is not available in " + useSite);
            }
        }
    }

    /**
     * Decodes a supported expression envelope; use-site scope validation belongs to the enclosing structure and
     * static field/operator type validation follows at program construction. Pre-ADR-031.3 field nodes
     * ({@code [1, name]}) fail with an explicit error.
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
            case 1 -> {
                if (fields.size() == 2) throw BindingIrV1.predatesPolicyPlane("unscoped expression field");
                BindingCbor.array(fields, 3);
                yield new Field(scope(fields.get(1)), BindingCbor.text(fields.get(2)));
            }
            case 2 -> {
                BindingCbor.array(fields, 3);
                if (!(fields.get(2) instanceof List<?> arguments)) throw new IllegalArgumentException("arguments");
                yield new Call(BindingCbor.text(fields.get(1)), arguments.stream()
                        .map(argument -> node(argument, depth + 1)).toList());
            }
            default -> throw new IllegalArgumentException("unknown expression node");
        };
    }
    static Scope scope(Object value) {
        long ordinal = BindingCbor.integer(value);
        if (ordinal < 0 || ordinal >= Scope.values().length) throw new IllegalArgumentException("field scope");
        return Scope.values()[(int) ordinal];
    }
    private static boolean uses(Node node, String operator) {
        return node instanceof Call call && (call.operator().equals(operator)
                || call.arguments().stream().anyMatch(argument -> uses(argument, operator)));
    }
    static void requireContextField(Scope scope, String name) {
        if (scope == Scope.CONTEXT && !CONTEXT_FIELDS.contains(name)) {
            throw new IllegalArgumentException("unknown context field: " + name);
        }
    }
    private static void collect(Node node, Set<Scope> scopes) {
        if (node instanceof Field field) scopes.add(field.scope());
        else if (node instanceof Call call) call.arguments().forEach(argument -> collect(argument, scopes));
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
