package org.yanoproject.x.execution.conformance;

import org.yanoproject.x.execution.tck.reference.ReferenceRuntimeFactory;
import org.yanoproject.x.execution.tck.reference.ReferenceTckPrograms;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.ExecutionRuntimeFactory;
import org.yanoproject.x.framework.api.execution.RuntimeOptions;

/**
 * The reference runtime with every TCK program, contributed as {@code execution-runtime} so that the
 * catalog's discovery, allow-listing and facades can be tested end to end. Test-only: reference guests
 * are trusted Java code.
 */
public final class ConformanceReferenceRuntimeFactory implements ExecutionRuntimeFactory {

    public static final String BUNDLE_ID = "org.yanoproject.fixture.execution-runtime";

    private final ReferenceRuntimeFactory delegate = ReferenceTckPrograms.factory();

    @Override
    public String runtimeId() {
        return delegate.runtimeId();
    }

    @Override
    public ExecutionRuntime open(RuntimeOptions options) {
        return delegate.open(options);
    }
}
