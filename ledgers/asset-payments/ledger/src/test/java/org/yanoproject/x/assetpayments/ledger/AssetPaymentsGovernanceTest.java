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

    @Test
    void retiredIsFinalAndNoChangeIsRefused() {
        ledger.block(T0);
        ledger.block(T0 + 1_000, governance(0, status(0, EURC_VAULT, AssetStatus.ACTIVE)),
                governance(1, status(0, EURC_VAULT, AssetStatus.ACTIVE)));
        assertThat(decision(0).orElseThrow().reason()).isEqualTo("NO_CHANGE");
        // EURC has never held a liability, so it retires at once, and stays retired.
        ledger.block(T0 + 2_000, governance(0, status(1, EURC_VAULT, AssetStatus.RETIRED)),
                governance(1, status(1, EURC_VAULT, AssetStatus.RETIRED)));
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.RETIRED);
        ledger.block(T0 + 3_000, governance(0, status(2, EURC_VAULT, AssetStatus.ACTIVE)),
                governance(1, status(2, EURC_VAULT, AssetStatus.ACTIVE)));
        assertThat(decision(2).orElseThrow().reason()).isEqualTo("RETIRED_IS_FINAL");
        assertThat(registry(EURC_VAULT).status()).isEqualTo(AssetStatus.RETIRED);
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
