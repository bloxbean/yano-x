package org.yanoproject.x.execution.tck;

import org.yanoproject.x.framework.api.execution.ProgramArtifact;

/** The runtime author's TCK programs. */
@FunctionalInterface
public interface TckPrograms {

    /** The artifact for a program, or {@code null} if the runtime does not supply it (the check fails). */
    ProgramArtifact artifact(TckProgram program);
}
