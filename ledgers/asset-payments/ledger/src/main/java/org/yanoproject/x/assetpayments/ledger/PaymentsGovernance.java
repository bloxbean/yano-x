package org.yanoproject.x.assetpayments.ledger;

import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Command;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.DecisionV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Proposal;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.RegistryEntryV1;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainMembershipEpoch;
import org.yanoproject.x.framework.api.appchain.AppChainMembershipView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The ledger's threshold-governed commands (ADR-059 D14 "{@code ASSET_*}, {@code PAUSE} / {@code RESUME}", D8a),
 * decided in {@code apply()} from finalized {@link PaymentsGovernanceV1#TOPIC} messages, the way the EUTxO bridge
 * decides its governed parameters: approvals from distinct members of the membership epoch at the block's height
 * accumulate on the exact command bytes, and the epoch's threshold decides the command.
 *
 * <p><b>Asset lifecycle</b> (D14). {@code ACTIVE}, {@code DEPOSITS_PAUSED}, {@code TRANSFERS_PAUSED} and
 * {@code WIND_DOWN} move freely among each other; {@code RETIRED} is terminal. It is accepted <b>only from
 * {@code WIND_DOWN}</b>, and only once the vault has been in {@code WIND_DOWN} for at least
 * {@code retire-after-wind-down-ms} of block time ({@code ap/wind-down/<VaultId>} records when it entered), a window
 * that covers the L1 stability depth and app finality. {@code WIND_DOWN} credits no deposit, so a deposit that was
 * already on L1 but not yet stable when the wind-down began is observed and resolved within the window, before
 * retirement can be decided: retirement never races a pending deposit. It is then refused while the vault has any
 * liability or unpaid claim: available or reserved value, in the vault's asset or in lovelace (min-ADA credits and
 * claims' {@code vaultLovelace}). A {@code PAID_MISMATCH} claim keeps its value reserved (D10), so it blocks
 * retirement until the incident procedure resolves it. Every finalized deposit is resolved in the block that carries
 * it ({@code CREDITED}, {@code UNCREDITED} or {@code UNATTRIBUTED}, D6), so the ledger holds no deposit awaiting a
 * decision; a deposit that lands on L1 after retirement is recorded {@code UNCREDITED(DEPOSITS_PAUSED)} and leaves
 * through the exit-mode {@code Refund} (D8f) or a governed sweep (D8d), as every uncredited deposit does. Neither the
 * {@code AssetId} nor the vault binding ever changes (D14).
 *
 * <p><b>Domain status leaf</b> (D8a). {@code PAUSE} moves it {@code ACTIVE → PAUSED}, {@code RESUME}
 * {@code PAUSED → ACTIVE}, each with the deciding block's height and timestamp as {@code since} and the leaf's exit
 * delay kept. The brake takes effect on L1 at the first anchor of that height or later, through the status leaf the
 * vault's {@code Exit} proves (V3), and members stop co-signing {@code Settle} once committed state is not
 * {@code ACTIVE} (D9). A {@code HALTED} leaf (D10 {@code PAID_MISMATCH}) is neither paused nor resumed here.
 *
 * <p><b>Replay safety.</b> A command names the number of governance commands decided so far; deciding one, applied
 * or refused, advances that number, writes its {@link DecisionV1} history record and voids every open proposal.
 * Every input that does not count (malformed, from a non-member, stale, a repeated approval) changes nothing but the
 * deterministic pruning of expired proposals.
 *
 * <p><b>Proposal capacity.</b> A member holds at most one open proposal it started; its newer command replaces it
 * (dropping the approvals the older one had), and approving another member's proposal uses no slot. One member can
 * therefore never crowd out the others: a {@code PAUSE} started by any member always gets a slot.
 */
final class PaymentsGovernance {
    /** What one governance message did; node-local diagnostics only. */
    enum Result { NO_MEMBERSHIP, MALFORMED, NOT_A_MEMBER, STALE, REPEATED, PROPOSALS_FULL, PENDING, APPLIED, REFUSED }

    private final AppChainMembershipView membership;
    private final byte[] domainStatusKey;
    private final long retireAfterWindDownMs;

    PaymentsGovernance(AppChainMembershipView membership, byte[] domainStatusKey, long retireAfterWindDownMs) {
        this.membership = membership;
        this.domainStatusKey = domainStatusKey.clone();
        this.retireAfterWindDownMs = retireAfterWindDownMs;
    }

    Result process(AssetAccounts.Store state, byte[] sender, byte[] body, AppBlock block) {
        if (membership == null) return Result.NO_MEMBERSHIP;
        Command command;
        try {
            command = Command.decode(body);
        } catch (IllegalArgumentException malformed) {
            return Result.MALFORMED;
        }
        AppChainMembershipEpoch epoch = membership.epochAt(block.height());
        if (epoch == null || sender == null || sender.length != 32
                || !epoch.members().contains(HexFormat.of().formatHex(sender))) {
            return Result.NOT_A_MEMBER;
        }
        long sequence = sequence(state);
        if (command.sequence() != sequence) return Result.STALE;

        List<Proposal> stored = state.get(AssetPaymentsContract.governanceProposalsKey())
                .map(PaymentsGovernanceV1::decodeProposals).orElse(List.of());
        List<Proposal> live = new ArrayList<>();
        for (Proposal proposal : stored) {
            // An approval binds the epoch it was given in and lives for the approval window.
            if (proposal.epochFromHeight() == epoch.fromHeight()
                    && block.height() - proposal.firstHeight() < PaymentsGovernanceV1.APPROVAL_WINDOW_BLOCKS) {
                live.add(proposal);
            }
        }
        boolean pruned = live.size() != stored.size();
        byte[] bytes = command.encode();
        int index = -1;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).sameCommand(bytes)) index = i;
        }
        Proposal proposal;
        if (index < 0) {
            // The sender's newer command replaces the proposal it started before, so the table holds at most one
            // proposal per member of the epoch and no member can crowd out the others (D15 PAUSE).
            live.removeIf(open -> open.proposedBy(sender));
            if (live.size() >= PaymentsGovernanceV1.MAX_OPEN_PROPOSALS) {
                // Unreachable while an epoch has at most MAX_APPROVERS members; kept as a bound on the record.
                if (pruned) writeProposals(state, live);
                return Result.PROPOSALS_FULL;
            }
            proposal = new Proposal(bytes, epoch.fromHeight(), block.height(), sender, List.of(sender));
            live.add(proposal);
        } else {
            if (live.get(index).contains(sender)) {
                if (pruned) writeProposals(state, live);
                return Result.REPEATED;
            }
            proposal = live.get(index).with(sender);
            live.set(index, proposal);
        }
        if (proposal.approvers().size() < epoch.threshold()) {
            writeProposals(state, live);
            return Result.PENDING;
        }
        DecisionV1 decision = decide(state, sequence, bytes, command, proposal.approvers(), block);
        state.put(AssetPaymentsContract.governanceHistoryKey(sequence), decision.encode());
        state.put(AssetPaymentsContract.governanceSequenceKey(), PaymentsRecords.encodeU64s(sequence + 1));
        state.delete(AssetPaymentsContract.governanceProposalsKey());
        return decision.outcome() == PaymentsGovernanceV1.Outcome.APPLIED ? Result.APPLIED : Result.REFUSED;
    }

    static long sequence(AssetAccounts.Store state) {
        return state.get(AssetPaymentsContract.governanceSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
    }

    private static void writeProposals(AssetAccounts.Store state, List<Proposal> proposals) {
        if (proposals.isEmpty()) {
            state.delete(AssetPaymentsContract.governanceProposalsKey());
        } else {
            state.put(AssetPaymentsContract.governanceProposalsKey(), PaymentsGovernanceV1.encodeProposals(proposals));
        }
    }

    private DecisionV1 decide(AssetAccounts.Store state, long sequence, byte[] bytes, Command command,
                              List<byte[]> approvers, AppBlock block) {
        return switch (command.action()) {
            case PaymentsGovernanceV1.AssetStatusChange change -> assetStatus(state, sequence, bytes, change,
                    approvers, block);
            case PaymentsGovernanceV1.Pause ignored -> domainStatus(state, sequence, bytes, approvers, block,
                    DomainStatusV1.Status.ACTIVE, DomainStatusV1.Status.PAUSED);
            case PaymentsGovernanceV1.Resume ignored -> domainStatus(state, sequence, bytes, approvers, block,
                    DomainStatusV1.Status.PAUSED, DomainStatusV1.Status.ACTIVE);
        };
    }

    private DecisionV1 assetStatus(AssetAccounts.Store state, long sequence, byte[] bytes,
                                          PaymentsGovernanceV1.AssetStatusChange change, List<byte[]> approvers,
                                          AppBlock block) {
        Hash32 vaultId = change.vaultId();
        AssetStatus to = change.status();
        Optional<RegistryEntryV1> entry = state.get(AssetPaymentsContract.registryKey(vaultId))
                .map(RegistryEntryV1::decode);
        if (entry.isEmpty()) {
            return refused(sequence, bytes, block, approvers, Optional.of(vaultId), to.code(), "UNKNOWN_VAULT");
        }
        AssetStatus from = entry.get().status();
        String refusal = null;
        if (from == to) {
            refusal = "NO_CHANGE";
        } else if (from == AssetStatus.RETIRED) {
            refusal = "RETIRED_IS_FINAL";
        } else if (to == AssetStatus.RETIRED && from != AssetStatus.WIND_DOWN) {
            refusal = "NOT_WOUND_DOWN";
        } else if (to == AssetStatus.RETIRED && !windDownElapsed(state, vaultId, block.timestamp())) {
            refusal = "WIND_DOWN_WINDOW";
        } else if (to == AssetStatus.RETIRED) {
            AssetAccounts.VaultCounters counters = new AssetAccounts(AssetPaymentsContract.PREFIX, state)
                    .counters(vaultId);
            if (counters.reserved().signum() != 0 || counters.lovelaceReserved().signum() != 0) {
                refusal = "UNPAID_CLAIMS";
            } else if (counters.available().signum() != 0 || counters.lovelaceAvailable().signum() != 0) {
                refusal = "OUTSTANDING_LIABILITIES";
            } else if (!liabilitiesZero(counters)) {
                // I4 says credited − paid = available + reserved; a nonzero remainder is ledger inconsistency.
                refusal = "LEDGER_INCONSISTENT";
            }
        }
        if (refusal != null) {
            return refused(sequence, bytes, block, approvers, Optional.of(vaultId), from.code(), refusal);
        }
        RegistryEntryV1 current = entry.get();
        state.put(AssetPaymentsContract.registryKey(vaultId), new RegistryEntryV1(current.vaultId(), current.asset(),
                current.scriptHash(), current.vaultAddress(), to, current.activeFromSlot(), current.addedAtHeight(),
                current.ticker(), current.decimals()).encode());
        if (to == AssetStatus.WIND_DOWN) {
            state.put(AssetPaymentsContract.windDownKey(vaultId),
                    PaymentsRecords.encodeU64s(block.height(), block.timestamp()));
        } else {
            state.delete(AssetPaymentsContract.windDownKey(vaultId));
        }
        return new DecisionV1(sequence, bytes, block.height(), block.timestamp(), approvers, Optional.of(vaultId),
                from.code(), to.code(), PaymentsGovernanceV1.Outcome.APPLIED, "");
    }

    /**
     * Whether the vault has been in {@code WIND_DOWN} for the retirement window. A missing record (never written for a
     * vault in {@code WIND_DOWN}, which genesis and every governed move write) counts as not elapsed.
     */
    private boolean windDownElapsed(AssetAccounts.Store state, Hash32 vaultId, long blockTimeMs) {
        return state.get(AssetPaymentsContract.windDownKey(vaultId))
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 2)[1])
                .filter(sinceMs -> blockTimeMs - sinceMs >= retireAfterWindDownMs)
                .isPresent();
    }

    private static boolean liabilitiesZero(AssetAccounts.VaultCounters counters) {
        return counters.credited().subtract(counters.paid()).equals(BigInteger.ZERO)
                && counters.lovelaceCredited().subtract(counters.lovelacePaid()).equals(BigInteger.ZERO);
    }

    private DecisionV1 domainStatus(AssetAccounts.Store state, long sequence, byte[] bytes, List<byte[]> approvers,
                                    AppBlock block, DomainStatusV1.Status required, DomainStatusV1.Status next) {
        DomainStatusV1 current = DomainStatusV1.decode(state.get(domainStatusKey)
                .orElseThrow(() -> new IllegalStateException("the domain status leaf is written at genesis")));
        if (current.status() != required) {
            String reason = current.status() == DomainStatusV1.Status.HALTED ? "DOMAIN_HALTED"
                    : required == DomainStatusV1.Status.ACTIVE ? "NOT_ACTIVE" : "NOT_PAUSED";
            return refused(sequence, bytes, block, approvers, Optional.empty(), current.status().code(), reason);
        }
        state.put(domainStatusKey, new DomainStatusV1(next, block.height(), block.timestamp(),
                current.exitDelayMs()).encode());
        return new DecisionV1(sequence, bytes, block.height(), block.timestamp(), approvers, Optional.empty(),
                current.status().code(), next.code(), PaymentsGovernanceV1.Outcome.APPLIED, "");
    }

    private static DecisionV1 refused(long sequence, byte[] bytes, AppBlock block, List<byte[]> approvers,
                                      Optional<Hash32> subject, int status, String reason) {
        return new DecisionV1(sequence, bytes, block.height(), block.timestamp(), approvers, subject, status, status,
                PaymentsGovernanceV1.Outcome.REFUSED, reason);
    }
}
