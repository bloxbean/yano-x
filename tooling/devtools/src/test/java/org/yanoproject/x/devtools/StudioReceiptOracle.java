package org.yanoproject.x.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yanoproject.x.composite.contracts.BindingReceiptV1;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Receipt parity oracle for Studio's bounded receipt decoder (ADR-031.2 contract C6).
 *
 * <p>Valid receipts come from the frozen golden vectors and from receipts built with {@link BindingReceiptV1} at
 * its bounds. Each is also mutated systematically (truncation, trailing bytes, every single-byte change in a
 * bounded window, non-canonical headers, indefinite lengths, tags and floats). {@link BindingReceiptV1#decode} is
 * the authority for acceptance, and the accepted view is {@link BindingReport#receiptView}.
 */
public final class StudioReceiptOracle {
    // Compact output: the mutation corpus is large and read only by tests.
    private static final ObjectMapper JSON = new ObjectMapper();

    private StudioReceiptOracle() { }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        ArrayNode cases = JSON.createArrayNode();
        Map<String, byte[]> valid = valid();
        for (var entry : valid.entrySet()) {
            add(cases, entry.getKey(), entry.getValue());
            int index = 0;
            for (byte[] mutation : mutations(entry.getValue())) add(cases, entry.getKey() + "#m" + index++, mutation);
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", "yano-x-studio-receipt-oracle-v1");
        root.set("cases", cases);
        Files.writeString(output, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    private static void add(ArrayNode cases, String name, byte[] bytes) {
        ObjectNode item = cases.addObject();
        item.put("name", name);
        item.put("hex", HexFormat.of().formatHex(bytes));
        try {
            BindingReceiptV1.decode(bytes);
            item.put("accepted", true);
            item.set("view", JSON.valueToTree(BindingReport.receiptView(bytes)));
        } catch (RuntimeException rejected) {
            item.put("accepted", false);
        }
    }

    static Map<String, byte[]> valid() throws IOException {
        Map<String, byte[]> valid = new LinkedHashMap<>();
        Properties vectors = new Properties();
        try (InputStream input = BindingReceiptV1.class.getResourceAsStream(
                "/cddl/declarative-bindings-v1-golden-vectors.properties")) {
            vectors.load(input);
        }
        for (String name : vectors.stringPropertyNames().stream().sorted().toList()) {
            if (name.startsWith("receipt.") && !name.endsWith(".cddl-root")) {
                valid.put(name, HexFormat.of().parseHex(vectors.getProperty(name)));
            }
        }
        byte[] id = new byte[32];
        Arrays.fill(id, (byte) 0x5a);
        var planned = new BindingReceiptV1.Step(0, 0, null, "records", id,
                List.of("kv-registry.entry-put.v1", "composite.command-accepted.v1"),
                List.of(new BindingReceiptV1.Condition("audit-record", -1),
                        new BindingReceiptV1.Condition("skip", 7)), "PLANNED", "", false);
        var child = new BindingReceiptV1.Step(1, 1, "audit-record", "audit", id, List.of(), List.of(),
                "REJECTED", "AUTHORIZATION", true);
        var effect = new BindingReceiptV1.Step(2, 1, "notify", "audit", id, List.of(), List.of(),
                "EFFECT_PLANNED", "", false);
        valid.put("synthetic.accepted.multi", new BindingReceiptV1(id, 42, true, null, "",
                List.of(planned, effect)).encode());
        valid.put("synthetic.rejected.child", new BindingReceiptV1(id, Long.MAX_VALUE, false, 1, "AUTHORIZATION",
                List.of(planned, child)).encode());
        // Text fields beginning with U+FEFF must be preserved (and therefore rejected as unknown statuses).
        byte[] bom = new BindingReceiptV1(id, 1, true, null, "", List.of()).encode();
        byte[] withBom = new byte[bom.length + 3];
        int status = 0;
        for (int index = 0; index < bom.length - 8; index++) {
            if (bom[index] == 0x68 && bom[index + 1] == 'A') { status = index; break; }
        }
        System.arraycopy(bom, 0, withBom, 0, status);
        withBom[status] = 0x6b;
        withBom[status + 1] = (byte) 0xef; withBom[status + 2] = (byte) 0xbb; withBom[status + 3] = (byte) 0xbf;
        System.arraycopy(bom, status + 1, withBom, status + 4, bom.length - status - 1);
        valid.put("mutant.bom-status", withBom);
        // Engine compaction keeps the failed step's own code while the receipt says RECEIPT_CAPACITY_EXCEEDED.
        valid.put("synthetic.truncated", new BindingReceiptV1(id, 7, false, 1, "RECEIPT_CAPACITY_EXCEEDED", List.of(
                planned, new BindingReceiptV1.Step(1, 1, "audit-record", "audit", id, List.of(),
                        List.of(new BindingReceiptV1.Condition("lookup-owner", 1)), "REJECTED", "LOOKUP_KEY_INVALID",
                        false))).encode());
        valid.put("synthetic.zero-height", new BindingReceiptV1(id, 0, false, 0, "X", List.of(
                new BindingReceiptV1.Step(0, 0, null, "a", id, List.of(), List.of(), "REJECTED", "X", false)))
                .encode());
        List<BindingReceiptV1.Condition> conditions = new ArrayList<>();
        for (int index = 0; index < BindingReceiptV1.MAX_CONDITION_RECORDS; index++) {
            conditions.add(new BindingReceiptV1.Condition("b" + index, index % 9 - 1));
        }
        valid.put("synthetic.max-conditions", new BindingReceiptV1(id, 1, false, 256, "C".repeat(127), List.of(
                new BindingReceiptV1.Step(256, 33, "z".repeat(127), "t".repeat(127), id,
                        Collections.nCopies(3, "e.v1"), conditions, "REJECTED", "C".repeat(127), true))).encode());
        return valid;
    }

    static List<byte[]> mutations(byte[] valid) {
        List<byte[]> result = new ArrayList<>();
        result.add(Arrays.copyOf(valid, valid.length - 1));
        result.add(Arrays.copyOf(valid, valid.length + 1));
        byte[] appended = Arrays.copyOf(valid, valid.length + 1);
        appended[valid.length] = (byte) 0xf6;
        result.add(appended);
        // Every position for small receipts and a stride through larger ones, so step and condition fields mutate.
        int stride = valid.length <= 256 ? 1 : 31;
        for (int index = 0; index < valid.length; index += index < 48 ? 1 : stride) {
            for (int value : new int[] {0x00, 0x17, 0x18, 0x19, 0x1f, 0x3f, 0x40, 0x5f, 0x60, 0x7f, 0x80, 0x9f,
                    0xa0, 0xc0, 0xd8, 0xf4, 0xf5, 0xf7, 0xf9, 0xfb, 0xff}) {
                if ((valid[index] & 0xff) == value) continue;
                byte[] changed = valid.clone();
                changed[index] = (byte) value;
                result.add(changed);
            }
        }
        // Non-canonical root header: definite array of 7 encoded with a one-byte length argument.
        byte[] longHeader = new byte[valid.length + 1];
        longHeader[0] = (byte) 0x98;
        longHeader[1] = 7;
        System.arraycopy(valid, 1, longHeader, 2, valid.length - 1);
        result.add(longHeader);
        // Indefinite-length root array.
        byte[] indefinite = new byte[valid.length + 1];
        indefinite[0] = (byte) 0x9f;
        System.arraycopy(valid, 1, indefinite, 1, valid.length - 1);
        indefinite[valid.length] = (byte) 0xff;
        result.add(indefinite);
        byte[] tagged = new byte[valid.length + 1];
        tagged[0] = (byte) 0xc0;
        System.arraycopy(valid, 0, tagged, 1, valid.length);
        result.add(tagged);
        return result;
    }
}
