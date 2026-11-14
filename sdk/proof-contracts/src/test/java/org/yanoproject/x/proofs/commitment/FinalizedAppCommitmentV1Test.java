package org.yanoproject.x.proofs.commitment;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;

class FinalizedAppCommitmentV1Test {
    private static final int VERSION_OFFSET = "yano-app-finalized-commitment-v1\0"
            .getBytes(StandardCharsets.US_ASCII).length;
    private static final int CHAIN_LENGTH_OFFSET = VERSION_OFFSET + Integer.BYTES;
    private static final int CHAIN_TEXT_OFFSET = CHAIN_LENGTH_OFFSET + Integer.BYTES;

    @Test
    void canonicalCommitmentAndReceiptMatchIndependentGoldenVectors() throws Exception {
        FinalizedAppCommitmentV1 commitment = sample();
        assertThat(commitment.encode()).hasSize(236);
        try (var vector = getClass().getResourceAsStream(
                "/META-INF/yano/contracts/commitment/v1/finalized-commitment.hex")) {
            assertThat(vector).isNotNull();
            assertThat(commitment.encode()).isEqualTo(HexFormat.of().parseHex(
                    new String(vector.readAllBytes(), StandardCharsets.US_ASCII).trim()));
        }
        assertThat(HexFormat.of().formatHex(commitment.digest()))
                .isEqualTo("6871ab20b6404d103d2a39200fa3ffe06a90789df20a64e70e074806469d43ee");
        assertThat(FinalizedAppCommitmentV1.decode(commitment.encode())).isEqualTo(commitment);
        CommitmentDestination destination = new CommitmentDestination("cardano:preprod", "anchor:primary", bytes(6));
        assertThat(HexFormat.of().formatHex(destination.receiptKey(commitment)))
                .isEqualTo("cb77b8347daf6f62be99f080dc68a277ab9e0cb5984ce6472086d28ae94d1ce3");
    }

    @Test
    void allDestinationDimensionsAndChainGenesisSeparateReceipts() {
        FinalizedAppCommitmentV1 commitment = sample();
        CommitmentDestination destination = new CommitmentDestination("network:a", "adapter:a", bytes(6));
        byte[] key = destination.receiptKey(commitment);
        assertThat(destination.receiptKey(FinalizedAppCommitmentV1.decode(commitment.encode()))).isEqualTo(key);
        assertThat(new CommitmentDestination("network:b", "adapter:a", bytes(6)).receiptKey(commitment)).isNotEqualTo(key);
        assertThat(new CommitmentDestination("network:a", "adapter:b", bytes(6)).receiptKey(commitment)).isNotEqualTo(key);
        assertThat(new CommitmentDestination("network:a", "adapter:a", bytes(7)).receiptKey(commitment)).isNotEqualTo(key);
        assertThat(destination.receiptKey(new FinalizedAppCommitmentV1("orders", "mpf-blake2b256-v1",
                bytes(1), bytes(8), bytes(3), 42, bytes(4), bytes(5)))).isNotEqualTo(key);
        assertThat(destination.receiptKey(new FinalizedAppCommitmentV1("other-chain", "mpf-blake2b256-v1",
                bytes(1), bytes(2), bytes(3), 42, bytes(4), bytes(5)))).isNotEqualTo(key);
    }

    @Test
    void rejectsNonCanonicalMalformedAndUnknownRepresentations() {
        byte[] encoded = sample().encode();
        for (int length = 0; length < encoded.length; length++) {
            byte[] truncated = Arrays.copyOf(encoded, length);
            assertThatThrownBy(() -> FinalizedAppCommitmentV1.decode(truncated))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> FinalizedAppCommitmentV1.decode(Arrays.copyOf(encoded, encoded.length + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FinalizedAppCommitmentV1("orders", "mpf", bytes(1), bytes(2), bytes(3),
                0, bytes(4), bytes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommitmentDestination("\uD800", "anchor", bytes(6)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownVersionWithoutCorruptingTheDomain() {
        byte[] encoded = sample().encode();
        assertThat(ByteBuffer.wrap(encoded).getInt(VERSION_OFFSET)).isEqualTo(1);
        ByteBuffer.wrap(encoded).putInt(VERSION_OFFSET, 2);
        assertThatThrownBy(() -> FinalizedAppCommitmentV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown finalized commitment domain/version");
    }

    @Test
    void rejectsMalformedChainLengthAfterTheCompleteVersionField() {
        byte[] encoded = sample().encode();
        assertThat(ByteBuffer.wrap(encoded).getInt(CHAIN_LENGTH_OFFSET)).isEqualTo(6);
        ByteBuffer.wrap(encoded).putInt(CHAIN_LENGTH_OFFSET, Integer.MAX_VALUE);
        assertThatThrownBy(() -> FinalizedAppCommitmentV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid identity length");
    }

    @Test
    void rejectsMalformedUtf8InsideChainTextWithoutChangingItsLength() {
        byte[] encoded = sample().encode();
        assertThat(Arrays.copyOfRange(encoded, CHAIN_TEXT_OFFSET, CHAIN_TEXT_OFFSET + 6))
                .isEqualTo("orders".getBytes(StandardCharsets.UTF_8));
        encoded[CHAIN_TEXT_OFFSET] = (byte) 0xff;
        assertThatThrownBy(() -> FinalizedAppCommitmentV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identity is not valid UTF-8");
    }

    @Test
    void inputsAndAccessorsCannotMutateIdentity() {
        byte[] original = bytes(1);
        FinalizedAppCommitmentV1 commitment = new FinalizedAppCommitmentV1("orders", "mpf", original,
                original, original, 42, original, original);
        byte[] encoded = commitment.encode();
        Arrays.fill(original, (byte) 9);
        Arrays.fill(commitment.genesisId(), (byte) 8);
        Arrays.fill(commitment.stateRoot(), (byte) 7);
        assertThat(commitment.encode()).isEqualTo(encoded);
        CommitmentDestination destination = new CommitmentDestination("network", "adapter", original);
        byte[] receipt = destination.receiptKey(commitment);
        Arrays.fill(original, (byte) 3);
        Arrays.fill(destination.configurationDigest(), (byte) 4);
        assertThat(destination.receiptKey(commitment)).isEqualTo(receipt);
    }

    private static FinalizedAppCommitmentV1 sample() {
        return new FinalizedAppCommitmentV1("orders", "mpf-blake2b256-v1", bytes(1), bytes(2),
                bytes(3), 42, bytes(4), bytes(5));
    }
    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
