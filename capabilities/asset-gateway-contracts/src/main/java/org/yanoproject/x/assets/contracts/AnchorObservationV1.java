package org.yanoproject.x.assets.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The claim of one {@code asset-anchor-v1} observation (ADR-059 D8b, D8h §3): an output of a phase-2-valid L1
 * transaction that carries the pinned anchor thread token at the pinned anchor script. The thread token is one-shot
 * and the anchor script never lets it leave, so such an output is the anchor; the claim reports what its datum says.
 *
 * <p>{@link Status#ACCEPTED} means the inline datum is an anchor datum v2 that the vault's
 * {@code MpfAnchorLib.acceptedAnchorState} accepts, judged on the decoded Plutus Data: the pinned genesis id,
 * application id, commitment profile and format fingerprint, member keys 32 bytes each and strictly ascending, and
 * {@code 1 ≤ threshold ≤ count} (D8h §8). The anchored state then carries the height, block hash, state root,
 * {@code updatedAtMs}, threshold and member keys. Anything else is {@link Status#UNACCEPTED}: no vault can reference
 * it, and the anchor identity is lost (D8b, a halt for the operators to handle). A consumer identifies a heartbeat by
 * {@code (height, updatedAtMs)} (D8g §1). The observation is stability-gated by the framework like every L1
 * observation, so a finalized one is an L1-stable anchor output (the D8b deposit gate, the handoff step 3 and the
 * proof floor all read it).
 *
 * <p>Wire form: {@code [1, tx_id, output_index, slot, block_hash, minted, status, [] / [height, block_hash,
 * state_root, updated_at_ms, threshold, [+ member_key]]]}; {@code minted} is 1 when the transaction mints the thread
 * policy (the bootstrap).
 */
public record AnchorObservationV1(Hash32 txId, long outputIndex, long slot, Hash32 blockHash, boolean minted,
                                  Optional<Anchored> anchored) {
    public static final int VERSION = 1;
    public static final String CLAIM_SCHEMA = "asset-anchor-claim-v1";
    /** The framework's member bound ({@code AppChainConfig.MAX_MEMBERS}). */
    public static final int MAX_MEMBERS = 32;

    public enum Status {
        ACCEPTED(0),
        UNACCEPTED(1);

        private final int code;

        Status(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /** The anchored app-ledger state of an accepted datum. */
    public record Anchored(long height, Hash32 blockHash, Hash32 stateRoot, long updatedAtMs, int threshold,
                           List<Hash32> memberKeys) {
        public Anchored {
            Objects.requireNonNull(blockHash, "block hash");
            Objects.requireNonNull(stateRoot, "state root");
            memberKeys = List.copyOf(memberKeys);
            if (height < 0 || updatedAtMs < 0) throw new IllegalArgumentException("height and time are non-negative");
            if (memberKeys.isEmpty() || memberKeys.size() > MAX_MEMBERS || threshold < 1
                    || threshold > memberKeys.size()) {
                throw new IllegalArgumentException("1 <= threshold <= member count <= 32");
            }
            for (int i = 1; i < memberKeys.size(); i++) {
                if (memberKeys.get(i - 1).compareTo(memberKeys.get(i)) >= 0) {
                    throw new IllegalArgumentException("member keys are strictly ascending");
                }
            }
        }

        /** The member keys as lowercase hex, the form of a membership handoff's set. */
        public Set<String> memberKeysHex() {
            Set<String> out = new TreeSet<>();
            memberKeys.forEach(key -> out.add(HexFormat.of().formatHex(key.bytes())));
            return out;
        }
    }

    public AnchorObservationV1 {
        Objects.requireNonNull(txId, "tx id");
        Objects.requireNonNull(blockHash, "block hash");
        Objects.requireNonNull(anchored, "anchored");
        if (outputIndex < 0 || outputIndex > PayoutClaimV1.MAX_INDEX || slot < 0) {
            throw new IllegalArgumentException("output index or slot out of range");
        }
    }

    public Status status() {
        return anchored.isPresent() ? Status.ACCEPTED : Status.UNACCEPTED;
    }

    public byte[] encode() {
        Array array = new Array();
        array.add(AssetCbor.uint(VERSION));
        array.add(AssetCbor.bstr(txId.bytes()));
        array.add(AssetCbor.uint(outputIndex));
        array.add(AssetCbor.uint(slot));
        array.add(AssetCbor.bstr(blockHash.bytes()));
        array.add(AssetCbor.uint(minted ? 1 : 0));
        array.add(AssetCbor.uint(status().code()));
        Array state = new Array();
        anchored.ifPresent(anchor -> {
            state.add(AssetCbor.uint(anchor.height()));
            state.add(AssetCbor.bstr(anchor.blockHash().bytes()));
            state.add(AssetCbor.bstr(anchor.stateRoot().bytes()));
            state.add(AssetCbor.uint(anchor.updatedAtMs()));
            state.add(AssetCbor.uint(anchor.threshold()));
            Array members = new Array();
            anchor.memberKeys().forEach(key -> members.add(AssetCbor.bstr(key.bytes())));
            state.add(members);
        });
        array.add(state);
        return AssetCbor.encode(array);
    }

    public static AnchorObservationV1 decode(byte[] cbor) {
        List<DataItem> items = AssetCbor.decodeArray(cbor, 8).getDataItems();
        if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("anchor claim version");
        long minted = AssetCbor.uintLong(items.get(5));
        long status = AssetCbor.uintLong(items.get(6));
        List<DataItem> state = AssetCbor.array(items.get(7), 0, 6).getDataItems();
        if (minted > 1 || status > 1 || (status == 0) != (state.size() == 6) || (status == 1 && !state.isEmpty())) {
            throw AssetCbor.malformed("anchor claim status");
        }
        try {
            Optional<Anchored> anchored = Optional.empty();
            if (status == 0) {
                List<Hash32> keys = new ArrayList<>();
                for (DataItem key : AssetCbor.array(state.get(5), 1, MAX_MEMBERS).getDataItems()) {
                    keys.add(Hash32.of(AssetCbor.bytes(key, 32, 32)));
                }
                long threshold = AssetCbor.uintLong(state.get(4));
                if (threshold > MAX_MEMBERS) throw AssetCbor.malformed("threshold out of range");
                anchored = Optional.of(new Anchored(AssetCbor.uintLong(state.get(0)),
                        Hash32.of(AssetCbor.bytes(state.get(1), 32, 32)),
                        Hash32.of(AssetCbor.bytes(state.get(2), 32, 32)),
                        AssetCbor.uintLong(state.get(3)), (int) threshold, keys));
            }
            return new AnchorObservationV1(Hash32.of(AssetCbor.bytes(items.get(1), 32, 32)),
                    AssetCbor.uintLong(items.get(2)), AssetCbor.uintLong(items.get(3)),
                    Hash32.of(AssetCbor.bytes(items.get(4), 32, 32)), minted == 1, anchored);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnchorObservationV1 that && Arrays.equals(encode(), that.encode());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(encode());
    }
}
