package org.yanoproject.x.composite.bindings;

import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.x.composite.contracts.BindingCbor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Descriptor-driven command view (ADR-031.3 §5.4): the inverse of the binding mapping encoder.
 *
 * <p>A kernel is <em>command-selectable</em> when every command is {@code ARRAY_WITH_OPCODE} with pairwise distinct
 * opcodes, or when it declares exactly one command whose layout is not {@code RAW_BYTES}. A view selects the
 * command, decodes every declared field (DATA and EVIDENCE) into a scalar of its declared type, and re-encodes the
 * fields through the same encoder that mapped commands use; the bytes must equal the body. A body the codec
 * accepted but the descriptor cannot represent canonically therefore has no view, and callers fail closed.
 * Only DATA-role fields are exposed to rules. The view is a pure function of the body and committed descriptors.
 */
public final class BindingCommandView {
    private BindingCommandView() { }

    /**
     * One canonical view of a command body.
     *
     * @param command the selected descriptor
     * @param data DATA-role fields present in the body, by name; EVIDENCE fields are never exposed
     */
    public record View(CommandDescriptor command, Map<String, Object> data) {
        public View {
            Objects.requireNonNull(command, "command");
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    /** Returns why a kernel's commands cannot be selected from a body, or {@code null} when they can. */
    public static String unselectableReason(List<CommandDescriptor> commands) {
        if (commands.isEmpty()) return "the kernel declares no command";
        if (commands.stream().allMatch(command -> command.layout() == CommandDescriptor.Layout.ARRAY_WITH_OPCODE)) {
            Set<Long> opcodes = new HashSet<>();
            for (CommandDescriptor command : commands) {
                if (!opcodes.add(command.opCode())) return "duplicate opcode " + command.opCode();
            }
            return null;
        }
        if (commands.size() == 1) {
            return commands.getFirst().layout() == CommandDescriptor.Layout.RAW_BYTES
                    ? "the only command is raw bytes" : null;
        }
        return "several commands without distinct opcodes";
    }

    /** Whether rules can select a command of this kernel and read {@code command.*}. */
    public static boolean selectable(List<CommandDescriptor> commands) {
        return unselectableReason(commands) == null;
    }

    /**
     * Builds the encoder input for field values: an opcode-prefixed or plain array in declaration order (absent
     * optional positional fields are encoded as null), or a map of the present values.
     */
    static Object tree(CommandDescriptor command, Map<String, Object> values) {
        if (command.layout() == CommandDescriptor.Layout.MAP) {
            Map<String, Object> map = new LinkedHashMap<>();
            command.fields().forEach(field -> {
                if (values.get(field.name()) != null) map.put(field.name(), values.get(field.name()));
            });
            return map;
        }
        List<Object> array = new ArrayList<>();
        if (command.layout() == CommandDescriptor.Layout.ARRAY_WITH_OPCODE) array.add(command.opCode());
        command.fields().forEach(field -> array.add(values.get(field.name())));
        return array;
    }

    /** Decodes a body without work accounting, for tooling and conformance tests. */
    public static View decode(List<CommandDescriptor> commands, byte[] body) {
        return decode(commands, body, units -> { });
    }

    /**
     * Decodes a body through the descriptors of a selectable kernel. Work is charged before each allocation:
     * {@code 1 + body.length} before decoding, then the mapping encoder's charge for the decoded fields before
     * they are re-encoded for the round-trip check.
     *
     * @param commands the kernel's descriptors, which must be selectable
     * @param body the command body the kernel's codec accepted
     * @param charge receives each work charge and may throw to stop evaluation
     * @return the canonical view
     * @throws IllegalArgumentException when the body has no canonical view under these descriptors
     */
    public static View decode(List<CommandDescriptor> commands, byte[] body, LongConsumer charge) {
        if (!selectable(commands)) throw new IllegalStateException("kernel commands are not selectable");
        charge.accept(1L + body.length);
        Object decoded = BindingCbor.decode(body, Math.max(1, body.length));
        CommandDescriptor command;
        Map<String, Object> values = new LinkedHashMap<>();
        if (commands.getFirst().layout() == CommandDescriptor.Layout.MAP) {
            command = commands.getFirst();
            if (!(decoded instanceof Map<?, ?> map)) throw new IllegalArgumentException("expected a command map");
            Set<String> names = new HashSet<>();
            command.fields().forEach(field -> names.add(field.name()));
            for (Object key : map.keySet()) {
                if (!names.contains(key)) throw new IllegalArgumentException("undeclared command field");
            }
            command.fields().forEach(field -> values.put(field.name(), map.get(field.name())));
        } else {
            if (!(decoded instanceof List<?> array)) throw new IllegalArgumentException("expected a command array");
            int offset = 0;
            if (commands.getFirst().layout() == CommandDescriptor.Layout.ARRAY_WITH_OPCODE) {
                if (array.isEmpty() || !(array.getFirst() instanceof Long opcode)) {
                    throw new IllegalArgumentException("missing command opcode");
                }
                command = commands.stream().filter(candidate -> candidate.opCode() == opcode).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("unknown command opcode"));
                offset = 1;
            } else {
                command = commands.getFirst();
            }
            if (array.size() != offset + command.fields().size()) {
                throw new IllegalArgumentException("command field count");
            }
            for (int index = 0; index < command.fields().size(); index++) {
                values.put(command.fields().get(index).name(), array.get(offset + index));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (CommandDescriptor.Field field : command.fields()) {
            Object value = values.get(field.name());
            if (value == null) {
                if (field.required()) throw new IllegalArgumentException("missing required command field");
                continue;
            }
            if (!field.type().accepts(value)) throw new IllegalArgumentException("command field type");
            if (field.role() == CommandDescriptor.Role.DATA) data.put(field.name(), value);
        }
        Object tree = tree(command, values);
        charge.accept(BindingWork.encoding(tree));
        if (!Arrays.equals(BindingCbor.encode(tree), body)) {
            throw new IllegalArgumentException("command body is not the canonical descriptor encoding");
        }
        return new View(command, data);
    }
}
