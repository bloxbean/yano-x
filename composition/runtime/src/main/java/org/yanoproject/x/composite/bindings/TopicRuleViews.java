package org.yanoproject.x.composite.bindings;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.yanoproject.api.appchain.schema.CborSchema;
import org.yanoproject.api.appchain.topics.TopicMessageView;
import org.yanoproject.api.appchain.topics.TopicWireCodec;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.RuleFact;
import org.yanoproject.api.appchain.transition.RuleValueView;
import org.yanoproject.x.composite.contracts.BindingExpressionV1.Type;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Committed schema projections and verified, signature-free topic rule inputs (ADR-038). */
public final class TopicRuleViews {
    public static final Map<String, Type> WRITER_FIELDS = Map.of(
            "kind", Type.TEXT, "signers", Type.TEXT_SET, "issuer", Type.TEXT);
    public static final Map<String, Type> SOURCE_FIELDS = Map.of(
            "topic", Type.TEXT, "writer.kind", Type.TEXT, "writer.signers", Type.TEXT_SET,
            "writer.issuer", Type.TEXT);
    private static final Map<String, Object> NO_WRITER = Map.of("kind", "none", "signers", List.of());

    private TopicRuleViews() { }

    /** Checks the immutable hash/IR contract and returns only fields typed identically by every declared schema. */
    public static Map<String, Type> commonFields(Map<String, byte[]> definitions) {
        Map<String, Type> common = null;
        for (var entry : new TreeMap<>(definitions).entrySet()) {
            CborSchema.Schema schema = CborSchema.decode(entry.getValue());
            if (!entry.getKey().equals(TopicWireCodec.blake2b256Hex(entry.getValue()))) {
                throw new IllegalArgumentException("RULE_PAYLOAD_SCHEMA_INVALID: hash does not match IR");
            }
            Map<String, Type> projected = fields(schema);
            if (common == null) common = new LinkedHashMap<>(projected);
            else common.entrySet().removeIf(field -> projected.get(field.getKey()) != field.getValue());
        }
        return common == null ? Map.of() : Collections.unmodifiableMap(common);
    }

    /** The first 32 eligible scalar members, exactly matching ADR-031.4's map projection. */
    public static Map<String, Type> fields(CborSchema.Schema schema) {
        if (!(schema.root() instanceof CborSchema.MapNode root)) return Map.of();
        Map<String, Type> fields = new LinkedHashMap<>();
        for (var member : root.fields()) {
            if (fields.size() == RuleValueView.MAX_FIELDS) break;
            if (!member.key().matches("[a-zA-Z][a-zA-Z0-9_]{0,62}")
                    || RuleFact.RESERVED_NAMES.contains(member.key())) continue;
            Type type = switch (member.value()) {
                case CborSchema.IntegerNode ignored -> Type.INTEGER;
                case CborSchema.TextNode ignored -> Type.TEXT;
                case CborSchema.BytesNode ignored -> Type.BYTES;
                case CborSchema.BooleanNode ignored -> Type.BOOLEAN;
                default -> null;
            };
            if (type != null) fields.put(member.key(), type);
        }
        return Collections.unmodifiableMap(fields);
    }

    public static boolean acceptsTopicSchema(List<CommandDescriptor> commands) {
        return !commands.isEmpty() && commands.stream()
                .allMatch(command -> command.layout() == CommandDescriptor.Layout.RAW_BYTES);
    }

    static Map<String, Object> writer(AppMessage source, boolean derived) {
        if (derived) return NO_WRITER;
        return TopicMessageView.of(source).flatMap(TopicMessageView::writer).map(verified -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kind", verified.kind().name().toLowerCase(java.util.Locale.ROOT));
            fields.put("signers", verified.signers().stream().distinct().sorted().toList());
            if (verified.issuer() != null) fields.put("issuer", verified.issuer());
            return Collections.unmodifiableMap(fields);
        }).orElse(NO_WRITER);
    }

    static Map<String, Object> source(AppMessage source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("topic", source.getTopic());
        writer(source, false).forEach((name, value) -> fields.put("writer." + name, value));
        return Collections.unmodifiableMap(fields);
    }

    static Map<String, Object> payload(AppMessage source, boolean derived, Map<String, byte[]> definitions,
                                       Map<String, Type> projection) {
        if (derived) throw new BindingFailure("RULE_FIELD_UNAVAILABLE");
        String hash = TopicMessageView.of(source).flatMap(TopicMessageView::schemaHash).orElse("");
        if (!definitions.containsKey(hash)) throw new BindingFailure("RULE_PAYLOAD_SCHEMA_MISMATCH");
        // The host has already verified canonical encoding and schema, bounded to MAX_VALUE_BYTES.
        // Decode only to project scalars: no signature or frame is parsed in the composite.
        try {
            CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(source.getBody()));
            decoder.setMaxPreallocationSize(CborSchema.MAX_VALUE_BYTES);
            List<DataItem> decoded = decoder.decode();
            if (decoded.size() != 1 || !(decoded.getFirst() instanceof co.nstant.in.cbor.model.Map map)) {
                throw new BindingFailure("RULE_FIELD_UNAVAILABLE");
            }
            Map<String, Object> values = new LinkedHashMap<>();
            projection.forEach((name, type) -> {
                DataItem item = map.get(new UnicodeString(name));
                Object value = switch (item) {
                    case UnsignedInteger number when type == Type.INTEGER -> integer(number.getValue());
                    case NegativeInteger number when type == Type.INTEGER -> integer(number.getValue());
                    case UnicodeString text when type == Type.TEXT
                            && text.getString().getBytes(StandardCharsets.UTF_8).length <= RuleFact.MAX_VALUE_BYTES ->
                            text.getString();
                    case ByteString bytes when type == Type.BYTES
                            && bytes.getBytes().length <= RuleFact.MAX_VALUE_BYTES -> bytes.getBytes().clone();
                    case SimpleValue bool when type == Type.BOOLEAN && SimpleValue.TRUE.equals(bool) -> true;
                    case SimpleValue bool when type == Type.BOOLEAN && SimpleValue.FALSE.equals(bool) -> false;
                    case null, default -> null;
                };
                if (value != null) values.put(name, value);
            });
            return Collections.unmodifiableMap(values);
        } catch (CborException malformed) {
            throw new BindingFailure("RULE_FIELD_UNAVAILABLE");
        }
    }

    private static Long integer(BigInteger integer) {
        return integer.bitLength() < 64 ? integer.longValue() : null;
    }
}
