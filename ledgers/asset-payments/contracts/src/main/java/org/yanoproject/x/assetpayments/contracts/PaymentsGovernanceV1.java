package org.yanoproject.x.assetpayments.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import org.yanoproject.x.assetpayments.contracts.PaymentsRecords.AssetStatus;
import org.yanoproject.x.assets.contracts.DomainStatusV1;
import org.yanoproject.x.assets.contracts.Hash32;
import org.yanoproject.x.assets.contracts.internal.AssetCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Threshold-governed commands of the {@code asset-payments} ledger (ADR-059 D14 "{@code ASSET_*}, {@code PAUSE} /
 * {@code RESUME}", D8a). They ride the reserved topic {@link #TOPIC} as privileged member submissions, the pattern of
 * governed membership and the EUTxO bridge's governed parameters: the ledger accumulates approvals from distinct
 * members of the membership epoch at the block's height on the <b>exact</b> command bytes, and decides the command
 * once the epoch's threshold approves it.
 *
 * <p><b>Command</b>: {@code [1, sequence, action]}, where {@code action} is one of
 * <pre>
 *   [0, vault_id, status]   ASSET_STATUS: move one registry entry to a new lifecycle status (D14)
 *   [1]                     PAUSE:  domain status leaf ACTIVE → PAUSED (D8a)
 *   [2]                     RESUME: domain status leaf PAUSED → ACTIVE (D8a)
 * </pre>
 * {@code sequence} must equal the number of governance commands the ledger has decided so far
 * ({@code ap/gov-seq}). Deciding a command, applied or refused, advances it and voids every open proposal, so a
 * command takes effect at most once, a late copy of a decided command is stale, and approvals for an old state never
 * combine with approvals for a new one.
 *
 * <p><b>Proposals</b> ({@link Proposal}) live in {@code ap/gov-proposals}, at most {@link #MAX_OPEN_PROPOSALS}, each
 * bound to the membership epoch it started in and dropped {@link #APPROVAL_WINDOW_BLOCKS} blocks after it started.
 * <b>History</b> ({@link DecisionV1}) is written once per decided command at {@code ap/gov-history/<sequence>} and
 * kept: it names the command, the approvers, the height and block time, the subject, the status before and after,
 * and whether the command was applied or refused (with the reason).
 */
public final class PaymentsGovernanceV1 {
    public static final String TOPIC = "~governance/asset-payments";
    public static final int VERSION = 1;
    public static final int MAX_COMMAND_BYTES = 256;
    public static final int MAX_OPEN_PROPOSALS = 16;
    /** Blocks after its first approval at which an undecided proposal is dropped. */
    public static final long APPROVAL_WINDOW_BLOCKS = 600;
    /** The membership bound of {@code AppChainConfig.MAX_MEMBERS}, which caps a proposal's approvers. */
    public static final int MAX_APPROVERS = 64;

    private PaymentsGovernanceV1() {
    }

    /** One governed action. */
    public sealed interface Action permits AssetStatusChange, Pause, Resume {
        int code();
    }

    /** {@code ASSET_STATUS}: a lifecycle change of one registry entry. */
    public record AssetStatusChange(Hash32 vaultId, AssetStatus status) implements Action {
        public AssetStatusChange {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public int code() {
            return 0;
        }
    }

    /** {@code PAUSE} of the domain status leaf. */
    public record Pause() implements Action {
        @Override
        public int code() {
            return 1;
        }
    }

    /** {@code RESUME} of the domain status leaf. */
    public record Resume() implements Action {
        @Override
        public int code() {
            return 2;
        }
    }

    public record Command(long sequence, Action action) {
        public Command {
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
            Objects.requireNonNull(action, "action");
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(VERSION));
            array.add(AssetCbor.uint(sequence));
            Array body = new Array();
            body.add(AssetCbor.uint(action.code()));
            if (action instanceof AssetStatusChange change) {
                body.add(AssetCbor.bstr(change.vaultId().bytes()));
                body.add(AssetCbor.uint(change.status().code()));
            }
            array.add(body);
            return AssetCbor.encode(array);
        }

        public static Command decode(byte[] bytes) {
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMMAND_BYTES) {
                throw AssetCbor.malformed("governance command must be 1-" + MAX_COMMAND_BYTES + " bytes");
            }
            List<DataItem> items = AssetCbor.decodeArray(bytes, 3).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != VERSION) throw AssetCbor.malformed("governance command version");
            long sequence = AssetCbor.uintLong(items.get(1));
            List<DataItem> body = AssetCbor.array(items.get(2), 1, 3).getDataItems();
            long code = AssetCbor.uintLong(body.getFirst());
            Action action = switch ((int) Math.min(code, Integer.MAX_VALUE)) {
                case 0 -> {
                    if (body.size() != 3) throw AssetCbor.malformed("ASSET_STATUS takes a vault and a status");
                    yield new AssetStatusChange(Hash32.of(AssetCbor.bytes(body.get(1), 32, 32)),
                            AssetStatus.fromCode(AssetCbor.uintLong(body.get(2))));
                }
                case 1 -> {
                    if (body.size() != 1) throw AssetCbor.malformed("PAUSE takes no arguments");
                    yield new Pause();
                }
                case 2 -> {
                    if (body.size() != 1) throw AssetCbor.malformed("RESUME takes no arguments");
                    yield new Resume();
                }
                default -> throw AssetCbor.malformed("unknown governance action");
            };
            return new Command(sequence, action);
        }
    }

    /**
     * An open proposal: {@code [command, epoch_from_height, first_height, [* approver(32)]]}, approvers in ascending
     * byte order.
     */
    public record Proposal(byte[] command, long epochFromHeight, long firstHeight, List<byte[]> approvers) {
        public Proposal {
            Command.decode(command);
            if (epochFromHeight < 0 || firstHeight < 0) throw new IllegalArgumentException("invalid proposal");
            approvers = sorted(approvers);
            command = command.clone();
        }

        @Override
        public byte[] command() {
            return command.clone();
        }

        /** This proposal with one more approver; the same proposal if the key already approved. */
        public Proposal with(byte[] approver) {
            if (contains(approver)) return this;
            List<byte[]> next = new ArrayList<>(approvers);
            next.add(approver.clone());
            return new Proposal(command, epochFromHeight, firstHeight, next);
        }

        public boolean contains(byte[] approver) {
            return approvers.stream().anyMatch(key -> Arrays.equals(key, approver));
        }

        public boolean sameCommand(byte[] other) {
            return Arrays.equals(command, other);
        }

        Array item() {
            Array array = new Array();
            array.add(AssetCbor.bstr(command));
            array.add(AssetCbor.uint(epochFromHeight));
            array.add(AssetCbor.uint(firstHeight));
            Array keys = new Array();
            approvers.forEach(key -> keys.add(AssetCbor.bstr(key)));
            array.add(keys);
            return array;
        }

        static Proposal of(DataItem item) {
            List<DataItem> fields = AssetCbor.array(item, 4, 4).getDataItems();
            return new Proposal(AssetCbor.bytes(fields.get(0), 1, MAX_COMMAND_BYTES),
                    AssetCbor.uintLong(fields.get(1)), AssetCbor.uintLong(fields.get(2)), approverKeys(fields.get(3)));
        }
    }

    public static byte[] encodeProposals(List<Proposal> proposals) {
        if (proposals.size() > MAX_OPEN_PROPOSALS) throw new IllegalArgumentException("too many open proposals");
        Array array = new Array();
        proposals.forEach(proposal -> array.add(proposal.item()));
        return AssetCbor.encode(array);
    }

    public static List<Proposal> decodeProposals(byte[] bytes) {
        List<Proposal> out = new ArrayList<>();
        for (DataItem item : AssetCbor.array(AssetCbor.decodeArray(bytes, arities(MAX_OPEN_PROPOSALS)), 0,
                MAX_OPEN_PROPOSALS).getDataItems()) {
            out.add(Proposal.of(item));
        }
        return List.copyOf(out);
    }

    /** What deciding a command did. The code is the wire value. */
    public enum Outcome { APPLIED, REFUSED }

    /**
     * The history record of one decided command: {@code [1, sequence, command, height, block_time_ms,
     * [* approver(32)], subject, from, to, outcome, reason]}. {@code subject} is the vault id for {@code ASSET_STATUS}
     * and empty for the domain status leaf; {@code from} and {@code to} are {@link AssetStatus} or
     * {@link DomainStatusV1.Status} codes; {@code to} equals {@code from} when the command was refused.
     */
    public record DecisionV1(long sequence, byte[] command, long height, long blockTimeMs, List<byte[]> approvers,
                             Optional<Hash32> subject, int from, int to, Outcome outcome, String reason) {
        public DecisionV1 {
            Command.decode(command);
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(outcome, "outcome");
            if (sequence < 0 || height < 0 || blockTimeMs < 0 || from < 0 || to < 0 || reason == null
                    || reason.length() > 64 || (outcome == Outcome.APPLIED) != reason.isEmpty()
                    || (outcome == Outcome.REFUSED && from != to)) {
                throw new IllegalArgumentException("invalid governance decision");
            }
            approvers = sorted(approvers);
            command = command.clone();
        }

        @Override
        public byte[] command() {
            return command.clone();
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(1));
            array.add(AssetCbor.uint(sequence));
            array.add(AssetCbor.bstr(command));
            array.add(AssetCbor.uint(height));
            array.add(AssetCbor.uint(blockTimeMs));
            Array keys = new Array();
            approvers.forEach(key -> keys.add(AssetCbor.bstr(key)));
            array.add(keys);
            array.add(AssetCbor.bstr(subject.map(Hash32::bytes).orElse(new byte[0])));
            array.add(AssetCbor.uint(from));
            array.add(AssetCbor.uint(to));
            array.add(AssetCbor.uint(outcome.ordinal()));
            array.add(new co.nstant.in.cbor.model.UnicodeString(reason));
            return AssetCbor.encode(array);
        }

        public static DecisionV1 decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 11).getDataItems();
            if (AssetCbor.uintLong(items.get(0)) != 1) throw AssetCbor.malformed("governance decision version");
            byte[] subject = AssetCbor.bytes(items.get(6), 0, 32);
            if (subject.length != 0 && subject.length != 32) throw AssetCbor.malformed("decision subject");
            long outcome = AssetCbor.uintLong(items.get(9));
            if (outcome > 1) throw AssetCbor.malformed("decision outcome");
            try {
                return new DecisionV1(AssetCbor.uintLong(items.get(1)),
                        AssetCbor.bytes(items.get(2), 1, MAX_COMMAND_BYTES), AssetCbor.uintLong(items.get(3)),
                        AssetCbor.uintLong(items.get(4)), approverKeys(items.get(5)),
                        subject.length == 0 ? Optional.empty() : Optional.of(Hash32.of(subject)),
                        (int) Math.min(AssetCbor.uintLong(items.get(7)), 255),
                        (int) Math.min(AssetCbor.uintLong(items.get(8)), 255), Outcome.values()[(int) outcome],
                        AssetCbor.text(items.get(10), 64));
            } catch (IllegalArgumentException invalid) {
                throw AssetCbor.malformed(invalid.getMessage());
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DecisionV1 that && Arrays.equals(encode(), that.encode());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(encode());
        }

        @Override
        public String toString() {
            return "DecisionV1[" + sequence + ", " + HexFormat.of().formatHex(command) + ", " + outcome + " "
                    + reason + "]";
        }
    }

    private static List<byte[]> approverKeys(DataItem item) {
        List<byte[]> keys = new ArrayList<>();
        for (DataItem key : AssetCbor.array(item, 0, MAX_APPROVERS).getDataItems()) {
            keys.add(AssetCbor.bytes(key, 32, 32));
        }
        return keys;
    }

    private static List<byte[]> sorted(List<byte[]> keys) {
        Objects.requireNonNull(keys, "approvers");
        if (keys.size() > MAX_APPROVERS) throw new IllegalArgumentException("too many approvers");
        List<byte[]> copy = new ArrayList<>();
        for (byte[] key : keys) {
            if (key == null || key.length != 32) throw new IllegalArgumentException("approver keys are 32 bytes");
            copy.add(key.clone());
        }
        copy.sort(Arrays::compareUnsigned);
        for (int i = 1; i < copy.size(); i++) {
            if (Arrays.equals(copy.get(i - 1), copy.get(i))) throw new IllegalArgumentException("duplicate approver");
        }
        return copy.stream().map(byte[]::clone).toList();
    }

    private static int[] arities(int max) {
        int[] out = new int[max + 1];
        for (int i = 0; i <= max; i++) out[i] = i;
        return out;
    }
}
