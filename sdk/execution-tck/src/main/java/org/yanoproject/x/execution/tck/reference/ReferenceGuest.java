package org.yanoproject.x.execution.tck.reference;

import java.util.Set;

/** A guest program written in Java. Guests must reach the host only through {@link GuestEnv}. */
public interface ReferenceGuest {

    Set<String> exports();

    /** Host functions this guest imports; checked against the table at load time. */
    Set<String> imports();

    /** Runs an entry point. Return {@code null} to simulate a guest that returns no byte string. */
    byte[] invoke(String export, byte[] input, GuestEnv env);
}
