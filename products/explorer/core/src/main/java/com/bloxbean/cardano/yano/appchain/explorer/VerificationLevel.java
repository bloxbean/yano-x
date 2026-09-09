package com.bloxbean.cardano.yano.appchain.explorer;

/** What the follower established for one block at ingest (ADR-050 §2.1); rows inherit it. */
public enum VerificationLevel {
    /** Canonical block and certificate verified under caller-pinned members. */
    VERIFIED_PINNED,
    /** Canonical block and certificate verified under the evidence bundle's declared members. */
    VERIFIED_DECLARED,
    /** No message, so no evidence bundle: the header comes from the node's JSON view only. */
    HEADER_ONLY,
    /** Evidence no longer retained by the node: rows come from the JSON view only. */
    JSON_ONLY;

    public boolean verified() {
        return this == VERIFIED_PINNED || this == VERIFIED_DECLARED;
    }
}
