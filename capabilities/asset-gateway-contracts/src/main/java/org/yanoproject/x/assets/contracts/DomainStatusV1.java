package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.List;
import java.util.Objects;

/**
 * The domain status leaf (ADR-059 D8a), kept by the consumer at one pinned key. The vault's {@code Exit} proves it
 * and reads {@code exitDelayMs} from it (V3); trigger (c) of {@code EnterExitMode} proves it {@code PAUSED} or
 * {@code HALTED} for longer than {@code maxPauseMs} (D8g §3).
 *
 * <p>Wire form: {@code [1, status, since_height, since_ms, exit_delay_ms]}, the strict deterministic subset of
 * {@code asset-gateway-v1.cddl}. The scripts read it at fixed offsets and reject anything else, including trailing
 * bytes.
 */
public record DomainStatusV1(Status status, long sinceHeight, long sinceMs, long exitDelayMs) {
    public static final int VERSION = 1;

    /** The domain status. The code is the wire value. */
    public enum Status {
        ACTIVE(0),
        PAUSED(1),
        HALTED(2);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Status fromCode(long code) {
            for (Status status : values()) if (status.code == code) return status;
            throw AssetCbor.malformed("unknown domain status");
        }
    }

    public DomainStatusV1 {
        Objects.requireNonNull(status, "status");
        if (sinceHeight < 0 || sinceMs < 0 || exitDelayMs < 0) {
            throw new IllegalArgumentException("domain status fields are non-negative");
        }
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.uint(status.code()));
        array.add(AssetCbor.uint(sinceHeight));
        array.add(AssetCbor.uint(sinceMs));
        array.add(AssetCbor.uint(exitDelayMs));
        return AssetCbor.encode(array);
    }

    public static DomainStatusV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 5).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("domain status version");
        try {
            return new DomainStatusV1(Status.fromCode(AssetCbor.uintLong(items.get(1))),
                    AssetCbor.uintLong(items.get(2)), AssetCbor.uintLong(items.get(3)),
                    AssetCbor.uintLong(items.get(4)));
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }
}
