package org.yanoproject.x.composite.contracts;

import java.util.List;
import java.util.Objects;

/**
 * Closed alternatives for producing one mapped scalar value.
 * Wire tags are frozen as field=0, literal=1, function=2, expression=3. Evidence destinations accept only
 * a direct {@link Field}: literals and computations may transform data but must never manufacture authority.
 * Destination schema validation and event-field presence checks are performed by the runtime program.
 */
public sealed interface BindingSourceV1 {
    Object wire();

    /** Copies a named top-level event field without changing its value. */
    record Field(String name) implements BindingSourceV1 {
        public Field { BindingExpressionV1.requireName(name); }
        @Override public Object wire() { return List.of(0, name); }
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

    static BindingSourceV1 fromWire(Object value) { return fromWire(value, 0); }

    private static BindingSourceV1 fromWire(Object value, int depth) {
        if (depth > 2 || !(value instanceof List<?> fields) || fields.isEmpty()) {
            throw new IllegalArgumentException("invalid binding source");
        }
        return switch (Math.toIntExact(BindingCbor.integer(fields.getFirst()))) {
            case 0 -> new Field(BindingCbor.text(BindingCbor.array(fields, 2).get(1)));
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
