package org.yanoproject.x.integration.ipfs;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnicodeString;
import org.yanoproject.x.integration.ConnectorTargetFingerprint;
import org.yanoproject.x.integration.ConnectorFingerprintDomain;
import org.yanoproject.x.integration.internal.CanonicalCbor;
import org.yanoproject.x.integration.internal.ContractValidation;

/** Credential-free identity of the configured IPFS pin target. */
public final class IpfsTargetFingerprint {
    private IpfsTargetFingerprint() {
    }

    /**
     * Computes the credential-free commitment to an IPFS target alias.
     *
     * @param targetId the configured target alias
     * @return the resolved-target commitment
     */
    public static ConnectorTargetFingerprint compute(String targetId) {
        String id = ContractValidation.alias(targetId, "targetId");
        Array descriptor = new Array();
        descriptor.add(new UnicodeString(id));
        return ConnectorTargetFingerprint.compute(ConnectorFingerprintDomain.IPFS_TARGET,
                CanonicalCbor.encode(descriptor));
    }
}
