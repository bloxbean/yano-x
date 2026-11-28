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
 *   [3, vault_id, reason]   ASSET_HOLD: a CIP-113 asset → ISSUER_HOLD after an attributed issuer event (ADR-061 D14)
 *   [4, vault_id]           ASSET_RESUME: a held CIP-113 asset → ACTIVE (ADR-061 D14)
 *   [5, params_policy, [tx_id, index], transfer_delegate, third_party_delegate, unfracking_delegate]
 *                           ASSET_REWIRE: approve the head wiring version of the CIP-113 deployment of
 *                           {@code params_policy} with the reviewed delegate hashes (ADR-061 D6)
 * </pre>
 * {@code ASSET_STATUS} never moves an asset into or out of {@code ISSUER_HOLD}: only {@code ASSET_HOLD} and
 * {@code ASSET_RESUME} do.
 * {@code sequence} must equal the number of governance commands the ledger has decided so far
 * ({@code ap/gov-seq}). Deciding a command, applied or refused, advances it and voids every open proposal, so a
 * command takes effect at most once, a late copy of a decided command is stale, and approvals for an old state never
 * combine with approvals for a new one.
 *
 * <p><b>Proposals</b> ({@link Proposal}) live in {@code ap/gov-proposals}, each bound to the membership epoch it
 * started in and dropped {@link #APPROVAL_WINDOW_BLOCKS} blocks after it started. Each member has at most <b>one</b>
 * open proposal of its own, the one it started: a member's newer command replaces its older proposal, and approving
 * another member's proposal takes no slot. So the table holds at most one proposal per member of the epoch
 * ({@link #MAX_OPEN_PROPOSALS}, the membership bound), and no single member can fill it to block the others' commands
 * (a {@code PAUSE} above all, D15).
 * <b>History</b> ({@link DecisionV1}) is written once per decided command at {@code ap/gov-history/<sequence>} and
 * kept: it names the command, the approvers, the height and block time, the subject, the status before and after,
 * and whether the command was applied or refused (with the reason).
 */
public final class PaymentsGovernanceV1 {
    public static final String TOPIC = "~governance/asset-payments";
    public static final int VERSION = 1;
    public static final int MAX_COMMAND_BYTES = 256;
    /** The membership bound of {@code AppChainConfig.MAX_MEMBERS}, which caps a proposal's approvers. */
    public static final int MAX_APPROVERS = 64;
    /** One open proposal per proposing member, so the membership bound. */
    public static final int MAX_OPEN_PROPOSALS = MAX_APPROVERS;
    /** Blocks after its first approval at which an undecided proposal is dropped. */
    public static final long APPROVAL_WINDOW_BLOCKS = 600;

    private PaymentsGovernanceV1() {
    }

    /** One governed action. */
    public sealed interface Action permits AssetStatusChange, Pause, Resume, AssetHold, AssetResume, AssetRewire {
        int code();
    }

    /** Why members hold a CIP-113 asset (ADR-061 D14 detection): the command records it; it changes no rule. */
    public enum HoldReason {
        SHORTFALL(0), FREEZE(1), DRIFT(2), OTHER(3);

        private final int code;

        HoldReason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static HoldReason fromCode(long code) {
            for (HoldReason reason : values()) if (reason.code == code) return reason;
            throw AssetCbor.malformed("unknown hold reason");
        }
    }

    /** {@code ASSET_HOLD(VaultId, reason)} (ADR-061 D14). */
    public record AssetHold(Hash32 vaultId, HoldReason reason) implements Action {
        public AssetHold {
            Objects.requireNonNull(vaultId, "vault id");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public int code() {
            return 3;
        }
    }

    /** {@code ASSET_RESUME(VaultId)} (ADR-061 D14). */
    public record AssetResume(Hash32 vaultId) implements Action {
        public AssetResume {
            Objects.requireNonNull(vaultId, "vault id");
        }

        @Override
        public int code() {
            return 4;
        }
    }

    /**
     * {@code ASSET_REWIRE(deployment, versionOutpoint, delegateHashes)} (ADR-061 D6): the deployment by its params
     * policy, the wiring version by its params outpoint (it must be the head), and the reviewed delegate hashes.
     */
    public record AssetRewire(byte[] paramsPolicy, Hash32 versionTxId, long versionIndex, byte[] transferDelegate,
                              byte[] thirdPartyDelegate, byte[] unfrackingDelegate) implements Action {
        public AssetRewire {
            paramsPolicy = hash28(paramsPolicy, "params policy");
            Objects.requireNonNull(versionTxId, "version tx id");
            if (versionIndex < 0 || versionIndex > 65_535) throw new IllegalArgumentException("version index");
            transferDelegate = hash28(transferDelegate, "transfer delegate");
            thirdPartyDelegate = hash28(thirdPartyDelegate, "third-party delegate");
            unfrackingDelegate = hash28(unfrackingDelegate, "unfracking delegate");
        }

        @Override
        public byte[] paramsPolicy() {
            return paramsPolicy.clone();
        }

        @Override
        public byte[] transferDelegate() {
            return transferDelegate.clone();
        }

        @Override
        public byte[] thirdPartyDelegate() {
            return thirdPartyDelegate.clone();
        }

        @Override
        public byte[] unfrackingDelegate() {
            return unfrackingDelegate.clone();
        }

        @Override
        public int code() {
            return 5;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AssetRewire that && Arrays.equals(paramsPolicy, that.paramsPolicy)
                    && versionTxId.equals(that.versionTxId) && versionIndex == that.versionIndex
                    && Arrays.equals(transferDelegate, that.transferDelegate)
                    && Arrays.equals(thirdPartyDelegate, that.thirdPartyDelegate)
                    && Arrays.equals(unfrackingDelegate, that.unfrackingDelegate);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(paramsPolicy) * 31 + versionTxId.hashCode();
        }

        private static byte[] hash28(byte[] value, String name) {
            if (value == null || value.length != 28) throw new IllegalArgumentException(name + " must be 28 bytes");
            return value.clone();
        }
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
            switch (action) {
                case AssetStatusChange change -> {
                    body.add(AssetCbor.bstr(change.vaultId().bytes()));
                    body.add(AssetCbor.uint(change.status().code()));
                }
                case AssetHold hold -> {
                    body.add(AssetCbor.bstr(hold.vaultId().bytes()));
                    body.add(AssetCbor.uint(hold.reason().code()));
                }
                case AssetResume resume -> body.add(AssetCbor.bstr(resume.vaultId().bytes()));
                case AssetRewire rewire -> {
                    body.add(AssetCbor.bstr(rewire.paramsPolicy()));
                    Array version = new Array();
                    version.add(AssetCbor.bstr(rewire.versionTxId().bytes()));
                    version.add(AssetCbor.uint(rewire.versionIndex()));
                    body.add(version);
                    body.add(AssetCbor.bstr(rewire.transferDelegate()));
                    body.add(AssetCbor.bstr(rewire.thirdPartyDelegate()));
                    body.add(AssetCbor.bstr(rewire.unfrackingDelegate()));
                }
                case Pause ignored -> { }
                case Resume ignored -> { }
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
            List<DataItem> body = AssetCbor.array(items.get(2), 1, 6).getDataItems();
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
                case 3 -> {
                    if (body.size() != 3) throw AssetCbor.malformed("ASSET_HOLD takes a vault and a reason");
                    yield new AssetHold(Hash32.of(AssetCbor.bytes(body.get(1), 32, 32)),
                            HoldReason.fromCode(AssetCbor.uintLong(body.get(2))));
                }
                case 4 -> {
                    if (body.size() != 2) throw AssetCbor.malformed("ASSET_RESUME takes a vault");
                    yield new AssetResume(Hash32.of(AssetCbor.bytes(body.get(1), 32, 32)));
                }
                case 5 -> {
                    if (body.size() != 6) throw AssetCbor.malformed("ASSET_REWIRE takes six fields");
                    List<DataItem> version = AssetCbor.array(body.get(2), 2, 2).getDataItems();
                    long index = AssetCbor.uintLong(version.get(1));
                    if (index > 65_535) throw AssetCbor.malformed("ASSET_REWIRE version index");
                    yield new AssetRewire(AssetCbor.bytes(body.get(1), 28, 28),
                            Hash32.of(AssetCbor.bytes(version.get(0), 32, 32)), index,
                            AssetCbor.bytes(body.get(3), 28, 28), AssetCbor.bytes(body.get(4), 28, 28),
                            AssetCbor.bytes(body.get(5), 28, 28));
                }
                default -> throw AssetCbor.malformed("unknown governance action");
            };
            return new Command(sequence, action);
        }
    }

    /**
     * An open proposal: {@code [command, epoch_from_height, first_height, proposer(32), [* approver(32)]]}, approvers
     * in ascending byte order. The proposer is the member whose approval started it, and is always an approver.
     */
    public record Proposal(byte[] command, long epochFromHeight, long firstHeight, byte[] proposer,
                           List<byte[]> approvers) {
        public Proposal {
            Command.decode(command);
            if (epochFromHeight < 0 || firstHeight < 0) throw new IllegalArgumentException("invalid proposal");
            if (proposer == null || proposer.length != 32) throw new IllegalArgumentException("invalid proposer");
            approvers = sorted(approvers);
            proposer = proposer.clone();
            byte[] first = proposer;
            if (approvers.stream().noneMatch(key -> Arrays.equals(key, first))) {
                throw new IllegalArgumentException("the proposer approves its own proposal");
            }
            command = command.clone();
        }

        @Override
        public byte[] command() {
            return command.clone();
        }

        @Override
        public byte[] proposer() {
            return proposer.clone();
        }

        public boolean proposedBy(byte[] member) {
            return Arrays.equals(proposer, member);
        }

        /** This proposal with one more approver; the same proposal if the key already approved. */
        public Proposal with(byte[] approver) {
            if (contains(approver)) return this;
            List<byte[]> next = new ArrayList<>(approvers);
            next.add(approver.clone());
            return new Proposal(command, epochFromHeight, firstHeight, proposer, next);
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
            array.add(AssetCbor.bstr(proposer));
            Array keys = new Array();
            approvers.forEach(key -> keys.add(AssetCbor.bstr(key)));
            array.add(keys);
            return array;
        }

        static Proposal of(DataItem item) {
            List<DataItem> fields = AssetCbor.array(item, 5, 5).getDataItems();
            return new Proposal(AssetCbor.bytes(fields.get(0), 1, MAX_COMMAND_BYTES),
                    AssetCbor.uintLong(fields.get(1)), AssetCbor.uintLong(fields.get(2)),
                    AssetCbor.bytes(fields.get(3), 32, 32), approverKeys(fields.get(4)));
        }
    }

    public static byte[] encodeProposals(List<Proposal> proposals) {
        if (proposals.size() > MAX_OPEN_PROPOSALS) throw new IllegalArgumentException("too many open proposals");
        requireOnePerProposer(proposals);
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
        try {
            requireOnePerProposer(out);
        } catch (IllegalArgumentException invalid) {
            throw AssetCbor.malformed(invalid.getMessage());
        }
        return List.copyOf(out);
    }

    private static void requireOnePerProposer(List<Proposal> proposals) {
        for (int i = 0; i < proposals.size(); i++) {
            for (int j = i + 1; j < proposals.size(); j++) {
                if (proposals.get(i).proposedBy(proposals.get(j).proposer)) {
                    throw new IllegalArgumentException("a member has at most one open proposal");
                }
            }
        }
    }

    /** The committed query of {@link Status}: no parameters. */
    public static final String STATUS_QUERY = "asset-payments/governance";

    /**
     * The committed governance view a member reads to build its next command: {@code [sequence, [* Proposal]]}, the
     * number of commands decided so far and the open proposals (those still inside their epoch and window may be
     * approved; the ledger prunes the rest on its next governance message).
     */
    public record Status(long sequence, List<Proposal> open) {
        public Status {
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
            open = List.copyOf(open);
        }

        public byte[] encode() {
            Array array = new Array();
            array.add(AssetCbor.uint(sequence));
            Array proposals = new Array();
            open.forEach(proposal -> proposals.add(proposal.item()));
            array.add(proposals);
            return AssetCbor.encode(array);
        }

        public static Status decode(byte[] bytes) {
            List<DataItem> items = AssetCbor.decodeArray(bytes, 2).getDataItems();
            List<Proposal> open = new ArrayList<>();
            for (DataItem item : AssetCbor.array(items.get(1), 0, MAX_OPEN_PROPOSALS).getDataItems()) {
                open.add(Proposal.of(item));
            }
            return new Status(AssetCbor.uintLong(items.get(0)), open);
        }
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
