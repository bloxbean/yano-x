package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Offline {@code appchain bindings compile|validate|graph|dry-run} commands.
 *
 * <p>Executable validation always opens an explicit directory of dependency-complete, manifested bundles
 * through the host catalog. OrderedLog is the sole host builtin, resolved exactly as by the live host.
 * No optional-provider fallback, environment-derived configuration, live node,
 * network connection, signing key, or effect delivery is used. All commands write their result to the supplied
 * output stream; they do not alter the document, context, fixture, plugin directory, or retained chain state.
 */
public final class BindingCli {
    private static final String USAGE = """
            Usage: appchain bindings compile|validate|graph|dry-run <document.yml> [options]
              or: appchain bindings receipt-key <source-message-id-hex>
              or: appchain bindings recipe dpp|feed --descriptor <actors.json> --members <members.json> --threshold <n>
              or: appchain bindings profile-check --profiles <profiles.json> --context <context.json>
                    --plugins-directory <directory>
              --plugins-directory <directory>  exact manifested runtime bundles (required except graph --ir)
              --context <context.json>         explicit chainId/settings/consensusProfile/membership
              --ir                             input is canonical binding IR hex, not YAML
              --fixture <fixture.json>         dry-run only; one fixture block and physical base state
              --prior-result <result.json>     dry-run only; carry prior rehearsal postState to next height
              --continuation-only             dry-run only; emit bounded continuation without receipt diagnostics
            compile prints canonical IR hex; validate prints JSON profile metadata; graph prints DOT.
            dry-run prints unchanged receipt hex, decoded receipt arrays, planned effects, and state changes.
            Dry-run assumes authenticated inputs; it does not verify signatures, finality, roots, or anchors.
            """;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                    .maxStringLength(2_097_152).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final ObjectMapper CANONICAL_JSON = JSON.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** Creates a stateless command dispatcher; catalog resources are scoped to each invocation. */
    public BindingCli() { }

    /**
     * Executes a bounded offline command without closing caller-owned streams.
     *
     * @param args arguments after the {@code bindings} subcommand
     * @param out result stream
     * @param err diagnostic stream
     * @return zero on success, 64 for usage, 74 for input I/O, or 2 for invalid authoring/catalog/fixture input
     */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            out.print(USAGE);
            out.flush();
            return args.length == 0 ? AppChainDevtoolsCli.EXIT_USAGE : AppChainDevtoolsCli.EXIT_OK;
        }
        try {
            if (args[0].equals("profile-check")) return BindingProfileCheck.run(args, out, err);
            if (args[0].equals("recipe")) {
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(recipe(args)));
                return AppChainDevtoolsCli.EXIT_OK;
            }
            if (args[0].equals("receipt-key")) {
                if (args.length != 2) throw new Usage("receipt-key requires one source message id");
                if (args[1].length() != 64) {
                    throw new IllegalArgumentException("source message id must contain 32 bytes");
                }
                byte[] id = HexFormat.of().parseHex(args[1]);
                if (id.length != 32) throw new IllegalArgumentException("source message id must contain 32 bytes");
                String canonical = HexFormat.of().formatHex(id);
                out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "workflowId", EventBindingWorkflow.ID,
                        "stateKeyHex", HexFormat.of().formatHex(
                                CompositeStateKeys.workflowStateKey(EventBindingWorkflow.ID, id)),
                        "receiptQueryPath", "composite/binding-receipt-v1/" + canonical,
                        "keyQueryPath", "composite/binding-receipt-key-v1/" + canonical)));
                return AppChainDevtoolsCli.EXIT_OK;
            }
            Arguments parsed = Arguments.parse(args);
            String input = read(parsed.document(), 1_048_576);
            BindingIrV1 decoded = parsed.ir() ? decode(input) : null;
            if (parsed.command().equals("graph") && decoded != null) {
                out.print(BindingGraph.dot(decoded));
                return AppChainDevtoolsCli.EXIT_OK;
            }
            var context = JSON.readValue(read(parsed.context(), 1_048_576), BindingCatalogSession.ContextInput.class);
            try (BindingPluginEnvironment environment = BindingPluginEnvironment.open(parsed.plugins())) {
                BindingCatalogSession session = new BindingCatalogSession(environment.providers(), context);
                var document = decoded == null ? BindingDocumentCompiler.parseDocument(input) : null;
                BindingIrV1 ir = decoded == null ? BindingDocumentCompiler.compile(document, session) : decoded;
                AppStateMachine machine = validateProfile(session, ir, document != null
                        && document.has("composite") ? "$.composite" : "$");
                switch (parsed.command()) {
                    case "compile" -> out.println(HexFormat.of().formatHex(ir.encode()));
                    case "graph" -> out.print(BindingGraph.dot(ir));
                    case "validate" -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("valid", true);
                        result.put("applicationId", machine.id());
                        result.put("operationalStatus", machine.operationalStatus());
                        result.put("manifest", machine.capabilityManifest());
                        result.put("bindingCount", ir.bindings().size());
                        result.put("componentCount", ir.components().size());
                        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    }
                    case "dry-run" -> {
                        var fixture = JSON.readValue(read(parsed.fixture(), 33_554_432), BindingDryRun.Fixture.class);
                        String identity = rehearsalIdentity(ir, context, machine.capabilityManifest());
                        if (parsed.priorResult() != null) {
                            var prior = JSON.readValue(read(parsed.priorResult(), 67_108_864), Rehearsal.class);
                            fixture = continueFixture(fixture, prior, identity);
                        }
                        var result = BindingDryRun.execute(machine, context, fixture);
                        Object output = parsed.continuationOnly()
                                ? new Continuation(result.assurance(), result.postState(), fixture.height(), identity)
                                : new Rehearsal(result.assurance(), result.receipts(), result.effects(),
                                        result.stateChanges(), result.postState(), fixture.height(), identity);
                        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                                output));
                    }
                    default -> throw new Usage("unknown bindings command");
                }
            }
            return AppChainDevtoolsCli.EXIT_OK;
        } catch (Usage error) {
            err.println(error.getMessage());
            err.print(USAGE);
            return AppChainDevtoolsCli.EXIT_USAGE;
        } catch (JsonProcessingException error) {
            err.println("Binding JSON input is invalid: " + diagnostic(error));
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        } catch (IOException error) {
            err.println("Binding input could not be read: " + diagnostic(error));
            return AppChainDevtoolsCli.EXIT_IO;
        } catch (RuntimeException error) {
            err.println("Binding input is invalid: " + diagnostic(error));
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static BindingIrV1 decode(String source) {
        String hex = source.strip();
        if (hex.length() > 131_072) throw new IllegalArgumentException("binding IR hex exceeds limit");
        return BindingIrV1.decode(HexFormat.of().parseHex(hex));
    }

    /** Keeps profile-construction diagnostics attached to the authored binding rather than an opaque provider cause. */
    private static AppStateMachine validateProfile(BindingCatalogSession session, BindingIrV1 ir, String root) {
        try { return session.validate(ir); }
        catch (RuntimeException error) {
            String detail = diagnostic(error);
            String path = root;
            for (int index = 0; index < ir.bindings().size(); index++) {
                if (detail.contains("binding '" + ir.bindings().get(index).id() + "'")) {
                    path += ".bindings[" + index + "]";
                    break;
                }
            }
            throw new IllegalArgumentException(path + ": " + detail, error);
        }
    }

    private static BindingRecipe.Output recipe(String[] args) throws IOException {
        if (args.length != 8 || !Set.of("dpp", "feed").contains(args[1])) {
            throw new Usage("recipe requires dpp|feed, --descriptor, --members, and --threshold");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 2; index < args.length; index += 2) {
            if (!Set.of("--descriptor", "--members", "--threshold").contains(args[index])
                    || args[index + 1].isBlank() || args[index + 1].startsWith("--")
                    || options.putIfAbsent(args[index], args[index + 1]) != null) {
                throw new Usage("unknown, duplicate, or missing recipe option");
            }
        }
        int threshold;
        try {
            threshold = Integer.parseInt(options.get("--threshold"));
        } catch (NumberFormatException invalid) {
            throw new Usage("--threshold: expected a 32-bit integer");
        }
        var descriptor = JSON.readValue(read(Path.of(options.get("--descriptor")), 1_048_576),
                BindingRecipe.Descriptor.class);
        var members = JSON.readValue(read(Path.of(options.get("--members")), 65_536), String[].class);
        return BindingRecipe.generate(args[1], descriptor, Arrays.asList(members),
                threshold);
    }

    static String read(Path path, int maximum) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IllegalArgumentException("binding input file exceeds size limit");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static String diagnostic(Exception error) {
        String jsonPath = "";
        if (error instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            StringBuilder path = new StringBuilder("$");
            for (var reference : mapping.getPath()) {
                path.append(reference.getFieldName() == null ? "[" + reference.getIndex() + "]"
                        : "." + reference.getFieldName());
            }
            jsonPath = path + ": ";
        }
        Throwable detail = error;
        for (int depth = 0; depth < 8 && detail.getCause() != null && detail.getCause() != detail; depth++) {
            // Preserve authored-field and binding context instead of exposing only a low-level parser cause.
            String contextual = detail.getMessage();
            if (contextual != null && (contextual.startsWith("$") || contextual.contains("binding '"))) break;
            detail = detail.getCause();
        }
        String message = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage();
        message = message.lines().findFirst().orElse("invalid input").replaceAll("[\\p{Cntrl}]", " ");
        message = jsonPath + message;
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    /**
     * Explicit continuation artifact. The identity guards accidental context mixing, not malicious modification;
     * all state/root/pending-effect values remain unverified caller assumptions, never node export evidence.
     */
    record Rehearsal(String assurance, java.util.List<Map<String, Object>> receipts,
                     java.util.List<Map<String, Object>> effects, java.util.List<BindingDryRun.Entry> stateChanges,
                     java.util.List<BindingDryRun.Entry> postState, long height, String executionIdentity) { }

    /** Minimal continuation stays below the input cap even at the fixture's maximum physical state size. */
    record Continuation(String assurance, java.util.List<BindingDryRun.Entry> postState,
                        long height, String executionIdentity) { }

    static BindingDryRun.Fixture continueFixture(BindingDryRun.Fixture next, Rehearsal prior, String identity) {
        if (!identity.equals(prior.executionIdentity())) {
            throw new IllegalArgumentException("--prior-result: binding IR or chain context differs");
        }
        if (prior.height() < 1 || prior.height() == Long.MAX_VALUE || next.height() != prior.height() + 1) {
            throw new IllegalArgumentException("--fixture.height: expected consecutive prior-result height");
        }
        if (!next.state().isEmpty()) {
            throw new IllegalArgumentException("--fixture.state: must be empty when using --prior-result");
        }
        if (prior.postState() == null) throw new IllegalArgumentException("--prior-result.postState: required");
        return new BindingDryRun.Fixture(next.height(), next.timestamp(), next.stateRootHex(), next.pendingEffects(),
                prior.postState(), next.messages());
    }

    private static String rehearsalIdentity(BindingIrV1 ir, BindingCatalogSession.ContextInput context, Object manifest)
            throws JsonProcessingException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update("yano-binding-rehearsal-v1\0".getBytes(StandardCharsets.US_ASCII));
            digest.update(ir.encode());
            digest.update(CANONICAL_JSON.writeValueAsBytes(context));
            digest.update(CANONICAL_JSON.writeValueAsBytes(manifest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Arguments(String command, Path document, Path plugins, Path context, Path fixture,
                             Path priorResult, boolean ir, boolean continuationOnly) {
        static Arguments parse(String[] args) {
            if (!Set.of("compile", "validate", "graph", "dry-run").contains(args[0])) {
                throw new Usage("unknown bindings command");
            }
            Map<String, String> values = new LinkedHashMap<>();
            String document = null;
            boolean ir = false;
            boolean continuationOnly = false;
            for (int i = 1; i < args.length; i++) {
                String argument = args[i];
                if (argument.equals("--ir")) {
                    if (ir) throw new Usage("duplicate --ir");
                    ir = true;
                } else if (argument.equals("--continuation-only")) {
                    if (continuationOnly) throw new Usage("duplicate --continuation-only");
                    continuationOnly = true;
                } else if (argument.startsWith("--")) {
                    if (!Set.of("--plugins-directory", "--context", "--fixture", "--prior-result").contains(argument)
                            || i + 1 == args.length || args[i + 1].startsWith("--")
                            || values.putIfAbsent(argument, args[++i]) != null) {
                        throw new Usage("unknown, missing, or duplicate bindings option");
                    }
                } else if (document == null) document = argument;
                else throw new Usage("expected one binding document");
            }
            boolean graphIr = args[0].equals("graph") && ir;
            if (document == null || !graphIr && (!values.containsKey("--plugins-directory")
                    || !values.containsKey("--context"))) {
                throw new Usage("document, plugin directory, and context required");
            }
            if (args[0].equals("dry-run") != values.containsKey("--fixture")) {
                throw new Usage("--fixture is required only for dry-run");
            }
            if (!args[0].equals("dry-run") && values.containsKey("--prior-result")) {
                throw new Usage("--prior-result is allowed only for dry-run");
            }
            if (!args[0].equals("dry-run") && continuationOnly) {
                throw new Usage("--continuation-only is allowed only for dry-run");
            }
            return new Arguments(args[0], Path.of(document), path(values.get("--plugins-directory")),
                    path(values.get("--context")), path(values.get("--fixture")),
                    path(values.get("--prior-result")), ir, continuationOnly);
        }
        private static Path path(String value) { return value == null ? null : Path.of(value); }
    }

    private static final class Usage extends IllegalArgumentException {
        Usage(String message) { super(message); }
    }
}
