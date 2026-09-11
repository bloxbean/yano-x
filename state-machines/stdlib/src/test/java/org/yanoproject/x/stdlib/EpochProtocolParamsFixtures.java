package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;

import java.math.BigInteger;

final class EpochProtocolParamsFixtures {
    private EpochProtocolParamsFixtures() { }

    static ProtocolParamsView preConway(long epoch) {
        return view(epoch, BigInteger.valueOf(2_000_000L), null);
    }

    static ProtocolParamsView conway(long epoch) {
        return view(epoch, BigInteger.valueOf(2_000_000L), BigInteger.valueOf(500_000_000L));
    }

    static ProtocolParamsView byron(long epoch) {
        return view(epoch, null, null);
    }

    private static ProtocolParamsView view(long epoch, BigInteger keyDeposit,
                                           BigInteger drepDeposit) {
        ProtocolParamsSnapshot snapshot = new ProtocolParamsSnapshot(
                Math.toIntExact(epoch),
                44,
                155381,
                90112,
                16384,
                1100,
                keyDeposit,
                null,
                18,
                500,
                null,
                null,
                null,
                null,
                null,
                drepDeposit == null ? 8 : 9,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                150,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                3,
                146,
                6,
                null,
                drepDeposit,
                20,
                null);
        return new ProtocolParamsView(epoch, ProtocolParamsCanonicalCodec.encode(snapshot));
    }
}
