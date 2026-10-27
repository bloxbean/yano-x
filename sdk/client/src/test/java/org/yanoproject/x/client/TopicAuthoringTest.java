package org.yanoproject.x.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.impl.EdDSASigningProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.appchain.topics.TopicWire.Delegation;
import org.yanoproject.api.appchain.topics.TopicWire.Frame;
import org.yanoproject.api.appchain.topics.TopicWire.RegistryCommand;
import org.yanoproject.api.appchain.topics.TopicWire.Signature;
import org.yanoproject.api.appchain.topics.TopicWireCodec;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicAuthoringTest {
    private static final String CHAIN = "topics-vector-chain";
    private static final String GENERATION = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void issuesTheIndependentDelegationAndProducesBothExactFrames() throws IOException {
        Delegation expected = TopicWireCodec.decodeDelegation(vector("delegation"));
        Delegation issued = TopicAuthoring.issueDelegation(CHAIN, GENERATION, expected.delegate(),
                expected.scope(), expected.notBefore(), expected.notAfter(), signer(3));
        assertThat(TopicWireCodec.encodeDelegation(issued)).isEqualTo(vector("delegation"));
        for (String name : List.of("frame-delegated", "frame-direct-2-of-2")) {
            Frame expectedFrame = TopicWireCodec.decodeFrame(vector(name));
            var request = TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders/new", expectedFrame.payload(),
                    expectedFrame.proof().nonce(), expectedFrame.proof().notAfter(), expectedFrame.proof().delegation());
            List<TopicAuthoring.Signer> signers = name.equals("frame-delegated")
                    ? List.of(signer(4)) : List.of(signer(5), signer(3));
            Frame frame = TopicAuthoring.signWrite(request, signers);
            assertThat(TopicWireCodec.encodeFrame(frame)).as(name).isEqualTo(vector(name));
            assertThat(TopicAuthoring.verifySignatures(CHAIN, GENERATION, "acme/orders/new", frame)).isTrue();
            assertThat(TopicAuthoring.verifySignatures(CHAIN, GENERATION, "acme/orders/other", frame)).isFalse();
            assertThat(TopicAuthoring.verifySignatures("another-chain", GENERATION, "acme/orders/new", frame))
                    .isFalse();
            assertThat(TopicAuthoring.verifySignatures(CHAIN, "ff".repeat(32), "acme/orders/new", frame)).isFalse();
        }
    }

    @Test
    void offlineOwnerSigningAccumulatesTheSameCanonicalCommand() throws IOException {
        RegistryCommand expected = TopicWireCodec.decodeRegistryCommand(vector("command-create-child"));
        RegistryCommand command = TopicAuthoring.addRegistrySignature(
                TopicAuthoring.signRegistry(expected.statement(), signer(2)), signer(1));
        assertThat(TopicWireCodec.encodeRegistryCommand(command)).isEqualTo(vector("command-create-child"));
        assertThatThrownBy(() -> TopicAuthoring.addRegistrySignature(command, signer(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicated");
    }

    @Test
    void refusesInvalidExternalSignaturesAndDelegatedScopes() throws IOException {
        Frame frame = TopicWireCodec.decodeFrame(vector("frame-delegated"));
        var request = TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders/new", frame.payload(),
                frame.proof().nonce(), frame.proof().notAfter(), frame.proof().delegation());
        assertThatThrownBy(() -> TopicAuthoring.completeWrite(request,
                List.of(new Signature(signer(4).publicKey(), new byte[64])))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicAuthoring.signWrite(request, List.of(signer(5))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("session key");
        assertThatThrownBy(() -> TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders-other", frame.payload(),
                frame.proof().nonce(), frame.proof().notAfter(), frame.proof().delegation()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not cover");
        assertThatThrownBy(() -> TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders/new", frame.payload(),
                frame.proof().nonce(), frame.proof().delegation().notAfter() + 1, frame.proof().delegation()))
                .isInstanceOf(IllegalArgumentException.class);
        var invalid = new Delegation(frame.proof().delegation().delegate(), "acme/orders/", 0, 1,
                signer(3).publicKey(), new byte[64]);
        assertThatThrownBy(() -> TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders/new", frame.payload(),
                frame.proof().nonce(), 1, invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotsPayloadAndRejectsForgedRequests() throws IOException {
        Frame frame = TopicWireCodec.decodeFrame(vector("frame-direct-2-of-2"));
        byte[] payload = frame.payload();
        var request = TopicAuthoring.prepareWrite(CHAIN, GENERATION, "acme/orders/new", payload,
                frame.proof().nonce(), frame.proof().notAfter(), null);
        payload[0] ^= 1;
        assertThat(request.payload()).isEqualTo(frame.payload());
        assertThatThrownBy(() -> new TopicAuthoring.WriteRequest(request.statement(), payload, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TopicAuthoring.Signer signer(int value) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) value);
        return new TopicAuthoring.Signer() {
            @Override
            public String publicKey() {
                return Hex.encode(KeyGenUtil.getPublicKeyFromPrivateKey(seed));
            }

            @Override
            public byte[] sign(byte[] statement) {
                return new EdDSASigningProvider().sign(statement, seed);
            }
        };
    }

    private static byte[] vector(String name) throws IOException {
        try (var input = TopicAuthoringTest.class.getResourceAsStream("/topics/v1/vectors.json")) {
            for (JsonNode vector : new ObjectMapper().readTree(input).get("vectors")) {
                if (vector.get("name").asText().equals(name)) return Hex.decode(vector.get("hex").asText());
            }
        }
        throw new IllegalArgumentException("missing vector " + name);
    }
}
