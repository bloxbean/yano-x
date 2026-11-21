package org.yanoproject.x.execution.tck;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.yanoproject.x.execution.tck.reference.ReferenceTckPrograms;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The TCK against the reference runtime: shows the kit can pass, and the expected known gaps. */
class ReferenceRuntimeTckTest {

    @TestFactory
    List<DynamicTest> referenceRuntimeConforms() {
        return RuntimeTck.builder(ReferenceTckPrograms.factory(), new ReferenceTckPrograms()).build().dynamicTests();
    }

    @Test
    void referenceRuntimeReportsOnlyTheMemoryGap() {
        List<TckResult> results = RuntimeTck.builder(ReferenceTckPrograms.factory(), new ReferenceTckPrograms()).build().run();
        List<String> warnings = results.stream().filter(r -> r.status() == TckResult.Status.WARN).map(TckResult::id).toList();
        assertEquals(List.of("memory.bounded"), warnings);
        assertTrue(results.stream().noneMatch(r -> r.status() == TckResult.Status.FAIL), results::toString);
    }

}
