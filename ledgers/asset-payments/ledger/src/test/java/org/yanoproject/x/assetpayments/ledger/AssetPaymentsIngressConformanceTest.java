package org.yanoproject.x.assetpayments.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Block;
import org.junit.jupiter.api.Test;
import org.yanoproject.x.assetpayments.contracts.AssetPaymentsContract;
import org.yanoproject.x.assetpayments.contracts.PaymentsBodies;
import org.yanoproject.x.assets.cardano.gateway.AssetVaultDepositObserverProvider;
import org.yanoproject.x.assets.contracts.AssetAccounts;
import org.yanoproject.x.assets.contracts.AssetId;
import org.yanoproject.x.assets.contracts.DepositDatumV1;
import org.yanoproject.x.assets.contracts.ExitLeafV1;
import org.yanoproject.x.framework.api.appchain.AppChainConsensusProfile;
import org.yanoproject.x.framework.api.appchain.AppChainMembershipView;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineContext;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.appchain.AppStateMachineResolver;
import org.yanoproject.x.framework.api.appchain.authmap.AuthenticatedMapValidatorResolver;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observation;
import org.yanoproject.x.framework.api.appchain.observation.ObservationProfileV1;
import org.yanoproject.x.framework.api.appchain.state.StateCommitmentIdentity;
import org.yanoproject.x.framework.api.appchain.l1view.L1Observer;
import org.yanoproject.x.framework.runtime.appchain.StateMachineConformance;
import org.yanoproject.x.walletauth.IntentEnvelopeV1;
import org.yanoproject.x.walletauth.WalletAuthorizer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.x.assetpayments.ledger.PaymentsHarness.*;

/**
 * The strongest in-process stand-in for the M3 devnet exit criterion: real Conway block bytes go
 * through the node's block parser and the real {@code asset-vault-deposit-v1} observer, and their
 * {@code ~l1/deposits} observations are sequenced into app blocks applied through the framework's
 * production commit path (FxKernel, MPF) by {@link StateMachineConformance}. Several independent
 * runs, a kill-and-reopen replay and a checkpoint restore must give byte-identical state roots at
 * every height (I13), and ADA plus two native tokens must be credited exactly once although the
 * same observations are delivered again later (rescan, I1). Junk outputs, a phase-2-invalid
 * transaction and a vault spend in the same blocks never credit (I2, I16).
 */
class AssetPaymentsIngressConformanceTest {
    static final long HARNESS_T0 = 1_700_000_000_000L; // StateMachineConformance block time base
    static final long NOT_AFTER = HARNESS_T0 + 100_000;
    static final int PER_BLOCK = 4;

    final TestWallet alice = TestWallet.of(1);
    final TestWallet bob = TestWallet.of(2);
    final L1Observer observer = new AssetVaultDepositObserverProvider().create(OBSERVER, observerSettings());
    int nonce;

    static byte[] datum(TestWallet beneficiary) {
        return new DepositDatumV1(beneficiary.accountId(), beneficiary.address(), beneficiary.keyHash()).encode();
    }

    static byte[] address(String bech32) {
        return new Address(bech32).getBytes();
    }

    List<L1Observation> observe(long blockNumber, long slot, List<L1BlockBytes.Tx> txs, List<Integer> invalid) {
        Block block = L1BlockBytes.parse(L1BlockBytes.block(blockNumber, slot, txs, invalid));
        byte[] hash = HexFormat.of().parseHex(block.getHeader().getHeaderBody().getBlockHash());
        List<L1Observation> observations = new ArrayList<>(observer.observe(slot, hash, block));
        observations.sort(L1Observation.CANONICAL_ORDER);
        return observations;
    }

    StateMachineConformance.CorpusMessage owner(TestWallet wallet, String action, byte[] body) {
        IntentEnvelopeV1 envelope = envelope(wallet.accountId(), action, new IntentEnvelopeV1.Owner(), ++nonce,
                NOT_AFTER, body);
        return new StateMachineConformance.CorpusMessage(AssetPaymentsContract.INTENT_TOPIC,
                PaymentsHarness.owner(wallet, envelope).encode());
    }

    static StateMachineConformance.CorpusMessage l1(L1Observation observation) {
        return new StateMachineConformance.CorpusMessage(observation.topic(), observation.encode());
    }

    static byte[] transfer(AssetId asset, TestWallet to, long amount) {
        return new PaymentsBodies.Transfer(asset, to.accountId(), BigInteger.valueOf(amount)).encode();
    }

    @Test
    void realL1DepositsCreditExactlyOnceAcrossRestartSnapshotAndRescan() {
        // L1 block 1: ADA and USDM to Alice, EURC to Bob, a mixed-asset deposit, a donated continuation,
        // a floor top-up; and a phase-2-invalid transaction carrying a large "deposit".
        List<L1Observation> first = observe(500, 9_000, List.of(
                new L1BlockBytes.Tx(1, List.of(
                        new L1BlockBytes.Output(address(ADA_ADDRESS), 10_000_000, Map.of(), datum(alice)),
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 1_500_000, Map.of(USDM, 100L), datum(alice)),
                        new L1BlockBytes.Output(address(EURC_ADDRESS), 1_400_000, Map.of(EURC, 42L), datum(bob)))),
                new L1BlockBytes.Tx(2, List.of(
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 1_500_000, Map.of(USDM, 7L, EURC, 7L),
                                datum(alice)),
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 2_000_000, Map.of(USDM, 9L),
                                HexFormat.of().parseHex("d87a8180")),
                        new L1BlockBytes.Output(address(EURC_ADDRESS), 5_000_000, Map.of(),
                                HexFormat.of().parseHex("d87b80")))),
                new L1BlockBytes.Tx(3, List.of(
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 1_500_000, Map.of(USDM, 1_000_000L),
                                datum(alice))))),
                List.of(2));
        assertThat(first).hasSize(5); // 3 deposits, mixed, floor; continuation and invalid tx unreported
        // L1 block 2: a vault spend (continuation) and a deposit in one block, the deposit hashing second.
        List<L1Observation> second = observe(501, 9_020, List.of(
                new L1BlockBytes.Tx(4, List.of(
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 3_000_000, Map.of(USDM, 50L),
                                HexFormat.of().parseHex("d87a81815820" + "77".repeat(32))))),
                new L1BlockBytes.Tx(5, List.of(
                        new L1BlockBytes.Output(address(USDM_ADDRESS), 1_300_000, Map.of(USDM, 50L), datum(bob))))),
                List.of());
        assertThat(second).hasSize(1);

        Map<Long, List<StateMachineConformance.CorpusMessage>> script = new LinkedHashMap<>();
        script.put(1L, List.of(owner(alice, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT),
                owner(bob, WalletAuthorizer.REGISTER_ACCOUNT, PaymentsBodies.REGISTER_ACCOUNT)));
        script.put(2L, first.subList(0, 4).stream().map(AssetPaymentsIngressConformanceTest::l1).toList());
        script.put(3L, List.of(l1(first.get(4)), owner(alice, AssetPaymentsContract.TRANSFER, transfer(USDM, bob, 30)),
                owner(bob, AssetPaymentsContract.TRANSFER, transfer(EURC, alice, 2))));
        // Rescan: the whole first L1 block is delivered again, then again with the second block.
        script.put(4L, first.subList(0, 4).stream().map(AssetPaymentsIngressConformanceTest::l1).toList());
        // Height 5 also carries the second block's deposit twice: staged reads make the second a no-op.
        script.put(5L, List.of(l1(first.get(4)), l1(second.getFirst()), l1(second.getFirst()), l1(first.get(1))));
        script.put(6L, List.of(l1(second.getFirst())));

        AssetAccounts keys = PaymentsHarness.KEYS;
        StateMachineConformance.Builder builder = StateMachineConformance.builder(new StableChainProvider())
                .chainId(CHAIN)
                .settings(PaymentsHarness.settings())
                .blocks(8)
                .messagesPerBlock(PER_BLOCK)
                .runs(3)
                .restartAtHeight(3)
                .snapshotAtHeight(4)
                .messageGenerator((height, index, random) -> {
                    List<StateMachineConformance.CorpusMessage> planned = script.getOrDefault(height, List.of());
                    return index < planned.size() ? planned.get(index)
                            : new StateMachineConformance.CorpusMessage("noop", new byte[] {(byte) index});
                });
        Map<String, byte[]> probes = new LinkedHashMap<>();
        for (var vault : Map.of("ada", ADA_VAULT, "usdm", USDM_VAULT, "eurc", EURC_VAULT).entrySet()) {
            probes.put("alice." + vault.getKey(), keys.exitLeafKey(vault.getValue(), alice.accountId()));
            probes.put("bob." + vault.getKey(), keys.exitLeafKey(vault.getValue(), bob.accountId()));
            probes.put("counters." + vault.getKey(), keys.countersKey(vault.getValue()));
        }
        probes.forEach(builder::stateProbe);

        StateMachineConformance.Result result = builder.run();
        assertThat(result.deterministic()).as(result.describeDivergence()).isTrue();
        assertThat(result.outcomesPerRun()).hasSize(4); // 3 runs (one with restart) + checkpoint restore

        Map<String, String> last = result.outcomesPerRun().getFirst().get(8L).stateValues();
        assertThat(leaf(last, "alice.ada").amount()).isEqualTo(BigInteger.valueOf(10_000_000));
        assertThat(leaf(last, "alice.usdm").amount()).isEqualTo(BigInteger.valueOf(70));
        assertThat(leaf(last, "alice.usdm").vaultLovelace()).isEqualTo(BigInteger.valueOf(1_500_000));
        assertThat(leaf(last, "bob.usdm").amount()).isEqualTo(BigInteger.valueOf(80));
        assertThat(leaf(last, "bob.usdm").vaultLovelace()).isEqualTo(BigInteger.valueOf(1_300_000));
        assertThat(leaf(last, "bob.eurc").amount()).isEqualTo(BigInteger.valueOf(40));
        assertThat(leaf(last, "alice.eurc").amount()).isEqualTo(BigInteger.TWO);
        assertThat(last.get("bob.ada")).isEqualTo("<absent>");
        AssetAccounts.VaultCounters usdm = counters(last, "counters.usdm");
        assertThat(usdm.credited()).isEqualTo(BigInteger.valueOf(150)); // 100 + 50, each exactly once
        assertThat(usdm.available()).isEqualTo(BigInteger.valueOf(150));
        assertThat(usdm.lovelaceCredited()).isEqualTo(BigInteger.valueOf(2_800_000));
        assertThat(counters(last, "counters.eurc").credited()).isEqualTo(BigInteger.valueOf(42));
        assertThat(counters(last, "counters.ada").credited()).isEqualTo(BigInteger.valueOf(10_000_000));

        // The rescans at heights 4-6 change no balance: the state after height 3 already holds every credit
        // except Bob's second-block deposit, which lands once at height 5.
        Map<Long, StateMachineConformance.HeightOutcome> run = result.outcomesPerRun().getFirst();
        assertThat(run.get(4L).stateValues()).isEqualTo(run.get(3L).stateValues());
        assertThat(run.get(6L).stateValues()).isEqualTo(run.get(5L).stateValues());
    }

    static ExitLeafV1 leaf(Map<String, String> values, String name) {
        return ExitLeafV1.decode(HexFormat.of().parseHex(values.get(name)));
    }

    static AssetAccounts.VaultCounters counters(Map<String, String> values, String name) {
        return AssetAccounts.VaultCounters.decode(HexFormat.of().parseHex(values.get(name)));
    }

    /**
     * The conformance harness builds its consensus profile with {@code l1.stability-depth} 0, which
     * no real chain may run with (ADR-059 D5) and the ledger refuses. This provider hands the ledger
     * the same profile at depth 1, the depth the test settings require; everything else is passed on.
     */
    static final class StableChainProvider implements AppStateMachineProvider {
        private final AssetPaymentsStateMachineProvider delegate = new AssetPaymentsStateMachineProvider();

        @Override public String id() { return delegate.id(); }
        @Override public AppStateMachine create() { return delegate.create(); }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return delegate.create(new AppStateMachineContext() {
                @Override public String chainId() { return context.chainId(); }
                @Override public Map<String, String> settings() { return context.settings(); }
                @Override public Optional<AppStateMachineResolver> stateMachineResolver() {
                    return context.stateMachineResolver();
                }
                @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                    return context.consensusProfile().map(StableChainProvider::atDepthOne);
                }
                @Override public Optional<ObservationProfileV1> observationProfile() {
                    return context.observationProfile();
                }
                @Override public Optional<AppChainMembershipView> membershipView() { return context.membershipView(); }
                @Override public Optional<StateCommitmentIdentity> stateCommitmentIdentity() {
                    return context.stateCommitmentIdentity();
                }
                @Override public Optional<AuthenticatedMapValidatorResolver> authenticatedMapValidatorResolver() {
                    return context.authenticatedMapValidatorResolver();
                }
            });
        }

        static AppChainConsensusProfile atDepthOne(AppChainConsensusProfile p) {
            int depth = Math.max(1, p.l1StabilityDepth());
            return new AppChainConsensusProfile(p.schemaVersion(), p.maxMessageBytes(), p.maxBlockMessages(),
                    p.maxBlockBytes(), depth, Math.max(depth, p.epochStabilityDepth()), p.blockMaxForwardSkewMs(),
                    p.enforceSenderSeq(), p.effectsEnabled(), p.effectsMaxPerBlock(), p.effectsMaxPayloadBytes(),
                    p.effectsMaxExpiryBlocks(), p.effectsResultWindowBlocks(), p.effectsDefaultGate(),
                    p.effectsOutcomeCommitment(), p.effectsStrictReservedPrefix(), p.effectResultSigners());
        }
    }
}
