package com.bloxbean.cardano.yano.appchain.trust.profile;

import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;

import java.util.Objects;

/**
 * Answers the TRQP-shaped question "is entity E authorized for A under framework F at
 * height H" from an {@code issuers} entry. The answer is derived from chain state only; the
 * proof that the entry existed at that height travels beside it.
 */
public final class TrqpEvaluator {
    private TrqpEvaluator() {
    }

    public record Answer(boolean authorized, String reason) {
        public Answer {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    public static Answer evaluate(int presence, TrustRegistryValues.IssuerValue issuer,
                                  String framework, String authorization, long height) {
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(authorization, "authorization");
        if (presence == AuthenticatedMapContract.PRESENCE_ABSENT) {
            return new Answer(false, "entity is not registered");
        }
        if (presence == AuthenticatedMapContract.PRESENCE_REVOKED) {
            return new Answer(false, "entity registration was revoked");
        }
        Objects.requireNonNull(issuer, "issuer");
        if (!issuer.framework().equals(framework)) {
            return new Answer(false, "entity is registered under framework " + issuer.framework());
        }
        if (!issuer.authorizations().contains(authorization)) {
            return new Answer(false, "authorization is not granted");
        }
        if (height < issuer.validFromHeight()) {
            return new Answer(false, "registration is not yet valid at height " + height);
        }
        if (issuer.validUntilHeight() != 0 && height > issuer.validUntilHeight()) {
            return new Answer(false, "registration expired at height " + issuer.validUntilHeight());
        }
        return new Answer(true, "authorized");
    }
}
