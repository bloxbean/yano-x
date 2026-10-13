package org.yanoproject.x.composite.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Closed expression tree emitted by the restricted CEL compiler, never an executable Java callback.
 * The wire envelope is {@code [1, resultType, root]}; type ordinals, scope ordinals, and node tags are consensus
 * constants. Node tags are literal=0, scoped field=1 ({@code [1, scope, name]}), call=2, quantifier=3. Construction
 * enforces implementation-wide structural maxima; the runtime also checks the tighter limits selected by the
 * enclosing binding profile.
 *
 * <p>ADR-031.3 amended this dialect in place: field references carry a {@link Scope}, the {@link Type#TEXT_SET}
 * field type exists for kernel-declared facts, and {@code in} tests text membership in a text set. Which scopes
 * are legal depends on where the expression is used ({@link #BINDING_SCOPES}, {@link #RULE_SCOPES}); the enclosing
 * IR structure enforces that at construction and decode.
 *
 * <p>ADR-031.4 amended it again in place: rules read declared state reads ({@link Scope#READS}, field node
 * {@code [1, 6, read, field]} or {@code [1, 6, read, "value", field]}) and quantify over a kernel's write view with
 * {@link Quantifier} ({@code [3, 0 all / 1 exists, body]}), whose body reads the current element
 * ({@link Scope#WRITE_ELEMENT}, {@code [1, 7, field]} or {@code [1, 7, "value", field]}). Element fields appear only
 * inside a quantifier body, and quantifiers never nest; construction and decode enforce both. The dialect also gains
 * {@code startsWith} (two texts or two byte strings) and {@code size} (text or bytes). Pre-ADR-031.3 field nodes and
 * pre-ADR-031.4 rule structures fail decode with explicit errors.
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
     * Frozen field-scope ordinals: event=0, command=1, params=2, config=3, context=4, facts=5, reads=6,
     * write-element=7. Do not reorder. The scope-by-use-site table (ADR-031.3, amended by ADR-031.4) decides where
     * each scope is legal.
     */
    public enum Scope {
        EVENT, COMMAND, PARAMS, CONFIG, CONTEXT, FACTS, READS, WRITE_ELEMENT;

        /**
         * Lower-case authoring name, identical to the CEL variable prefix; {@code writes} for write elements, which
         * CEL reaches through the quantified {@code writes} list.
         */
        public String label() { return this == WRITE_ELEMENT ? "writes" : name().toLowerCase(Locale.ROOT); }
    }
    /** Scopes legal in binding conditions, lookups, and mappings. */
    public static final Set<Scope> BINDING_SCOPES = Set.copyOf(EnumSet.of(Scope.EVENT, Scope.CONTEXT));
    /**
     * Scopes legal in admission-rule expression clauses; {@link Scope#COMMAND} additionally requires a command
     * selector, and {@link Scope#WRITE_ELEMENT} is legal only inside a {@link Quantifier} body.
     */
    public static final Set<Scope> RULE_SCOPES = Set.copyOf(EnumSet.of(Scope.COMMAND, Scope.PARAMS, Scope.CONFIG,
            Scope.CONTEXT, Scope.FACTS, Scope.READS, Scope.WRITE_ELEMENT));
    /**
     * Scopes legal in a rule's keys: lookup keys, lookup expectations, and read keys (ADR-031.4). No key reads a
     * read or quantifies, so reads never chain.
     */
    public static final Set<Scope> RULE_KEY_SCOPES = Set.copyOf(EnumSet.of(Scope.COMMAND, Scope.PARAMS,
            Scope.CONFIG, Scope.CONTEXT, Scope.FACTS));
    /** The prefix of a value field of a schema-typed record, in read and write-element field names. */
    public static final String VALUE_FIELD = "value";
    /** The five {@code context.*} fields (ADR-031.3 §5.3); no other context name exists. */
    public static final Set<String> CONTEXT_FIELDS = Set.of("height", "sender", "derived", "depth", "binding");
    private static final List<String> OPERATORS = List.of("eq", "ne", "lt", "le", "gt", "ge", "and", "or", "not",
            "if", "add", "sub", "mul", "div", "mod", "neg", "concat", "in", "startsWith", "size");
    private static final String VIEW_NAME = "[a-zA-Z][a-zA-Z0-9_]{0,62}";

    /** Data-only expression node; execution semantics belong to the versioned runtime interpreter. */
    public sealed interface Node permits Literal, Field, Call, Quantifier { Object wire(); }
    /** A non-null supported scalar; byte-array inputs and accessor results are defensively copied. */
    public record Literal(Object value) implements Node {
        public Literal {
            requireScalar(value);
            if (value instanceof byte[] bytes) value = bytes.clone();
        }
        @Override public Object value() { return value instanceof byte[] bytes ? bytes.clone() : value; }
        @Override public Object wire() { return List.of(0, value()); }
    }
    /**
     * A top-level field of one scope; no reflection, arbitrary property traversal, or Java objects. A read field's
     * name is {@code <read>.<field>} or {@code <read>.value.<field>}, and a write-element field's name is
     * {@code <field>} or {@code value.<field>}; their parts are CEL identifiers and are encoded as separate array
     * elements.
     */
    public record Field(Scope scope, String name) implements Node {
        public Field {
            Objects.requireNonNull(scope, "scope");
            switch (scope) {
                case READS -> requireViewPath(name, true);
                case WRITE_ELEMENT -> requireViewPath(name, false);
                default -> requireName(name);
            }
            requireContextField(scope, name);
        }
        /** An event field, the only scope that existed before ADR-031.3. */
        public Field(String name) { this(Scope.EVENT, name); }
        /** A declared field of a read, including the reserved {@code present}. */
        public static Field read(String read, String field) { return new Field(Scope.READS, read + "." + field); }
        /** A value field of a schema-typed record read. */
        public static Field readValue(String read, String field) {
            return new Field(Scope.READS, read + "." + VALUE_FIELD + "." + field);
        }
        /** A content or coverage field of the current write-view element. */
        public static Field element(String field) { return new Field(Scope.WRITE_ELEMENT, field); }
        /** A value field of the current write-view element. */
        public static Field elementValue(String field) {
            return new Field(Scope.WRITE_ELEMENT, VALUE_FIELD + "." + field);
        }
        /** The read a {@link Scope#READS} field names. */
        public String readName() {
            if (scope != Scope.READS) throw new IllegalStateException("not a read field");
            return name.substring(0, name.indexOf('.'));
        }
        @Override public Object wire() {
            if (scope != Scope.READS && scope != Scope.WRITE_ELEMENT) return List.of(1, scope.ordinal(), name);
            List<Object> wire = new ArrayList<>(List.of(1, scope.ordinal()));
            wire.addAll(List.of(name.split("\\.")));
            return List.copyOf(wire);
        }
    }
    /** An allowlisted fixed-arity operator whose argument order is semantically significant. */
    public record Call(String operator, List<Node> arguments) implements Node {
        public Call {
            if (!OPERATORS.contains(operator)) throw new IllegalArgumentException("unsupported expression operator");
            arguments = List.copyOf(arguments);
            int arity = switch (operator) { case "not", "neg", "size" -> 1; case "if" -> 3; default -> 2; };
            if (arguments.size() != arity) throw new IllegalArgumentException("incorrect expression arity");
        }
        @Override public Object wire() {
            return List.of(2, operator, arguments.stream().map(Node::wire).toList());
        }
    }

    /**
     * A bounded quantifier over the write view (ADR-031.4): {@code all} holds when the body holds for every element,
     * {@code exists} when it holds for some element, evaluated in index order with short-circuit. The body reads the
     * current element through {@link Scope#WRITE_ELEMENT} fields and contains no quantifier.
     *
     * @param exists {@code true} for {@code exists}, {@code false} for {@code all}
     * @param body boolean body evaluated per element
     */
    public record Quantifier(boolean exists, Node body) implements Node {
        public Quantifier {
            Objects.requireNonNull(body, "body");
            if (contains(body, Quantifier.class)) throw new IllegalArgumentException("quantifiers do not nest");
        }
        @Override public Object wire() { return List.of(3, exists ? 1 : 0, body.wire()); }
    }

    public BindingExpressionV1 {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(root, "root");
        if (resultType == Type.TEXT_SET) throw new IllegalArgumentException("text set is a field type only");
        if (count(root, 0) > 512) throw new IllegalArgumentException("expression exceeds node limit");
        requireElementsQuantified(root, false);
    }
    /** Returns the data-only canonical-encoder input; callers serialize it with {@link BindingCbor}. */
    public Object wire() { return List.of(1, resultType.ordinal(), root.wire()); }

    /** Whether any node of this expression applies {@code operator}. */
    public boolean usesOperator(String operator) { return uses(root, operator); }

    /** Whether this expression quantifies over the write view. */
    public boolean quantifies() { return contains(root, Quantifier.class); }

    /**
     * Returns every scope this expression reads, in scope order, for use-site validation. A quantifier reads the write
     * view, so it reports {@link Scope#WRITE_ELEMENT} even when its body reads no element field.
     */
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
                Scope scope = scope(fields.get(1));
                yield new Field(scope, switch (scope) {
                    case READS -> viewPath(fields, 4, 5);
                    case WRITE_ELEMENT -> viewPath(fields, 3, 4);
                    default -> BindingCbor.text(BindingCbor.array(fields, 3).get(2));
                });
            }
            case 3 -> {
                BindingCbor.array(fields, 3);
                long quantifier = BindingCbor.integer(fields.get(1));
                if (quantifier != 0 && quantifier != 1) throw new IllegalArgumentException("unknown quantifier");
                yield new Quantifier(quantifier == 1, node(fields.get(2), depth + 1));
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
    /**
     * Joins the parts of a read or write-element field node, which has {@code simple} or {@code simple + 1}
     * elements; the longer form's value marker must be {@code "value"}.
     */
    private static String viewPath(List<?> fields, int simple, int valued) {
        if (fields.size() != simple && fields.size() != valued) throw new IllegalArgumentException("view field node");
        List<String> parts = new ArrayList<>();
        for (int index = 2; index < fields.size(); index++) {
            String part = BindingCbor.text(fields.get(index));
            // One identifier per element: a dotted element would re-encode as a different array.
            if (!part.matches(VIEW_NAME)) throw new IllegalArgumentException("view field node");
            parts.add(part);
        }
        return String.join(".", parts);
    }

    static Scope scope(Object value) {
        long ordinal = BindingCbor.integer(value);
        if (ordinal < 0 || ordinal >= Scope.values().length) throw new IllegalArgumentException("field scope");
        return Scope.values()[(int) ordinal];
    }
    private static boolean uses(Node node, String operator) {
        return switch (node) {
            case Call call -> call.operator().equals(operator)
                    || call.arguments().stream().anyMatch(argument -> uses(argument, operator));
            case Quantifier quantifier -> uses(quantifier.body(), operator);
            default -> false;
        };
    }
    private static boolean contains(Node node, Class<? extends Node> kind) {
        if (kind.isInstance(node)) return true;
        return switch (node) {
            case Call call -> call.arguments().stream().anyMatch(argument -> contains(argument, kind));
            case Quantifier quantifier -> contains(quantifier.body(), kind);
            default -> false;
        };
    }
    /** Element fields appear only inside a quantifier body. */
    private static void requireElementsQuantified(Node node, boolean quantified) {
        switch (node) {
            case Field field -> {
                if (field.scope() == Scope.WRITE_ELEMENT && !quantified) {
                    throw new IllegalArgumentException("write elements are read only inside a quantifier");
                }
            }
            case Call call -> call.arguments().forEach(argument -> requireElementsQuantified(argument, quantified));
            case Quantifier quantifier -> requireElementsQuantified(quantifier.body(), true);
            case Literal ignored -> { }
        }
    }
    /**
     * Checks a read field name ({@code read.field} or {@code read.value.field}) or a write-element field name
     * ({@code field} or {@code value.field}); every part is a CEL identifier that is not a reserved word.
     */
    static void requireViewPath(String name, boolean read) {
        if (name == null) throw new IllegalArgumentException("invalid view field name");
        String[] parts = name.split("\\.", -1);
        int simple = read ? 2 : 1;
        boolean valued = parts.length == simple + 1 && parts[parts.length - 2].equals(VALUE_FIELD);
        if (parts.length != simple && !valued) throw new IllegalArgumentException("invalid view field name");
        for (String part : parts) {
            if (!part.matches(VIEW_NAME) || BindingIrV1.CEL_RESERVED_WORDS.contains(part)) {
                throw new IllegalArgumentException("invalid view field name");
            }
        }
    }
    static void requireContextField(Scope scope, String name) {
        if (scope == Scope.CONTEXT && !CONTEXT_FIELDS.contains(name)) {
            throw new IllegalArgumentException("unknown context field: " + name);
        }
    }
    private static void collect(Node node, Set<Scope> scopes) {
        switch (node) {
            case Field field -> scopes.add(field.scope());
            case Call call -> call.arguments().forEach(argument -> collect(argument, scopes));
            case Quantifier quantifier -> {
                scopes.add(Scope.WRITE_ELEMENT);
                collect(quantifier.body(), scopes);
            }
            case Literal ignored -> { }
        }
    }
    private static int count(Node node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("expression exceeds depth limit");
        int total = 1;
        List<Node> children = switch (node) {
            case Call call -> call.arguments();
            case Quantifier quantifier -> List.of(quantifier.body());
            default -> List.of();
        };
        for (Node child : children) {
            total += count(child, depth + 1);
            if (total > 512) return total;
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
