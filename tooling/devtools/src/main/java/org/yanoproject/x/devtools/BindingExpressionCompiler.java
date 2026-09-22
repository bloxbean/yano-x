package org.yanoproject.x.devtools;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerFactory;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;

import java.util.Map;

/** Offline CEL parser/type checker that lowers only the committed yano-x-cel-v1 operator set. */
public final class BindingExpressionCompiler {
    private BindingExpressionCompiler() { }

    /**
     * Compiles author-supplied CEL into the restricted, versioned expression IR committed by a profile.
     * The official CEL compiler parses and type-checks the source; the lowering pass then rejects constructs
     * outside the Yano X dialect. No CEL evaluator or checked CEL AST is shipped as executable consensus data.
     *
     * @param source expression text using declared fields as {@code event.fieldName}
     * @param fields event schema used for static type checking
     * @param limits selected profile bounds, checked again against the lowered expression
     * @return canonical-serializable expression with an explicit scalar result type
     * @throws IllegalArgumentException if parsing, type checking, dialect lowering, or bounds validation fails
     */
    public static BindingExpressionV1 compile(String source, Map<String, Type> fields, Limits limits) {
        if (source == null || source.length() > 8192) throw new IllegalArgumentException("expression source limit");
        var builder = CelCompilerFactory.standardCelCompilerBuilder().setStandardMacros()
                .setOptions(CelOptions.current().maxExpressionCodePointSize(8192)
                        .maxParseRecursionDepth(64).maxParseExpressionNodeCount(1024).build());
        fields.forEach((name, type) -> builder.addVar("event." + name, celType(type)));
        try {
            CelAbstractSyntaxTree ast = builder.build().compile(source).getAst();
            BindingExpressionV1 expression = new BindingExpressionV1(type(ast.getResultType()),
                    lower(ast.getExpr(), ast, 0));
            BindingExpressionEvaluator.validate(expression, fields, limits);
            return expression;
        } catch (CelValidationException failure) {
            throw new IllegalArgumentException("invalid binding expression: " + failure.getMessage(), failure);
        }
    }

    private static Node lower(CelExpr expression, CelAbstractSyntaxTree ast, int depth) {
        if (depth > 32) throw new IllegalArgumentException("expression depth limit");
        return switch (expression.getKind()) {
            case CONSTANT -> {
                var value = expression.constant();
                yield new Literal(switch (value.getKind()) {
                    case INT64_VALUE -> value.int64Value();
                    case BOOLEAN_VALUE -> value.booleanValue();
                    case STRING_VALUE -> value.stringValue();
                    case BYTES_VALUE -> value.bytesValue().toByteArray();
                    default -> throw new IllegalArgumentException("unsupported expression literal");
                });
            }
            case IDENT -> {
                String name = expression.ident().name();
                if (!name.startsWith("event.")) throw new IllegalArgumentException("expression scope");
                yield new Field(name.substring("event.".length()));
            }
            case SELECT -> {
                var select = expression.select();
                if (select.testOnly() || select.operand().getKind() != CelExpr.ExprKind.Kind.IDENT
                        || !select.operand().ident().name().equals("event")) {
                    throw new IllegalArgumentException("unsupported expression selection");
                }
                yield new Field(select.field());
            }
            case CALL -> {
                var call = expression.call();
                if (call.target().isPresent()) throw new IllegalArgumentException("method calls are not allowed");
                String operator = switch (call.function()) {
                    case "_==_" -> "eq";
                    case "_!=_" -> "ne";
                    case "_<_" -> "lt";
                    case "_<=_" -> "le";
                    case "_>_" -> "gt";
                    case "_>=_" -> "ge";
                    case "_&&_" -> "and";
                    case "_||_" -> "or";
                    case "!_" -> "not";
                    case "_?_:_" -> "if";
                    case "_+_" -> type(ast.getTypeOrThrow(expression.id())) == Type.INTEGER ? "add" : "concat";
                    case "_-_" -> "sub";
                    case "_*_" -> "mul";
                    case "_/_" -> "div";
                    case "_%_" -> "mod";
                    case "-_" -> "neg";
                    default -> throw new IllegalArgumentException(
                            "unsupported expression function: " + call.function());
                };
                yield new Call(operator, call.args().stream().map(arg -> lower(arg, ast, depth + 1)).toList());
            }
            default -> throw new IllegalArgumentException("unsupported expression syntax: " + expression.getKind());
        };
    }

    private static CelType celType(Type type) {
        return switch (type) {
            case INTEGER -> SimpleType.INT;
            case TEXT -> SimpleType.STRING;
            case BYTES -> SimpleType.BYTES;
            case BOOLEAN -> SimpleType.BOOL;
        };
    }
    private static Type type(CelType type) {
        for (Type candidate : Type.values()) if (celType(candidate).equals(type)) return candidate;
        throw new IllegalArgumentException("unsupported expression type: " + type);
    }
}
