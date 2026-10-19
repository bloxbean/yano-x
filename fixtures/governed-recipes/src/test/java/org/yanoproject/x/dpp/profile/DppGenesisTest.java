package org.yanoproject.x.dpp.profile;

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

class DppGenesisTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> MEMBERS = List.of(
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(1))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(2))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(3))));

    @Test
    void sameDescriptorAndMembersYieldTheSameGenesis() {
        TrustRegistryGenesis.Descriptor descriptor = DppGenesis.demo(DppGenesis.DEFAULT_CHAIN_ID);
        assertThat(descriptor.actors()).extracting(TrustRegistryGenesis.Actor::id)
                .containsExactlyElementsOf(DppGenesis.DEMO_ACTOR_IDS);
        AuthenticatedMapContract.Genesis first = DppGenesis.genesis(descriptor, MEMBERS, 2);
        AuthenticatedMapContract.Genesis second = DppGenesis.genesis(
                TrustRegistryGenesis.parse(TrustRegistryGenesis.toJson(descriptor)), MEMBERS, 2);
        assertThat(AuthenticatedMapContract.genesisId(second))
                .isEqualTo(AuthenticatedMapContract.genesisId(first));
        assertThat(DppStarterProfile.matches(first)).isTrue();
        assertThat(TrustRegistryProfile.matches(first)).as("not a registry").isFalse();
        assertThat(first.initialEntries()).isEmpty();
        assertThat(first.collections()).extracting(AuthenticatedMapContract.CollectionDescriptor::id)
                .containsExactlyElementsOf(DppStarterProfile.COLLECTION_IDS);

        Map<String, String> settings = DppGenesis.settings(first);
        assertThat(settings.keySet()).containsExactly(
                "machines.authenticated-map.genesis-cbor-hex",
                "state.commitment-profile", "state.format-fingerprint", "state.genesis-id");
        assertThat(settings.get("state.commitment-profile")).isEqualTo("mpf-blake2b256-v1");
        assertThat(settings.get("state.genesis-id"))
                .isEqualTo(HEX.formatHex(AuthenticatedMapContract.genesisId(first)));
        assertThat(DppGenesis.properties(first, 0))
                .allMatch(line -> line.startsWith("yano.app-chain.chains[0]."));
        AuthenticatedMapContract.Genesis decoded = AuthenticatedMapContract.decodeGenesis(
                HEX.parseHex(settings.get("machines.authenticated-map.genesis-cbor-hex")));
        assertThat(DppStarterProfile.matches(decoded)).isTrue();

        AuthenticatedMapContract.Genesis different = DppGenesis.genesis(descriptor, MEMBERS, 3);
        assertThat(AuthenticatedMapContract.genesisId(different))
                .isNotEqualTo(AuthenticatedMapContract.genesisId(first));
        // The registry's demo genesis under the same members is a different chain identity.
        AuthenticatedMapContract.Genesis registry = TrustRegistryGenesis.genesis(
                TrustRegistryGenesis.demo(DppGenesis.DEFAULT_CHAIN_ID), MEMBERS, 2);
        assertThat(AuthenticatedMapContract.genesisId(registry))
                .isNotEqualTo(AuthenticatedMapContract.genesisId(first));
        assertThat(TrustRegistryProfile.matches(registry)).isTrue();
    }

    @Test
    void demoSeedsDifferFromTheRegistryDemoSeeds() {
        assertThat(DppGenesis.demoActorSeed("issuer-a"))
                .isNotEqualTo(TrustRegistryGenesis.demoActorSeed("issuer-a"));
        TrustRegistryGenesis.Actor issuer = DppGenesis.demo("c").actors().stream()
                .filter(actor -> actor.id().equals("issuer-a")).findFirst().orElseThrow();
        assertThat(issuer.publicKeyHex()).isEqualTo(HEX.formatHex(
                KeyGenUtil.getPublicKeyFromPrivateKey(DppGenesis.demoActorSeed("issuer-a"))));
        assertThat(issuer.roles()).containsExactly(DppStarterProfile.CLAIM_ISSUER_ROLE);
    }

    @Test
    void registryDescriptorsAreRefused() {
        TrustRegistryGenesis.Descriptor demo = DppGenesis.demo("c");
        TrustRegistryGenesis.Descriptor withIssuer = new TrustRegistryGenesis.Descriptor(
                demo.chainId(), demo.organizations(), demo.actors(), demo.authority(),
                List.of(new TrustRegistryGenesis.InitialIssuer("issuer-x",
                        new TrustRegistryValues.IssuerValue("f", List.of(), 1, 0))),
                List.of());
        assertThatThrownBy(() -> DppGenesis.genesis(withIssuer, MEMBERS, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no initial issuers");
    }

    @Test
    void keyProofsMustBindTheActorToTheChain() {
        TrustRegistryGenesis.Descriptor demo = DppGenesis.demo("dpp-starter-chain");
        TrustRegistryGenesis.ActorKey otherChain = TrustRegistryGenesis.actorKey(
                "another-chain", "maker-a", "maker-a-k1", DppGenesis.demoActorSeed("maker-a"));
        TrustRegistryGenesis.Descriptor tampered = new TrustRegistryGenesis.Descriptor(
                demo.chainId(), demo.organizations(),
                demo.actors().stream().map(actor -> actor.id().equals("maker-a")
                        ? new TrustRegistryGenesis.Actor(actor.id(), actor.organizationId(),
                        actor.roles(), actor.keyId(), actor.publicKeyHex(), otherChain.keyProofHex())
                        : actor).toList(),
                demo.authority(), List.of(), List.of());
        assertThatThrownBy(() -> DppGenesis.genesis(tampered, MEMBERS, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maker-a");
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
