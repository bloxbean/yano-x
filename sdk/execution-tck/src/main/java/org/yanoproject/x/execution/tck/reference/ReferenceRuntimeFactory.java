package org.yanoproject.x.execution.tck.reference;

import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Factory for the reference runtime. A program artifact is the UTF-8 name of a registered guest;
 * every instance gets a new guest object from the registered supplier.
 *
 * <p>The reference runtime exists to test the SPI, the TCK and the execution adapter. Its guests are
 * trusted Java code with no sandbox, so it is never a production runtime.</p>
 */
public final class ReferenceRuntimeFactory implements ExecutionRuntimeFactory {

    public static final String RUNTIME_ID = "reference";
    public static final String MEDIA_TYPE = "application/x-yano-reference";

    private final Map<String, Supplier<ReferenceGuest>> programs;

    public ReferenceRuntimeFactory(Map<String, Supplier<ReferenceGuest>> programs) {
        this.programs = programs;
    }

    @Override
    public String runtimeId() {
        return RUNTIME_ID;
    }

    @Override
    public ExecutionRuntime open(RuntimeOptions options) {
        return new ReferenceRuntime(programs);
    }
}
