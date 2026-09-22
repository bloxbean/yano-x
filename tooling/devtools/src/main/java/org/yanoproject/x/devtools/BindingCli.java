package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.x.composite.contracts.BindingIrV1;

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
              --plugins-directory <directory>  exact manifested runtime bundles (required except graph --ir)
              --context <context.json>         explicit chainId/settings/consensusProfile/membership
              --ir                             input is canonical binding IR hex, not YAML
              --fixture <fixture.json>         dry-run only; one fixture block and physical base state
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
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

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
                BindingIrV1 ir = decoded == null ? BindingDocumentCompiler.compile(input, session) : decoded;
                AppStateMachine machine = session.validate(ir);
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
                        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                                BindingDryRun.execute(machine, context, fixture)));
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
        } catch (IllegalArgumentException | IllegalStateException error) {
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

    static String read(Path path, int maximum) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IllegalArgumentException("binding input file exceeds size limit");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static String diagnostic(Exception error) {
        Throwable detail = error;
        for (int depth = 0; depth < 8 && detail.getCause() != null && detail.getCause() != detail; depth++) {
            // Preserve authored-field and binding context instead of exposing only a low-level parser cause.
            String contextual = detail.getMessage();
            if (contextual != null && (contextual.startsWith("$") || contextual.contains("binding '"))) break;
            detail = detail.getCause();
        }
        String message = detail.getMessage() == null ? detail.getClass().getSimpleName() : detail.getMessage();
        message = message.lines().findFirst().orElse("invalid input").replaceAll("[\\p{Cntrl}]", " ");
        return message.length() > 512 ? message.substring(0, 512) : message;
    }

    private record Arguments(String command, Path document, Path plugins, Path context, Path fixture, boolean ir) {
        static Arguments parse(String[] args) {
            if (!Set.of("compile", "validate", "graph", "dry-run").contains(args[0])) {
                throw new Usage("unknown bindings command");
            }
            Map<String, String> values = new LinkedHashMap<>();
            String document = null;
            boolean ir = false;
            for (int i = 1; i < args.length; i++) {
                String argument = args[i];
                if (argument.equals("--ir")) {
                    if (ir) throw new Usage("duplicate --ir");
                    ir = true;
                } else if (argument.startsWith("--")) {
                    if (!Set.of("--plugins-directory", "--context", "--fixture").contains(argument)
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
            return new Arguments(args[0], Path.of(document), path(values.get("--plugins-directory")),
                    path(values.get("--context")), path(values.get("--fixture")), ir);
        }
        private static Path path(String value) { return value == null ? null : Path.of(value); }
    }

    private static final class Usage extends IllegalArgumentException {
        Usage(String message) { super(message); }
    }
}
