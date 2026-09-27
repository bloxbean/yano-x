package org.yanoproject.x.devtools;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator;
import org.yanoproject.x.composite.bindings.BindingExpressionEvaluator.Scoped;
import org.yanoproject.x.composite.contracts.BindingExpressionV1;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Call;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Field;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Literal;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Node;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Quantifier;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Scope;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;
import org.yanoproject.x.composite.contracts.BindingIrV1.Limits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline CEL parser/type checker that lowers only the committed yano-x-cel-v1 operator set.
 *
 * <p>Fields are scoped (ADR-031.3): {@code event.*} and {@code context.*} in bindings; {@code command.*},
 * {@code params.*}, {@code config.*}, {@code context.*} and {@code facts.*} in admission rules. Each use site
 * declares only its legal scopes, so misuse is a CEL undeclared reference, reported with its position and the
 * name of the unavailable scope. Kernel-declared text sets are CEL {@code list(string)} and usable with {@code in}.
 *
 * <p>ADR-031.4: a rule's declared reads are variables {@code reads.<read>.<field>} and
 * {@code reads.<read>.value.<field>}. When the use site declares write-element fields, {@code writes} is a list of a
 * typed element, and CEL's {@code writes.all(w, e)} and {@code writes.exists(w, e)} macros lower to the dialect's
 * quantifier; any other comprehension target, macro or nesting is rejected. {@code startsWith(a, b)} and
 * {@code size(a)} are accepted in global and member form.
 */
public final class BindingExpressionCompiler {
    private static final Pattern UNDECLARED = Pattern.compile("undeclared reference to '([a-z]+)");
    private static final String ELEMENT_TYPE = "yano.x.WriteElement";
    private static final String ELEMENT_VALUE_TYPE = "yano.x.WriteValue";
    private static final CelFunctionDecl STARTS_WITH = CelFunctionDecl.newFunctionDeclaration("startsWith",
            CelOverloadDecl.newGlobalOverload("yano_starts_with_text", SimpleType.BOOL, SimpleType.STRING,
                    SimpleType.STRING),
            CelOverloadDecl.newGlobalOverload("yano_starts_with_bytes", SimpleType.BOOL, SimpleType.BYTES,
                    SimpleType.BYTES),
            // CEL declares only the text member form; this adds the bytes one, so both forms lower alike.
            CelOverloadDecl.newMemberOverload("yano_bytes_starts_with_bytes", SimpleType.BOOL, SimpleType.BYTES,
                    SimpleType.BYTES));

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
        // Only the two quantifier macros exist; has(), exists_one(), map() and filter() stay unavailable.
        var builder = CelCompilerFactory.standardCelCompilerBuilder()
                .setStandardMacros(CelStandardMacro.ALL, CelStandardMacro.EXISTS)
                .setOptions(CelOptions.current().maxExpressionCodePointSize(8192)
                        .maxParseRecursionDepth(64).maxParseExpressionNodeCount(1024).build())
                .addFunctionDeclarations(STARTS_WITH);
        fields.scopes().forEach((scope, declared) -> {
            if (scope != Scope.WRITE_ELEMENT) {
                declared.forEach((name, type) -> builder.addVar(scope.label() + "." + name, celType(type)));
            }
        });
        if (fields.scopes().containsKey(Scope.WRITE_ELEMENT)) declareWrites(builder, fields.of(Scope.WRITE_ELEMENT));
        try {
            CelAbstractSyntaxTree ast = builder.build().compile(source).getAst();
            BindingExpressionV1 expression = new BindingExpressionV1(type(ast.getResultType()),
                    lower(ast.getExpr(), ast, 0, null));
            BindingExpressionEvaluator.validate(expression, fields, limits);
            return expression;
        } catch (CelValidationException failure) {
            var issues = failure.getErrors();
            var location = issues.isEmpty() ? null : issues.getFirst().getSourceLocation();
            int line = location == null ? -1 : location.getLine();
            int column = location == null ? -1 : location.getColumn();
            boolean positioned = line >= 1 && column >= 0 && !hasNonLineFeedBreak(source);
            String message = "invalid binding expression: " + failure.getMessage();
            String code = null;
            Matcher undeclared = UNDECLARED.matcher(failure.getMessage());
            if (undeclared.find()) {
                String name = undeclared.group(1);
                for (Scope scope : Scope.values()) {
                    if (!scope.label().equals(name)) continue;
                    if (scope == Scope.WRITE_ELEMENT && fields.scopes().containsKey(Scope.READS)) {
                        // A rule clause (the only site that declares reads) whose attached kernel declares no write
                        // view (ADR-031.4); a key site reports the scope as unavailable instead.
                        message = "the attached component has no write view: " + failure.getMessage();
                        code = "RULE_WRITES_UNSUPPORTED";
                    } else if (!fields.scopes().containsKey(scope)) {
                        message = name + " scope is not available in " + useSite + ": " + failure.getMessage();
                        code = "RULE_SCOPE_INVALID";
                    } else if (scope == Scope.FACTS) {
                        // An available scope without the named member: the kernel declares no such fact.
                        code = "RULE_FACT_UNKNOWN";
                    } else if (scope == Scope.READS) {
                        code = "RULE_READ_UNKNOWN_FIELD";
                    } else if (scope != Scope.EVENT) {
                        code = "RULE_FIELD_UNKNOWN";
                    }
                }
            } else if (failure.getMessage().contains("undefined field")
                    && fields.scopes().containsKey(Scope.WRITE_ELEMENT)) {
                code = "RULE_FIELD_UNKNOWN";
            }
            throw new ExpressionException(message, failure,
                    positioned ? line : null, positioned ? utf16Column(source, line, column) : null, code);
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
        private final String code;

        ExpressionException(String message, Throwable cause) { this(message, cause, null, null, null); }

        ExpressionException(String message, Throwable cause, Integer line, Integer column, String code) {
            super(message, cause);
            this.line = line;
            this.column = column;
            this.code = code;
        }

        /**
         * A more specific diagnostic code than {@code EXPRESSION_INVALID}, or {@code null}: {@code RULE_SCOPE_INVALID}
         * when the expression reads a scope its use site does not provide, and {@code RULE_FACT_UNKNOWN} or
         * {@code RULE_FIELD_UNKNOWN} when it reads an undeclared member of an available rule scope (ADR-031.3), and
         * {@code RULE_READ_UNKNOWN_FIELD} or {@code RULE_WRITES_UNSUPPORTED} for an undeclared read field or a
         * quantifier where the attached kernel has no write view (ADR-031.4).
         */
        public String code() { return code; }

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

    /**
     * Declares {@code writes} as a list of a typed element: content fields by name, and value fields under a nested
     * {@code value} struct (ADR-031.4). The element types exist only for checking; lowering maps
     * {@code w.<field>} and {@code w.value.<field>} to write-element fields.
     */
    private static void declareWrites(CelCompilerBuilder builder, Map<String, Type> declared) {
        Map<String, CelType> content = new LinkedHashMap<>();
        Map<String, CelType> values = new LinkedHashMap<>();
        declared.forEach((name, type) -> {
            if (name.startsWith(BindingExpressionV1.VALUE_FIELD + ".")) {
                values.put(name.substring(BindingExpressionV1.VALUE_FIELD.length() + 1), celType(type));
            } else {
                content.put(name, celType(type));
            }
        });
        StructType value = StructType.create(ELEMENT_VALUE_TYPE, ImmutableSet.copyOf(values.keySet()),
                field -> Optional.ofNullable(values.get(field)));
        if (!values.isEmpty()) content.put(BindingExpressionV1.VALUE_FIELD, value);
        StructType element = StructType.create(ELEMENT_TYPE, ImmutableSet.copyOf(content.keySet()),
                field -> Optional.ofNullable(content.get(field)));
        builder.setTypeProvider(new CelTypeProvider() {
            @Override public ImmutableList<CelType> types() { return ImmutableList.of(element, value); }
            @Override public Optional<CelType> findType(String name) {
                return name.equals(ELEMENT_TYPE) ? Optional.of(element)
                        : name.equals(ELEMENT_VALUE_TYPE) ? Optional.of(value) : Optional.empty();
            }
        });
        builder.addVar("writes", ListType.create(element));
    }

    /**
     * Lowers a checked CEL expression. {@code element} is the quantifier's iteration variable while lowering its
     * body, otherwise {@code null}.
     */
    private static Node lower(CelExpr expression, CelAbstractSyntaxTree ast, int depth, String element) {
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
                if (name.equals(element)) throw new IllegalArgumentException("select a field of the write element");
                if (dot < 0) throw new IllegalArgumentException("expression scope");
                yield new Field(scope(name.substring(0, dot)), name.substring(dot + 1));
            }
            case SELECT -> {
                var select = expression.select();
                if (select.testOnly()) throw new IllegalArgumentException("unsupported expression selection");
                CelExpr operand = select.operand();
                if (element != null && operand.getKind() == CelExpr.ExprKind.Kind.IDENT
                        && operand.ident().name().equals(element)) {
                    yield Field.element(select.field());
                }
                if (element != null && operand.getKind() == CelExpr.ExprKind.Kind.SELECT
                        && operand.select().operand().getKind() == CelExpr.ExprKind.Kind.IDENT
                        && operand.select().operand().ident().name().equals(element)
                        && operand.select().field().equals(BindingExpressionV1.VALUE_FIELD)) {
                    yield Field.elementValue(select.field());
                }
                if (operand.getKind() != CelExpr.ExprKind.Kind.IDENT) {
                    throw new IllegalArgumentException("unsupported expression selection");
                }
                yield new Field(scope(operand.ident().name()), select.field());
            }
            case COMPREHENSION -> lowerQuantifier(expression.comprehension(), ast, depth, element);
            case CALL -> {
                var call = expression.call();
                if (call.function().equals("startsWith") || call.function().equals("size")) {
                    // Global and member forms lower to one operator; the receiver becomes the first operand.
                    var operands = new ArrayList<CelExpr>();
                    call.target().ifPresent(operands::add);
                    operands.addAll(call.args());
                    yield new Call(call.function(), operands.stream()
                            .map(arg -> lower(arg, ast, depth + 1, element)).toList());
                }
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
                yield new Call(operator, call.args().stream().map(arg -> lower(arg, ast, depth + 1, element))
                        .toList());
            }
            default -> throw new IllegalArgumentException("unsupported expression syntax: " + expression.getKind());
        };
    }

    /**
     * Lowers the expansion of {@code writes.all(w, e)} or {@code writes.exists(w, e)}: a comprehension over the
     * {@code writes} variable whose boolean accumulator starts {@code true} and is and-ed with the body ({@code all}),
     * or starts {@code false} and is or-ed with it ({@code exists}). Every other comprehension (another macro,
     * another range, or one nested inside a quantifier body) is rejected.
     */
    private static Node lowerQuantifier(CelExpr.CelComprehension comprehension, CelAbstractSyntaxTree ast, int depth,
                                        String element) {
        if (element != null) throw new IllegalArgumentException("quantifiers do not nest");
        for (Scope scope : Scope.values()) {
            if (scope.label().equals(comprehension.iterVar())) {
                // CEL would resolve "<scope>.<field>" to the scope's variable, not to the element.
                throw new IllegalArgumentException("a quantifier variable cannot be named " + scope.label());
            }
        }
        CelExpr range = comprehension.iterRange();
        if (range.getKind() != CelExpr.ExprKind.Kind.IDENT || !range.ident().name().equals("writes")
                || !comprehension.iterVar2().isEmpty()) {
            throw new IllegalArgumentException("only writes.all and writes.exists quantify, over writes");
        }
        CelExpr initial = comprehension.accuInit();
        CelExpr step = comprehension.loopStep();
        if (initial.getKind() != CelExpr.ExprKind.Kind.CONSTANT
                || initial.constant().getKind() != CelConstant.Kind.BOOLEAN_VALUE
                || step.getKind() != CelExpr.ExprKind.Kind.CALL || step.call().args().size() != 2) {
            throw new IllegalArgumentException("only writes.all and writes.exists quantify, over writes");
        }
        boolean exists = !initial.constant().booleanValue();
        String accumulate = exists ? "_||_" : "_&&_";
        CelExpr accumulator = step.call().args().getFirst();
        if (!step.call().function().equals(accumulate) || accumulator.getKind() != CelExpr.ExprKind.Kind.IDENT
                || !accumulator.ident().name().equals(comprehension.accuVar())) {
            throw new IllegalArgumentException("only writes.all and writes.exists quantify, over writes");
        }
        return new Quantifier(exists, lower(step.call().args().get(1), ast, depth + 1, comprehension.iterVar()));
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
