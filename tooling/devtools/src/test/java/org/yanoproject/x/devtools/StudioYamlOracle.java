package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Differential oracle for Studio's `yano-x-studio-yaml-v1` parser (ADR-031.2 contract C4).
 *
 * <p>Every case is parsed with {@link BindingDocumentCompiler#parseDocument}, the exact Jackson/SnakeYAML path the
 * authoritative compiler uses, and its typed tree is written next to the input. Studio's Node test requires that
 * every document Studio accepts is accepted here with an identical typed tree. Studio may reject more documents;
 * it must never read a value differently. Cases are deterministic: curated edge cases, a seeded combinatorial set,
 * first-party example documents and YAML blocks from the binding documentation.
 */
public final class StudioYamlOracle {
    // ASCII-only output keeps unpaired surrogates that Jackson accepted representable as JSON escapes.
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static {
        JSON.getFactory().configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);
    }

    private static final ObjectMapper BLUEPRINT = AppChainProjectRenderer.configured(
            new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()));

    private StudioYamlOracle() { }

    /**
     * Writes the oracle JSON.
     *
     * @param args repository root and output file
     */
    public static void main(String[] args) throws IOException {
        Path repository = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        ArrayNode cases = JSON.createArrayNode();
        for (Map.Entry<String, String> entry : cases(repository).entrySet()) {
            ObjectNode item = cases.addObject();
            item.put("name", entry.getKey());
            item.put("input", entry.getValue());
            try {
                item.set("java", JSON.createObjectNode().put("accepted", true)
                        .set("tree", typed(BindingDocumentCompiler.parseDocument(entry.getValue()))));
            } catch (RuntimeException rejected) {
                item.set("java", JSON.createObjectNode().put("accepted", false));
            }
            // Blueprints are read by the project renderer's own mapper, which has no alias pre-scan.
            try {
                item.set("blueprint", JSON.createObjectNode().put("accepted", true)
                        .set("tree", typed(BLUEPRINT.readTree(entry.getValue()))));
            } catch (IOException | RuntimeException rejected) {
                item.set("blueprint", JSON.createObjectNode().put("accepted", false));
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", "yano-x-studio-yaml-oracle-v1");
        root.set("cases", cases);
        Files.writeString(output, JSON.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    /** Typed representation shared with the Node test; integers are decimal text, never JavaScript numbers. */
    static JsonNode typed(JsonNode node) {
        ObjectNode result = JSON.createObjectNode();
        if (node == null || node.isMissingNode() || node.isNull()) return result.put("null", true);
        if (node.isObject()) {
            ArrayNode entries = result.putArray("map");
            node.properties().forEach(entry -> entries.addArray().add(entry.getKey()).add(typed(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode items = result.putArray("seq");
            node.forEach(item -> items.add(typed(item)));
            return result;
        }
        if (node.isBoolean()) return result.put("bool", node.booleanValue());
        if (node.isIntegralNumber()) return result.put("int", node.bigIntegerValue().toString());
        if (node.isNumber()) return result.put("float", node.asText());
        if (node.isTextual()) return result.put("text", node.textValue());
        return result.put("other", node.getNodeType().name());
    }

    static Map<String, String> cases(Path repository) throws IOException {
        Map<String, String> cases = new LinkedHashMap<>();
        curated(cases);
        combinations(cases);
        structures(cases);
        limits(cases);
        mutations(cases);
        generated(cases, 1_500);
        try (Stream<Path> examples = Files.list(repository.resolve("examples/bindings"))) {
            for (Path file : examples.filter(path -> path.toString().endsWith(".yaml")).sorted().toList()) {
                cases.put("example:" + file.getFileName(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        Pattern fence = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL);
        List<Path> documents = new ArrayList<>();
        documents.add(repository.resolve("docs/appchain/DECLARATIVE_BINDINGS_CLI.md"));
        documents.add(repository.resolve("docs/appchain/DECLARATIVE_BINDINGS.md"));
        try (Stream<Path> chapters = Files.list(repository.resolve("docs/appchain/bindings"))) {
            documents.addAll(chapters.filter(path -> path.toString().endsWith(".md")).sorted().toList());
        }
        for (Path document : documents) {
            Matcher matcher = fence.matcher(Files.readString(document, StandardCharsets.UTF_8));
            for (int index = 0; matcher.find(); index++) {
                cases.put("doc:" + document.getFileName() + "#" + index, matcher.group(1));
            }
        }
        return cases;
    }

    private static final List<String> SCALARS = List.of(
            "0", "-0", "1", "-1", "42", "007", "017", "08", "09", "1_000", "-1_000", "+5", "+0", "0x1F", "0x_1",
            "0b101", "0o17", "0o8", "1e3", "1.5", ".5", "0.", "1.", "-.5", ".inf", "-.inf", ".nan", "NaN", "Infinity",
            "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
            "99999999999999999999999", "123456789012345678901234567890123",
            "true", "false", "True", "False", "TRUE", "FALSE", "yes", "no", "Yes", "No", "YES", "NO", "on", "off",
            "On", "Off", "ON", "OFF", "y", "n", "Y", "N", "null", "Null", "NULL", "~", "",
            "abc", "kv-registry.entry-put.v1", "a b", "a#b", "a #b", "x:y", "x: y", "2001-12-14",
            "2001-12-14t21:59:43.10-05:00", "12:30", "1:20:30", "_x", "/x", "x/y", "\u00e9t\u00e9", "\uD83D\uDE00x",
            "a'b", "a\"b", "<<", "=", "@x", "`x", "%x", "!x", "!!str x", "&x", "*x", "|x", ">x", "?x", "- x", "-x",
            ",x", "[x", "{x", "x]", "x}", "x,y", "'q'", "\"q\"", "'it''s'", "\"a\\\"b\"", "\"\\u00e9\"",
            "\"\\x41\"", "\"\\/\"", "\"\\e\"", "\"\\N\"", "\"\\ud83d\\ude00\"", "\"\\ud800\"", "\"\\t\"",
            "'a\\nb'", "\"tab\there\"", "x\u0085y", "\"x\u0085y\"", "\"x\u2028y\"", "\"\u00a0\"", "x ", "\"\"", "''",
            "0x", "1e", "1_0", "0_1", "- ", "x\u0001", "\"x\u007f\"", "\"\ufeffx\"", "A", "Z9", "a.b.c",
            "procurement-approved:", "'procurement-approved:'", "event.valueLength < 100", "'event.a >= 2'",
            "a\\b", "\"a\\\\b\"", "value-format", "\u00e0-la-carte");

    private static void curated(Map<String, String> cases) {
        cases.put("empty", "");
        cases.put("comment-only", "# nothing\n");
        cases.put("bom", "\uFEFFa: 1\n");
        cases.put("crlf", "a: 1\r\nb:\r\n  - x\r\n");
        cases.put("lone-cr", "a: 1\rb: 2\n");
        cases.put("document-start", "---\na: 1\n");
        cases.put("document-start-comment", "--- # start\na: 1\n");
        cases.put("document-start-inline", "--- {a: 1}\n");
        cases.put("document-end", "a: 1\n...\n");
        cases.put("multiple-documents", "a: 1\n---\nb: 2\n");
        cases.put("directive", "%YAML 1.1\n---\na: 1\n");
        cases.put("tab-value", "a:\tb\n");
        cases.put("tab-indent", "a:\n\t- b\n");
        cases.put("trailing-space", "a: b   \n");
        cases.put("no-final-newline", "a: b");
        cases.put("merge-key", "a: {x: 1}\nb:\n  <<: {y: 2}\n");
        cases.put("quoted-merge-key", "b:\n  '<<': {y: 2}\n");
        cases.put("anchor", "a: &x 1\nb: *x\n");
        cases.put("tag", "a: !!str 5\n");
        cases.put("custom-tag", "a: !custom 5\n");
        cases.put("complex-key", "? a\n: b\n");
        cases.put("literal-block", "a: |\n  x\n  y\n");
        cases.put("folded-block", "a: >-\n  one\n  two\n");
        cases.put("plain-multiline", "a: plain\n  continued\n");
        cases.put("quoted-multiline", "a: \"multi\n  line\"\n");
        cases.put("single-multiline", "a: 'x\n  y'\n");
        cases.put("space-before-colon", "a : b\n");
        cases.put("colon-no-space", "a:b\n");
        cases.put("empty-value", "a:\nb: 1\n");
        cases.put("empty-sequence-item", "a:\n  -\n  - x\n");
        cases.put("duplicate-block", "a: 1\na: 2\n");
        cases.put("duplicate-quoted", "a: 1\n'a': 2\n");
        cases.put("duplicate-flow", "a: {b: 1, b: 2}\n");
        cases.put("indentless-sequence", "a:\n- x\n- y\nb: 1\n");
        cases.put("compact-mapping", "a:\n  - id: x\n    machine: y\n  - {id: z}\n");
        cases.put("misaligned-compact", "a:\n  - id: x\n   machine: y\n");
        cases.put("nested-sequence", "a:\n  - - x\n    - y\n");
        cases.put("sequence-on-next-line", "a:\n  -\n    id: x\n");
        cases.put("value-next-line", "a:\n  b\n");
        cases.put("flow-multiline", "a: {b: 1,\n  c: [x,\n    y]}\n");
        cases.put("flow-multiline-shallow", "a: {b: 1,\nc: 2}\n");
        cases.put("flow-trailing-comma", "a: [1, 2, ]\n");
        cases.put("flow-map-trailing-comma", "a: {b: 1, }\n");
        cases.put("flow-empty", "a: {}\nb: []\n");
        cases.put("flow-pair-in-sequence", "a: [b: 1]\n");
        cases.put("flow-json-adjacent-colon", "{\"a\":1}\n");
        cases.put("flow-json-space-colon", "{\"a\" : 1}\n");
        cases.put("flow-plain-no-space", "{a:1}\n");
        cases.put("flow-comment", "a: [1, # one\n  2]\n");
        cases.put("json-document", "{\n  \"composite\": {\n    \"components\": [\n      {\"id\": \"a\", "
                + "\"machine\": \"kv-registry\"}\n    ],\n    \"bindings\": []\n  }\n}\n");
        cases.put("json-closing-column-zero", "{\n  \"a\": [\n    1\n]\n}\n");
        cases.put("top-sequence", "- a\n- b\n");
        cases.put("top-scalar", "hello\n");
        cases.put("top-flow-sequence", "[1, 2]\n");
        cases.put("mapping-after-sequence", "a:\n  - x\n  b: 1\n");
        cases.put("sequence-after-mapping", "a:\n  b: 1\n  - x\n");
        cases.put("deeper-after-scalar", "a: b\n    c: d\n");
        cases.put("comment-after-flow", "a: {b: 1} # c\n");
        cases.put("content-after-flow", "a: {b: 1} x\n");
        cases.put("content-after-quote", "a: 'x' y\n");
        cases.put("hash-no-space", "a: 'x'#y\n");
        cases.put("key-special", "a b: 1\n");
        cases.put("key-quoted-special", "\"a b\": 1\n'c: d': 2\n");
        cases.put("key-digit", "017: 1\n");
        cases.put("key-true", "true: 1\nyes: 2\n");
        cases.put("key-null", "~: 1\n");
        cases.put("key-empty-quoted", "'': 1\n");
        cases.put("unicode-escape-upper", "a: \"\\U0001F600\"\n");
        cases.put("control-in-comment", "# x\u0085a: 1\nb: 2\n");
        cases.put("line-separator-in-comment", "# x\u2028a: 1\nb: 2\n");
        cases.put("nbsp-indent", "a:\n\u00a0 b: 1\n");
        cases.put("quoted-key-missing-colon", "a: 1\n\"b\" x\n");
        cases.put("plain-key-missing-colon", "a: 1\nb 2\n");
        cases.put("flow-key-colon-next-line", "a: {b\n  : 1}\n");
        cases.put("document-start-hash", "---#comment\na: 1\n");
        cases.put("quoted-key-space-colon", "'a' : 1\n");
        cases.put("tab-later-line", "a: 1\nb:\t2\n");
        cases.put("noncharacter-fffe", "a: \"x\uFFFEy\"\n");
        cases.put("noncharacter-ffff", "a: \"x\uFFFFy\"\n");
        cases.put("key-reserved-on", "on: 1\n");
        cases.put("key-reserved-no-quoted", "'no': 1\n");
        cases.put("key-merge-plain", "<<: 1\n");
        cases.put("key-slash", "a/b: 1\n");
        for (int length : new int[] {1_024, 1_025, 49_999, 50_000, 50_001}) {
            cases.put("key-length-" + length, "k" + "x".repeat(length - 1) + ": 1\n");
            cases.put("quoted-key-length-" + length, "'k" + "x".repeat(length - 1) + "': 1\n");
        }
        cases.put("blueprint-shape", """
                apiVersion: yano.bloxbean.com/v1alpha1
                kind: AppChainProject
                metadata:
                  name: "my-appchain"
                spec:
                  yanoVersion: "0.1.0-pre2"
                  network: "devnet"
                  chains:
                    - chainId: "orders"
                      recipe: "declarative-composite"
                      topology:
                        members: 3
                        memberKeys: ["aa", "bb"]
                      composite:
                        components:
                          - {id: records, machine: kv-registry}
                        bindings: []
                """);
    }

    /**
     * Malformed neighbours of well-formed documents: each colon dropped, text inserted after each quote and each
     * space removed, plus escaped keys around SnakeYAML's implicit-key source limit.
     */
    private static void mutations(Map<String, String> cases) {
        List<String> seeds = List.of("a: 1\nb: 'x'\nc: \"y\"\n", "a:\n  b: {c: 1, \"d\": 'e'}\n  f: [1, \"g\"]\n",
                "\"q\": 1\n'r': \"s\"\nt: [\"u\", 'v']\n",
                "components:\n  - {id: a, machine: kv-registry}\nbindings: []\n");
        for (int seed = 0; seed < seeds.size(); seed++) {
            String base = seeds.get(seed);
            for (int index = 0; index < base.length(); index++) {
                char character = base.charAt(index);
                String before = base.substring(0, index);
                String after = base.substring(index + 1);
                if (character == ':') cases.put("mutation:" + seed + ":drop-colon@" + index, before + after);
                if (character == ' ') cases.put("mutation:" + seed + ":drop-space@" + index, before + after);
                if (character == '"' || character == '\'') {
                    for (String insert : List.of("x", " x", ":", "=5", ":x", "\"", "'", " #c")) {
                        cases.put("mutation:" + seed + ":after-quote@" + index + ":" + insert,
                                before + character + insert + after);
                    }
                }
            }
        }
        for (int count : new int[] {80, 85, 86, 100, 127, 128, 170, 171, 200, 256}) {
            String key = "\"" + "\\u0041".repeat(count) + "\"";
            cases.put("long-escaped-key:" + count, key + ": 1\n");
            cases.put("long-escaped-flow-key:" + count, "a: {" + key + ": 1}\n");
        }
    }

    private static void combinations(Map<String, String> cases) {
        List<String> contexts = List.of("k: %s\n", "k: [%s]\n", "k: {x: %s}\n", "- %s\n", "%s: v\n",
                "k: {%s: v}\n", "k:\n  %s\n", "k:\n  - %s\n", "k: [a, %s, b]\n", "k: {x: %s, y: 1}\n",
                "k: %s # comment\n");
        for (int s = 0; s < SCALARS.size(); s++) {
            for (int c = 0; c < contexts.size(); c++) {
                cases.put("scalar[" + s + "]@" + c, contexts.get(c).formatted(SCALARS.get(s)));
            }
        }
    }

    private static void structures(Map<String, String> cases) {
        for (int indent = 1; indent <= 4; indent++) {
            String pad = " ".repeat(indent);
            cases.put("indent-" + indent, "a:\n" + pad + "b: 1\n" + pad + "c:\n" + pad + pad + "- x\n");
            cases.put("indent-seq-" + indent, "a:\n" + pad + "- b: 1\n" + pad + "  c: 2\n");
            cases.put("indent-mixed-" + indent, "a:\n" + pad + "b: 1\n" + pad.substring(1) + " c: 2\n");
        }
        cases.put("blank-lines", "\n\na: 1\n\n\nb:\n\n  - x\n\n");
        cases.put("comment-lines", "# h\na: 1 # c\n  # nested comment\nb: 2\n");
        cases.put("comment-column-zero-in-block", "a:\n  b: 1\n# c\n  d: 2\n");
    }

    private static void limits(Map<String, String> cases) {
        for (int depth : new int[] {46, 47, 48, 49, 50}) {
            cases.put("flow-depth-" + depth, "a: " + "[".repeat(depth - 1) + "1" + "]".repeat(depth - 1) + "\n");
            StringBuilder block = new StringBuilder();
            for (int level = 0; level < depth; level++) block.append("  ".repeat(level)).append("k").append(level)
                    .append(":\n");
            block.append("  ".repeat(depth)).append("v\n");
            cases.put("block-depth-" + depth, block.toString());
        }
        for (int length : new int[] {65_536, 65_537, 131_072, 131_073}) {
            cases.put("string-" + length, "a: \"" + "x".repeat(length) + "\"\n");
            cases.put("plain-" + length, "a: " + "x".repeat(length) + "\n");
        }
        // {a: [n integers]} is n + 5 parser tokens; the compiler pre-scan allows at most 32,768.
        for (int items : new int[] {32_762, 32_763, 32_764}) {
            cases.put("tokens-" + items, "a: [" + String.join(",", java.util.Collections.nCopies(items, "1")) + "]\n");
        }
        cases.put("characters-262144", "a: " + "x".repeat(262_144 - 4) + "\n");
        cases.put("characters-262145", "a: " + "x".repeat(262_145 - 4) + "\n");
    }

    private static void generated(Map<String, String> cases, int count) {
        SplittableRandom random = new SplittableRandom(20260925L);
        List<String> keys = List.of("a", "b", "id", "value-format", "'q'", "\"d\"", "x.y", "017", "true", "k_1");
        for (int index = 0; index < count; index++) {
            StringBuilder text = new StringBuilder();
            write(text, random, keys, 0, random.nextInt(4));
            cases.put("generated-" + index, text.toString());
        }
    }

    private static void write(StringBuilder text, SplittableRandom random, List<String> keys, int indent, int depth) {
        int entries = 1 + random.nextInt(3);
        boolean sequence = random.nextInt(4) == 0;
        for (int entry = 0; entry < entries; entry++) {
            String pad = " ".repeat(indent);
            String head = sequence ? pad + "- " : pad + keys.get(random.nextInt(keys.size())) + ":";
            if (depth > 0 && random.nextBoolean()) {
                text.append(head.stripTrailing()).append(random.nextInt(8) == 0 ? " # c" : "").append('\n');
                write(text, random, keys, indent + 2, depth - 1);
                continue;
            }
            String value = switch (random.nextInt(5)) {
                case 0 -> "[" + SCALARS.get(random.nextInt(SCALARS.size())) + ", "
                        + SCALARS.get(random.nextInt(SCALARS.size())) + "]";
                case 1 -> "{" + keys.get(random.nextInt(keys.size())) + ": "
                        + SCALARS.get(random.nextInt(SCALARS.size())) + "}";
                default -> SCALARS.get(random.nextInt(SCALARS.size()));
            };
            text.append(head).append(sequence ? "" : " ").append(value)
                    .append(random.nextInt(10) == 0 ? " # c" : "").append('\n');
        }
    }
}
