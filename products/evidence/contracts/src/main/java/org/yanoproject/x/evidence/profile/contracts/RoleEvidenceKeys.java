package org.yanoproject.x.evidence.profile.contracts;

import org.yanoproject.x.roles.contracts.RoleWorkflowIdentifiers;

import java.nio.charset.StandardCharsets;

/** Evidence-profile state keys stored within the generic role-approvals component namespace. */
public final class RoleEvidenceKeys {
    private RoleEvidenceKeys() {
    }

    public static byte[] evidenceApproval(String evidenceId, long businessVersion) {
        if (businessVersion < 1) {
            throw new IllegalArgumentException("businessVersion must be positive");
        }
        return ("e/" + RoleWorkflowIdentifiers.id(evidenceId, "evidenceId")
                + "/v/" + businessVersion + "/approval")
                .getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] approvalConsumption(String proposalId) {
        return ("approval-consumption/"
                + RoleWorkflowIdentifiers.id(proposalId, "proposalId"))
                .getBytes(StandardCharsets.US_ASCII);
    }
}
