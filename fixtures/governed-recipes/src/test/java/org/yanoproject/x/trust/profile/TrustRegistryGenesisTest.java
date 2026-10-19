package org.yanoproject.x.trust.profile;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustRegistryGenesisTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> MEMBERS = List.of(
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(1))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(2))),
            HEX.formatHex(KeyGenUtil.getPublicKeyFromPrivateKey(seed(3))));

    @Test
    void sameDescriptorAndMembersYieldTheSameGenesis() {
        TrustRegistryGenesis.Descriptor descriptor = TrustRegistryGenesis.demo("trust-registry-chain");
        AuthenticatedMapContract.Genesis first = TrustRegistryGenesis.genesis(descriptor, MEMBERS, 2);
        AuthenticatedMapContract.Genesis second = TrustRegistryGenesis.genesis(
                TrustRegistryGenesis.parse(TrustRegistryGenesis.toJson(descriptor)), MEMBERS, 2);
        assertThat(AuthenticatedMapContract.genesisId(second))
                .isEqualTo(AuthenticatedMapContract.genesisId(first));
        assertThat(TrustRegistryProfile.matches(first)).isTrue();
        assertThat(first.initialEntries()).hasSize(1);
        assertThat(first.initialEntries().getFirst().collectionId()).isEqualTo("issuers");

        Map<String, String> settings = TrustRegistryGenesis.settings(first);
        assertThat(settings.keySet()).containsExactly(
                "machines.authenticated-map.genesis-cbor-hex",
                "state.commitment-profile", "state.format-fingerprint", "state.genesis-id");
        assertThat(settings.get("state.commitment-profile")).isEqualTo("mpf-blake2b256-v1");
        assertThat(settings.get("state.genesis-id"))
                .isEqualTo(HEX.formatHex(AuthenticatedMapContract.genesisId(first)));
        assertThat(TrustRegistryGenesis.properties(first, 0))
                .allMatch(line -> line.startsWith("yano.app-chain.chains[0]."));

        AuthenticatedMapContract.Genesis decoded = AuthenticatedMapContract.decodeGenesis(
                HEX.parseHex(settings.get("machines.authenticated-map.genesis-cbor-hex")));
        assertThat(TrustRegistryProfile.matches(decoded)).isTrue();

        AuthenticatedMapContract.Genesis different = TrustRegistryGenesis.genesis(
                descriptor, MEMBERS, 3);
        assertThat(AuthenticatedMapContract.genesisId(different))
                .isNotEqualTo(AuthenticatedMapContract.genesisId(first));
    }

    @Test
    void keyProofsMustBindTheActorToTheChain() {
        TrustRegistryGenesis.Descriptor demo = TrustRegistryGenesis.demo("trust-registry-chain");
        TrustRegistryGenesis.Actor issuer = demo.actors().stream()
                .filter(actor -> actor.id().equals("issuer-a")).findFirst().orElseThrow();
        TrustRegistryGenesis.ActorKey otherChain = TrustRegistryGenesis.actorKey(
                "another-chain", "issuer-a", "issuer-a-k1",
                TrustRegistryGenesis.demoActorSeed("issuer-a"));
        assertThat(otherChain.publicKeyHex()).isEqualTo(issuer.publicKeyHex());
        TrustRegistryGenesis.Descriptor tampered = new TrustRegistryGenesis.Descriptor(
                demo.chainId(), demo.organizations(),
                demo.actors().stream().map(actor -> actor.id().equals("issuer-a")
                        ? new TrustRegistryGenesis.Actor(actor.id(), actor.organizationId(),
                        actor.roles(), actor.keyId(), actor.publicKeyHex(),
                        otherChain.keyProofHex())
                        : actor).toList(),
                demo.authority(), demo.issuers(), demo.schemas());
        assertThatThrownBy(() -> TrustRegistryGenesis.genesis(tampered, MEMBERS, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer-a");
    }

    @Test
    void descriptorRejectsDanglingReferences() {
        String json = """
                {"schemaVersion":1,"chainId":"c","organizations":[{"id":"org-a"}],
                 "actors":[{"id":"x","organizationId":"org-b","roles":["registrar"],"keyId":"k",
                            "publicKeyHex":"%s","keyProofHex":"00"}],
                 "authority":{"id":"admins","administratorActorIds":["x"]}}
                """.formatted("00".repeat(32));
        assertThatThrownBy(() -> TrustRegistryGenesis.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown organization");
        assertThatThrownBy(() -> TrustRegistryGenesis.unsignedConfig("c", MEMBERS, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) value);
        return seed;
    }
}
