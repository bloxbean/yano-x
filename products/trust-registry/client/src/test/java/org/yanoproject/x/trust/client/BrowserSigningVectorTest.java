package org.yanoproject.x.trust.client;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapAuthorizationContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.trust.profile.TrustRegistryProfile;
import org.yanoproject.x.trust.profile.TrustRegistryValues;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The vectors the console's browser signer reproduces (ADR-053 §2.1). Every value here is what
 * the stock contracts produce for fixed inputs, so a drift in the Java encoders or in the
 * TypeScript port fails a test on one side or the other rather than a node at write time.
 *
 * <p>The same constants are asserted by {@code products/trust-registry/ui/src/lib/signer.test.ts}.
 */
class BrowserSigningVectorTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] SEED = HEX.parseHex(
            "1111111111111111111111111111111111111111111111111111111111111111");
    private static final byte[] AUTHORIZATION_ID = HEX.parseHex(
            "2222222222222222222222222222222222222222222222222222222222222222");
    private static final byte[] GENESIS_ID = HEX.parseHex(
            "3333333333333333333333333333333333333333333333333333333333333333");
    private static final String CHAIN_ID = "trust-registry-chain";

    private static AuthenticatedMapContract.Command command() {
        return AuthenticatedMapContract.Command.single(AuthenticatedMapContract.Mutation.put(
                TrustRegistryProfile.STATUS, TrustRegistryProfile.statusKey("list-1", 5),
                new TrustRegistryValues.StatusValue(1, 3).encode()));
    }

    private static AuthenticatedMapAuthorizationContract.MapActionV1 action() {
        AuthenticatedMapContract.Command command = command();
        return new AuthenticatedMapAuthorizationContract.MapActionV1(command.batch(), command.mutations(),
                List.of(new AuthenticatedMapAuthorizationContract.AuthorizationAssignmentV1(
                        0, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, TrustRegistryProfile.ISSUER_POLICY, 1)));
    }

    static final String PUBLIC_KEY =
            "d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737";
    static final String ACTION = "84010081870066737461747573486c6973742d312f3544830101030040"
            + "40818400036c6973737565722d777269746501";
    static final String COMMITMENT =
            "77fba8f56dd2960fae14bb60ec375a4a7ff8b15033efc6764fbae16c6d81e74e";
    static final String STATEMENT = "8f015820"
            + "2222222222222222222222222222222222222222222222222222222222222222"
            + "7474727573742d72656769737472792d636861696e"
            + "5820" + "3333333333333333333333333333333333333333333333333333333333333333"
            + "5820" + COMMITMENT
            + "8100"
            + "6c6973737565722d7772697465" + "01"
            + "686973737565722d61" + "01"
            + "6b6973737565722d612d6b31"
            + "5820" + PUBLIC_KEY
            + "07" + "186b" + "00";

    @Test
    void actionBytesAndCommitmentArePinned() {
        assertThat(HEX.formatHex(AuthenticatedMapAuthorizationContract.encodeAction(action())))
                .isEqualTo(ACTION);
        assertThat(HEX.formatHex(AuthenticatedMapAuthorizationContract.actionCommitment(action())))
                .isEqualTo(COMMITMENT);
    }

    @Test
    void theStatementAndTheSignedCommandArePinned() {
        byte[] publicKey = KeyGenUtil.getPublicKeyFromPrivateKey(SEED);
        assertThat(HEX.formatHex(publicKey)).isEqualTo(PUBLIC_KEY);

        AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1 authorization =
                AuthenticatedMapAuthorizationContract.MapActorAuthorizationV1.sign(
                        AUTHORIZATION_ID, CHAIN_ID, GENESIS_ID,
                        AuthenticatedMapAuthorizationContract.actionCommitment(action()),
                        List.of(0), TrustRegistryProfile.ISSUER_POLICY, 1, "issuer-a", 1,
                        "issuer-a-k1", publicKey, 7, 107, SEED);
        assertThat(HEX.formatHex(authorization.unsignedStatement()))
                .isEqualTo(STATEMENT);
        assertThat(authorization.verifyClaimedKey()).isTrue();

        // The full command a browser submits: the action, then the authorization as its evidence.
        String command = HEX.formatHex(TrustRegistrySigner.governedCommand(command(),
                TrustRegistryProfile.ISSUER_POLICY, 1,
                new TrustRegistrySigner.ActorContext("issuer-a", 1, "issuer-a-k1", publicKey, SEED),
                CHAIN_ID, GENESIS_ID, 7, 107, AUTHORIZATION_ID));
        assertThat(command).startsWith("830158" + Integer.toHexString(ACTION.length() / 2) + ACTION);
        assertThat(command).contains(STATEMENT);
        assertThat(command).endsWith("5840575570d7c3877ec43f03b98f0ea9e1bb89deb6170c038d9010c447"
                + "2234b14efc091c7f6d28f6bc77f60ad2bc283858905e486a33a866db4d485e517487ff8802");
    }
}
