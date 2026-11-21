package org.yanoproject.x.execution;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.execution.abi.v1.AbiV1;
import org.yanoproject.x.execution.abi.v1.BlockInfo;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EffectResultInput;
import org.yanoproject.x.execution.abi.v1.EntryInputs;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.execution.abi.v1.Hashes;
import org.yanoproject.x.execution.abi.v1.HostSessionV1;
import org.yanoproject.x.execution.abi.v1.HostTableV1;
import org.yanoproject.x.execution.abi.v1.MessageInput;
import org.yanoproject.x.execution.abi.v1.Receipt;
import org.yanoproject.x.execution.abi.v1.StagedState;
import org.yanoproject.x.execution.abi.v1.StateView;
import org.yanoproject.x.execution.abi.v1.Write;
import org.yanoproject.x.framework.api.execution.ExecutionEngineException;
import org.yanoproject.x.framework.api.execution.ExecutionProfile;
import org.yanoproject.x.framework.api.execution.ExecutionResult;
import org.yanoproject.x.framework.api.execution.ExecutionRuntime;
import org.yanoproject.x.framework.api.execution.HostAbort;
import org.yanoproject.x.framework.api.execution.HostCalls;
import org.yanoproject.x.framework.api.execution.InstanceConfig;
import org.yanoproject.x.framework.api.execution.LoadedProgram;
import org.yanoproject.x.framework.api.execution.ProgramArtifact;
import org.yanoproject.x.framework.api.execution.ProgramInstance;
import org.yanoproject.x.framework.api.execution.ProgramRejectedException;
import org.yanoproject.x.framework.api.execution.Readiness;
import org.yanoproject.x.framework.api.appchain.AppBlock;
import org.yanoproject.x.framework.api.appchain.AppBlockExecutionContext;
import org.yanoproject.x.framework.api.appchain.AppQueryContext;
import org.yanoproject.x.framework.api.appchain.AppQueryException;
import org.yanoproject.x.framework.api.appchain.AppStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateReader;
import org.yanoproject.x.framework.api.appchain.AppStateWriter;
import org.yanoproject.x.framework.api.appchain.effects.AppEffectEmitter;
import org.yanoproject.x.framework.api.appchain.effects.EffectIntent;
import org.yanoproject.x.framework.api.appchain.effects.EffectResult;
import org.yanoproject.x.framework.api.appchain.effects.FinalityGate;
import org.yanoproject.x.framework.api.appchain.effects.ResultPolicy;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AppStateMachine} that runs a guest program through the runtime SPI.
 *
 * <p>Per block, every committed effect result is one {@code on_effect_result} invocation and then
 * every application message (topics not starting with {@code ~}) is one {@code apply_message}
 * invocation, in block order. An invocation's writes and effects reach the block only if it
 * completes with Ok. A rejection, trap, compute or host-fuel exhaustion, or ABI violation discards
 * them and, if the descriptor asks for it, leaves a {@link Receipt}. Because every one of those
 * outcomes is deterministic, one bad message never stops the chain. A non-deterministic failure
 * ({@link ExecutionEngineException}) is thrown, so the node stops applying instead of diverging.
 *
 * <p>Instance scope (guest ABI section 2): a block's transitions share one guest instance, created
 * at the block's first transition and replaced after every transition that does not end in Ok, so
 * guest memory carries only between committed transitions of one block. A discarded transition
 * leaves nothing behind, not even in guest memory. {@code validate} and {@code query} run on a fresh
 * instance each time.
 */
final class ExecutionBackedStateMachine implements AppStateMachine {

    static final String BAD_OUTPUT = "bad-output";

    private final String chainId;
    private final ExecutionSettings settings;
    private final ExecutionDescriptor descriptor;
    private final ExecutionRuntime runtime;
    private final LoadedProgram program;
    private final String fingerprintHex;

    // Block scope: Yano's writer shows committed state only, so staged writes are tracked here.
    private long blockHeight = -1;
    private StagedState blockState;
    private int effectsThisBlock;
    private ProgramInstance blockInstance;
    private HostSessionV1 currentSession;
    private final HostCalls blockHost = (index, args) -> currentSession.call(index, args);

    private final AtomicLong invocations = new AtomicLong();
    private final AtomicLong instances = new AtomicLong();
    private final Map<Receipt.Kind, AtomicLong> discarded = new EnumMap<>(Receipt.Kind.class);

    ExecutionBackedStateMachine(String chainId, ExecutionSettings settings, ExecutionRuntime runtime) {
        this.chainId = chainId;
        this.settings = settings;
        this.descriptor = settings.descriptor();
        this.runtime = runtime;
        for (Receipt.Kind kind : Receipt.Kind.values()) {
            discarded.put(kind, new AtomicLong());
        }

        byte[] fingerprint = ExecutionDescriptor.fingerprint(runtime.descriptor());
        this.fingerprintHex = HexFormat.of().formatHex(fingerprint);
        if (!Arrays.equals(fingerprint, descriptor.engineFingerprint())) {
            throw new IllegalStateException("execution runtime '" + descriptor.runtimeId() + "' has engine fingerprint "
                    + fingerprintHex + " but the chain pins " + HexFormat.of().formatHex(descriptor.engineFingerprint())
                    + "; install the pinned engine version: " + runtime.descriptor().fingerprintComponents());
        }
        if (!Arrays.equals(Hashes.blake2b256(settings.program()), descriptor.programDigest())) {
            throw new IllegalStateException("program bytes do not match the pinned blake2b-256 digest "
                    + HexFormat.of().formatHex(descriptor.programDigest()));
        }
        Readiness readiness = runtime.readiness(ExecutionProfile.CONSENSUS);
        if (readiness.status() == Readiness.Status.UNAVAILABLE
                || (readiness.status() == Readiness.Status.DEGRADED && !settings.allowDegraded())) {
            throw new IllegalStateException("execution runtime '" + descriptor.runtimeId() + "' is "
                    + readiness.status() + " for consensus: " + readiness.reason());
        }
        try {
            this.program = runtime.load(new ProgramArtifact(descriptor.programMediaType(), descriptor.abiVersion(),
                    settings.program()), new InstanceConfig(AbiV1.REQUIRED_EXPORTS, HostTableV1.FUNCTIONS,
                    descriptor.memoryLimitBytes()));
        } catch (ProgramRejectedException e) {
            throw new IllegalStateException("execution program rejected (" + e.code() + "): " + e.getMessage(), e);
        }
    }

    @Override
    public String id() {
        return ExecutionStateMachineProvider.ID;
    }

    // ------------------------------------------------------------------ consensus path

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        AppBlock block = context.block();
        try {
            StagedState state = enterBlock(block.height(), writer);
            BlockInfo info = blockInfo(block);
            List<AppMessage> messages = context.messages();
            for (int i = 0; i < messages.size(); i++) {
                AppMessage message = messages.get(i);
                if (message.getTopic() == null || message.getTopic().startsWith("~")) {
                    continue; // framework topics are handled by the framework, never by guests
                }
                byte[] input = new EntryInputs.ApplyMessage(info, messageInput(message, context.originalMessageIndex(i)),
                        descriptor.config()).encode();
                transition(AbiV1.APPLY_MESSAGE, input, block.height(), state, writer, effects,
                        message.getMessageId(), Receipt.messageKey(message.getMessageId()));
            }
        } finally {
            // apply is the last callback of a block; a retried block must start from committed state.
            leaveBlock();
        }
    }

    @Override
    public void onEffectResult(AppBlockExecutionContext context, EffectResult result, AppStateWriter writer,
                               AppEffectEmitter effects) {
        if (!program.exports().contains(AbiV1.ON_EFFECT_RESULT)) {
            return;
        }
        AppBlock block = context.block();
        StagedState state = enterBlock(block.height(), writer);
        EffectResultInput resultInput = new EffectResultInput(result.effectId().chainId(), result.effectId().height(),
                result.effectId().ordinal(), result.type(), result.scope(), result.outcome().code(),
                result.externalRef(), result.detailHash(), result.resultHeight());
        byte[] input = new EntryInputs.OnEffectResult(blockInfo(block), resultInput, descriptor.config()).encode();
        try {
            transition(AbiV1.ON_EFFECT_RESULT, input, block.height(), state, writer, effects, null,
                    Receipt.effectKey(result.effectId().hash()));
        } catch (RuntimeException e) {
            leaveBlock(); // the block is aborted; a retry starts from committed state
            throw e;
        }
    }

    private StagedState enterBlock(long height, AppStateWriter writer) {
        if (height != blockHeight) {
            closeBlockInstance(); // a block that was never finished leaves nothing to the next one
            blockHeight = height;
            blockState = new StagedState(writer::get);
            effectsThisBlock = 0;
        }
        return blockState;
    }

    private void leaveBlock() {
        closeBlockInstance();
        blockHeight = -1;
        blockState = null;
        effectsThisBlock = 0;
    }

    private void transition(String export, byte[] input, long height, StagedState state, AppStateWriter writer,
                            AppEffectEmitter effects, byte[] sourceMessageId, byte[] receiptKey) {
        HostSessionV1 session = new HostSessionV1(HostSessionV1.Mode.TRANSITION, state,
                descriptor.budgets().transitionHost(), settings.effectLimits(effectsThisBlock), HostSessionV1.LogSink.DISCARD);
        ExecutionResult result = invokeInBlock(export, input, session);
        Receipt receipt = switch (result) {
            case ExecutionResult.Completed completed -> {
                EntryOutputs.Transition outcome = decodeTransition(completed.output());
                if (outcome == null) {
                    yield new Receipt(height, Receipt.Kind.ABI_VIOLATION, BAD_OUTPUT);
                }
                if (outcome instanceof EntryOutputs.Transition.Rejected rejected) {
                    yield new Receipt(height, Receipt.Kind.REJECTED, rejected.code());
                }
                commit(session, state, writer, effects, sourceMessageId);
                yield null;
            }
            case ExecutionResult.Trapped trapped ->
                    new Receipt(height, Receipt.Kind.TRAPPED, trapped.code().name().toLowerCase(Locale.ROOT).replace('_', '-'));
            case ExecutionResult.OutOfCompute ignored -> new Receipt(height, Receipt.Kind.OUT_OF_COMPUTE, "compute");
            case ExecutionResult.HostAborted aborted -> aborted.abort().kind() == HostAbort.Kind.HOST_FUEL_EXHAUSTED
                    ? new Receipt(height, Receipt.Kind.OUT_OF_HOST_FUEL, aborted.abort().code())
                    : new Receipt(height, Receipt.Kind.ABI_VIOLATION, aborted.abort().code());
        };
        if (receipt != null) {
            closeBlockInstance(); // the next transition must not see this one's guest memory
            discarded.get(receipt.kind()).incrementAndGet();
            if (descriptor.receipts()) {
                writer.put(receiptKey, receipt.encode());
                state.put(receiptKey, receipt.encode());
            }
        }
    }

    private void commit(HostSessionV1 session, StagedState state, AppStateWriter writer, AppEffectEmitter effects,
                        byte[] sourceMessageId) {
        // Effects first: the session already checked them against the chain's limits, so a failure
        // here means the host and the framework disagree, and nothing of this block may commit.
        for (EffectIntentArg arg : session.effects()) {
            effects.emit(new EffectIntent(arg.type(), arg.payload(), arg.scope(),
                    arg.gate() == -1 ? FinalityGate.CHAIN_DEFAULT : FinalityGate.fromCode(arg.gate()),
                    ResultPolicy.fromCode(arg.resultPolicy()), arg.expiryBlocks(), sourceMessageId));
            effectsThisBlock++;
        }
        List<Write> writes = session.writes();
        for (Write write : writes) {
            if (write.isDelete()) {
                writer.delete(write.key());
            } else {
                writer.put(write.key(), write.value());
            }
        }
        state.apply(writes);
    }

    private static EntryOutputs.Transition decodeTransition(byte[] output) {
        if (output.length > AbiV1.MAX_TRANSITION_OUTPUT_BYTES) {
            return null;
        }
        try {
            return EntryOutputs.decodeTransition(output);
        } catch (CborException e) {
            return null;
        }
    }

    private ExecutionResult invokeInBlock(String export, byte[] input, HostSessionV1 session) {
        if (blockInstance == null) {
            blockInstance = program.instantiate(blockHost, descriptor.budgets().transitionCompute());
            instances.incrementAndGet();
        }
        invocations.incrementAndGet();
        currentSession = session;
        try {
            return blockInstance.invoke(export, input);
        } catch (RuntimeException e) {
            closeBlockInstance();
            throw e;
        } finally {
            currentSession = null;
        }
    }

    private void closeBlockInstance() {
        if (blockInstance != null) {
            ProgramInstance instance = blockInstance;
            blockInstance = null;
            instance.close();
        }
    }

    private ExecutionResult invokeFresh(String export, byte[] input, HostSessionV1 session, long computeBudget) {
        invocations.incrementAndGet();
        instances.incrementAndGet();
        try (ProgramInstance instance = program.instantiate(session, computeBudget)) {
            return instance.invoke(export, input);
        }
    }

    // ------------------------------------------------------------------ off-consensus paths

    @Override
    public AdmissionResult validateForBlock(AppMessage message, long candidateHeight, AppStateReader committedState) {
        if (!program.exports().contains(AbiV1.VALIDATE)) {
            return AdmissionResult.accept();
        }
        byte[] input = new EntryInputs.Validate(chainId, candidateHeight, messageInput(message, 0), descriptor.config()).encode();
        HostSessionV1 session = new HostSessionV1(HostSessionV1.Mode.READ_ONLY, reader(committedState),
                descriptor.budgets().validateHost(), settings.effectLimits(0), HostSessionV1.LogSink.DISCARD);
        ExecutionResult result;
        try {
            result = invokeFresh(AbiV1.VALIDATE, input, session, descriptor.budgets().validateCompute());
        } catch (ExecutionEngineException e) {
            return AdmissionResult.reject("execution-engine-failure"); // admission is advisory: fail closed
        }
        if (result instanceof ExecutionResult.Completed completed) {
            try {
                return switch (EntryOutputs.decodeVerdict(completed.output())) {
                    case EntryOutputs.Verdict.Accept accept -> AdmissionResult.accept();
                    case EntryOutputs.Verdict.Reject reject -> AdmissionResult.reject(reject.code(), Map.of("code", reject.code()));
                };
            } catch (CborException e) {
                return AdmissionResult.reject("execution-" + BAD_OUTPUT);
            }
        }
        return AdmissionResult.reject("execution-" + RuntimeOutcome.of(result));
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        if (!program.exports().contains(AbiV1.QUERY)) {
            throw new AppQueryException(AppQueryException.Code.UNSUPPORTED, "program does not export query");
        }
        byte[] input = new EntryInputs.Query(chainId, state.committedHeight(), path, params, descriptor.config()).encode();
        HostSessionV1 session = new HostSessionV1(HostSessionV1.Mode.READ_ONLY, reader(state),
                descriptor.budgets().queryHost(), settings.effectLimits(0), HostSessionV1.LogSink.DISCARD);
        ExecutionResult result = invokeFresh(AbiV1.QUERY, input, session, descriptor.budgets().queryCompute());
        if (!(result instanceof ExecutionResult.Completed completed)) {
            throw new IllegalStateException("query failed: " + RuntimeOutcome.of(result));
        }
        if (completed.output().length > AbiV1.MAX_QUERY_OUTPUT_BYTES + 16) {
            throw new IllegalStateException("query output too large");
        }
        EntryOutputs.QueryOutcome outcome = EntryOutputs.decodeQuery(completed.output());
        return switch (outcome) {
            case EntryOutputs.QueryOutcome.Payload payload -> payload.bytes();
            case EntryOutputs.QueryOutcome.Error error -> throw new AppQueryException(
                    error.code().equals(EntryOutputs.QueryOutcome.UNSUPPORTED)
                            ? AppQueryException.Code.UNSUPPORTED : AppQueryException.Code.INVALID_REQUEST,
                    "query " + error.code());
        };
    }

    @Override
    public Map<String, Object> operationalStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("runtime", descriptor.runtimeId());
        status.put("engine", runtime.descriptor().engineName() + " " + runtime.descriptor().engineVersion());
        status.put("meteringUnit", runtime.descriptor().metering().unit());
        status.put("engineFingerprint", fingerprintHex);
        status.put("programDigest", HexFormat.of().formatHex(descriptor.programDigest()));
        status.put("readiness", runtime.readiness(ExecutionProfile.CONSENSUS).toString());
        status.put("invocations", invocations.get());
        status.put("instances", instances.get());
        Map<String, Long> counts = new LinkedHashMap<>();
        discarded.forEach((kind, count) -> counts.put(kind.name(), count.get()));
        status.put("discardedTransitions", counts);
        return status;
    }

    // ------------------------------------------------------------------ conversions

    private static StateView reader(AppStateReader reader) {
        return reader::get;
    }

    private static BlockInfo blockInfo(AppBlock block) {
        return new BlockInfo(block.chainId(), block.height(), block.timestamp(), block.l1Slot(),
                block.l1BlockHash(), block.prevHash(), block.proposer());
    }

    private static MessageInput messageInput(AppMessage message, int blockIndex) {
        return new MessageInput(message.getMessageId(), message.getTopic(), message.getSender(),
                message.getSenderSeq(), message.getExpiresAt(), message.getBody(), blockIndex);
    }

    /** Short outcome names for admission codes and errors. */
    private static final class RuntimeOutcome {
        static String of(ExecutionResult result) {
            return switch (result) {
                case ExecutionResult.Completed c -> "completed";
                case ExecutionResult.Trapped t -> "trapped";
                case ExecutionResult.OutOfCompute o -> "out-of-compute";
                case ExecutionResult.HostAborted h -> h.abort().kind() == HostAbort.Kind.HOST_FUEL_EXHAUSTED
                        ? "out-of-host-fuel" : "abi-violation";
            };
        }
    }
}
