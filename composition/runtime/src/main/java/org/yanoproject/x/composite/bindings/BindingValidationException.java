package org.yanoproject.x.composite.bindings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Profile-construction rejection with a stable code and the declaration it concerns.
 *
 * <p>This is a diagnostic refinement of the {@link IllegalArgumentException}s {@link BindingProgram} has always
 * thrown: {@link #getMessage()} is byte-for-byte the historical text, acceptance rules are unchanged, and nothing
 * here enters profile, IR, receipt or state bytes. Authoring tools may read the structured fields to point at an
 * authored binding, clause or field; they must not parse the message. Locations never include literal values.
 */
public final class BindingValidationException extends IllegalArgumentException {
    private final String code;
    private final Integer bindingIndex;
    private final String bindingId;
    private final Integer clauseIndex;
    private final String part;
    private final String field;
    private final List<Integer> argumentPath;

    private BindingValidationException(String message, Throwable cause, String code, Integer bindingIndex,
                                       String bindingId, Integer clauseIndex, String part, String field,
                                       List<Integer> argumentPath) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.bindingIndex = bindingIndex;
        this.bindingId = bindingId;
        this.clauseIndex = clauseIndex;
        this.part = part;
        this.field = field;
        this.argumentPath = List.copyOf(argumentPath);
    }

    /** Creates a rejection at its origin, before any declaration context has been added. */
    static BindingValidationException of(String code, String message) {
        return new BindingValidationException(message, null, code, null, null, null, null, null, List.of());
    }

    /**
     * Prefixes {@code "<location>: "} exactly as the historical wrappers did, with the original exception as cause.
     * The innermost part, field and clause win because they are the most specific; binding identity comes from the
     * outermost wrapper. A plain {@link IllegalArgumentException} from a collaborator receives {@code fallbackCode}.
     */
    static BindingValidationException wrap(String location, IllegalArgumentException cause, String fallbackCode,
                                           Context context) {
        BindingValidationException inner = structured(cause, fallbackCode);
        List<Integer> arguments = new ArrayList<>();
        if (context.argument() != null) arguments.add(context.argument());
        arguments.addAll(inner.argumentPath);
        return new BindingValidationException(location + ": " + cause.getMessage(), cause, inner.code,
                context.bindingIndex() != null ? context.bindingIndex() : inner.bindingIndex,
                context.bindingId() != null ? context.bindingId() : inner.bindingId,
                inner.clauseIndex != null ? inner.clauseIndex : context.clauseIndex(),
                inner.part != null ? inner.part : context.part(),
                inner.field != null ? inner.field : context.field(), arguments);
    }

    /**
     * Adds context without prefixing the message. The result replaces {@code cause} rather than wrapping it: the
     * message and the underlying cause are unchanged, so the historical exception chain keeps its depth.
     *
     * <p>A plain {@link IllegalArgumentException} here came from a selected kernel's descriptors rather than from
     * this validator, so it is classified {@code KERNEL_CONTRACT_INVALID} without a declaration part (the authored
     * reference is not known to be wrong); the original exception is retained as suppressed for its class and stack.
     */
    static BindingValidationException annotate(IllegalArgumentException cause, Context context) {
        if (!(cause instanceof BindingValidationException inner)) {
            var kernel = new BindingValidationException(cause.getMessage(), cause.getCause(), "KERNEL_CONTRACT_INVALID",
                    context.bindingIndex(), context.bindingId(), context.clauseIndex(), null, null, List.of());
            kernel.addSuppressed(cause);
            return kernel;
        }
        return new BindingValidationException(cause.getMessage(), cause.getCause(), inner.code,
                inner.bindingIndex != null ? inner.bindingIndex : context.bindingIndex(),
                inner.bindingId != null ? inner.bindingId : context.bindingId(),
                inner.clauseIndex != null ? inner.clauseIndex : context.clauseIndex(),
                inner.part != null ? inner.part : context.part(),
                inner.field != null ? inner.field : context.field(), inner.argumentPath);
    }

    private static BindingValidationException structured(IllegalArgumentException cause, String fallbackCode) {
        return cause instanceof BindingValidationException value ? value
                : new BindingValidationException(cause.getMessage(), cause.getCause(), fallbackCode, null, null, null,
                        null, null, List.of());
    }

    /** Declaration context added by one wrapper; {@code null} fields add nothing. */
    record Context(Integer bindingIndex, String bindingId, Integer clauseIndex, String part, String field,
                   Integer argument) {
        static final Context NONE = new Context(null, null, null, null, null, null);
        static Context part(String part) { return new Context(null, null, null, part, null, null); }
        static Context field(String part, String field) { return new Context(null, null, null, part, field, null); }
        static Context argument(int index) { return new Context(null, null, null, null, null, index); }
    }

    /** Stable rejection code, for example {@code BINDING_TYPE_MISMATCH}. */
    public String code() { return code; }

    /** Zero-based index of the binding in the committed IR, or {@code null} for program-wide failures. */
    public Integer bindingIndex() { return bindingIndex; }

    /** Binding identifier, or {@code null} for program-wide failures. */
    public String bindingId() { return bindingId; }

    /** Zero-based condition clause index, or {@code null}. */
    public Integer clauseIndex() { return clauseIndex; }

    /**
     * Declaration part: {@code source-event}, {@code condition}, {@code lookup-key}, {@code lookup-operand},
     * {@code expression}, {@code raw-body}, {@code mapping}, {@code target}, {@code target-command} or
     * {@code target-field}; {@code null} when unknown or when a kernel descriptor, not a declaration, failed.
     */
    public String part() { return part; }

    /** Mapping or target command field name, or {@code null}. */
    public String field() { return field; }

    /** Nested zero-based function-argument indexes from the mapping source inward; empty when not applicable. */
    public List<Integer> argumentPath() { return argumentPath; }
}
