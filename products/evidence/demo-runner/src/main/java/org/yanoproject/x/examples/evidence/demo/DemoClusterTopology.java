package org.yanoproject.x.examples.evidence.demo;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed validation of the fixed ADR-013 Milestone 1 demo topology.
 * Anchor address/policy are cluster-observed identity, not an independent
 * trust anchor; authenticity still comes from pinned app-chain membership.
 */
record DemoClusterTopology(Set<String> memberKeys,
                           int threshold,
                           String stateMachine,
                           String observedAnchorScriptAddress,
                           String observedAnchorThreadPolicyId) {
    static final int EXPECTED_MEMBERS = 3;
    static final int EXPECTED_THRESHOLD = 2;
    static final String EXPECTED_STATE_MACHINE = "evidence-registry";

    DemoClusterTopology {
        memberKeys = Set.copyOf(memberKeys);
    }

    static DemoClusterTopology verify(List<YanoAuditClient.Status> statuses,
                                      Set<String> expectedMemberKeys,
                                      int expectedThreshold) {
        return verify(statuses, expectedMemberKeys, expectedThreshold, EXPECTED_STATE_MACHINE);
    }

    static DemoClusterTopology verify(List<YanoAuditClient.Status> statuses,
                                      Set<String> expectedMemberKeys,
                                      int expectedThreshold,
                                      String expectedStateMachine) {
        if (statuses == null || statuses.size() != EXPECTED_MEMBERS
                || expectedMemberKeys == null
                || expectedMemberKeys.size() != EXPECTED_MEMBERS
                || expectedMemberKeys.stream().anyMatch(
                key -> key == null || !key.matches("[0-9a-f]{64}"))
                || expectedThreshold != EXPECTED_THRESHOLD) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED, "TOPOLOGY_INPUT");
        }
        Set<String> identities = new LinkedHashSet<>();
        for (YanoAuditClient.Status status : statuses) {
            if (status == null) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "MISSING_STATUS");
            }
            if (status.members() != EXPECTED_MEMBERS) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "MEMBER_COUNT");
            }
            if (status.threshold() != expectedThreshold) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "THRESHOLD");
            }
            if (!expectedStateMachine.equals(status.stateMachine())) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "STATE_MACHINE");
            }
            if (!identities.add(status.memberKey())) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "DUPLICATE_MEMBER_KEY");
            }
        }
        if (identities.size() != EXPECTED_MEMBERS
                || !identities.equals(expectedMemberKeys)) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED, "MEMBER_KEY_SET");
        }
        YanoAuditClient.Status observed = statuses.getFirst();
        return new DemoClusterTopology(identities, observed.threshold(),
                observed.stateMachine(), null, null);
    }

    static DemoClusterTopology verifyAnchored(List<YanoAuditClient.Status> statuses,
                                              Set<String> expectedMemberKeys,
                                              int expectedThreshold) {
        return verifyAnchored(statuses, expectedMemberKeys, expectedThreshold,
                EXPECTED_STATE_MACHINE);
    }

    static DemoClusterTopology verifyAnchored(List<YanoAuditClient.Status> statuses,
                                              Set<String> expectedMemberKeys,
                                              int expectedThreshold,
                                              String expectedStateMachine) {
        DemoClusterTopology base = verify(
                statuses, expectedMemberKeys, expectedThreshold, expectedStateMachine);
        String scriptAddress = statuses.getFirst().anchorScriptAddress();
        String threadPolicyId = statuses.getFirst().anchorThreadPolicyId();
        if (scriptAddress == null || scriptAddress.isBlank()
                || threadPolicyId == null
                || !threadPolicyId.matches("[0-9a-f]{56}")) {
            throw new DemoException(DemoError.CLUSTER_DIVERGED, "ANCHOR_IDENTITY");
        }
        for (YanoAuditClient.Status status : statuses) {
            if (!scriptAddress.equals(status.anchorScriptAddress())
                    || !threadPolicyId.equals(status.anchorThreadPolicyId())) {
                throw new DemoException(DemoError.CLUSTER_DIVERGED, "ANCHOR_IDENTITY");
            }
        }
        return new DemoClusterTopology(base.memberKeys(), base.threshold(),
                base.stateMachine(), scriptAddress, threadPolicyId);
    }

    int members() {
        return memberKeys.size();
    }
}
