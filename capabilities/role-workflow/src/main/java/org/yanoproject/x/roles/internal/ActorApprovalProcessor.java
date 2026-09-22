package org.yanoproject.x.roles.internal;

import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.AppStateWriter;
import org.yanoproject.api.appchain.transition.StateMutation;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.x.roles.GovernedCryptoWork;
import org.yanoproject.x.roles.contracts.ActorKeyEpochV1;
import org.yanoproject.x.roles.contracts.ActorRecordV1;
import org.yanoproject.x.roles.contracts.ActorStatementV1;
import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.ApprovalProposalV1;
import org.yanoproject.x.roles.contracts.GovernedAuthorizationLimitsV1;
import org.yanoproject.x.roles.contracts.OrganizationRecordV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.roles.contracts.RoleApprovalStatsV1;
import org.yanoproject.x.roles.contracts.RoleWorkflowIdentifiers;
import org.yanoproject.x.roles.contracts.RoleWorkflowKeys;
import org.yanoproject.x.roles.contracts.RoleWorkflowResultCode;
import org.yanoproject.x.roles.contracts.RolePendingQueriesV1;
import org.yanoproject.x.roles.contracts.SignedActorCommandV1;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bounded actor-signed approval lifecycle shared by standalone workflows and declarative transition kernels.
 * Domain decisions return approval-owned plans; shared actor-owned signature-work reservations remain outside
 * those plans so a failed cascade cannot refund expensive authorization work.
 */
public final class ActorApprovalProcessor {
    private final String chainId;
    private final String payloadDomain;
    private final GovernedAuthorizationLimitsV1 limits;

    /**
     * Creates a product-neutral lifecycle; application targets verify the exact payload domain on use.
     *
     * @param chainId committed chain identity
     * @param limits committed authorization and lifecycle bounds
     */
    public ActorApprovalProcessor(
            String chainId,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = RoleWorkflowIdentifiers.chainId(chainId);
        this.payloadDomain = null;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Creates a lifecycle restricted to one application payload domain.
     *
     * @param chainId committed chain identity
     * @param payloadDomain exact domain required in every signed statement
     * @param limits committed authorization and lifecycle bounds
     */
    public ActorApprovalProcessor(
            String chainId,
            String payloadDomain,
            GovernedAuthorizationLimitsV1 limits
    ) {
        this.chainId = RoleWorkflowIdentifiers.chainId(chainId);
        this.payloadDomain = RoleWorkflowIdentifiers.payloadDomain(payloadDomain);
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Commits the shared pure expiry plan for a standalone workflow's block-maintenance phase.
     *
     * @param height candidate block height
     * @param approvalState approval-owned block writer
     */
    public void prepareHeight(long height, AppStateWriter approvalState) {
        commit(prepareHeightPlan(height, approvalState), approvalState);
    }

    /**
     * Plans bounded expiry maintenance, including blocks with no approval commands.
     * The returned plan changes only approval-owned state; reading it does not reclaim capacity until commit.
     *
     * @param height candidate block height
     * @param approvalState approval-owned authoritative or overlay view
     * @return complete expiry/index/statistics plan, with no effects or crypto reservations
     */
    public TransitionPlan prepareHeightPlan(long height, AppStateReader approvalState) {
        ApprovalPendingIndexV1 index = pendingIndex(approvalState);
        List<ApprovalPendingIndexV1.Entry> expired = index.entries().stream()
                .filter(entry -> entry.deadlineHeight() < height).toList();
        if (expired.size() > limits.maximumExpiryWorkPerBlock()) {
            throw new IllegalStateException("approval expiry work exceeds genesis bound");
        }
        ApprovalPendingIndexV1 updated = index;
        RoleApprovalStatsV1 totals = expired.isEmpty() ? null : stats(approvalState);
        List<StateMutation> mutations = new ArrayList<>();
        for (ApprovalPendingIndexV1.Entry entry : expired) {
            ApprovalProposalV1 due = proposal(approvalState, entry.proposalId());
            if (due == null) {
                throw new IllegalStateException(
                        "approval pending index points to an absent proposal");
            }
            requirePending(due, ApprovalProposalV1.ProposalStatus.EXPIRED);
            ApprovalProposalV1 terminal = copy(due, ApprovalProposalV1.ProposalStatus.EXPIRED, due.decisions());
            mutations.add(StateMutation.put(RoleWorkflowKeys.proposal(due.proposalId()), terminal.encode()));
            deleteMarkerPlans(mutations, due);
            totals = totals.terminal(ApprovalProposalV1.ProposalStatus.EXPIRED);
            updated = updated.remove(due.proposalId());
        }
        if (!expired.isEmpty()) mutations.add(StateMutation.put(RoleWorkflowKeys.approvalStats(), totals.encode()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalPendingIndex(), updated.encode()));
        return TransitionPlan.mutations(mutations);
    }

    /**
     * Standalone adapter: reserves signature work, evaluates the pure decision, then commits its domain plan.
     * Business rejection leaves any already-reserved actor work charged but makes no approval-owned writes.
     *
     * @param command decoded actor-signed command
     * @param height candidate block height
     * @param actorState actor-owned writer containing the shared work counter
     * @param approvalState approval-owned writer for proposal, index, and statistics state
     * @return stable domain outcome, including exact replay and work exhaustion
     */
    public RoleWorkflowResultCode apply(
            SignedActorCommandV1 command,
            long height,
            AppStateWriter actorState,
            AppStateWriter approvalState
    ) {
        RoleWorkflowResultCode precondition = precondition(command, height);
        if (precondition != null) return precondition;
        if (!GovernedCryptoWork.reserve(actorState, height, 1,
                limits.maximumCryptoWorkUnitsPerBlock())) {
            return RoleWorkflowResultCode.CRYPTO_WORK_EXCEEDED;
        }
        Result result = decide(command, height, facts(command, height, actorState, approvalState));
        commit(result.plan(), approvalState);
        return result.code();
    }

    /**
     * Reports whether legacy preconditions reach the shared signature-work reservation.
     * Wrong chain/domain and expired commands require no signature work and therefore reserve nothing.
     *
     * @param command decoded actor command
     * @param height candidate block height
     * @return true if the command reaches authorization work, even if its signature later fails
     */
    public boolean requiresCryptoWork(SignedActorCommandV1 command, long height) {
        return precondition(command, height) == null;
    }

    private RoleWorkflowResultCode precondition(SignedActorCommandV1 command, long height) {
        ActorStatementV1 statement = command.statement();
        if (!statement.chainId().equals(chainId)
                || payloadDomain != null && !statement.payloadDomain().equals(payloadDomain)) {
            return RoleWorkflowResultCode.WRONG_GENESIS;
        }
        return statement.deadlineHeight() < height ? RoleWorkflowResultCode.EXPIRED : null;
    }

    /**
     * Snapshots typed authorization and approval facts without retaining readers or performing signature work.
     * Callers must reserve the shared crypto budget before invoking this method and {@link #decide}.
     * Corrupt required approval records fail closed during snapshot construction, before signature rejection;
     * such corruption is an infrastructure invariant failure, not a business result or a refundable charge.
     *
     * @param command decoded command whose exact proposal/policy records are needed
     * @param height candidate block height used for actor-key eligibility
     * @param actorState actor-owned read view
     * @param approvalState approval-owned read view, including earlier cascade writes
     * @return immutable domain facts; no writer or state reader is retained
     */
    public Facts facts(SignedActorCommandV1 command, long height,
                       AppStateReader actorState, AppStateReader approvalState) {
        ActorStatementV1 statement = command.statement();
        if (!requiresCryptoWork(command, height)) return new Facts(null, null, null, 0, null, null);
        ActorEligibility actor = actorEligibility(actorState, statement, height);
        if (actor == null) return new Facts(null, null, null, 0, null, null);
        ApprovalProposalV1 existing = proposal(approvalState, statement.proposalId());
        long revision = 0;
        ApprovalPolicyV1 policy = null;
        if (statement.action() == ActorStatementV1.Action.PROPOSE && existing == null) {
            revision = pointer(approvalState, RoleWorkflowKeys.policyCurrent(statement.policyId()));
            if (revision != 0 && revision == statement.policyRevision()) {
                policy = policy(approvalState, statement.policyId(), revision);
            }
        } else if ((statement.action() == ActorStatementV1.Action.APPROVE
                || statement.action() == ActorStatementV1.Action.REJECT) && existing != null
                && existing.status() == ApprovalProposalV1.ProposalStatus.PENDING
                && proposalMatches(existing, statement)) {
            policy = policy(approvalState, existing.policyId(), existing.policyRevision());
        }
        return new Facts(actor, existing, policy, revision, pendingIndex(approvalState), stats(approvalState));
    }

    /**
     * Evaluates one command as a pure approval-owned plan, never reserving work or mutating state itself.
     * Both standalone and composite execution use this decision path. Rejected/replayed commands return
     * empty plans; callers must not infer acceptance from whether any mutations were produced.
     *
     * @param command command corresponding to the supplied facts
     * @param height candidate block height
     * @param facts immutable state snapshot from {@link #facts}
     * @return result code, complete plan, resulting proposal, and explicit change indicator
     */
    public Result decide(SignedActorCommandV1 command, long height, Facts facts) {
        RoleWorkflowResultCode precondition = precondition(command, height);
        if (precondition != null) return unchanged(precondition, facts.proposal());
        if (facts.actor() == null) return unchanged(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR, facts.proposal());
        if (!command.verify(facts.actor().key().publicKey())) {
            return unchanged(RoleWorkflowResultCode.INVALID_SIGNATURE, facts.proposal());
        }
        ActorStatementV1 statement = command.statement();
        return switch (statement.action()) {
            case PROPOSE -> propose(statement, facts, height);
            case APPROVE, REJECT -> vote(command, facts, height);
            case CANCEL -> cancel(statement, facts);
        };
    }

    /**
     * Immutable command facts. Nullable records represent absent or inapplicable state, not authorization.
     *
     * @param actor currently eligible actor/key, or null
     * @param proposal existing proposal, or null
     * @param policy exact required policy revision, or null
     * @param currentPolicyRevision current pointer used only for a new proposal
     * @param pendingIndex bounded pending index, absent when authorization cannot proceed
     * @param statistics approval counters, absent when authorization cannot proceed
     */
    public record Facts(ActorEligibility actor, ApprovalProposalV1 proposal, ApprovalPolicyV1 policy,
                        long currentPolicyRevision, ApprovalPendingIndexV1 pendingIndex,
                        RoleApprovalStatsV1 statistics) { }

    /**
     * Pure decision result. Changed proposals produce events only after an accepted result, not on replays.
     *
     * @param code stable domain outcome
     * @param plan complete approval-owned business writes, without work-accounting mutations
     * @param proposal resulting or existing proposal, nullable if it does not exist
     * @param changed true exactly when this decision changes proposal state
     */
    public record Result(RoleWorkflowResultCode code, TransitionPlan plan, ApprovalProposalV1 proposal,
                         boolean changed) { }

    private static Result unchanged(RoleWorkflowResultCode code, ApprovalProposalV1 proposal) {
        return new Result(code, TransitionPlan.empty(), proposal, false);
    }

    public boolean cancelByGovernance(
            String proposalId,
            AppStateWriter approvalState
    ) {
        ApprovalProposalV1 proposal = proposal(approvalState, proposalId);
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return false;
        }
        ApprovalPendingIndexV1 index = pendingIndex(approvalState);
        Result result = terminalPlan(proposal, index, stats(approvalState),
                ApprovalProposalV1.ProposalStatus.CANCELLED, proposal.decisions());
        commit(result.plan(), approvalState);
        return true;
    }

    public boolean canCancelByGovernance(
            String proposalId,
            AppStateWriter approvalState
    ) {
        ApprovalProposalV1 proposal = proposal(approvalState, proposalId);
        return proposal != null
                && proposal.status() == ApprovalProposalV1.ProposalStatus.PENDING;
    }

    public static RolePendingQueriesV1.ApprovalPage pendingPage(
            AppStateReader state,
            RolePendingQueriesV1.PageQuery query,
            int maximumPageSize
    ) {
        if (query.limit() > maximumPageSize) {
            throw new IllegalArgumentException("approval page exceeds genesis limit");
        }
        List<ApprovalPendingIndexV1.Entry> remaining = pendingIndex(state).entries()
                .stream().filter(entry -> query.afterId().isEmpty()
                        || entry.proposalId().compareTo(query.afterId()) > 0)
                .toList();
        List<ApprovalPendingIndexV1.Entry> selected = remaining.stream()
                .limit(query.limit()).toList();
        String next = remaining.size() > selected.size() && !selected.isEmpty()
                ? selected.getLast().proposalId() : "";
        return new RolePendingQueriesV1.ApprovalPage(selected.stream()
                .map(entry -> new RolePendingQueriesV1.ApprovalEntry(
                        entry.proposalId(), entry.deadlineHeight(), entry.policyId(),
                        entry.proposerActorId())).toList(), next);
    }

    /** Fails startup when the retained pending index, markers, or totals disagree. */
    public static void verifyPendingState(
            AppStateReader state,
            GovernedAuthorizationLimitsV1 limits
    ) {
        ApprovalPendingIndexV1 index = pendingIndex(state);
        if (index.entries().size() > limits.maximumPendingApprovals()) {
            throw new IllegalStateException("approval pending index exceeds genesis bound");
        }
        requireDimensionBound(index.entries(), ApprovalPendingIndexV1.Entry::proposerActorId,
                limits.maximumPendingPerActor(), "actor");
        requireDimensionBound(index.entries(), ApprovalPendingIndexV1.Entry::policyId,
                limits.maximumPendingPerPolicy(), "policy");
        requireDimensionBound(index.entries(), entry -> Long.toString(entry.deadlineHeight()),
                limits.maximumPendingPerDeadline(), "deadline");

        for (ApprovalPendingIndexV1.Entry entry : index.entries()) {
            ApprovalProposalV1 proposal = state.get(RoleWorkflowKeys.proposal(
                            entry.proposalId()))
                    .map(ApprovalProposalV1::decode)
                    .orElseThrow(() -> new IllegalStateException(
                            "approval pending index points to an absent proposal"));
            if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING
                    || proposal.deadlineHeight() != entry.deadlineHeight()
                    || !proposal.policyId().equals(entry.policyId())
                    || !proposal.proposerActorId().equals(entry.proposerActorId())) {
                throw new IllegalStateException(
                        "approval pending index is incompatible with proposal state");
            }
            requireMarker(state, RoleWorkflowKeys.approvalDeadline(
                    proposal.deadlineHeight(), proposal.proposalId()), proposal.payloadHash());
            requireMarker(state, RoleWorkflowKeys.approvalByActor(
                    proposal.proposerActorId(), proposal.proposalId()), proposal.payloadHash());
            requireMarker(state, RoleWorkflowKeys.approvalByPolicy(
                    proposal.policyId(), proposal.proposalId()), proposal.payloadHash());
        }
        RoleApprovalStatsV1 totals = state.get(RoleWorkflowKeys.approvalStats())
                .map(RoleApprovalStatsV1::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "approval aggregate statistics are absent"));
        if (totals.pending() != index.entries().size()) {
            throw new IllegalStateException(
                    "approval pending index disagrees with aggregate statistics");
        }
    }

    private Result propose(ActorStatementV1 statement, Facts facts, long height) {
        ActorEligibility actor = facts.actor();
        ApprovalProposalV1 existing = facts.proposal();
        if (existing != null) {
            return unchanged(proposalMatches(existing, statement)
                    ? RoleWorkflowResultCode.EXACT_REPLAY
                    : RoleWorkflowResultCode.CONFLICT, existing);
        }
        if (statement.deadlineHeight() <= height) {
            return unchanged(RoleWorkflowResultCode.EXPIRED, null);
        }
        long currentRevision = facts.currentPolicyRevision();
        if (currentRevision == 0) return unchanged(RoleWorkflowResultCode.UNKNOWN_RECORD, null);
        if (currentRevision != statement.policyRevision()) {
            return unchanged(RoleWorkflowResultCode.WRONG_REVISION, null);
        }
        ApprovalPolicyV1 policy = facts.policy();
        requirePolicyIdentity(policy, statement.policyId(), statement.policyRevision());
        if (policy.status() != RecordStatus.ACTIVE) {
            return unchanged(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR, null);
        }
        long maximumDeadline;
        try {
            maximumDeadline = Math.addExact(height, policy.maximumLifetimeBlocks());
        } catch (ArithmeticException exhausted) {
            maximumDeadline = Long.MAX_VALUE;
        }
        if (statement.deadlineHeight() > maximumDeadline) {
            return unchanged(RoleWorkflowResultCode.LIMIT_EXCEEDED, null);
        }
        String proposerRole = policy.proposerRoles().isEmpty()
                ? actor.actor().roles().getFirst()
                : policy.proposerRoles().stream()
                .filter(actor.actor().roles()::contains).findFirst().orElse(null);
        if (proposerRole == null) return unchanged(RoleWorkflowResultCode.ROLE_MISMATCH, null);

        ApprovalPendingIndexV1 index = facts.pendingIndex();
        RoleApprovalStatsV1 currentStats = facts.statistics();
        if (currentStats.pending() >= limits.maximumPendingApprovals()) {
            return unchanged(RoleWorkflowResultCode.CAPACITY_EXCEEDED, null);
        }
        if (currentStats.pending() != index.entries().size()) {
            throw new IllegalStateException(
                    "approval pending index disagrees with aggregate statistics");
        }
        if (!hasCapacity(index, statement.actorId(), statement.policyId(),
                statement.deadlineHeight())) {
            return unchanged(RoleWorkflowResultCode.CAPACITY_EXCEEDED, null);
        }
        ApprovalProposalV1 created = new ApprovalProposalV1(
                statement.proposalId(), statement.policyId(),
                statement.policyRevision(), policy.digest(), statement.payloadDomain(),
                statement.payloadHash(), statement.deadlineHeight(),
                ApprovalProposalV1.ProposalStatus.PENDING, statement.actorId(),
                actor.actor().organizationId(), actor.organization().revision(),
                proposerRole, statement.actorRevision(), statement.keyId(), height,
                List.of());
        List<StateMutation> mutations = new ArrayList<>();
        mutations.add(StateMutation.put(RoleWorkflowKeys.proposal(statement.proposalId()), created.encode()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalStats(), currentStats.proposalCreated().encode()));
        putMarkerPlans(mutations, created);
        ApprovalPendingIndexV1 updated = index.add(new ApprovalPendingIndexV1.Entry(
                created.proposalId(), created.deadlineHeight(), created.policyId(),
                created.proposerActorId()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalPendingIndex(), updated.encode()));
        return new Result(RoleWorkflowResultCode.ACCEPTED, TransitionPlan.mutations(mutations), created, true);
    }

    private Result vote(SignedActorCommandV1 command, Facts facts, long height) {
        ActorEligibility actor = facts.actor();
        ActorStatementV1 statement = command.statement();
        ApprovalProposalV1 proposal = facts.proposal();
        if (proposal == null) return unchanged(RoleWorkflowResultCode.UNKNOWN_RECORD, null);
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return unchanged(RoleWorkflowResultCode.TERMINAL, proposal);
        }
        if (!proposalMatches(proposal, statement)) {
            return unchanged(RoleWorkflowResultCode.CONFLICT, proposal);
        }
        ApprovalPolicyV1 policy = facts.policy();
        requirePolicyIdentity(policy, proposal.policyId(), proposal.policyRevision());
        if (!MessageDigest.isEqual(policy.digest(), proposal.policyDigest())) {
            throw new IllegalStateException("proposal policy revision is incompatible");
        }
        ApprovalProposalV1.AcceptedDecisionV1 prior = proposal.decisions().stream()
                .filter(decision -> decision.actorId().equals(statement.actorId()))
                .findFirst().orElse(null);
        if (prior != null) {
            return unchanged(MessageDigest.isEqual(prior.statementDigest(), statement.digest())
                    ? RoleWorkflowResultCode.EXACT_REPLAY
                    : RoleWorkflowResultCode.CONFLICT, proposal);
        }
        ApprovalPolicyV1.RequiredClause clause = policy.clause(statement.clauseId());
        if (clause == null || !actor.actor().roles().contains(clause.role())) {
            return unchanged(RoleWorkflowResultCode.ROLE_MISMATCH, proposal);
        }
        if (clause.distinctBy() == ApprovalPolicyV1.DistinctBy.ORGANIZATION
                && proposal.decisions().stream().anyMatch(decision ->
                decision.clauseId().equals(clause.clauseId())
                        && decision.organizationId()
                        .equals(actor.actor().organizationId()))) {
            return unchanged(RoleWorkflowResultCode.DISTINCTNESS_DUPLICATE, proposal);
        }
        if (statement.action() == ActorStatementV1.Action.REJECT
                && policy.rejectionMode() == ApprovalPolicyV1.RejectionMode.DISABLED) {
            return unchanged(RoleWorkflowResultCode.ROLE_MISMATCH, proposal);
        }
        ApprovalProposalV1.AcceptedDecisionV1 decision =
                new ApprovalProposalV1.AcceptedDecisionV1(
                        statement.action(), statement.actorId(),
                        actor.actor().organizationId(), actor.organization().revision(),
                        clause.role(), statement.actorRevision(), statement.keyId(),
                        statement.clauseId(), statement.digest(), command.signature(), height);
        List<ApprovalProposalV1.AcceptedDecisionV1> decisions =
                new ArrayList<>(proposal.decisions());
        decisions.add(decision);
        ApprovalProposalV1.ProposalStatus status = statement.action()
                == ActorStatementV1.Action.REJECT
                ? ApprovalProposalV1.ProposalStatus.REJECTED
                : satisfied(policy, decisions)
                ? ApprovalProposalV1.ProposalStatus.APPROVED
                : ApprovalProposalV1.ProposalStatus.PENDING;
        if (status == ApprovalProposalV1.ProposalStatus.PENDING) {
            ApprovalProposalV1 updated = copy(proposal, status, decisions);
            return new Result(RoleWorkflowResultCode.ACCEPTED, TransitionPlan.mutations(List.of(
                    StateMutation.put(RoleWorkflowKeys.proposal(proposal.proposalId()), updated.encode()))),
                    updated, true);
        }
        return terminalPlan(proposal, facts.pendingIndex(), facts.statistics(), status, decisions);
    }

    private Result cancel(ActorStatementV1 statement, Facts facts) {
        ApprovalProposalV1 proposal = facts.proposal();
        if (proposal == null) return unchanged(RoleWorkflowResultCode.UNKNOWN_RECORD, null);
        if (proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING) {
            return unchanged(RoleWorkflowResultCode.TERMINAL, proposal);
        }
        if (!proposalMatches(proposal, statement)) {
            return unchanged(RoleWorkflowResultCode.CONFLICT, proposal);
        }
        if (!proposal.proposerActorId().equals(statement.actorId())) {
            return unchanged(RoleWorkflowResultCode.UNAUTHORIZED_ACTOR, proposal);
        }
        return terminalPlan(proposal, facts.pendingIndex(), facts.statistics(),
                ApprovalProposalV1.ProposalStatus.CANCELLED, proposal.decisions());
    }

    private boolean hasCapacity(
            ApprovalPendingIndexV1 index,
            String actorId,
            String policyId,
            long deadlineHeight
    ) {
        return index.entries().size() < limits.maximumPendingApprovals()
                && index.entries().stream().filter(entry ->
                entry.proposerActorId().equals(actorId)).count()
                < limits.maximumPendingPerActor()
                && index.entries().stream().filter(entry ->
                entry.policyId().equals(policyId)).count()
                < limits.maximumPendingPerPolicy()
                && index.entries().stream().filter(entry ->
                entry.deadlineHeight() == deadlineHeight).count()
                < limits.maximumPendingPerDeadline();
    }

    private static boolean satisfied(
            ApprovalPolicyV1 policy,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        for (ApprovalPolicyV1.RequiredClause clause : policy.clauses()) {
            long count = decisions.stream()
                    .filter(decision ->
                            decision.action() == ActorStatementV1.Action.APPROVE)
                    .filter(decision -> decision.clauseId().equals(clause.clauseId()))
                    .map(decision -> clause.distinctBy()
                            == ApprovalPolicyV1.DistinctBy.ACTOR
                            ? decision.actorId() : decision.organizationId())
                    .distinct().count();
            if (count < clause.minimumCount()) return false;
        }
        return true;
    }

    private static Result terminalPlan(
            ApprovalProposalV1 proposal,
            ApprovalPendingIndexV1 index,
            RoleApprovalStatsV1 statistics,
            ApprovalProposalV1.ProposalStatus status,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        requirePending(proposal, status);
        indexEntry(index, proposal.proposalId());
        ApprovalProposalV1 updated = copy(proposal, status, decisions);
        List<StateMutation> mutations = new ArrayList<>();
        mutations.add(StateMutation.put(RoleWorkflowKeys.proposal(proposal.proposalId()), updated.encode()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalStats(), statistics.terminal(status).encode()));
        deleteMarkerPlans(mutations, proposal);
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalPendingIndex(),
                index.remove(proposal.proposalId()).encode()));
        return new Result(RoleWorkflowResultCode.ACCEPTED, TransitionPlan.mutations(mutations), updated, true);
    }

    private static void requirePending(ApprovalProposalV1 proposal, ApprovalProposalV1.ProposalStatus status) {
        if (proposal == null
                || proposal.status() != ApprovalProposalV1.ProposalStatus.PENDING
                || status == ApprovalProposalV1.ProposalStatus.PENDING) {
            throw new IllegalStateException("invalid pending approval transition");
        }
    }

    private static ApprovalProposalV1 copy(
            ApprovalProposalV1 proposal,
            ApprovalProposalV1.ProposalStatus status,
            List<ApprovalProposalV1.AcceptedDecisionV1> decisions
    ) {
        return new ApprovalProposalV1(
                proposal.proposalId(), proposal.policyId(), proposal.policyRevision(),
                proposal.policyDigest(), proposal.payloadDomain(), proposal.payloadHash(),
                proposal.deadlineHeight(), status, proposal.proposerActorId(),
                proposal.proposerOrganizationId(),
                proposal.proposerOrganizationRevision(), proposal.proposerRole(),
                proposal.proposerActorRevision(), proposal.proposerKeyId(),
                proposal.createdHeight(), decisions);
    }

    private static boolean proposalMatches(
            ApprovalProposalV1 proposal,
            ActorStatementV1 statement
    ) {
        return proposal.policyId().equals(statement.policyId())
                && proposal.policyRevision() == statement.policyRevision()
                && proposal.payloadDomain().equals(statement.payloadDomain())
                && MessageDigest.isEqual(proposal.payloadHash(), statement.payloadHash())
                && proposal.deadlineHeight() == statement.deadlineHeight();
    }

    private static ActorEligibility actorEligibility(
            AppStateReader state,
            ActorStatementV1 statement,
            long height
    ) {
        long actorRevision = pointer(state,
                RoleWorkflowKeys.actorCurrent(statement.actorId()));
        if (actorRevision == 0 || actorRevision != statement.actorRevision()) return null;
        ActorRecordV1 actor = state.get(RoleWorkflowKeys.actorRevision(
                        statement.actorId(), actorRevision))
                .map(bytes -> decodeState(bytes, ActorRecordV1::decode, "actor"))
                .orElseThrow(() -> new IllegalStateException(
                        "actor current pointer is dangling"));
        long organizationRevision = pointer(state,
                RoleWorkflowKeys.organizationCurrent(actor.organizationId()));
        if (organizationRevision == 0) return null;
        OrganizationRecordV1 organization = state.get(
                        RoleWorkflowKeys.organizationRevision(
                                actor.organizationId(), organizationRevision))
                .map(bytes -> decodeState(bytes, OrganizationRecordV1::decode, "organization"))
                .orElseThrow(() -> new IllegalStateException(
                        "organization current pointer is dangling"));
        ActorKeyEpochV1 key = actor.key(statement.keyId());
        if (!actor.actorId().equals(statement.actorId())
                || actor.revision() != actorRevision
                || !organization.organizationId().equals(actor.organizationId())
                || organization.revision() != organizationRevision
                || actor.status() != RecordStatus.ACTIVE
                || organization.status() != RecordStatus.ACTIVE
                || key == null || !key.activeAt(height)) {
            return null;
        }
        return new ActorEligibility(actor, organization, key);
    }

    private static ApprovalPolicyV1 policy(
            AppStateReader state,
            String policyId,
            long revision
    ) {
        return state.get(RoleWorkflowKeys.policyRevision(policyId, revision))
                .map(bytes -> decodeState(bytes, ApprovalPolicyV1::decode, "approval policy"))
                .orElseThrow(() -> new IllegalStateException(
                        "approval policy revision is absent"));
    }

    private static void requirePolicyIdentity(
            ApprovalPolicyV1 policy,
            String policyId,
            long revision
    ) {
        if (!policy.policyId().equals(policyId) || policy.revision() != revision) {
            throw new IllegalStateException("approval policy revision is incompatible");
        }
    }

    private static ApprovalProposalV1 proposal(AppStateReader state, String proposalId) {
        return state.get(RoleWorkflowKeys.proposal(proposalId))
                .map(bytes -> decodeState(bytes, ApprovalProposalV1::decode, "approval proposal")).orElse(null);
    }

    private static RoleApprovalStatsV1 stats(AppStateReader state) {
        return state.get(RoleWorkflowKeys.approvalStats())
                .map(bytes -> decodeState(bytes, RoleApprovalStatsV1::decode, "approval statistics"))
                .orElseGet(RoleApprovalStatsV1::empty);
    }

    private static ApprovalPendingIndexV1 pendingIndex(AppStateReader state) {
        return state.get(RoleWorkflowKeys.approvalPendingIndex())
                .map(bytes -> decodeState(bytes, ApprovalPendingIndexV1::decode, "approval pending index"))
                .orElseGet(ApprovalPendingIndexV1::empty);
    }

    private static ApprovalPendingIndexV1.Entry indexEntry(
            ApprovalPendingIndexV1 index,
            String proposalId
    ) {
        return index.entries().stream()
                .filter(entry -> entry.proposalId().equals(proposalId))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "pending proposal is absent from approval index"));
    }

    private static void putMarkerPlans(List<StateMutation> mutations, ApprovalProposalV1 proposal) {
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalDeadline(
                proposal.deadlineHeight(), proposal.proposalId()),
                proposal.payloadHash()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalByActor(
                proposal.proposerActorId(), proposal.proposalId()),
                proposal.payloadHash()));
        mutations.add(StateMutation.put(RoleWorkflowKeys.approvalByPolicy(
                proposal.policyId(), proposal.proposalId()), proposal.payloadHash()));
    }

    private static void deleteMarkerPlans(List<StateMutation> mutations, ApprovalProposalV1 proposal) {
        mutations.add(StateMutation.delete(RoleWorkflowKeys.approvalDeadline(
                proposal.deadlineHeight(), proposal.proposalId())));
        mutations.add(StateMutation.delete(RoleWorkflowKeys.approvalByActor(
                proposal.proposerActorId(), proposal.proposalId())));
        mutations.add(StateMutation.delete(RoleWorkflowKeys.approvalByPolicy(
                proposal.policyId(), proposal.proposalId())));
    }

    private static <T> void requireDimensionBound(
            List<T> values,
            Function<T, String> classifier,
            int maximum,
            String dimension
    ) {
        Map<String, Long> counts = values.stream().collect(Collectors.groupingBy(
                classifier, Collectors.counting()));
        if (counts.values().stream().anyMatch(count -> count > maximum)) {
            throw new IllegalStateException(
                    "approval pending " + dimension + " count exceeds genesis bound");
        }
    }

    private static void requireMarker(
            AppStateReader state,
            byte[] key,
            byte[] expected
    ) {
        byte[] actual = state.get(key).orElseThrow(() -> new IllegalStateException(
                "approval pending marker is absent"));
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IllegalStateException("approval pending marker is incompatible");
        }
    }

    private static long pointer(AppStateReader state, byte[] key) {
        byte[] encoded = state.get(key).orElse(null);
        if (encoded == null) return 0;
        if (encoded.length != Long.BYTES) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        long revision = ByteBuffer.wrap(encoded).getLong();
        if (revision < 1) {
            throw new IllegalStateException("corrupt role-workflow pointer");
        }
        return revision;
    }

    /** Corrupt authenticated records are infrastructure failures, never malformed submitted commands. */
    private static <T> T decodeState(byte[] bytes, Function<byte[], T> decoder, String kind) {
        try {
            return decoder.apply(bytes);
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException("corrupt " + kind + " state", corrupt);
        }
    }

    /**
     * Snapshot of active identity records and the eligible signing-key epoch; signature verification is separate.
     *
     * @param actor current actor revision
     * @param organization current active organization revision
     * @param key actor key epoch active at the executing height
     */
    public record ActorEligibility(
            ActorRecordV1 actor,
            OrganizationRecordV1 organization,
            ActorKeyEpochV1 key
    ) {
    }

    /** Applies an already complete, effect-free domain plan without interpreting its result. */
    private static void commit(TransitionPlan plan, AppStateWriter state) {
        for (StateMutation mutation : plan.mutations()) {
            if (mutation.kind() == StateMutation.Kind.PUT) state.put(mutation.key(), mutation.value());
            else state.delete(mutation.key());
        }
    }
}
