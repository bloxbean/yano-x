package org.yanoproject.x.execution.tck.reference;

/** What a reference guest can reach: compute metering, call-depth accounting and host functions. */
public interface GuestEnv {

    /** Charges compute units. Guests call this in every loop iteration, as an engine would meter. */
    void tick(long units);

    /** Enters a guest call frame; the runtime enforces a deterministic depth limit. */
    void enter();

    void exit();

    /** Calls a host function by its table name. Absent and nothing are both {@code null}. */
    byte[] host(String name, byte[]... args);
}
