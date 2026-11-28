package org.yanoproject.x.assetpayments.ledger;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.AssetStatusChange;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.Command;
import org.yanoproject.x.assetpayments.contracts.PaymentsGovernanceV1.DecisionV1;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * ADR-059 D14 governed commands and D8a {@code PAUSE} / {@code RESUME} through the {@code asset-payments} state
 * machine: a threshold of distinct members of the epoch at the block's height must submit the exact command; the
 * decision is written once to the history; a stale, repeated, non-member or expired approval changes nothing.
 */
class AssetPaymentsGovernanceTest {
    final PaymentsHarness ledger = new PaymentsHarness(AssetPaymentsPayoutTest.settings());
    final TestWallet alice = TestWallet.of(1);
    int nonce = 5_000;

    static Command status(long sequence, Hash32 vault, AssetStatus status) {
        return new Command(sequence, new AssetStatusChange(vault, status));
    }

    PaymentsRecords.RegistryEntryV1 registry(Hash32 vault) {
        return ledger.get(AssetPaymentsContract.registryKey(vault)).map(PaymentsRecords.RegistryEntryV1::decode)
                .orElseThrow();
    }

    Optional<DecisionV1> decision(long sequence) {
        return ledger.get(AssetPaymentsContract.governanceHistoryKey(sequence)).map(DecisionV1::decode);
    }

    long sequence() {
        return ledger.get(AssetPaymentsContract.governanceSequenceKey())
                .map(bytes -> PaymentsRecords.decodeU64s(bytes, 1)[0]).orElse(0L);
    }

    DomainStatusV1 domainStatus() {
        return ledger.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow();
    }

    @Test
    void aStatusChangeNeedsAThresholdOfDistinctMembersAndIsRecordedOnce() {
        ledger.block(T0);
        Command pause = status(0, USDM_VAULT, AssetStatus.TRANSFERS_PAUSED);
        // One member, twice, and a non-member: below the threshold of two.
        ledger.block(T0 + 1_000, governance(0, pause), governance(0, pause),
                new Message(PaymentsGovernanceV1.TOPIC, pause.encode()));
        assertThat(registry(USDM_VAULT).status()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(decision(0)).isEmpty();
        List<PaymentsGovernanceV1.Proposal> open = PaymentsGovernanceV1.decodeProposals(
                ledger.get(AssetPaymentsContract.governanceProposalsKey()).orElseThrow());
        assertThat(open).hasSize(1);
        assertThat(open.getFirst().approvers()).containsExactly(MEMBERS.get(0));

        ledger.block(T0 + 2_000, governance(1, pause));
        assertThat(registry(USDM_VAULT).status()).isEqualTo(AssetStatus.TRANSFERS_PAUSED);
        DecisionV1 recorded = decision(0).orElseThrow();
        assertThat(recorded.outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(recorded.subject()).contains(USDM_VAULT);
        assertThat(recorded.from()).isEqualTo(AssetStatus.ACTIVE.code());
        assertThat(recorded.to()).isEqualTo(AssetStatus.TRANSFERS_PAUSED.code());
        assertThat(recorded.height()).isEqualTo(ledger.height);
        assertThat(recorded.blockTimeMs()).isEqualTo(T0 + 2_000);
        assertThat(recorded.approvers()).containsExactly(MEMBERS.get(0), MEMBERS.get(1));
        assertThat(sequence()).isEqualTo(1);
        assertThat(ledger.get(AssetPaymentsContract.governanceProposalsKey())).isEmpty();
        // Only the status changed: identity, vault binding and display data are immutable (D14).
        PaymentsRecords.RegistryEntryV1 entry = registry(USDM_VAULT);
        assertThat(entry.asset()).isEqualTo(USDM);
        assertThat(entry.vaultId()).isEqualTo(USDM_VAULT);
        assertThat(entry.scriptHash()).isEqualTo(USDM_HASH);
        assertThat(entry.ticker()).isEqualTo("USDM");

        // A late copy of the decided command is stale, and so is any other command for sequence 0.
        TreeMap<String, byte[]> before = new TreeMap<>(ledger.state);
        ledger.block(T0 + 3_000, governance(2, pause), governance(2, status(0, EURC_VAULT, AssetStatus.WIND_DOWN)));
        assertThat(ledger.state).containsExactlyEntriesOf(before);
    }

    @Test
    void decidingACommandVoidsEveryOtherOpenProposal() {
        ledger.block(T0, governance(0, status(0, EURC_VAULT, AssetStatus.WIND_DOWN)),
                governance(0, status(0, USDM_VAULT, AssetStatus.DEPOSITS_PAUSED)),
                governance(1, status(0, USDM_VAULT, AssetStatus.DEPOSITS_PAUSED)));
        assertThat(registry(USDM_VAULT).status()).isEqualTo(AssetStatus.DEPOSITS_PAUSED);
        // The EURC approval was for sequence 0; completing it later is stale.
        ledger.block(T0 + 1_000, governance(1, status(0, EURC_VAULT, AssetStatus.WIND_DOWN)));
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.ACTIVE);
        ledger.block(T0 + 2_000, governance(1, status(1, EURC_VAULT, AssetStatus.WIND_DOWN)),
                governance(2, status(1, EURC_VAULT, AssetStatus.WIND_DOWN)));
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.WIND_DOWN);
        assertThat(sequence()).isEqualTo(2);
    }

    @Test
    void approvalsExpireAfterTheApprovalWindow() {
        Command change = status(0, ADA_VAULT, AssetStatus.DEPOSITS_PAUSED);
        ledger.block(T0, governance(0, change));
        long started = ledger.height;
        while (ledger.height < started + PaymentsGovernanceV1.APPROVAL_WINDOW_BLOCKS - 1) ledger.block(T0 + 1);
        ledger.block(T0 + 2, governance(1, change)); // exactly the window later: the first approval is gone
        assertThat(registry(ADA_VAULT).status()).isEqualTo(AssetStatus.ACTIVE);
        assertThat(decision(0)).isEmpty();
        ledger.block(T0 + 3, governance(2, change));
        assertThat(registry(ADA_VAULT).status()).isEqualTo(AssetStatus.DEPOSITS_PAUSED);
        assertThat(decision(0).orElseThrow().approvers()).containsExactly(MEMBERS.get(1), MEMBERS.get(2));
    }

    @Test
    void pauseAndResumeMoveTheDomainStatusLeafAndKeepItsExitDelay() {
        ledger.block(T0);
        Command pause = new Command(0, new PaymentsGovernanceV1.Pause());
        ledger.block(T0 + 5_000, governance(0, pause), governance(2, pause));
        assertThat(domainStatus()).isEqualTo(new DomainStatusV1(DomainStatusV1.Status.PAUSED, ledger.height,
                T0 + 5_000, 86_400_000L));
        assertThat(decision(0).orElseThrow().subject()).isEmpty();

        Command pauseAgain = new Command(1, new PaymentsGovernanceV1.Pause());
        ledger.block(T0 + 6_000, governance(0, pauseAgain), governance(1, pauseAgain));
        assertThat(decision(1).orElseThrow().reason()).isEqualTo("NOT_ACTIVE");
        assertThat(domainStatus().sinceMs()).isEqualTo(T0 + 5_000);

        Command resume = new Command(2, new PaymentsGovernanceV1.Resume());
        ledger.block(T0 + 7_000, governance(1, resume), governance(2, resume));
        assertThat(domainStatus()).isEqualTo(new DomainStatusV1(DomainStatusV1.Status.ACTIVE, ledger.height,
                T0 + 7_000, 86_400_000L));
        Command resumeAgain = new Command(3, new PaymentsGovernanceV1.Resume());
        ledger.block(T0 + 8_000, governance(1, resumeAgain), governance(2, resumeAgain));
        assertThat(decision(3).orElseThrow().reason()).isEqualTo("NOT_PAUSED");
        assertThat(decision(3).orElseThrow().from()).isEqualTo(DomainStatusV1.Status.ACTIVE.code());
        assertThat(sequence()).isEqualTo(4);
    }

    @Test
    void aHaltedDomainIsNeitherPausedNorResumedByGovernance() {
        ledger.block(T0);
        ledger.writer().put(AssetPaymentsContract.domainStatusKey(),
                new DomainStatusV1(DomainStatusV1.Status.HALTED, 1, T0, 86_400_000L).encode());
        Command resume = new Command(0, new PaymentsGovernanceV1.Resume());
        ledger.block(T0 + 1_000, governance(0, resume), governance(1, resume));
        assertThat(decision(0).orElseThrow().reason()).isEqualTo("DOMAIN_HALTED");
        assertThat(domainStatus().status()).isEqualTo(DomainStatusV1.Status.HALTED);
    }

    /** Two of the three members decide {@code command} in one block at {@code timestamp}; returns the decision. */
    DecisionV1 decide(long timestamp, Command command) {
        ledger.block(timestamp, governance(0, command), governance(1, command));
        return decision(command.sequence()).orElseThrow();
    }

    @Test
    void retiredIsFinalAndNoChangeIsRefused() {
        ledger.block(T0);
        assertThat(decide(T0 + 1_000, status(0, EURC_VAULT, AssetStatus.ACTIVE)).reason()).isEqualTo("NO_CHANGE");
        // EURC has never held a liability; it still retires only from WIND_DOWN, once the window has passed.
        decide(T0 + 2_000, status(1, EURC_VAULT, AssetStatus.WIND_DOWN));
        decide(T0 + 2_000 + RETIRE_WINDOW, status(2, EURC_VAULT, AssetStatus.RETIRED));
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.RETIRED);
        assertThat(ledger.get(AssetPaymentsContract.windDownKey(EURC_VAULT))).isEmpty();
        assertThat(decide(T0 + 3_000 + RETIRE_WINDOW, status(3, EURC_VAULT, AssetStatus.ACTIVE)).reason())
                .isEqualTo("RETIRED_IS_FINAL");
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.RETIRED);
    }

    /** The default retirement window at depth 1: an hour of block time (D14). */
    static final long RETIRE_WINDOW = 3_600_000;

    @Test
    void retirementIsAcceptedOnlyFromWindDownAndOnlyAfterItsWindow() {
        ledger.block(T0);
        // Straight from ACTIVE (or any status but WIND_DOWN): refused, so retirement never races a pending deposit.
        DecisionV1 direct = decide(T0 + 1_000, status(0, EURC_VAULT, AssetStatus.RETIRED));
        assertThat(direct.reason()).isEqualTo("NOT_WOUND_DOWN");
        assertThat(decide(T0 + 1_000, status(1, EURC_VAULT, AssetStatus.DEPOSITS_PAUSED)).outcome())
                .isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(decide(T0 + 1_000, status(2, EURC_VAULT, AssetStatus.RETIRED)).reason()).isEqualTo("NOT_WOUND_DOWN");

        // The wind-down start is recorded with the deciding block's height and time.
        long start = T0 + 2_000;
        decide(start, status(3, EURC_VAULT, AssetStatus.WIND_DOWN));
        assertThat(PaymentsRecords.decodeU64s(ledger.get(AssetPaymentsContract.windDownKey(EURC_VAULT)).orElseThrow(),
                2)).containsExactly(ledger.height, start);
        DecisionV1 early = decide(start + RETIRE_WINDOW - 1, status(4, EURC_VAULT, AssetStatus.RETIRED));
        assertThat(early.reason()).isEqualTo("WIND_DOWN_WINDOW");
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.WIND_DOWN);

        // Leaving WIND_DOWN clears the record, and a new wind-down starts the window again.
        decide(start + RETIRE_WINDOW, status(5, EURC_VAULT, AssetStatus.ACTIVE));
        assertThat(ledger.get(AssetPaymentsContract.windDownKey(EURC_VAULT))).isEmpty();
        long again = start + RETIRE_WINDOW + 1_000;
        decide(again, status(6, EURC_VAULT, AssetStatus.WIND_DOWN));
        assertThat(decide(again + RETIRE_WINDOW / 2, status(7, EURC_VAULT, AssetStatus.RETIRED)).reason())
                .isEqualTo("WIND_DOWN_WINDOW");
        DecisionV1 retired = decide(again + RETIRE_WINDOW, status(8, EURC_VAULT, AssetStatus.RETIRED));
        assertThat(retired.outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(retired.from()).isEqualTo(AssetStatus.WIND_DOWN.code());
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.RETIRED);
    }

    @Test
    void theRetirementWindowIsConfiguredAndBoundedByTheStabilityDepth() {
        assertThat(AssetPaymentsSettings.defaultRetireAfterWindDownMs(1)).isEqualTo(3_600_000);
        assertThat(AssetPaymentsSettings.defaultRetireAfterWindDownMs(120)).isEqualTo(120 * 60_000);
        java.util.Map<String, String> tooShort = new java.util.LinkedHashMap<>(AssetPaymentsPayoutTest.settings());
        tooShort.put("machines.asset-payments.retire-after-wind-down-ms", "19999"); // below depth 1 x 20 s
        assertThatThrownBy(() -> new PaymentsHarness(tooShort)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retire-after-wind-down-ms");
    }

    @Test
    void aVaultConfiguredInWindDownStartsItsWindowAtGenesis() {
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>(AssetPaymentsPayoutTest.settings());
        settings.put("machines.asset-payments.vaults.2.status", "WIND_DOWN");
        PaymentsHarness chain = new PaymentsHarness(settings);
        chain.block(T0);
        assertThat(PaymentsRecords.decodeU64s(chain.get(AssetPaymentsContract.windDownKey(EURC_VAULT)).orElseThrow(),
                2)[1]).isEqualTo(T0);
        Command retire = status(0, EURC_VAULT, AssetStatus.RETIRED);
        chain.block(T0 + RETIRE_WINDOW - 1, governance(0, retire), governance(1, retire));
        assertThat(chain.get(AssetPaymentsContract.governanceHistoryKey(0)).map(DecisionV1::decode).orElseThrow()
                .reason()).isEqualTo("WIND_DOWN_WINDOW");
    }

    @Test
    void oneMemberFillingProposalsCannotBlockAPause() {
        ledger.block(T0);
        // Member 0 starts a proposal of its own first.
        Command windDown = status(0, USDM_VAULT, AssetStatus.WIND_DOWN);
        ledger.block(T0 + 1_000, governance(0, windDown));
        // Member 1 submits every other distinct command for this sequence: with member 0's, enough to fill the
        // shared 16-slot table this replaced, which then refused the PAUSE below.
        List<Command> flood = new java.util.ArrayList<>();
        for (Hash32 vault : List.of(ADA_VAULT, USDM_VAULT, EURC_VAULT)) {
            for (AssetStatus to : AssetStatus.values()) {
                if (!(vault.equals(USDM_VAULT) && to == AssetStatus.WIND_DOWN)) flood.add(status(0, vault, to));
            }
        }
        flood.add(new Command(0, new PaymentsGovernanceV1.Resume()));
        assertThat(flood.size() + 1).isGreaterThanOrEqualTo(16);
        for (Command command : flood) ledger.block(T0 + 2_000, governance(1, command));
        // Each newer command of member 1 replaced its older one: one proposal each, nothing decided.
        List<PaymentsGovernanceV1.Proposal> open = PaymentsGovernanceV1.decodeProposals(
                ledger.get(AssetPaymentsContract.governanceProposalsKey()).orElseThrow());
        assertThat(open).hasSize(2);
        assertThat(open).anyMatch(proposal -> proposal.proposedBy(MEMBERS.get(0))
                && proposal.sameCommand(windDown.encode()));
        assertThat(open).anyMatch(proposal -> proposal.proposedBy(MEMBERS.get(1))
                && proposal.sameCommand(flood.getLast().encode()));
        assertThat(sequence()).isZero();

        // A 2-of-3 PAUSE by the other two members still gets a slot and decides.
        Command pause = new Command(0, new PaymentsGovernanceV1.Pause());
        ledger.block(T0 + 3_000, governance(2, pause), governance(0, pause));
        assertThat(decision(0).orElseThrow().outcome()).isEqualTo(PaymentsGovernanceV1.Outcome.APPLIED);
        assertThat(domainStatus().status()).isEqualTo(DomainStatusV1.Status.PAUSED);
    }

    @Test
    void approvingAnotherMembersProposalTakesNoSlotOfTheApprover() {
        // Three of four members decide, so approvals can be seen pending.
        List<byte[]> four = List.of(MEMBERS.get(0), MEMBERS.get(1), MEMBERS.get(2), filled(32, 0x74));
        PaymentsHarness chain = new PaymentsHarness(AssetPaymentsPayoutTest.settings(), profile(1),
                height -> new org.yanoproject.x.framework.api.appchain.AppChainMembershipEpoch(0,
                        four.stream().map(java.util.HexFormat.of()::formatHex).toList(), 3));
        chain.block(T0);
        Command own = status(0, EURC_VAULT, AssetStatus.DEPOSITS_PAUSED);
        Command pause = new Command(0, new PaymentsGovernanceV1.Pause());
        chain.block(T0 + 1_000, governance(1, own), governance(0, pause), governance(1, pause));
        List<PaymentsGovernanceV1.Proposal> open = PaymentsGovernanceV1.decodeProposals(
                chain.get(AssetPaymentsContract.governanceProposalsKey()).orElseThrow());
        // Member 1 approved member 0's PAUSE and still holds its own proposal.
        assertThat(open).hasSize(2);
        assertThat(open).anyMatch(proposal -> proposal.proposedBy(MEMBERS.get(1)) && proposal.sameCommand(own.encode()));
        assertThat(open).anyMatch(proposal -> proposal.proposedBy(MEMBERS.get(0))
                && proposal.sameCommand(pause.encode()) && proposal.approvers().size() == 2);
        chain.block(T0 + 2_000, governance(2, pause));
        assertThat(chain.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow()
                .status()).isEqualTo(DomainStatusV1.Status.PAUSED);
    }

    @Test
    void aProposalOtherMembersApprovedIsNotReplacedByItsProposer() {
        // Three of three members decide: dropping any approval would leave the PAUSE one short.
        PaymentsHarness chain = new PaymentsHarness(AssetPaymentsPayoutTest.settings(), profile(1),
                height -> new org.yanoproject.x.framework.api.appchain.AppChainMembershipEpoch(0,
                        MEMBERS.stream().map(java.util.HexFormat.of()::formatHex).toList(), 3));
        chain.block(T0);
        Command pause = new Command(0, new PaymentsGovernanceV1.Pause());
        chain.block(T0 + 1_000, governance(0, pause), governance(1, pause));
        // Member 0's newer command would have replaced its PAUSE and dropped member 1's approval: it is refused.
        byte[] before = chain.get(AssetPaymentsContract.governanceProposalsKey()).orElseThrow();
        chain.block(T0 + 2_000, governance(0, status(0, EURC_VAULT, AssetStatus.DEPOSITS_PAUSED)));
        assertThat(chain.get(AssetPaymentsContract.governanceProposalsKey())).hasValue(before);
        assertThat(PaymentsGovernanceV1.decodeProposals(before)).singleElement().satisfies(open -> {
            assertThat(open.sameCommand(pause.encode())).isTrue();
            assertThat(open.approvers()).containsExactly(MEMBERS.get(0), MEMBERS.get(1));
        });
        chain.block(T0 + 3_000, governance(2, pause));
        assertThat(chain.get(AssetPaymentsContract.domainStatusKey()).map(DomainStatusV1::decode).orElseThrow()
                .status()).isEqualTo(DomainStatusV1.Status.PAUSED);

        // A proposal only its proposer approved is still replaced.
        Command first = status(1, EURC_VAULT, AssetStatus.DEPOSITS_PAUSED);
        Command second = status(1, USDM_VAULT, AssetStatus.DEPOSITS_PAUSED);
        chain.block(T0 + 4_000, governance(0, first), governance(0, second));
        assertThat(PaymentsGovernanceV1.decodeProposals(chain.get(AssetPaymentsContract.governanceProposalsKey())
                .orElseThrow())).singleElement().satisfies(open -> assertThat(open.sameCommand(second.encode()))
                .isTrue());
    }

    @Test
    void identicalInputsGiveIdenticalStateOnEveryMember() {
        PaymentsHarness other = new PaymentsHarness(AssetPaymentsPayoutTest.settings());
        for (PaymentsHarness member : List.of(ledger, other)) {
            member.block(T0, governance(0, status(0, USDM_VAULT, AssetStatus.WIND_DOWN)));
            member.block(T0 + 1_000, governance(2, status(0, USDM_VAULT, AssetStatus.WIND_DOWN)),
                    governance(1, new Command(1, new PaymentsGovernanceV1.Pause())));
        }
        assertThat(other.state).containsExactlyEntriesOf(ledger.state);
    }

    @Test
    void privilegedSubmissionsAreScreenedStructurally() {
        Command known = status(0, USDM_VAULT, AssetStatus.WIND_DOWN);
        assertThat(ledger.machine.validatePrivilegedSystemSubmission(PaymentsGovernanceV1.TOPIC, known.encode())
                .isAccepted()).isTrue();
        assertThat(ledger.machine.validatePrivilegedSystemSubmission(PaymentsGovernanceV1.TOPIC,
                status(0, Hash32.of(filled(32, 9)), AssetStatus.WIND_DOWN).encode()).reason())
                .isEqualTo("UNKNOWN_VAULT");
        assertThat(ledger.machine.validatePrivilegedSystemSubmission(PaymentsGovernanceV1.TOPIC, new byte[] {1})
                .isAccepted()).isFalse();
        assertThat(ledger.machine.validatePrivilegedSystemSubmission("~governance/other", known.encode())
                .isAccepted()).isFalse();
        // A user intent can never carry a governed action: it is not one of the ledger's actions.
        IntentEnvelopeV1 forged = envelope(alice.accountId(), "PAUSE", new IntentEnvelopeV1.Owner(), ++nonce,
                T0 + 60_000, PaymentsBodies.REGISTER_ACCOUNT);
        ledger.block(T0, intent(owner(alice, envelope(alice.accountId(), WalletAuthorizer.REGISTER_ACCOUNT,
                new IntentEnvelopeV1.Owner(), ++nonce, T0 + 60_000, PaymentsBodies.REGISTER_ACCOUNT))),
                intent(owner(alice, forged)));
        assertThat(ledger.receipt(forged)).isEmpty();
        assertThat(domainStatus().status()).isEqualTo(DomainStatusV1.Status.ACTIVE);
    }

    @Test
    void commandsRoundTripAndRejectNonCanonicalOrUnknownShapes() {
        for (Command command : List.of(status(7, USDM_VAULT, AssetStatus.RETIRED),
                new Command(0, new PaymentsGovernanceV1.Pause()), new Command(3, new PaymentsGovernanceV1.Resume()))) {
            assertThat(Command.decode(command.encode())).isEqualTo(command);
        }
        byte[] pause = new Command(0, new PaymentsGovernanceV1.Pause()).encode();
        byte[] unknown = pause.clone();
        unknown[unknown.length - 1] = 3; // action code 3
        assertThatThrownBy(() -> Command.decode(unknown)).isInstanceOf(IllegalArgumentException.class);
        byte[] badStatus = status(0, USDM_VAULT, AssetStatus.ACTIVE).encode();
        badStatus[badStatus.length - 1] = 9;
        assertThatThrownBy(() -> Command.decode(badStatus)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Command.decode(new byte[300])).isInstanceOf(IllegalArgumentException.class);
    }
}
