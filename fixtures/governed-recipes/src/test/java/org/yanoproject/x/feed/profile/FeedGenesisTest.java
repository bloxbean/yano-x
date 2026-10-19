package org.yanoproject.x.feed.profile;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.profile.TrustRegistryGenesis;
import org.yanoproject.x.trust.profile.TrustRegistryProfile;
import org.yanoproject.x.trust.profile.TrustRegistryValues;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedGenesisTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> MEMBERS = List.of(
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(1))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(2))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(3))));

    @Test
    void sameDescriptorAndMembersYieldTheSameGenesis() {
        TrustRegistryGenesis.Descriptor descriptor = FeedGenesis.demo(FeedGenesis.DEFAULT_CHAIN_ID);
        assertThat(descriptor.actors()).extracting(TrustRegistryGenesis.Actor::id)
                .containsExactlyElementsOf(FeedGenesis.DEMO_ACTOR_IDS);
        AuthenticatedMapContract.Genesis first = FeedGenesis.genesis(descriptor, MEMBERS, 2);
        AuthenticatedMapContract.Genesis second = FeedGenesis.genesis(
                TrustRegistryGenesis.parse(TrustRegistryGenesis.toJson(descriptor)), MEMBERS, 2);
        assertThat(AuthenticatedMapContract.genesisId(second))
                .isEqualTo(AuthenticatedMapContract.genesisId(first));
        assertThat(FeedStarterProfile.matches(first)).isTrue();
        assertThat(TrustRegistryProfile.matches(first)).as("not a registry").isFalse();
        assertThat(first.initialEntries()).isEmpty();
        assertThat(first.collections()).extracting(AuthenticatedMapContract.CollectionDescriptor::id)
                .containsExactlyElementsOf(FeedStarterProfile.COLLECTION_IDS);

        Map<String, String> settings = FeedGenesis.settings(first);
        assertThat(settings.keySet()).containsExactly(
                "machines.authenticated-map.genesis-cbor-hex",
                "state.commitment-profile", "state.format-fingerprint", "state.genesis-id");
        assertThat(settings.get("state.genesis-id"))
                .isEqualTo(HEX.formatHex(AuthenticatedMapContract.genesisId(first)));
        assertThat(FeedGenesis.properties(first, 0))
                .allMatch(line -> line.startsWith("yano.app-chain.chains[0]."));
        AuthenticatedMapContract.Genesis decoded = AuthenticatedMapContract.decodeGenesis(
                HEX.parseHex(settings.get("machines.authenticated-map.genesis-cbor-hex")));
        assertThat(FeedStarterProfile.matches(decoded)).isTrue();

        AuthenticatedMapContract.Genesis different = FeedGenesis.genesis(descriptor, MEMBERS, 3);
        assertThat(AuthenticatedMapContract.genesisId(different))
                .isNotEqualTo(AuthenticatedMapContract.genesisId(first));
        AuthenticatedMapContract.Genesis registry = TrustRegistryGenesis.genesis(
                TrustRegistryGenesis.demo(FeedGenesis.DEFAULT_CHAIN_ID), MEMBERS, 2);
        assertThat(FeedStarterProfile.matches(registry)).isFalse();
    }

    @Test
    void demoConsortiumHasTwoPublishersInOneOrganization() {
        TrustRegistryGenesis.Descriptor demo = FeedGenesis.demo("c");
        List<TrustRegistryGenesis.Actor> publishers = demo.actors().stream()
                .filter(actor -> actor.roles().contains(FeedStarterProfile.PUBLISHER_ROLE)).toList();
        assertThat(publishers).extracting(TrustRegistryGenesis.Actor::organizationId)
                .containsExactly("feed-ops", "feed-ops", "audit-guild");
        assertThat(demo.actors().stream().filter(actor -> actor.roles().contains(FeedStarterProfile.SOURCE_ROLE)))
                .extracting(TrustRegistryGenesis.Actor::id).containsExactlyElementsOf(FeedGenesis.DEMO_SOURCE_IDS);
        assertThat(FeedGenesis.demoActorSeed("source-alpha"))
                .isNotEqualTo(TrustRegistryGenesis.demoActorSeed("source-alpha"));
        TrustRegistryGenesis.Actor source = demo.actors().get(1);
        assertThat(source.publicKeyHex()).isEqualTo(HEX.formatHex(
                KeyGenUtil.getPublicKeyFromPrivateKey(FeedGenesis.demoActorSeed("source-alpha"))));
        assertThat(FeedGenesis.demoFeed(1_790_000_000L).sources()).isEqualTo(FeedGenesis.DEMO_SOURCE_IDS);
    }

    @Test
    void registryDescriptorsAreRefused() {
        TrustRegistryGenesis.Descriptor demo = FeedGenesis.demo("c");
        TrustRegistryGenesis.Descriptor withIssuer = new TrustRegistryGenesis.Descriptor(
                demo.chainId(), demo.organizations(), demo.actors(), demo.authority(),
                List.of(new TrustRegistryGenesis.InitialIssuer("issuer-x",
                        new TrustRegistryValues.IssuerValue("f", List.of(), 1, 0))),
                List.of());
        assertThatThrownBy(() -> FeedGenesis.genesis(withIssuer, MEMBERS, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no initial issuers");
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
