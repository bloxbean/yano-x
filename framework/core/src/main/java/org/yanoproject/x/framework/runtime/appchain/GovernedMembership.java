package org.yanoproject.x.framework.runtime.appchain;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.slf4j.Logger;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppChainConfig;
import org.yanoproject.x.framework.api.appchain.codec.internal.CborStructurePreflight;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Chain-governed membership (ADR app-layer/008.3): membership changes are
 * finalized app messages on {@code ~governance/membership}. A command
 * ACTIVATES once threshold-many distinct members' identical commands are
 * finalized within the approval window; activation appends a membership epoch
 * effective {@code activation-lag} blocks later. Everything here is a
 * deterministic function of finalized history: every node derives identical
 * pending state and epochs, catch-up included.
 *
 * <pre>
 *   membership-command = [ version(1), op, subject, activation-lag ]
 *     op 0 = add-member    subject = member key (bstr, 32)
 *     op 1 = remove-member subject = member key (bstr, 32)
 *     op 2 = set-threshold subject = k (uint)
 * </pre>
 *
 * Guard rails (evaluated at activation, deterministic): a change never drops
 * the member count below the active threshold; thresholds stay in [1, n];
 * removing the configured FIXED proposer is void; and the resulting
 * threshold-of-members pair must satisfy {@code ConsensusQuorum} under the
 * chain's {@code consensus.max-byzantine-members} bound f
 * ({@code 2·threshold − n > f} and {@code threshold ≤ n − f}), because every
 * block's consensus context is built from it (bloxbean/yano#163). A violating
 * activation is a deterministic no-op, never a stall.
 *
 * <p><b>Anchor handoff gate</b> (ADR-059 D8b, framework addition 4; opt-in with
 * {@value #HANDOFF_SETTING}{@code =true}): an approved change is committed as a
 * single {@link MembershipHandoff} instead of activating. It activates only when the
 * {@link MembershipHandoffGate} reports, for a later finalized block, that an
 * L1-stable anchor output carries exactly the new set; the epoch then starts
 * {@code activation-lag} blocks after that block. While one handoff is pending,
 * further approved changes are void (deterministic no-ops), so the old set keeps
 * authority until the handoff is final and an interrupted handoff is simply retried.
 * The pending handoff persists with the block commit like the approval state.</p>
 */
final class GovernedMembership {

    static final String TOPIC = "~governance/membership";
    static final int OP_ADD = 0;
    static final int OP_REMOVE = 1;
    static final int OP_SET_THRESHOLD = 2;
    static final long DEFAULT_APPROVAL_WINDOW_BLOCKS = 600;

    /** Chain-governed membership selected ({@code membership.mode: governed}, ADR 008.3)? Default: static. */
    static boolean selected(java.util.Map<String, String> settings) {
        return "governed".equalsIgnoreCase(settings.getOrDefault("membership.mode", "static"));
    }

    /** {@code membership.approval-window-blocks}; topic governance has its own committed window (D5). */
    static long approvalWindowBlocks(AppChainConfig config) {
        long value = Long.parseLong(config.pluginSettings().getOrDefault("membership.approval-window-blocks",
                String.valueOf(DEFAULT_APPROVAL_WINDOW_BLOCKS)).trim());
        return value > 0 ? value : DEFAULT_APPROVAL_WINDOW_BLOCKS;
    }
    static final long DEFAULT_ACTIVATION_LAG = 10;
    /** Opt-in L1 anchor handoff gate for governed changes (ADR-059 D8b). */
    static final String HANDOFF_SETTING = MembershipHandoff.SETTING;

    /** {@value #HANDOFF_SETTING}: true, false (default). Anything else fails start. */
    static boolean handoffSelected(java.util.Map<String, String> settings) {
        return MembershipHandoff.selected(settings);
    }

    private static final String META_PENDING = "gov_pending";
    private static final String META_HANDOFF = "gov_anchor_handoff";
    private static final CborStructurePreflight.Limits COMMAND_CBOR_LIMITS =
            new CborStructurePreflight.Limits(256, 3, 16, 8, 64);

    private final MemberGroup group;
    private final String fixedProposerHex;
    private final long approvalWindowBlocks;
    private final int maxByzantineMembers;
    private final Logger log;
    private Predicate<EpochEffect> epochGuard = ignored -> true;
    private final boolean handoffGated;
    private volatile MembershipHandoffGate handoffGate = MembershipHandoffGate.NONE;
    /** Read by the anchor thread; written only while processing a finalized block. */
    private volatile MembershipHandoff pendingHandoff;

    void setEpochGuard(Predicate<EpochEffect> guard) {
        this.epochGuard = Objects.requireNonNull(guard, "guard");
    }

    void setHandoffGate(MembershipHandoffGate gate) {
        this.handoffGate = Objects.requireNonNull(gate, "gate");
    }

    boolean handoffGated() {
        return handoffGated;
    }

    /** The committed change awaiting its anchor handoff, if the gate is on and one is pending. */
    java.util.Optional<MembershipHandoff> pendingHandoff() {
        return java.util.Optional.ofNullable(pendingHandoff);
    }

    /** command bytes (hex) → approval state; rebuilt from meta on restart. */
    private final LinkedHashMap<String, Pending> pending = new LinkedHashMap<>();

    record Command(int op, byte[] memberKey, int threshold, long activationLag) {
    }

    private static final class Pending {
        final long firstSeenHeight;
        final Set<String> approvers = new TreeSet<>();

        Pending(long firstSeenHeight) {
            this.firstSeenHeight = firstSeenHeight;
        }
    }

    /** One meta write to include in the block's atomic commit batch. */
    record MetaWrite(String key, byte[] value) {
    }

    /** Result of processing one block: batch writes + post-commit epoch effects. */
    record Result(List<MetaWrite> writes, List<EpochEffect> effects) {
        static final Result EMPTY = new Result(List.of(), List.of());
    }

    record EpochEffect(long fromHeight, Set<String> members, int threshold) {
    }

    GovernedMembership(MemberGroup group, String fixedProposerHex,
                       long approvalWindowBlocks, int maxByzantineMembers, Logger log) {
        this(group, fixedProposerHex, approvalWindowBlocks, maxByzantineMembers, false, log);
    }

    GovernedMembership(MemberGroup group, String fixedProposerHex,
                       long approvalWindowBlocks, int maxByzantineMembers,
                       boolean handoffGated, Logger log) {
        this.handoffGated = handoffGated;
        this.group = Objects.requireNonNull(group, "group");
        this.fixedProposerHex = fixedProposerHex != null
                ? fixedProposerHex.toLowerCase(Locale.ROOT) : "";
        this.approvalWindowBlocks = approvalWindowBlocks > 0
                ? approvalWindowBlocks : DEFAULT_APPROVAL_WINDOW_BLOCKS;
        this.maxByzantineMembers = maxByzantineMembers;
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Restore pending-approval state persisted with the last committed block. */
    void restore(AppLedgerStore ledger) {
        byte[] handoff = ledger.metaBytes(META_HANDOFF);
        pendingHandoff = handoff == null || handoff.length == 0 ? null : decodeHandoff(handoff);
        if (pendingHandoff != null && !handoffGated) {
            throw new IllegalStateException("The ledger holds a pending anchor membership handoff "
                    + "but " + HANDOFF_SETTING + " is not enabled");
        }
        byte[] stored = ledger.metaBytes(META_PENDING);
        pending.clear();
        if (stored == null || stored.length == 0) {
            return;
        }
        Map cbor = (Map) CborSerializationUtil.deserializeOne(stored);
        for (DataItem keyItem : cbor.getKeys()) {
            String commandHex = ((UnicodeString) keyItem).getString();
            Array value = (Array) cbor.get(keyItem);
            Pending entry = new Pending(
                    ((UnsignedInteger) value.getDataItems().get(0)).getValue().longValue());
            Array approvers = (Array) value.getDataItems().get(1);
            for (DataItem approver : approvers.getDataItems()) {
                entry.approvers.add(((UnicodeString) approver).getString());
            }
            pending.put(commandHex, entry);
        }
    }

    /**
     * Deterministically process one finalized block's governance messages.
     * Returns the meta writes for the SAME atomic commit batch; activated
     * epochs are applied to the in-memory group HERE (so later commands in
     * the same block see them — and the returned {@code member_epochs} write
     * reflects them). If the subsequent commit fails (fatal disk-class error),
     * the node aborts the round and a restart re-derives the group from the
     * ledger — no divergence survives. The effects list is informational.
     */
    Result processBlock(AppBlock block) {
        boolean touched = false;
        boolean handoffTouched = false;
        List<EpochEffect> effects = new ArrayList<>();
        long height = block.height();

        // ADR-059 D8b step 4: a finalized L1-stable handoff activates the committed set.
        MembershipHandoff handoff = pendingHandoff;
        if (handoffGated && handoff != null && height > handoff.committedHeight()
                && handoffGate.finalizesHandoff(block, handoff)) {
            EpochEffect effect = new EpochEffect(height + handoff.activationLag(),
                    handoff.members(), handoff.threshold());
            effects.add(effect);
            group.appendEpoch(effect.fromHeight(), effect.members(), effect.threshold());
            pendingHandoff = null;
            handoffTouched = true;
            log.info("Governance handoff FINALIZED on L1 at height {}: {}-of-{} from height {}",
                    height, effect.threshold(), effect.members().size(), effect.fromHeight());
        }

        // Expire stale half-approved commands (window is in blocks)
        touched |= pending.entrySet().removeIf(
                e -> height - e.getValue().firstSeenHeight > approvalWindowBlocks);

        for (AppMessage message : block.messages()) {
            if (!TOPIC.equals(message.getTopic())) {
                continue;
            }
            String approver = HexUtil.encodeHexString(message.getSender()).toLowerCase(Locale.ROOT);
            if (!group.containsAt(approver, height)) {
                continue; // not a member at this height — deterministic skip
            }
            Command command;
            try {
                command = decodeCommand(message.getBody());
            } catch (Exception e) {
                log.warn("Malformed governance command in block {} — ignored ({})",
                        height, e.toString());
                continue;
            }
            String commandHex = HexUtil.encodeHexString(message.getBody());
            Pending entry = pending.computeIfAbsent(commandHex, k -> new Pending(height));
            touched |= entry.approvers.add(approver);

            int required = group.thresholdAt(height);
            if (group.currentApprovers(entry.approvers, height).size() >= required) {
                pending.remove(commandHex);
                touched = true;
                EpochEffect effect = activate(command, height);
                if (effect != null && handoffGated) {
                    // D8b steps 1-3: commit as pending; the anchor carries it before it activates.
                    if (pendingHandoff != null) {
                        log.warn("Governance: a membership handoff is already pending at height {} "
                                + "— change void", height);
                    } else {
                        pendingHandoff = new MembershipHandoff(height, effect.members(),
                                effect.threshold(), effect.fromHeight() - height);
                        handoffTouched = true;
                        log.info("Governance COMMITTED (pending anchor handoff): {}-of-{} at height {}",
                                effect.threshold(), effect.members().size(), height);
                    }
                } else if (effect != null) {
                    effects.add(effect);
                    // Later commands in the SAME block see the new epoch
                    group.appendEpoch(effect.fromHeight(), effect.members(), effect.threshold());
                }
            }
        }

        if (!touched && !handoffTouched && effects.isEmpty()) {
            return Result.EMPTY;
        }
        List<MetaWrite> writes = new ArrayList<>();
        writes.add(new MetaWrite(META_PENDING, encodePending()));
        if (handoffTouched) {
            MembershipHandoff current = pendingHandoff;
            writes.add(new MetaWrite(META_HANDOFF,
                    current == null ? new byte[0] : encodeHandoff(current)));
        }
        if (!effects.isEmpty()) {
            writes.add(new MetaWrite("member_epochs",
                    group.encode().getBytes(StandardCharsets.UTF_8)));
        }
        return new Result(writes, effects);
    }

    /** Apply guard rails; null = void activation (deterministic no-op). */
    private EpochEffect activate(Command command, long height) {
        Set<String> members = new LinkedHashSet<>(group.membersAt(height));
        int threshold = group.thresholdAt(height);
        long fromHeight = height + Math.max(1, command.activationLag());
        String change;

        switch (command.op()) {
            case OP_ADD -> {
                String key = HexUtil.encodeHexString(command.memberKey()).toLowerCase(Locale.ROOT);
                if (members.contains(key)) {
                    log.info("Governance: add of existing member {} at height {} — no-op", key, height);
                    return null;
                }
                if (members.size() >= AppChainConfig.MAX_MEMBERS) {
                    log.warn("Governance: adding a member would exceed the v1 limit of {} "
                                    + "at height {} — void",
                            AppChainConfig.MAX_MEMBERS, height);
                    return null;
                }
                members.add(key);
                change = "add member " + key;
            }
            case OP_REMOVE -> {
                String key = HexUtil.encodeHexString(command.memberKey()).toLowerCase(Locale.ROOT);
                if (key.equals(fixedProposerHex)) {
                    log.warn("Governance: removal of the FIXED proposer at height {} is void", height);
                    return null;
                }
                if (!members.remove(key)) {
                    log.info("Governance: removal of non-member {} at height {} — no-op", key, height);
                    return null;
                }
                if (members.size() < threshold) {
                    log.warn("Governance: removing {} would leave {} member(s) below threshold {} "
                            + "at height {} — void", key, members.size(), threshold, height);
                    return null;
                }
                change = "remove member " + key;
            }
            case OP_SET_THRESHOLD -> {
                if (command.threshold() < 1 || command.threshold() > members.size()) {
                    log.warn("Governance: threshold {} outside [1, {}] at height {} — void",
                            command.threshold(), members.size(), height);
                    return null;
                }
                threshold = command.threshold();
                change = "threshold " + threshold;
            }
            default -> {
                return null;
            }
        }
        String violation = MemberGroup.quorumViolation(members.size(), threshold, maxByzantineMembers);
        if (violation != null) {
            log.warn("Governance: {} at height {} would leave a {}-of-{} membership that breaks the "
                            + "consensus quorum rules for consensus.max-byzantine-members={} ({}) — void",
                    change, height, threshold, members.size(), maxByzantineMembers, violation);
            return null;
        }
        EpochEffect effect = new EpochEffect(fromHeight, members, threshold);
        if (!epochGuard.test(effect)) {
            log.warn("Governance: membership at height {} violates the observation profile — void", height);
            return null;
        }
        log.info("Governance ACTIVATED: {} from height {} ({}-of-{})",
                change, fromHeight, threshold, members.size());
        return effect;
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    static byte[] encodeCommand(int op, byte[] memberKeyOrNull, int threshold, long activationLag) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(1)); // version
        arr.add(new UnsignedInteger(op));
        if (op == OP_SET_THRESHOLD) {
            arr.add(new UnsignedInteger(threshold));
        } else {
            arr.add(new ByteString(memberKeyOrNull));
        }
        arr.add(new UnsignedInteger(activationLag));
        return CborSerializationUtil.serialize(arr);
    }

    static Command decodeCommand(byte[] body) {
        if (!CborStructurePreflight.accepts(body, COMMAND_CBOR_LIMITS)) {
            throw new IllegalArgumentException("Invalid bounded governance command CBOR");
        }
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(body)).getDataItems();
        if (items.size() != 4) {
            throw new IllegalArgumentException("Governance command must have 4 fields");
        }
        long version = ((UnsignedInteger) items.get(0)).getValue().longValue();
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported governance command version: " + version);
        }
        int op = ((UnsignedInteger) items.get(1)).getValue().intValue();
        long lag = ((UnsignedInteger) items.get(3)).getValue().longValue();
        if (op == OP_SET_THRESHOLD) {
            int threshold = ((UnsignedInteger) items.get(2)).getValue().intValue();
            return new Command(op, null, threshold, lag);
        }
        if (op != OP_ADD && op != OP_REMOVE) {
            throw new IllegalArgumentException("Unknown governance op: " + op);
        }
        byte[] key = ((ByteString) items.get(2)).getBytes();
        if (key.length != 32) {
            throw new IllegalArgumentException("Member key must be 32 bytes");
        }
        return new Command(op, key, 0, lag);
    }

    /** [version(1), committed-height, threshold, activation-lag, [member-hex...]] */
    static byte[] encodeHandoff(MembershipHandoff handoff) {
        Array arr = new Array();
        arr.add(new UnsignedInteger(1));
        arr.add(new UnsignedInteger(handoff.committedHeight()));
        arr.add(new UnsignedInteger(handoff.threshold()));
        arr.add(new UnsignedInteger(handoff.activationLag()));
        Array members = new Array();
        new TreeSet<>(handoff.members()).forEach(member -> members.add(new UnicodeString(member)));
        arr.add(members);
        return CborSerializationUtil.serialize(arr);
    }

    static MembershipHandoff decodeHandoff(byte[] encoded) {
        List<DataItem> items = ((Array) CborSerializationUtil.deserializeOne(encoded)).getDataItems();
        if (items.size() != 5 || ((UnsignedInteger) items.get(0)).getValue().longValue() != 1) {
            throw new IllegalStateException("Unsupported membership handoff record");
        }
        Set<String> members = new TreeSet<>();
        for (DataItem member : ((Array) items.get(4)).getDataItems()) {
            members.add(((UnicodeString) member).getString());
        }
        return new MembershipHandoff(
                ((UnsignedInteger) items.get(1)).getValue().longValueExact(), members,
                ((UnsignedInteger) items.get(2)).getValue().intValueExact(),
                ((UnsignedInteger) items.get(3)).getValue().longValueExact());
    }

    private byte[] encodePending() {
        Map cbor = new Map();
        for (var entry : pending.entrySet()) {
            Array value = new Array();
            value.add(new UnsignedInteger(entry.getValue().firstSeenHeight));
            Array approvers = new Array();
            for (String approver : entry.getValue().approvers) {
                approvers.add(new UnicodeString(approver));
            }
            value.add(approvers);
            cbor.put(new UnicodeString(entry.getKey()), value);
        }
        return CborSerializationUtil.serialize(cbor);
    }
}
