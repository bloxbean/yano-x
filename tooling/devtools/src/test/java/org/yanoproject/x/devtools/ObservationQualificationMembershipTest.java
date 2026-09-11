package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationQualificationMembershipTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> ORIGINAL = IntStream.range(1, 6)
            .mapToObj(i -> "%064x".formatted(i)).toList();

    @Test
    void recoveryStagesCannotCrossUnreviewedOpenRoundBoundaries() {
        assertThat(ObservationQualificationMembership.validStart(53, false, false)).isTrue();
        assertThat(ObservationQualificationMembership.validStart(54, true, false)).isTrue();
        assertThat(ObservationQualificationMembership.validStart(61, true, false)).isTrue();
        assertThat(ObservationQualificationMembership.validStart(62, true, false)).isFalse();
        assertThat(ObservationQualificationMembership.validStart(53, true, false)).isFalse();
        assertThat(ObservationQualificationMembership.validStart(74, false, true)).isTrue();
        assertThat(ObservationQualificationMembership.validStart(73, false, true)).isFalse();
        assertThat(ObservationQualificationMembership.validStart(75, false, true)).isFalse();
        assertThat(ObservationQualificationMembership.validStart(74, true, true)).isFalse();
    }

    @Test
    void membershipSelectionPinsBothSidesOfEachActivationHeight() {
        var added = new ArrayList<>(ORIGINAL);
        added.add("06".repeat(32));
        var epochs = List.of(new ObservationQualificationMembership.Epoch(0, ORIGINAL),
                new ObservationQualificationMembership.Epoch(64, added),
                new ObservationQualificationMembership.Epoch(85, ORIGINAL));
        assertThat(ObservationQualificationMembership.selectMembers(epochs, ORIGINAL, 63)).hasSize(5);
        assertThat(ObservationQualificationMembership.selectMembers(epochs, ORIGINAL, 64)).hasSize(6);
        assertThat(ObservationQualificationMembership.selectMembers(epochs, ORIGINAL, 84)).hasSize(6);
        assertThat(ObservationQualificationMembership.selectMembers(epochs, ORIGINAL, 85)).hasSize(5);
        assertThatThrownBy(() -> ObservationQualificationMembership.selectMembers(List.of(
                new ObservationQualificationMembership.Epoch(0, ORIGINAL),
                new ObservationQualificationMembership.Epoch(64, ORIGINAL)), ORIGINAL, 64))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationQualificationMembership.selectMembers(List.of(
                new ObservationQualificationMembership.Epoch(0, ORIGINAL),
                new ObservationQualificationMembership.Epoch(0, added)), ORIGINAL, 64))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void governanceWireIsCanonicalAndKeepsTenBlockActivationLag() {
        assertThat(HEX.formatHex(ObservationQualificationMembership.command(true, new byte[32])))
                .isEqualTo("8401005820" + "00".repeat(32) + "0a");
        assertThat(HEX.formatHex(ObservationQualificationMembership.command(false, new byte[32])))
                .isEqualTo("8401015820" + "00".repeat(32) + "0a");
        assertThatThrownBy(() -> ObservationQualificationMembership.command(true, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalRequiresPinnedSenderFullSignatureBodyAndMessageIdentity() throws Exception {
        byte[] seed = HEX.parseHex("12".repeat(32));
        byte[] sender = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
        String chain = ObservationQualificationConfig.CHAIN_ID;
        String topic = "~governance/membership";
        byte[] body = ObservationQualificationMembership.command(true, new byte[32]);
        byte[] id = AppMessage.computeMessageId(chain, topic, sender, 7, 1000, body);
        byte[] signature = CryptoConfiguration.INSTANCE.getSigningProvider().sign(
                AppMessage.signedBodyBytes(chain, topic, sender, 7, 1000, body), seed);
        var signed = new AppMessage(1, id, chain, topic, sender, 7, 1000, body, 0, signature);
        var decoded = ObservationQualificationMembership.signedMessage(new ObjectMapper().valueToTree(signed));
        ObservationQualificationMembership.verifyApproval(decoded, body, HEX.formatHex(id), List.of(HEX.formatHex(sender)));
        assertThatThrownBy(() -> ObservationQualificationMembership.verifyApproval(decoded,
                ObservationQualificationMembership.command(false, new byte[32]), HEX.formatHex(id),
                List.of(HEX.formatHex(sender)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ObservationQualificationMembership.verifyApproval(decoded, body,
                HEX.formatHex(id), ORIGINAL)).isInstanceOf(IllegalStateException.class);
        signature[0] ^= 1;
        var invalid = new AppMessage(1, id, chain, topic, sender, 7, 1000, body, 0, signature);
        assertThatThrownBy(() -> ObservationQualificationMembership.verifyApproval(invalid, body,
                HEX.formatHex(id), List.of(HEX.formatHex(sender)))).isInstanceOf(IllegalStateException.class);
    }
}
