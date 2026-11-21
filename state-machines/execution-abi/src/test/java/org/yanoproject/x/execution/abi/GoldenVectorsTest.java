package org.yanoproject.x.execution.abi;

import org.junit.jupiter.api.Test;
import org.yanoproject.x.execution.abi.cbor.CanonicalCbor;
import org.yanoproject.x.execution.abi.cbor.CborException;
import org.yanoproject.x.execution.abi.v1.BlockInfo;
import org.yanoproject.x.execution.abi.v1.EffectIntentArg;
import org.yanoproject.x.execution.abi.v1.EffectResultInput;
import org.yanoproject.x.execution.abi.v1.EntryInputs;
import org.yanoproject.x.execution.abi.v1.EntryOutputs;
import org.yanoproject.x.execution.abi.v1.ExecutionDescriptor;
import org.yanoproject.x.execution.abi.v1.MessageInput;
import org.yanoproject.x.execution.abi.v1.Receipt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Golden vectors for guest ABI v1 in {@code spec/vectors/abi-v1.json}. Guest SDKs in other
 * languages test their encoders and decoders against the same file.
 *
 * <p>Regenerate with {@code ./gradlew :guest-abi:test -PupdateVectors=true}; a diff in the file is
 * an ABI change and needs review.
 */
class GoldenVectorsTest {

    private static final HexFormat HEX = HexFormat.of();

    record Valid(String name, String type, Object value, byte[] cbor, Function<byte[], Object> decoder) {
    }

    record Invalid(String name, String type, String hex, String error, Function<byte[], Object> decoder) {
    }

    private static byte[] filled(int n, int b) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) b);
        return out;
    }

    private static final BlockInfo BLOCK = new BlockInfo("demo-chain", 42, 1_700_000_042_000L, 123_456,
            filled(32, 0xB1), filled(32, 0xA0), filled(32, 0x50));
    private static final MessageInput MESSAGE = new MessageInput(filled(32, 0x1D), "orders", filled(32, 0x11), 7, 0,
            "hello".getBytes(StandardCharsets.UTF_8), 3);
    private static final byte[] CONFIG = {(byte) 0xA0};

    static List<Valid> valid() {
        List<Valid> v = new ArrayList<>();
        var apply = new EntryInputs.ApplyMessage(BLOCK, MESSAGE, CONFIG);
        v.add(new Valid("apply-message-input", "apply-message-input", apply, apply.encode(), EntryInputs.ApplyMessage::decode));
        var result = new EffectResultInput("demo-chain", 40, 2, "cardano.payment", "order-17", 1,
                filled(32, 0xEE), filled(32, 0xDD), 42);
        var onResult = new EntryInputs.OnEffectResult(BLOCK, result, CONFIG);
        v.add(new Valid("on-effect-result-input", "on-effect-result-input", onResult, onResult.encode(),
                EntryInputs.OnEffectResult::decode));
        var expired = new EffectResultInput("demo-chain", 40, 3, "cardano.payment", "", 4, new byte[0], null, 42);
        var onExpired = new EntryInputs.OnEffectResult(BLOCK, expired, CONFIG);
        v.add(new Valid("on-effect-result-input-expired-no-detail", "on-effect-result-input", onExpired,
                onExpired.encode(), EntryInputs.OnEffectResult::decode));
        var validate = new EntryInputs.Validate("demo-chain", 43, MESSAGE, CONFIG);
        v.add(new Valid("validate-input", "validate-input", validate, validate.encode(), EntryInputs.Validate::decode));
        var query = new EntryInputs.Query("demo-chain", 42, "orders/by-id", "17".getBytes(StandardCharsets.UTF_8), CONFIG);
        v.add(new Valid("query-input", "query-input", query, query.encode(), EntryInputs.Query::decode));

        var ok = new EntryOutputs.Transition.Ok();
        v.add(new Valid("transition-ok", "transition-outcome", ok, ok.encode(), EntryOutputs::decodeTransition));
        var rejected = new EntryOutputs.Transition.Rejected("insufficient-balance");
        v.add(new Valid("transition-rejected", "transition-outcome", rejected, rejected.encode(), EntryOutputs::decodeTransition));
        var accept = new EntryOutputs.Verdict.Accept();
        v.add(new Valid("verdict-accept", "verdict", accept, accept.encode(), EntryOutputs::decodeVerdict));
        var reject = new EntryOutputs.Verdict.Reject("bad-signature");
        v.add(new Valid("verdict-reject", "verdict", reject, reject.encode(), EntryOutputs::decodeVerdict));
        var payload = new EntryOutputs.QueryOutcome.Payload(new byte[] {0, 0, 0, 0, 0, 0, 0, 5});
        v.add(new Valid("query-payload", "query-outcome", payload, payload.encode(), EntryOutputs::decodeQuery));
        var unsupported = new EntryOutputs.QueryOutcome.Error("unsupported");
        v.add(new Valid("query-unsupported", "query-outcome", unsupported, unsupported.encode(), EntryOutputs::decodeQuery));

        var minimal = new EffectIntentArg("cardano.payment", new byte[] {1, 2, 3}, "", -1, 0, 0);
        v.add(new Valid("effect-intent-minimal", "effect-intent", minimal, minimal.encode(), EffectIntentArg::decode));
        var full = new EffectIntentArg("cardano.payment", new byte[] {1, 2, 3}, "order-17", 1, 1, 50);
        v.add(new Valid("effect-intent-full", "effect-intent", full, full.encode(), EffectIntentArg::decode));

        var receipt = new Receipt(42, Receipt.Kind.OUT_OF_COMPUTE, "compute");
        v.add(new Valid("receipt", "receipt", receipt, receipt.encode(), Receipt::decode));

        var descriptor = new ExecutionDescriptor("graaljs", filled(32, 0xF0), "text/javascript", filled(32, 0xD1), 1,
                new ExecutionDescriptor.Budgets(5_000_000, 1_000_000, 500_000, 200_000, 2_000_000, 500_000),
                0, CONFIG, true);
        v.add(new Valid("execution-descriptor", "execution-descriptor", descriptor, descriptor.encode(),
                ExecutionDescriptor::decode));
        return v;
    }

    static List<Invalid> invalid() {
        Function<byte[], Object> transition = EntryOutputs::decodeTransition;
        Function<byte[], Object> intent = EffectIntentArg::decode;
        return List.of(
                new Invalid("non-shortest-integer", "transition-outcome", "a1001800", CborException.NON_CANONICAL_INT, transition),
                new Invalid("indefinite-map", "transition-outcome", "bf0000ff", CborException.INDEFINITE_LENGTH, transition),
                new Invalid("trailing-bytes", "transition-outcome", "a1000000", CborException.TRAILING_BYTES, transition),
                new Invalid("unsorted-keys", "transition-outcome", "a2016161 0001".replace(" ", ""), CborException.MAP_KEY_ORDER, transition),
                new Invalid("duplicate-keys", "transition-outcome", "a200000000", CborException.DUPLICATE_KEY, transition),
                new Invalid("unknown-status", "transition-outcome", "a10002", CborException.SCHEMA, transition),
                new Invalid("ok-with-code", "transition-outcome", "a20000016161", CborException.SCHEMA, transition),
                new Invalid("unknown-key", "transition-outcome", "a2000002f5", CborException.SCHEMA, transition),
                new Invalid("bad-code-grammar", "transition-outcome", "a20001016142", CborException.SCHEMA, transition),
                new Invalid("float", "transition-outcome", "a100f90000", CborException.UNSUPPORTED_TYPE, transition),
                new Invalid("tagged", "transition-outcome", "a100c000", CborException.UNSUPPORTED_TYPE, transition),
                new Invalid("effect-default-scope-present", "effect-intent",
                        HEX.formatHex(CanonicalCbor.encode(java.util.Map.of(0L, "a", 1L, new byte[0], 2L, ""))),
                        CborException.SCHEMA, intent),
                new Invalid("effect-reserved-type", "effect-intent",
                        HEX.formatHex(CanonicalCbor.encode(java.util.Map.of(0L, "~fx", 1L, new byte[0]))),
                        CborException.SCHEMA, intent),
                new Invalid("effect-expiry-without-result", "effect-intent",
                        HEX.formatHex(CanonicalCbor.encode(java.util.Map.of(0L, "a", 1L, new byte[0], 5L, 10L))),
                        CborException.SCHEMA, intent));
    }

    @Test
    void validVectorsRoundTrip() {
        for (Valid vector : valid()) {
            Object decoded = vector.decoder().apply(vector.cbor());
            assertEquals(vector.value(), decoded, vector.name());
            assertArrayEquals(vector.cbor(), CanonicalCbor.encode(CanonicalCbor.decode(vector.cbor())), vector.name());
        }
    }

    @Test
    void invalidVectorsAreRejected() {
        for (Invalid vector : invalid()) {
            CborException e = assertThrows(CborException.class,
                    () -> vector.decoder().apply(HEX.parseHex(vector.hex())), vector.name());
            assertEquals(vector.error(), e.code(), vector.name() + ": " + e.getMessage());
        }
    }

    @Test
    void vectorFileMatches() throws IOException {
        String rendered = render();
        Path file = Path.of(System.getProperty("vectors.dir", "spec/vectors")).resolve("abi-v1.json");
        if (Boolean.parseBoolean(System.getProperty("vectors.update", "false"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rendered);
        }
        assertEquals(rendered, Files.readString(file),
                "spec/vectors/abi-v1.json is stale; regenerate with -PupdateVectors=true and review the diff");
    }

    private static String render() {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"abi\": 1,\n  \"encoding\": \"RFC 8949 core deterministic CBOR, ABI subset\",\n");
        out.append("  \"valid\": [\n");
        List<Valid> valid = valid();
        for (int i = 0; i < valid.size(); i++) {
            Valid v = valid.get(i);
            out.append("    {\"name\": ").append(json(v.name())).append(", \"type\": ").append(json(v.type()))
                    .append(",\n     \"diagnostic\": ").append(json(CanonicalCbor.diagnostic(CanonicalCbor.decode(v.cbor()))))
                    .append(",\n     \"cbor\": ").append(json(HEX.formatHex(v.cbor()))).append('}')
                    .append(i + 1 < valid.size() ? ",\n" : "\n");
        }
        out.append("  ],\n  \"invalid\": [\n");
        List<Invalid> invalid = invalid();
        for (int i = 0; i < invalid.size(); i++) {
            Invalid v = invalid.get(i);
            out.append("    {\"name\": ").append(json(v.name())).append(", \"type\": ").append(json(v.type()))
                    .append(", \"cbor\": ").append(json(v.hex())).append(", \"error\": ").append(json(v.error())).append('}')
                    .append(i + 1 < invalid.size() ? ",\n" : "\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
