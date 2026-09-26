package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.x.composite.bindings.DeclarativeCompositeProvider;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.contracts.BindingSourceV1;
import org.yanoproject.x.dpp.profile.DppGenesis;
import org.yanoproject.x.dpp.profile.DppStarterProfile;
import org.yanoproject.x.dpp.profile.DppValues;
import org.yanoproject.x.feed.profile.Aggregation;
import org.yanoproject.x.feed.profile.FeedGenesis;
import org.yanoproject.x.feed.profile.FeedStarterProfile;
import org.yanoproject.x.feed.profile.FeedValues;
import org.yanoproject.x.roles.DeclarativeRoleProviders;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Public, deterministic local-demo product fixtures; generators never print private signing material. */
public final class BindingProductFixtures {
    private BindingProductFixtures() { }

    record Fixture(String name, AuthenticatedMapContract.Genesis genesis, BindingIrV1 ir,
                   String collection, byte[] key, byte[] value, String policy, String clause,
                   String proposer, String firstVoter, String sameOrganizationVoter, String independentVoter,
                   Function<String, byte[]> actorSeed) {
        String chain() { return genesis.chainId(); }
    }

    static List<byte[]> memberSeeds() {
        List<byte[]> seeds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            byte[] seed = new byte[32];
            seed[0] = (byte) (111 + index);
            seeds.add(seed);
        }
        return List.copyOf(seeds);
    }

    static List<String> members() {
        return memberSeeds().stream().map(KeyGenUtil::getPublicKeyFromPrivateKey)
                .map(BindingProductFixtures::hex).sorted().toList();
    }

    static Fixture fixture(boolean dpp) {
        String chain = dpp ? "binding-dpp-demo" : "binding-feed-demo";
        var descriptor = dpp ? DppGenesis.demo(chain) : FeedGenesis.demo(chain);
        if (dpp) {
            var actors = new ArrayList<>(descriptor.actors());
            var extra = TrustRegistryGenesis.actorKey(chain, "auditor-same", "auditor-same-k1",
                    DppGenesis.demoActorSeed("auditor-same"));
            actors.add(new TrustRegistryGenesis.Actor("auditor-same", "cert-body-a",
                    List.of(DppStarterProfile.AUDITOR_ROLE), extra.keyId(), extra.publicKeyHex(), extra.keyProofHex()));
            descriptor = new TrustRegistryGenesis.Descriptor(chain, descriptor.organizations(), actors,
                    descriptor.authority(), List.of(), List.of());
        }
        var genesis = dpp ? DppGenesis.genesis(descriptor, members(), 2)
                : FeedGenesis.genesis(descriptor, members(), 2);
        byte[] value;
        if (dpp) {
            value = new DppValues.CertificateValue("product-1", "independent-audit", "cert-body-a",
                    FeedValues.sha256("qualification-evidence".getBytes(StandardCharsets.UTF_8)), 1, 0).encode();
        } else {
            // Honest NO_QUORUM closure: no fabricated observation or claimed on-chain oracle result.
            var feed = FeedGenesis.demoFeed(0);
            value = Aggregation.aggregate(feed, 0, feed.sources().stream().map(Aggregation.Input::absent).toList())
                    .record(1, FeedValues.sha256(feed.encode()), new byte[0]).encode();
        }
        return new Fixture(dpp ? "dpp" : "feed", genesis, document(genesis),
                dpp ? DppStarterProfile.CERTIFICATES : FeedStarterProfile.ROUNDS,
                dpp ? DppStarterProfile.certificateKey("certificate-1")
                        : FeedStarterProfile.roundKey("cold-store-7", 0),
                value, dpp ? DppStarterProfile.CERTIFICATION_POLICY : FeedStarterProfile.ROUND_POLICY,
                dpp ? DppStarterProfile.CERTIFICATION_CLAUSE : FeedStarterProfile.ROUND_CLAUSE,
                dpp ? "certifier-a" : "ops-a", dpp ? "auditor-a" : "publisher-a",
                dpp ? "auditor-same" : "publisher-c", dpp ? "auditor-b" : "publisher-b",
                dpp ? DppGenesis::demoActorSeed : FeedGenesis::demoActorSeed);
    }

    static List<AppChainConfig> configurations(Fixture fixture, List<Integer> ports) {
        List<AppChainConfig> configs = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int selfPort = ports.get(index);
            configs.add(AppChainConfig.builder(fixture.chain()).signingKeyHex(hex(memberSeeds().get(index)))
                    .memberKeysHex(new java.util.LinkedHashSet<>(members())).proposerKeyHex(members().getFirst())
                    .threshold(2).blockIntervalMs(TrustRegistryGenesis.BLOCK_INTERVAL_MS)
                    .peers(ports.stream().filter(port -> port != selfPort)
                            .map(port -> new AppChainConfig.AppPeer("127.0.0.1", port)).toList())
                    .stateCommitmentIdentity(StdlibTestStateCommitments.mpf(fixture.chain()))
                    .stateMachineId(DeclarativeCompositeProvider.ID)
                    .pluginSettings(Map.of("membership.mode", "governed", DeclarativeCompositeProvider.IR_SETTING,
                            hex(fixture.ir().encode()))).build());
        }
        return configs;
    }

    private static BindingIrV1 document(AuthenticatedMapContract.Genesis genesis) {
        String roleGenesis = hex(genesis.governedGenesis().encode());
        return new BindingIrV1(List.of(
                new BindingIrV1.Component("actors", DeclarativeRoleProviders.ACTORS_ID, "actors.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(roleGenesis)), 0),
                new BindingIrV1.Component("reviews", DeclarativeRoleProviders.APPROVALS_ID, "reviews.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(roleGenesis),
                                "actor-component", new BindingSourceV1.Literal("actors")), 0),
                new BindingIrV1.Component("registry", AuthenticatedMapLeafStateMachine.ID, "registry.v1",
                        Map.of("genesis-cbor-hex", new BindingSourceV1.Literal(
                                        hex(AuthenticatedMapContract.encodeGenesis(genesis))),
                                "actors", new BindingSourceV1.Literal("actors"),
                                "approvals", new BindingSourceV1.Literal("reviews")), 0)),
                List.of(new BindingIrV1.Binding("apply-approved", "reviews", DeclarativeRoleProviders.APPROVED_EVENT,
                        List.of(), new BindingIrV1.CommandTarget("registry", "apply-action",
                        BindingIrV1.Mapping.fields(List.of(
                                new BindingIrV1.Assignment("action", new BindingSourceV1.Field("action")),
                                new BindingIrV1.Assignment("approvalReference",
                                        new BindingSourceV1.Field("proposalId"))))))),
                BindingIrV1.Limits.DEFAULT);
    }

    static String yaml(Fixture fixture) {
        StringBuilder text = new StringBuilder(
                "# Deterministic local-demo fixture; never use demo keys in production.\n");
        text.append("# Chain: ").append(fixture.chain())
                .append("; membership.mode=governed; threshold=2; blockIntervalMs=1000\n");
        text.append("# Proposer: ").append(members().getFirst()).append('\n');
        text.append("# Members: ").append(String.join(",", members())).append('\n');
        text.append("# Source-checkout fixture regeneration: :state-machines:stdlib:printBindingProductExamples\n");
        text.append("# Your own chain: yano.sh appchain bindings recipe dpp|feed "
                + "(see docs/appchain/DECLARATIVE_BINDINGS_CLI.md).\n");
        text.append("# Qualifications: existing product schemas/policies; "
                + "known-key certified state/consumption proofs.\n");
        text.append("# Does not claim existing portal projection or legacy AttestCertificate compatibility.\n");
        text.append("composite:\n  components:\n");
        for (var component : fixture.ir().components()) {
            text.append("    - id: ").append(component.id()).append("\n      machine: ").append(component.machineId());
            text.append("\n      topic: ").append(component.ingressTopic()).append("\n      config:\n");
            component.configuration().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    text.append("        ").append(entry.getKey()).append(": \"")
                            .append(entry.getValue().value()).append("\"\n"));
        }
        text.append("""
                  bindings:
                    - id: apply-approved
                      from: { component: reviews, event: role-approvals.proposal-approved.v1 }
                      to:
                        component: registry
                        command: apply-action
                        map:
                          action: { field: action }
                          approvalReference: { field: proposalId }
                """);
        return text.toString();
    }

    /** Prints only public fixture documents, encoded to keep Gradle logging separate from document content. */
    public static void main(String[] arguments) {
        for (boolean dpp : new boolean[]{true, false}) {
            var fixture = fixture(dpp);
            System.out.println(fixture.name().toUpperCase() + "_YAML_BASE64="
                    + Base64.getEncoder().encodeToString(yaml(fixture).getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
