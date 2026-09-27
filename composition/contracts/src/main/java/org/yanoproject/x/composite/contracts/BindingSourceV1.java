package org.yanoproject.x.composite.contracts;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Closed alternatives for producing one mapped scalar value.
 * Wire tags are frozen as field=0, literal=1, function=2, expression=3. A field source names one field of one
 * scope, {@code [0, scope, name]} (ADR-031.3); the enclosing use site decides which scopes are legal.
 * Evidence destinations accept only a direct event {@link Field}: literals, context values and computations may
 * transform data but must never manufacture authority. Reads and write elements (ADR-031.4) are never sources: no
 * key, lookup expectation, or mapping reads a read or quantifies, so reads never chain. Destination schema
 * validation and field presence checks are performed by the runtime program.
 */
public sealed interface BindingSourceV1 {
    Object wire();

    /** Copies a named top-level field of one scope without changing its value. */
    record Field(BindingExpressionV1.Scope scope, String name) implements BindingSourceV1 {
        public Field {
            Objects.requireNonNull(scope, "scope");
            if (scope == BindingExpressionV1.Scope.READS || scope == BindingExpressionV1.Scope.WRITE_ELEMENT) {
                throw new IllegalArgumentException(scope.label() + " scope is not a binding source");
            }
            BindingExpressionV1.requireName(name);
            BindingExpressionV1.requireContextField(scope, name);
        }
        /** An event field, the only field source that existed before ADR-031.3. */
        public Field(String name) { this(BindingExpressionV1.Scope.EVENT, name); }
        @Override public Object wire() { return List.of(0, scope.ordinal(), name); }
    }
    /** Fixed scalar embedded in the committed document; byte-valued literals are defensively copied. */
    record Literal(BindingExpressionV1.Literal scalar) implements BindingSourceV1 {
        public Literal { Objects.requireNonNull(scalar, "scalar"); }
        public Literal(Object value) { this(new BindingExpressionV1.Literal(value)); }
        public Object value() { return scalar.value(); }
        @Override public Object wire() { return List.of(1, value()); }
    }
    /**
     * Call to a versioned stock function, with at most eight arguments and two function levels.
     * Expression/function mixing is deliberately excluded so the two evaluation contracts remain explicit.
     */
    record Function(String functionId, List<BindingSourceV1> arguments) implements BindingSourceV1 {
        public Function {
            BindingExpressionV1.requireName(functionId);
            arguments = List.copyOf(arguments);
            if (arguments.isEmpty() || arguments.size() > 8) throw new IllegalArgumentException("function arity");
            for (BindingSourceV1 argument : arguments) {
                if (argument instanceof Expression || argument instanceof Function nested
                        && nested.arguments.stream().anyMatch(value -> value instanceof Function
                                || value instanceof Expression)) {
                    throw new IllegalArgumentException("function nesting exceeds limit");
                }
            }
        }
        @Override public Object wire() {
            return List.of(2, functionId, arguments.stream().map(BindingSourceV1::wire).toList());
        }
    }
    /** Typed restricted-CEL IR; contains no source text, general evaluator, or host callback. */
    record Expression(BindingExpressionV1 expression) implements BindingSourceV1 {
        public Expression { Objects.requireNonNull(expression, "expression"); }
        @Override public Object wire() { return List.of(3, expression.wire()); }
    }

    /** Returns every scope this source reads, including inside functions and expressions. */
    static Set<BindingExpressionV1.Scope> scopes(BindingSourceV1 source) {
        Set<BindingExpressionV1.Scope> scopes = EnumSet.noneOf(BindingExpressionV1.Scope.class);
        collect(source, scopes);
        return Collections.unmodifiableSet(scopes);
    }

    /**
     * Rejects a source that reads a scope outside {@code allowed}.
     *
     * @throws IllegalArgumentException naming the first illegal scope
     */
    static void requireScopes(BindingSourceV1 source, Set<BindingExpressionV1.Scope> allowed, String useSite) {
        for (BindingExpressionV1.Scope scope : scopes(source)) {
            if (!allowed.contains(scope)) {
                throw new IllegalArgumentException(scope.label() + " scope is not available in " + useSite);
            }
        }
    }

    private static void collect(BindingSourceV1 source, Set<BindingExpressionV1.Scope> scopes) {
        switch (source) {
            case Field field -> scopes.add(field.scope());
            case Literal ignored -> { }
            case Function function -> function.arguments().forEach(argument -> collect(argument, scopes));
            case Expression expression -> scopes.addAll(expression.expression().scopes());
        }
    }

    static BindingSourceV1 fromWire(Object value) { return fromWire(value, 0); }

    private static BindingSourceV1 fromWire(Object value, int depth) {
        if (depth > 2 || !(value instanceof List<?> fields) || fields.isEmpty()) {
            throw new IllegalArgumentException("invalid binding source");
        }
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> {
                if (fields.size() == 2) throw BindingIrV1.predatesPolicyPlane("unscoped field source");
                BindingCbor.array(fields, 3);
                yield new Field(BindingExpressionV1.scope(fields.get(1)), BindingCbor.text(fields.get(2)));
            }
            case 1 -> new Literal(BindingCbor.array(fields, 2).get(1));
            case 2 -> {
                BindingCbor.array(fields, 3);
                if (!(fields.get(2) instanceof List<?> arguments))
                        throw new IllegalArgumentException("function arguments");
                yield new Function(BindingCbor.text(fields.get(1)), arguments.stream()
                        .map(argument -> fromWire(argument, depth + 1)).toList());
            }
            case 3 -> new Expression(BindingExpressionV1.fromWire(BindingCbor.array(fields, 2).get(1)));
            default -> throw new IllegalArgumentException("unknown binding source");
        };
    }
}
