package org.yanoproject.x.composite.bindings;

/** Expected deterministic cascade rejection, caught at the per-message boundary. */
public final class BindingFailure extends RuntimeException {
    private final String code;
    private final String bindingId;
    private final int clause;
    public BindingFailure(String code) {
        this(code, null, -1);
    }
    private BindingFailure(String code, String bindingId, int clause) {
        super(code, null, false, false);
        this.code = code;
        this.bindingId = bindingId;
        this.clause = clause;
    }
    public String code() { return code; }
    /** Adds diagnostic location without changing the stable consensus rejection code. */
    public BindingFailure at(String bindingId, int clause) { return new BindingFailure(code, bindingId, clause); }
    public String bindingId() { return bindingId; }
    public int clause() { return clause; }
}
