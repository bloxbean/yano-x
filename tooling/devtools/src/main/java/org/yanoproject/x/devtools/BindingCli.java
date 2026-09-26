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
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import org.yanoproject.api.appchain.AppQueryContext;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.CompositeProfile;
import org.yanoproject.x.composite.CompositeProfileCodec;
import org.yanoproject.x.composite.bindings.BindingValidationException;
import org.yanoproject.x.composite.contracts.BindingIrV1;
import org.yanoproject.x.composite.CompositeStateKeys;
import org.yanoproject.x.composite.bindings.EventBindingWorkflow;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Offline {@code appchain bindings compile|validate|graph|dry-run|catalog} commands.
 *
 * <p>Executable validation always opens an explicit directory of dependency-complete, manifested bundles
 * through the host catalog. OrderedLog is the sole host builtin, resolved exactly as by the live host.
 * No optional-provider fallback, environment-derived configuration, live node,
 * network connection, signing key, or effect delivery is used. All commands write their result to the supplied
 * output stream; they do not alter the document, context, fixture, plugin directory, or retained chain state.
 *
 * <p>{@code --report <file>} additionally writes a {@code yano-x-binding-report-v1} envelope for editors
 * (ADR-031.2). Without it, standard output, standard error and exit codes are exactly as before. With it, the
 * report is written atomically before standard output is printed; a report that cannot be written (including one
 * above its size bound) makes the command exit 74 without standard output.
 */
public final class BindingCli {
    private static final String USAGE = """
            Usage: appchain bindings compile|validate|graph|dry-run <document.yml> [options]
              or: appchain bindings catalog [<document.yml>] --plugins-directory <directory> --context <context.json>
                    [--machine <selector>]... [--all]
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
              --report <report.json>           compile/validate/dry-run: also write an editor report envelope
            compile prints canonical IR hex; validate prints JSON profile metadata; graph prints DOT.
            dry-run prints unchanged receipt hex, decoded receipt arrays, planned effects, and state changes.
            catalog prints editor descriptors for explicitly probed machines; it installs nothing.
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
     * @return zero on success, 64 for usage, 74 for input or report I/O, or 2 for invalid authoring/catalog/fixture
     *         input
     */
    public int run(String[] args, PrintWriter out, PrintWriter err) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            out.print(USAGE);
            out.flush();
            return args.length == 0 ? AppChainDevtoolsCli.EXIT_USAGE : AppChainDevtoolsCli.EXIT_OK;
        }
        try {
            if (args[0].equals("profile-check")) return BindingProfileCheck.run(args, out, err);
            if (args[0].equals("catalog")) return BindingAuthoringCatalog.run(args, out, err);
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
            if (parsed.report() != null) {
                try {
                    BindingReport.requireSafeTarget(parsed.report(), Arrays.asList(parsed.document(),
                            parsed.context(), parsed.fixture(), parsed.priorResult()), parsed.plugins());
                } catch (IllegalArgumentException unsafe) {
                    throw new Usage(unsafe.getMessage());
                }
            }
            return new Invocation(parsed).execute(out, err);
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

    /**
     * One compile/validate/graph/dry-run execution. Standard output is buffered only when a report is requested so
     * the report can be written first; every message, stream and exit status otherwise matches the historical CLI.
     */
    private static final class Invocation {
        private final Arguments parsed;
        private final BindingInputs inputs = new BindingInputs();
        private String stage = "document";
        private BindingPluginEnvironment environment;
        private boolean wrapped;
        private BindingIrV1 ir;
        private Map<String, Object> result;
        private BindingDiagnostic diagnostic;
        private Map<String, Object> catalogIdentity;

        private Invocation(Arguments parsed) { this.parsed = parsed; }

        int execute(PrintWriter out, PrintWriter err) throws IOException {
            StringWriter buffer = new StringWriter();
            PrintWriter output = parsed.report() == null ? out : new PrintWriter(buffer);
            int code;
            try {
                code = body(output);
            } catch (Usage usage) {
                throw usage;
            } catch (JsonProcessingException error) {
                err.println("Binding JSON input is invalid: " + diagnostic(error));
                diagnostic = jsonDiagnostic(error);
                code = AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
            } catch (IOException error) {
                err.println("Binding input could not be read: " + diagnostic(error));
                return AppChainDevtoolsCli.EXIT_IO;
            } catch (RuntimeException error) {
                err.println("Binding input is invalid: " + diagnostic(error));
                diagnostic = classify(error);
                code = AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
            }
            if (parsed.report() != null) {
                try {
                    BindingReport.write(parsed.report(), BindingReport.encode(report(code)));
                } catch (IOException | RuntimeException failure) {
                    err.println("Binding report could not be written: " + diagnostic(failure));
                    return AppChainDevtoolsCli.EXIT_IO;
                }
                output.flush();
                out.print(buffer);
            }
            return code;
        }

        private int body(PrintWriter out) throws IOException {
            String input = inputs.read("document", parsed.document(), 1_048_576);
            stage = parsed.ir() ? "ir" : "document";
            BindingIrV1 decoded = parsed.ir() ? decode(input) : null;
            if (parsed.command().equals("graph") && decoded != null) {
                out.print(BindingGraph.dot(decoded));
                return AppChainDevtoolsCli.EXIT_OK;
            }
            stage = "context";
            var context = JSON.readValue(inputs.read("context", parsed.context(), 1_048_576),
                    BindingCatalogSession.ContextInput.class);
            stage = "plugins";
            // As before, a close failure is suppressed by the primary failure and replaces only a success.
            try (BindingPluginEnvironment opened = BindingPluginEnvironment.open(parsed.plugins())) {
                environment = opened;
                return withCatalog(out, input, decoded, context);
            }
        }

        private int withCatalog(PrintWriter out, String input, BindingIrV1 decoded,
                                BindingCatalogSession.ContextInput context) throws IOException {
            // Captured while the catalog is open; the report is written after the environment closes.
            catalogIdentity = BindingToolIdentity.catalogIdentity(environment.catalog());
            BindingCatalogSession session = new BindingCatalogSession(environment.providers(), context);
            stage = "document";
            var document = decoded == null ? BindingDocumentCompiler.parseDocument(input) : null;
            wrapped = document != null && document.has("composite");
            ir = decoded == null ? BindingDocumentCompiler.compile(document, session) : decoded;
            stage = "profile";
            AppStateMachine machine = validateProfile(session, ir, wrapped ? "$.composite" : "$");
            boolean reporting = parsed.report() != null;
            if (reporting) {
                result = new LinkedHashMap<>();
                result.put("irHex", HexFormat.of().formatHex(ir.encode()));
                result.put("irSha256", BindingInputs.sha256(ir.encode()));
                result.put("profile", reportOnly(() -> profileIdentity(machine)));
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("components", ir.components().size());
                counts.put("bindings", ir.bindings().size());
                result.put("counts", counts);
            }
            switch (parsed.command()) {
                case "compile" -> out.println(HexFormat.of().formatHex(ir.encode()));
                case "graph" -> out.print(BindingGraph.dot(ir));
                case "validate" -> {
                    Map<String, Object> validation = new LinkedHashMap<>();
                    validation.put("valid", true);
                    validation.put("applicationId", machine.id());
                    validation.put("operationalStatus", machine.operationalStatus());
                    validation.put("manifest", machine.capabilityManifest());
                    validation.put("bindingCount", ir.bindings().size());
                    validation.put("componentCount", ir.components().size());
                    out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(validation));
                }
                case "dry-run" -> {
                    stage = "fixture";
                    var fixture = JSON.readValue(inputs.read("fixture", parsed.fixture(), 33_554_432),
                            BindingDryRun.Fixture.class);
                    String identity = rehearsalIdentity(ir, context, machine.capabilityManifest());
                    Map<String, Object> rehearsal = new LinkedHashMap<>();
                    rehearsal.put("continuationOnly", parsed.continuationOnly());
                    rehearsal.put("fromPriorResult", parsed.priorResult() != null);
                    rehearsal.put("priorPostStateSha256", null);
                    Rehearsal prior = null;
                    if (parsed.priorResult() != null) {
                        stage = "priorResult";
                        prior = JSON.readValue(inputs.read("priorResult", parsed.priorResult(), 67_108_864),
                                Rehearsal.class);
                        stage = "continuation";
                        fixture = continueFixture(fixture, prior, identity);
                    }
                    stage = "execution";
                    var executed = BindingDryRun.execute(machine, context, fixture);
                    if (reporting && prior != null) {
                        List<BindingDryRun.Entry> priorState = prior.postState();
                        rehearsal.put("priorPostStateSha256",
                                reportOnly(() -> BindingReport.postStateDigest(priorState)));
                    }
                    Object output = parsed.continuationOnly()
                            ? new Continuation(executed.assurance(), executed.postState(), fixture.height(), identity)
                            : new Rehearsal(executed.assurance(), executed.receipts(), executed.effects(),
                                    executed.stateChanges(), executed.postState(), fixture.height(), identity);
                    if (reporting) {
                        rehearsal.putAll(rehearsalReport(fixture, executed, identity));
                        result.put("rehearsal", rehearsal);
                    }
                    out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
                }
                default -> throw new Usage("unknown bindings command");
            }
            return AppChainDevtoolsCli.EXIT_OK;
        }

        /** Keeps profile-construction diagnostics attached to the authored binding rather than an opaque cause. */
        private AppStateMachine validateProfile(BindingCatalogSession session, BindingIrV1 ir, String root) {
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
                String message = path + ": " + detail;
                if (parsed.report() != null) {
                    // Report-only: the explanation reconstructs providers, so plain invocations never run it.
                    Optional<BindingValidationException> located =
                            session.explainProfileFailure(ir, error, environment.catalog());
                    diagnostic = located.isPresent() ? profileDiagnostic(located.get(), message, ir)
                            : BindingDiagnostic.error("PROFILE_CONSTRUCTION_FAILED", message, true,
                                    BindingDiagnostic.Location.input(parsed.ir() ? "ir" : "document"));
                }
                throw new IllegalArgumentException(message, error);
            }
        }

        /**
         * Location of a structured construction failure. Document input gets authored path segments; IR input has
         * no authored document, so it carries only IR indexes and names. The exception's own part and field win
         * over anything derived from segments. The detail names bindings and fields, so it may contain input.
         */
        private BindingDiagnostic profileDiagnostic(BindingValidationException located, String message,
                                                    BindingIrV1 ir) {
            String part = BindingDiagnostic.Location.part(located.part());
            BindingDiagnostic.Location location;
            if (parsed.ir()) {
                location = new BindingDiagnostic.Location("ir", null, null, null, null, null, null,
                        located.bindingIndex(), located.bindingId(), located.clauseIndex(), part, located.field(),
                        located.argumentPath(), null, null);
            } else {
                BindingDocumentPath segments = profilePath(located, ir);
                if (wrapped) segments = segments.under("composite");
                location = BindingDiagnostic.Location.fromSegments("document", segments, null, null)
                        .withBinding(located.bindingIndex(), located.bindingId())
                        .withPart(part, located.field(), located.argumentPath());
            }
            String code = BindingDiagnostic.CODES.containsKey(located.code()) ? located.code()
                    : "PROFILE_CONSTRUCTION_FAILED";
            return BindingDiagnostic.error(code, message, true, location);
        }

        /** Authored document segments for a structured construction failure; never inferred from message text. */
        private static BindingDocumentPath profilePath(BindingValidationException located, BindingIrV1 ir) {
            BindingDocumentPath path = BindingDocumentPath.ROOT;
            if (located.bindingIndex() == null) return path;
            path = path.field("bindings").index(located.bindingIndex());
            var binding = ir.bindings().get(located.bindingIndex());
            BindingDocumentPath target = path.field("to");
            if (binding.target() instanceof BindingIrV1.EffectTarget) target = target.field("effect");
            String part = located.part() == null ? "" : located.part();
            switch (part) {
                case "source-event" -> path = path.field("from").field("event");
                case "condition", "expression", "lookup-key", "lookup-operand" -> {
                    if (located.clauseIndex() == null) break;
                    path = path.field("when").index(located.clauseIndex());
                    if (part.equals("expression")) path = path.field("expr");
                    if (part.equals("lookup-key")) path = path.field("lookup").field("key");
                    if (part.equals("lookup-operand")) path = path.field("lookup").field("eq");
                }
                case "mapping" -> {
                    path = located.field() == null ? target.field("map") : target.field("map").field(located.field());
                    for (Integer argument : located.argumentPath()) path = path.field("args").index(argument);
                }
                case "target-command" -> path = target.field("command");
                case "target", "target-field" -> {
                    boolean assigned = located.field() != null && binding.target().mapping().fields().stream()
                            .anyMatch(assignment -> assignment.field().equals(located.field()));
                    path = assigned ? target.field("map").field(located.field()) : target.field("map");
                }
                case "raw-body" -> path = target.field("rawBody");
                default -> { }
            }
            return path;
        }

        private BindingDiagnostic classify(RuntimeException error) {
            if (diagnostic != null) return diagnostic;
            if (error instanceof BindingAuthoringException authored) {
                // Compiler paths are relative to the parsed root, which already includes any composite wrapper.
                return authored.diagnostic();
            }
            String detail = BindingCli.diagnostic(error);
            String message = error.getMessage() == null ? "" : error.getMessage();
            if (message.equals("binding input file exceeds size limit")) {
                String role = inputs.snapshot(List.of()).stream().filter(value -> value.state().equals("oversized"))
                        .map(BindingInputs.Observed::role).findFirst().orElse(stage);
                return BindingDiagnostic.error("INPUT_TOO_LARGE", detail, false,
                        BindingDiagnostic.Location.input(role));
            }
            return switch (stage) {
                case "ir" -> BindingDiagnostic.error("IR_INVALID", detail, false,
                        BindingDiagnostic.Location.input("ir"));
                case "plugins" -> BindingDiagnostic.error("PLUGIN_CATALOG_INVALID", detail, true,
                        BindingDiagnostic.Location.input("plugins"));
                case "continuation" -> BindingDiagnostic.error("CONTINUATION_MISMATCH", detail, false,
                        BindingDiagnostic.Location.input("priorResult"));
                case "execution" -> message.startsWith("fixture admission rejected")
                        ? BindingDiagnostic.error("FIXTURE_ADMISSION_REJECTED", detail, true,
                                BindingDiagnostic.Location.input("fixture"))
                        : BindingDiagnostic.error("FIXTURE_INVALID", detail, true,
                                BindingDiagnostic.Location.input("fixture"));
                default -> BindingDiagnostic.error("UNCLASSIFIED", detail, true,
                        BindingDiagnostic.Location.input(stage));
            };
        }

        private BindingDiagnostic jsonDiagnostic(JsonProcessingException error) {
            List<Object> segments = new ArrayList<>();
            if (error instanceof JsonMappingException mapping) {
                for (var reference : mapping.getPath()) {
                    segments.add(reference.getFieldName() == null ? (Object) reference.getIndex()
                            : reference.getFieldName());
                }
            }
            var location = error.getLocation();
            BindingDocumentPath path = new BindingDocumentPath(segments);
            var structured = new BindingDiagnostic.Location(stage, path.segments(), path.toString(),
                    location == null || location.getLineNr() < 1 ? null : location.getLineNr(),
                    location == null || location.getColumnNr() < 1 ? null : location.getColumnNr(), null, null, null,
                    null, null, null, null, null, null, null);
            // Host constructors validate these records; their text can repeat supplied values.
            if (stage.equals("context") && error instanceof ValueInstantiationException instantiation
                    && instantiation.getCause() instanceof IllegalArgumentException invalid) {
                return BindingDiagnostic.error("CONTEXT_INVALID", invalid.getMessage(), true, structured);
            }
            if (stage.equals("fixture") && error instanceof ValueInstantiationException instantiation
                    && instantiation.getCause() instanceof IllegalArgumentException invalid) {
                return BindingDiagnostic.error("FIXTURE_INVALID", invalid.getMessage(), true, structured);
            }
            // Jackson messages can quote input values, so JSON failures carry only the code and structured path.
            return BindingDiagnostic.error("JSON_INVALID", null, false, structured);
        }

        private Map<String, Object> report(int code) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", BindingReport.SCHEMA);
            report.put("operation", parsed.command());
            report.put("operationOutcome", code == AppChainDevtoolsCli.EXIT_OK ? "completed" : "failed");
            report.put("assurance", BindingReport.ASSURANCE);
            report.putAll(BindingToolIdentity.toolIdentity());
            report.put("catalog", catalogIdentity);
            List<String> expected = new ArrayList<>(List.of("document", "context"));
            if (parsed.command().equals("dry-run")) expected.add("fixture");
            if (parsed.priorResult() != null) expected.add("priorResult");
            report.put("inputs", inputs.snapshot(expected).stream().map(observed -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("role", observed.role());
                value.put("state", observed.state());
                value.put("bytes", observed.bytes() < 0 ? null : Long.toString(observed.bytes()));
                value.put("sha256", observed.sha256());
                return value;
            }).toList());
            report.put("documentFormat", parsed.ir() ? "ir-hex" : "yaml");
            report.put("result", code == AppChainDevtoolsCli.EXIT_OK ? result : null);
            report.put("diagnostics", diagnostic == null ? List.of() : List.of(diagnostic));
            report.put("receiptCodes", BindingAuthoringLanguage.receiptCodes());
            return report;
        }
    }

    /**
     * Computes a value that exists only in the report. A failure yields {@code null} so requesting a report can
     * never change the operation's outcome, stderr or exit status.
     */
    private static <T> T reportOnly(java.util.function.Supplier<T> value) {
        try {
            return value.get();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /**
     * Report-only profile identity from the catalog-created machine, cross-checked against its canonical active
     * profile bytes. Returns {@code null} rather than guessing when the machine does not publish a fixed digest.
     */
    private static Map<String, Object> profileIdentity(AppStateMachine machine) {
        Object digest = machine.operationalStatus().get("activeProfileDigest");
        if (!(digest instanceof String value) || !value.matches("[0-9a-f]{64}")) return null;
        byte[] bytes = machine.query("composite/active-profile-v1", new byte[0], new AppQueryContext() {
            @Override public Optional<byte[]> get(byte[] key) { return Optional.empty(); }
            @Override public byte[] stateRoot() { return new byte[32]; }
            @Override public long committedHeight() { return 0; }
        });
        CompositeProfile profile = CompositeProfileCodec.decode(bytes);
        if (!HexFormat.of().formatHex(profile.digest()).equals(value)) {
            throw new IllegalArgumentException("active profile bytes do not match the published profile digest");
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("digestHex", value);
        identity.put("schemaVersion", profile.schemaVersion());
        identity.put("executionVersion", profile.profileVersion());
        return identity;
    }

    private static Map<String, Object> rehearsalReport(BindingDryRun.Fixture fixture, BindingDryRun.Result executed,
                                                       String identity) {
        Map<String, Object> rehearsal = new LinkedHashMap<>();
        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("height", Long.toString(fixture.height()));
        assumptions.put("timestamp", Long.toString(fixture.timestamp()));
        assumptions.put("stateRootHex", fixture.stateRootHex().toLowerCase());
        assumptions.put("pendingEffects", Long.toString(fixture.pendingEffects()));
        rehearsal.put("assumptions", assumptions);
        rehearsal.put("executionIdentity", identity);
        rehearsal.put("assurance", executed.assurance());
        rehearsal.put("postStateSha256", BindingReport.postStateDigest(executed.postState()));
        rehearsal.put("stateChangeCount", executed.stateChanges().size());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int index = 0; index < fixture.messages().size(); index++) {
            var message = fixture.messages().get(index);
            var receipt = executed.receipts().get(index);
            byte[] bytes = HexFormat.of().parseHex((String) receipt.get("receiptHex"));
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("messageIndex", index);
            value.put("messageIdHex", message.messageIdHex().toLowerCase());
            value.put("topic", message.topic());
            value.put("disposition", executed.dispositions().get(index));
            value.put("receiptHex", receipt.get("receiptHex"));
            value.put("receipt", BindingReport.receiptView(bytes));
            messages.add(value);
        }
        rehearsal.put("messages", messages);
        rehearsal.put("effects", executed.effects().stream().map(effect -> {
            // Explicit field order: the dry-run effect maps are unordered.
            Map<String, Object> value = new LinkedHashMap<>();
            for (String key : List.of("effectId", "type", "payloadHex", "gate", "result", "scope")) {
                value.put(key, effect.get(key));
            }
            value.put("expiryBlocks", String.valueOf(effect.get("expiryBlocks")));
            return value;
        }).toList());
        return rehearsal;
    }

    private static BindingIrV1 decode(String source) {
        String hex = source.strip();
        if (hex.length() > 131_072) throw new IllegalArgumentException("binding IR hex exceeds limit");
        return BindingIrV1.decode(HexFormat.of().parseHex(hex));
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
        return new BindingInputs().read("input", path, maximum);
    }

    static String diagnostic(Exception error) {
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
            digest.update("yano-binding-rehearsal-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            digest.update(ir.encode());
            digest.update(CANONICAL_JSON.writeValueAsBytes(context));
            digest.update(CANONICAL_JSON.writeValueAsBytes(manifest));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Arguments(String command, Path document, Path plugins, Path context, Path fixture,
                             Path priorResult, boolean ir, boolean continuationOnly, Path report) {
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
                    if (!Set.of("--plugins-directory", "--context", "--fixture", "--prior-result", "--report")
                            .contains(argument) || i + 1 == args.length || args[i + 1].startsWith("--")
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
            if (args[0].equals("graph") && values.containsKey("--report")) {
                throw new Usage("--report is allowed only for compile, validate and dry-run");
            }
            return new Arguments(args[0], Path.of(document), path(values.get("--plugins-directory")),
                    path(values.get("--context")), path(values.get("--fixture")),
                    path(values.get("--prior-result")), ir, continuationOnly, path(values.get("--report")));
        }
        private static Path path(String value) { return value == null ? null : Path.of(value); }
    }

    static final class Usage extends IllegalArgumentException {
        Usage(String message) { super(message); }
    }
}
