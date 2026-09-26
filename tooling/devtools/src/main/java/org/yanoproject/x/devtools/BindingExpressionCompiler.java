package org.yanoproject.x.devtools;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerFactory;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline CEL parser/type checker that lowers only the committed yano-x-cel-v1 operator set.
 *
 * <p>Fields are scoped (ADR-031.3): {@code event.*} and {@code context.*} in bindings; {@code command.*},
 * {@code params.*}, {@code config.*}, {@code context.*} and {@code facts.*} in admission rules. Each use site
 * declares only its legal scopes, so misuse is a CEL undeclared reference, reported with its position and the
 * name of the unavailable scope. Kernel-declared text sets are CEL {@code list(string)} and usable with {@code in}.
 */
public final class BindingExpressionCompiler {
    private static final Pattern UNDECLARED = Pattern.compile("undeclared reference to '([a-z]+)");

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
    /**
     * Compiles CEL over the fields of the scopes legal at one use site.
     *
     * @param source expression text using declared fields as {@code <scope>.<field>}
     * @param fields declared field types per available scope; a scope absent here is unavailable
     * @param limits selected profile bounds, checked again against the lowered expression
     * @param useSite diagnostic name of the use site, for example {@code "a binding"}
     * @return canonical-serializable expression with an explicit scalar result type
     * @throws IllegalArgumentException if parsing, type checking, dialect lowering, or bounds validation fails
     */
    public static BindingExpressionV1 compile(String source, Scoped<Type> fields, Limits limits, String useSite) {
        if (source == null || source.length() > 8192) throw new ExpressionException("expression source limit", null);
        var builder = CelCompilerFactory.standardCelCompilerBuilder().setStandardMacros()
                .setOptions(CelOptions.current().maxExpressionCodePointSize(8192)
                        .maxParseRecursionDepth(64).maxParseExpressionNodeCount(1024).build());
        fields.scopes().forEach((scope, declared) -> declared.forEach((name, type) ->
                builder.addVar(scope.label() + "." + name, celType(type))));
        try {
            CelAbstractSyntaxTree ast = builder.build().compile(source).getAst();
            BindingExpressionV1 expression = new BindingExpressionV1(type(ast.getResultType()),
                    lower(ast.getExpr(), ast, 0));
            BindingExpressionEvaluator.validate(expression, fields, limits);
            return expression;
        } catch (CelValidationException failure) {
            var issues = failure.getErrors();
            var location = issues.isEmpty() ? null : issues.getFirst().getSourceLocation();
            int line = location == null ? -1 : location.getLine();
            int column = location == null ? -1 : location.getColumn();
            boolean positioned = line >= 1 && column >= 0 && !hasNonLineFeedBreak(source);
            String message = "invalid binding expression: " + failure.getMessage();
            Matcher undeclared = UNDECLARED.matcher(failure.getMessage());
            if (undeclared.find()) {
                String name = undeclared.group(1);
                for (Scope scope : Scope.values()) {
                    if (scope.label().equals(name) && !fields.scopes().containsKey(scope)) {
                        message = name + " scope is not available in " + useSite + ": " + failure.getMessage();
                    }
                }
            }
            throw new ExpressionException(message, failure,
                    positioned ? line : null, positioned ? utf16Column(source, line, column) : null);
        } catch (ExpressionException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new ExpressionException(failure.getMessage(), failure);
        }
    }

    /**
     * Restricted-CEL authoring failure. The message is the historical text; positions are one-based, relative to
     * the expression source, and measured in UTF-16 code units. They are omitted when CEL reports no position.
     */
    public static final class ExpressionException extends IllegalArgumentException {
        private final Integer line;
        private final Integer column;

        ExpressionException(String message, Throwable cause) { this(message, cause, null, null); }

        ExpressionException(String message, Throwable cause, Integer line, Integer column) {
            super(message, cause);
            this.line = line;
            this.column = column;
        }

        /** One-based source line within the expression, or {@code null}. */
        public Integer line() { return line; }

        /** One-based UTF-16 column within that line, or {@code null}. */
        public Integer column() { return column; }
    }

    /** Converts CEL's zero-based code-point column into a one-based UTF-16 column on the same line. */
    static Integer utf16Column(String source, int line, int codePointColumn) {
        if (hasNonLineFeedBreak(source)) return null;
        int start = 0;
        for (int current = 1; current < line; current++) {
            int next = source.indexOf('\n', start);
            if (next < 0) return null;
            start = next + 1;
        }
        int offset = start;
        for (int index = 0; index < codePointColumn; index++) {
            if (offset >= source.length()) return null;
            offset += Character.charCount(source.codePointAt(offset));
        }
        return offset - start + 1;
    }

    /** Whether the source has a line break other than LF, which position conversion does not model. */
    static boolean hasNonLineFeedBreak(String source) {
        return source.chars().anyMatch(character -> character == '\r' || character == 0x85 || character == 0x2028
                || character == 0x2029);
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
                int dot = name.indexOf('.');
                if (dot < 0) throw new IllegalArgumentException("expression scope");
                yield new Field(scope(name.substring(0, dot)), name.substring(dot + 1));
            }
            case SELECT -> {
                var select = expression.select();
                if (select.testOnly() || select.operand().getKind() != CelExpr.ExprKind.Kind.IDENT) {
                    throw new IllegalArgumentException("unsupported expression selection");
                }
                yield new Field(scope(select.operand().ident().name()), select.field());
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
                    case "@in" -> "in";
                    default -> throw new IllegalArgumentException(
                            "unsupported expression function: " + call.function());
                };
                yield new Call(operator, call.args().stream().map(arg -> lower(arg, ast, depth + 1)).toList());
            }
            default -> throw new IllegalArgumentException("unsupported expression syntax: " + expression.getKind());
        };
    }

    private static Scope scope(String label) {
        for (Scope scope : Scope.values()) if (scope.label().equals(label)) return scope;
        throw new IllegalArgumentException("expression scope");
    }
    private static CelType celType(Type type) {
        return switch (type) {
            case INTEGER -> SimpleType.INT;
            case TEXT -> SimpleType.STRING;
            case BYTES -> SimpleType.BYTES;
            case BOOLEAN -> SimpleType.BOOL;
            case TEXT_SET -> ListType.create(SimpleType.STRING);
        };
    }
    private static Type type(CelType type) {
        for (Type candidate : Type.values()) {
            if (candidate != Type.TEXT_SET && celType(candidate).equals(type)) return candidate;
        }
        throw new IllegalArgumentException("unsupported expression type: " + type);
    }
}
