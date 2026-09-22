package org.yanoproject.x.stdlib;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.appchain.AppStateReader;
import org.yanoproject.api.appchain.codec.MessageCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.EventDescriptor;
import org.yanoproject.api.appchain.transition.TransitionContext;
import org.yanoproject.api.appchain.transition.TransitionDecision;
import org.yanoproject.api.appchain.transition.TransitionEvent;
import org.yanoproject.api.appchain.transition.TransitionKernel;
import org.yanoproject.api.appchain.transition.TransitionPlan;
import org.yanoproject.api.appchain.transition.TransitionScalars;
import org.yanoproject.x.stdlib.contracts.DocTrailContract;
import org.yanoproject.x.stdlib.contracts.KvRegistryContract;
import org.yanoproject.x.stdlib.contracts.BalancesContract;
import org.yanoproject.x.stdlib.contracts.ApprovalsContract;
import org.yanoproject.x.stdlib.contracts.internal.StdlibContractCbor;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Composition adapters around the same pure decisions used by standalone stock machines.
 * Adapters declare command/event/configuration schemas and attach events only after a decision approves.
 * They do not infer domain intent from arbitrary writes or bypass ownership checks. Event scalar/byte
 * limits belong to the composition contract; standalone execution retains its existing domain wire format.
 */
final class StockTransitionKernels {
    private StockTransitionKernels() { }

    /**
     * Stages canonical proposal payloads for atomic threshold-to-binding execution. This adapter is exposed
     * only with legacy on-approved effects disabled. Terminal votes do not emit the approved event again.
     */
    static TransitionKernel<ApprovalsContract.Command, ApprovalsTransitions.Facts> approvals(
            ApprovalsTransitions transitions) {
        return new TransitionKernel<>() {
            @Override public MessageCodec<ApprovalsContract.Command> codec() {
                return codecOf(ApprovalsContract.Command.class, ApprovalsContract::decodeCommand,
                        command -> switch (command.operation()) {
                            case 0 -> ApprovalsContract.propose(command.itemId(), command.payload(),
                                    command.required(), command.deadlineMillis());
                            case 1 -> ApprovalsContract.approve(command.itemId());
                            default -> ApprovalsContract.reject(command.itemId());
                        });
            }
            @Override public ApprovalsTransitions.Facts facts(ApprovalsContract.Command command,
                                                               TransitionContext context, AppStateReader state) {
                return ApprovalsTransitions.facts(command, state, true, true);
            }
            /** Logical lookup wire is strict UTF-8 item-id bytes, not a prefixed state key. */
            @Override public byte[] lookupKey(byte[] logicalKey) {
                return ApprovalsContract.itemKey(logicalText(logicalKey));
            }
            @Override public List<CommandDescriptor> commands() {
                var item = field("itemId", TransitionScalars.Type.TEXT);
                return List.of(new CommandDescriptor("propose", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0,
                                List.of(item, field("payload", TransitionScalars.Type.BYTES),
                                        field("required", TransitionScalars.Type.INTEGER),
                                        field("deadlineMillis", TransitionScalars.Type.INTEGER))),
                        new CommandDescriptor("approve", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, List.of(item)),
                        new CommandDescriptor("reject", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 2, List.of(item)));
            }
            @Override public List<EventDescriptor> events() {
                var item = field("itemId", TransitionScalars.Type.TEXT);
                var proposer = field("proposer", TransitionScalars.Type.BYTES);
                var hash = field("payloadHash", TransitionScalars.Type.BYTES);
                return List.of(new EventDescriptor("approvals.item-proposed.v1", List.of(item, proposer, hash,
                                field("required", TransitionScalars.Type.INTEGER),
                                field("deadlineMillis", TransitionScalars.Type.INTEGER))),
                        new EventDescriptor("approvals.item-approved.v1", List.of(item, hash,
                                field("payload", TransitionScalars.Type.BYTES),
                                field("approvers", TransitionScalars.Type.BYTES),
                                field("approverCount", TransitionScalars.Type.INTEGER), proposer)),
                        new EventDescriptor("approvals.item-rejected.v1", List.of(item,
                                field("rejecter", TransitionScalars.Type.BYTES), proposer)));
            }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
            @Override public TransitionDecision decide(ApprovalsContract.Command command, TransitionContext context,
                                                        ApprovalsTransitions.Facts facts) {
                var result = transitions.evaluate(command, context, facts);
                var item = result.item();
                return switch (result.change()) {
                    case PROPOSED -> withEvent(result.plan(), "approvals.item-proposed.v1", Map.of(
                            "itemId", command.itemId(), "proposer", item.proposer(), "payloadHash", item.payloadHash(),
                            "required", (long) item.required(), "deadlineMillis", item.deadline()));
                    case REJECTED -> withEvent(result.plan(), "approvals.item-rejected.v1", Map.of(
                            "itemId", command.itemId(), "rejecter", item.rejecter(), "proposer", item.proposer()));
                    case APPROVED -> {
                        if (facts.stagedPayload().isEmpty()) {
                            yield TransitionDecision.reject("APPROVAL_PAYLOAD_UNAVAILABLE",
                                    "approved action payload was not staged");
                        }
                        Array approvers = new Array();
                        item.approvers().forEach(sender -> approvers.add(new ByteString(sender)));
                        yield withEvent(result.plan(), "approvals.item-approved.v1", Map.of(
                                "itemId", command.itemId(), "payloadHash", item.payloadHash(),
                                "payload", facts.stagedPayload().orElseThrow(),
                                "approvers", CborSerializationUtil.serialize(approvers),
                                "approverCount", (long) item.approvers().size(), "proposer", item.proposer()));
                    }
                    default -> TransitionDecision.approve(result.plan());
                };
            }
        };
    }

    /**
     * Adds int64 minor-unit event projections to balance plans. A composed command whose amount or resulting
     * balances cannot be represented by the event schema is rejected before any writes; standalone balances
     * retain their existing arbitrary-precision representation and do not use this event adapter.
     */
    static TransitionKernel<BalancesContract.Command, BalancesTransitions.Facts> balances(
            BalancesTransitions transitions) {
        return new TransitionKernel<>() {
            @Override public MessageCodec<BalancesContract.Command> codec() {
                return codecOf(BalancesContract.Command.class, BalancesContract::decodeCommand,
                        command -> command.mint() ? BalancesContract.mint(command.account(), command.amount())
                                : BalancesContract.transfer(command.account(), command.amount()));
            }
            @Override public BalancesTransitions.Facts facts(BalancesContract.Command command,
                                                              TransitionContext context, AppStateReader state) {
                return BalancesTransitions.facts(command, context, state);
            }
            /** Logical lookup wire is strict UTF-8 account-id bytes; the existing balance helper adds its prefix. */
            @Override public byte[] lookupKey(byte[] logicalKey) {
                return BalancesContract.accountKey(logicalText(logicalKey));
            }
            @Override public List<CommandDescriptor> commands() {
                List<CommandDescriptor.Field> fields = List.of(field("to", TransitionScalars.Type.TEXT),
                        field("amount", TransitionScalars.Type.INTEGER));
                return List.of(new CommandDescriptor("mint", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0, fields),
                        new CommandDescriptor("transfer", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, fields));
            }
            @Override public List<EventDescriptor> events() {
                return List.of(new EventDescriptor("balances.minted.v1", List.of(
                        field("account", TransitionScalars.Type.TEXT), field("amount", TransitionScalars.Type.INTEGER),
                        field("balanceAfter", TransitionScalars.Type.INTEGER))),
                        new EventDescriptor("balances.transferred.v1", List.of(
                                field("from", TransitionScalars.Type.TEXT), field("to", TransitionScalars.Type.TEXT),
                                field("amount", TransitionScalars.Type.INTEGER),
                                field("fromBalanceAfter", TransitionScalars.Type.INTEGER),
                                field("toBalanceAfter", TransitionScalars.Type.INTEGER))));
            }
            @Override public ConfigurationDescriptor configuration() {
                return new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting(
                        "minter", TransitionScalars.Type.TEXT, "")));
            }
            @Override public TransitionDecision decide(BalancesContract.Command command, TransitionContext context,
                                                        BalancesTransitions.Facts facts) {
                TransitionDecision decision = transitions.decide(command, context, facts);
                if (!(decision instanceof TransitionDecision.Approved approved)) return decision;
                try {
                    long amount = command.amount().longValueExact();
                    String sender = HexUtil.encodeHexString(context.sender());
                    if (command.mint()) {
                        return withEvent(approved.plan(), "balances.minted.v1", Map.of("account", command.account(),
                                "amount", amount, "balanceAfter",
                                facts.recipientBalance().add(command.amount()).longValueExact()));
                    }
                    boolean self = sender.equals(command.account());
                    long fromAfter = (self ? facts.senderBalance()
                            : facts.senderBalance().subtract(command.amount())).longValueExact();
                    long toAfter = (self ? facts.senderBalance()
                            : facts.recipientBalance().add(command.amount())).longValueExact();
                    return withEvent(approved.plan(), "balances.transferred.v1", Map.of("from", sender,
                            "to", command.account(), "amount", amount,
                            "fromBalanceAfter", fromAfter, "toBalanceAfter", toAfter));
                } catch (ArithmeticException overflow) {
                    return TransitionDecision.reject("BALANCE_EVENT_RANGE",
                            "balance event requires signed int64 minor units");
                }
            }
        };
    }

    static TransitionKernel<KvRegistryTransitions.Command, KvRegistryTransitions.Facts> registry(
            KvRegistryTransitions transitions) {
        return new TransitionKernel<>() {
            @Override public MessageCodec<KvRegistryTransitions.Command> codec() {
                return codecOf(KvRegistryTransitions.Command.class, transitions::decodeCommand,
                        command -> command.operation() == 0 ? KvRegistryContract.put(command.key(), command.value())
                                : KvRegistryContract.delete(command.key()));
            }
            @Override public KvRegistryTransitions.Facts facts(KvRegistryTransitions.Command command,
                                                              TransitionContext context, AppStateReader state) {
                return new KvRegistryTransitions.Facts(state.get(command.key()));
            }
            @Override public List<CommandDescriptor> commands() {
                List<CommandDescriptor.Field> fields = List.of(field("key", TransitionScalars.Type.BYTES),
                        field("value", TransitionScalars.Type.BYTES));
                return List.of(new CommandDescriptor("put", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 0, fields),
                        new CommandDescriptor("delete", CommandDescriptor.Layout.ARRAY_WITH_OPCODE, 1, fields));
            }
            @Override public List<EventDescriptor> events() {
                return List.of(new EventDescriptor("kv-registry.entry-put.v1", List.of(
                        field("key", TransitionScalars.Type.BYTES), field("value", TransitionScalars.Type.BYTES),
                        field("valueHash", TransitionScalars.Type.BYTES),
                        field("valueLength", TransitionScalars.Type.INTEGER),
                        field("owner", TransitionScalars.Type.BYTES), field("height", TransitionScalars.Type.INTEGER),
                        optional("previousValueHash", TransitionScalars.Type.BYTES))),
                        new EventDescriptor("kv-registry.entry-deleted.v1", List.of(
                                field("key", TransitionScalars.Type.BYTES),
                                field("owner", TransitionScalars.Type.BYTES),
                                field("height", TransitionScalars.Type.INTEGER),
                                field("previousValueHash", TransitionScalars.Type.BYTES))));
            }
            @Override public ConfigurationDescriptor configuration() {
                return new ConfigurationDescriptor(List.of(new ConfigurationDescriptor.Setting(
                        "value-format", TransitionScalars.Type.TEXT, "raw")));
            }
            @Override public TransitionDecision decide(KvRegistryTransitions.Command command,
                                                      TransitionContext context, KvRegistryTransitions.Facts facts) {
                TransitionDecision decision = transitions.decide(command, context, facts);
                if (!(decision instanceof TransitionDecision.Approved approved)
                        || approved.plan().mutations().isEmpty()) return decision;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("key", command.key());
                event.put("owner", context.sender());
                event.put("height", context.height());
                facts.currentEntry().ifPresent(entry -> event.put("previousValueHash",
                        Blake2bUtil.blake2bHash256(KvRegistryTransitions.decodeValue(entry))));
                if (command.operation() == 0) {
                    event.put("value", command.value());
                    event.put("valueHash", Blake2bUtil.blake2bHash256(command.value()));
                    event.put("valueLength", (long) command.value().length);
                }
                return withEvent(approved.plan(), "kv-registry.entry-"
                        + (command.operation() == 0 ? "put" : "deleted") + ".v1", event);
            }
        };
    }

    static TransitionKernel<DocTrailTransitions.Append, DocTrailTransitions.Facts> documentTrail(
            DocTrailTransitions transitions) {
        return new TransitionKernel<>() {
            @Override public MessageCodec<DocTrailTransitions.Append> codec() {
                return codecOf(DocTrailTransitions.Append.class, DocTrailTransitions::decodeCommand,
                        command -> DocTrailContract.append(command.entityId(),
                                command.entryHash(), command.reference()));
            }
            @Override public DocTrailTransitions.Facts facts(DocTrailTransitions.Append command,
                                                            TransitionContext context, AppStateReader state) {
                return DocTrailTransitions.facts(state, command);
            }
            /** Logical lookup wire is strict UTF-8 entity-id bytes, resolved to the existing document-head key. */
            @Override public byte[] lookupKey(byte[] logicalKey) {
                return DocTrailContract.entityKey(logicalText(logicalKey));
            }
            @Override public List<CommandDescriptor> commands() {
                return List.of(new CommandDescriptor("append", CommandDescriptor.Layout.ARRAY, 0, List.of(
                        field("entityId", TransitionScalars.Type.TEXT),
                        field("entryHash", TransitionScalars.Type.BYTES),
                        field("reference", TransitionScalars.Type.TEXT))));
            }
            @Override public List<EventDescriptor> events() {
                return List.of(new EventDescriptor("doc-trail.entry-appended.v1", List.of(
                        field("entityId", TransitionScalars.Type.TEXT),
                        field("entryHash", TransitionScalars.Type.BYTES),
                        field("reference", TransitionScalars.Type.TEXT),
                        field("headHash", TransitionScalars.Type.BYTES),
                        field("count", TransitionScalars.Type.INTEGER),
                        field("sender", TransitionScalars.Type.BYTES))));
            }
            @Override public ConfigurationDescriptor configuration() { return ConfigurationDescriptor.empty(); }
            @Override public TransitionDecision decide(DocTrailTransitions.Append command,
                                                      TransitionContext context, DocTrailTransitions.Facts facts) {
                if (facts.current().isPresent() && facts.current().get().count() == Long.MAX_VALUE) {
                    return TransitionDecision.reject("DOC_COUNT_OVERFLOW", "document trail count exhausted");
                }
                TransitionDecision decision = transitions.decide(command, context, facts);
                if (!(decision instanceof TransitionDecision.Approved approved)) return decision;
                var head = DocTrailTransitions.decodeHead(approved.plan().mutations().getFirst().value());
                return withEvent(approved.plan(), "doc-trail.entry-appended.v1", Map.of(
                        "entityId", command.entityId(), "entryHash", command.entryHash(),
                        "reference", command.reference(),
                        "headHash", head.headHash(), "count", head.count(), "sender", context.sender()));
            }
        };
    }

    /**
     * Decodes one bounded logical identifier without accepting malformed UTF-8 replacement characters.
     * Domain helpers apply their existing text and complete physical-key bounds after this conversion;
     * the composition layer additionally checks the outer component prefix before reading state.
     */
    private static String logicalText(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > StdlibContractCbor.MAX_STATE_KEY_BYTES) {
            throw new IllegalArgumentException("logical lookup identifier exceeds stock key bounds");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("logical lookup identifier must be valid UTF-8", malformed);
        }
    }

    private static TransitionDecision withEvent(TransitionPlan plan, String id, Map<String, Object> fields) {
        byte[] payload;
        try {
            // The host codec preflights exact aggregate UTF-8/CBOR size before allocating its output.
            payload = TransitionScalars.encode(fields);
        } catch (IllegalArgumentException oversized) {
            return TransitionDecision.reject("EVENT_PAYLOAD_TOO_LARGE", "event exceeds composition byte budget");
        }
        return TransitionDecision.approve(new TransitionPlan(plan.mutations(), plan.effects(), plan.consumptions(),
                plan.receipts(), List.of(new TransitionEvent(id, payload))));
    }

    private static CommandDescriptor.Field field(String name, TransitionScalars.Type type) {
        return new CommandDescriptor.Field(name, type, true, CommandDescriptor.Role.DATA);
    }
    private static CommandDescriptor.Field optional(String name, TransitionScalars.Type type) {
        return new CommandDescriptor.Field(name, type, false, CommandDescriptor.Role.DATA);
    }
    private static <C> MessageCodec<C> codecOf(Class<C> type, Function<byte[], C> decode, Function<C, byte[]> encode) {
        return new MessageCodec<>() {
            @Override public byte[] encode(C value) { return encode.apply(value); }
            @Override public C decode(byte[] body) { return decode.apply(body); }
            @Override public Class<C> type() { return type; }
        };
    }
}
