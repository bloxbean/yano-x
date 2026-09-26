package org.yanoproject.x.devtools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.transition.CommandDescriptor;
import org.yanoproject.api.appchain.transition.ConfigurationDescriptor;
import org.yanoproject.api.appchain.transition.TransitionKernel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code appchain bindings catalog}: editor descriptors, {@code yano-x-binding-authoring-catalog-v1}
 * (ADR-031.2 contract C1).
 *
 * <p>Selectors are enumerated without constructing providers. Only explicitly requested instances are probed:
 * the components of a supplied document (with their exact authored configuration), each {@code --machine}
 * selector with empty configuration, or every selector with {@code --all}. Probing constructs trusted installed
 * plugin code through the host catalog exactly as the compiler does; it installs, activates or trusts nothing.
 * The output describes descriptors for one plugin catalog, context and configuration. It is editing assistance,
 * not plugin trust, runtime availability, profile validation or deployment approval.
 */
final class BindingAuthoringCatalog {
    static final String SCHEMA = "yano-x-binding-authoring-catalog-v1";
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_SELECTORS = 512;
    static final int MAX_INSTANCES = 256;
    /** Binding IR component and machine identifier syntax ({@code BindingIrV1}); other selectors cannot compose. */
    static final Pattern IR_IDENTIFIER = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    static final String ASSURANCE = "Editing assistance for the identified plugin catalog, context and configurations "
            + "only. Not plugin trust, runtime availability, profile validation or deployment approval.";
    private static final String USAGE = "Usage: bindings catalog [<document.yml>] --plugins-directory <directory> "
            + "--context <context.json> [--machine <selector>]... [--all]";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(48)
                    .maxStringLength(2_097_152).maxNumberLength(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    private static final ObjectMapper OUTPUT = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private BindingAuthoringCatalog() { }

    /**
     * Runs {@code catalog} with {@code catalog} at argument zero.
     *
     * @return 0 when the catalog was written, 2 for invalid inputs, 64 for usage or 74 for unreadable inputs
     */
    static int run(String[] args, PrintWriter out, PrintWriter err) {
        Path document = null; Path plugins = null; Path context = null;
        boolean all = false;
        Set<String> machines = new LinkedHashSet<>();
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--all" -> {
                    if (all) return usage(err, "duplicate --all");
                    all = true;
                }
                case "--plugins-directory", "--context", "--machine" -> {
                    if (index + 1 == args.length || args[index + 1].startsWith("--") || args[index + 1].isBlank()) {
                        return usage(err, "missing value for " + argument);
                    }
                    String value = args[++index];
                    if (argument.equals("--machine")) {
                        if (!IR_IDENTIFIER.matcher(value).matches()) {
                            return usage(err, "--machine must be a binding machine identifier");
                        }
                        if (!machines.add(value) || machines.size() > MAX_SELECTORS) {
                            return usage(err, "duplicate or too many --machine selectors");
                        }
                    } else if (argument.equals("--plugins-directory")) {
                        if (plugins != null) return usage(err, "duplicate --plugins-directory");
                        plugins = Path.of(value);
                    } else {
                        if (context != null) return usage(err, "duplicate --context");
                        context = Path.of(value);
                    }
                }
                default -> {
                    if (argument.startsWith("--")) return usage(err, "unknown catalog option");
                    if (document != null) return usage(err, "expected at most one binding document");
                    document = Path.of(argument);
                }
            }
        }
        if (plugins == null || context == null) return usage(err, "plugin directory and context required");
        try {
            out.print(OUTPUT.writeValueAsString(export(document, plugins, context, machines, all)));
            out.println();
            return AppChainDevtoolsCli.EXIT_OK;
        } catch (JsonProcessingException error) {
            err.println("Binding JSON input is invalid: " + BindingCli.diagnostic(error));
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        } catch (IOException error) {
            err.println("Binding input could not be read: " + BindingCli.diagnostic(error));
            return AppChainDevtoolsCli.EXIT_IO;
        } catch (RuntimeException error) {
            err.println("Binding input is invalid: " + BindingCli.diagnostic(error));
            return AppChainDevtoolsCli.EXIT_INVALID_CONFIG;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static int usage(PrintWriter err, String message) {
        err.println(message);
        err.println(USAGE);
        err.flush();
        return AppChainDevtoolsCli.EXIT_USAGE;
    }

    static Map<String, Object> export(Path documentPath, Path plugins, Path contextPath, Set<String> requested,
                                      boolean all) throws IOException {
        BindingInputs inputs = new BindingInputs();
        List<BindingDocumentCompiler.AuthoredComponent> authored = List.of();
        boolean wrapped = false;
        if (documentPath != null) {
            var root = BindingDocumentCompiler.parseDocument(inputs.read("document", documentPath, 1_048_576));
            wrapped = root != null && root.has("composite");
            authored = BindingDocumentCompiler.authoredComponents(root);
        }
        String contextText = inputs.read("context", contextPath, 1_048_576);
        var context = JSON.readValue(contextText, BindingCatalogSession.ContextInput.class);
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("schema", SCHEMA);
        catalog.put("assurance", ASSURANCE);
        catalog.putAll(BindingToolIdentity.toolIdentity());
        try (BindingPluginEnvironment environment = BindingPluginEnvironment.open(plugins)) {
            BindingCatalogSession session = new BindingCatalogSession(environment.providers(), context);
            catalog.put("catalog", BindingToolIdentity.catalogIdentity(environment.catalog()));
            List<BindingInputs.Observed> observed = inputs.snapshot(List.of());
            Map<String, Object> contextIdentity = new LinkedHashMap<>();
            var contextInput = observed.stream().filter(value -> value.role().equals("context")).findFirst()
                    .orElseThrow();
            contextIdentity.put("sha256", contextInput.sha256());
            contextIdentity.put("bytes", Long.toString(contextInput.bytes()));
            contextIdentity.put("chainId", context.chainId());
            catalog.put("context", contextIdentity);
            catalog.put("document", observed.stream().filter(value -> value.role().equals("document"))
                    .findFirst().map(value -> {
                        Map<String, Object> document = new LinkedHashMap<>();
                        document.put("sha256", value.sha256());
                        document.put("bytes", Long.toString(value.bytes()));
                        return document;
                    }).orElse(null));
            catalog.put("language", BindingAuthoringLanguage.language());
            List<String> selectors = session.selectors();
            if (selectors.size() > MAX_SELECTORS) throw new IllegalArgumentException("catalog selector limit exceeded");
            catalog.put("selectors", selectors.stream().map(selector -> selector(session, selector)).toList());
            // Plan every probe first so the instance bound is enforced before any provider is constructed.
            Map<String, Probe> planned = new LinkedHashMap<>();
            Map<String, List<String>> componentIds = new LinkedHashMap<>();
            for (var component : authored) {
                String key = instanceKey(component.machine(), component.configuration());
                componentIds.computeIfAbsent(key, ignored -> new ArrayList<>()).add(component.id());
                planned.putIfAbsent(key, new Probe(component.machine(), component.configuration(), "document",
                        component));
            }
            List<String> explicit = new ArrayList<>(requested);
            if (all) explicit.addAll(selectors);
            for (String machine : explicit) {
                planned.putIfAbsent(instanceKey(machine, Map.of()), new Probe(machine, Map.of(),
                        requested.contains(machine) ? "explicit" : "all", null));
            }
            if (planned.size() > MAX_INSTANCES) throw new IllegalArgumentException("catalog instance limit exceeded");
            List<Map<String, Object>> instances = new ArrayList<>();
            for (var entry : planned.entrySet()) {
                Probe next = entry.getValue();
                Map<String, Object> instance = probe(session, next.machine(), next.configuration(), next.basis(),
                        next.component(), wrapped);
                List<String> ids = componentIds.get(entry.getKey());
                if (ids != null) instance.put("componentIds", List.copyOf(ids));
                instances.add(instance);
            }
            catalog.put("instances", instances);
            Map<String, Object> effects = new LinkedHashMap<>();
            effects.put("payloadSchemas", "unavailable");
            effects.put("note", "effect types are free text; no effect payload schema is published by this catalog");
            catalog.put("effects", effects);
        }
        byte[] encoded = OUTPUT.writeValueAsString(catalog).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("authoring catalog exceeds its byte limit");
        return catalog;
    }

    private static Map<String, Object> selector(BindingCatalogSession session, String selector) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("machineId", selector);
        var provenance = session.provenance(selector);
        Map<String, Object> origin = new LinkedHashMap<>();
        if (provenance.isEmpty()) {
            origin.put("kind", "builtin");
        } else {
            origin.put("kind", "bundle");
            origin.put("bundleId", provenance.get().bundleId());
            origin.put("digest", provenance.get().digest());
            origin.put("digestMode", provenance.get().digestMode().name());
        }
        value.put("origin", origin);
        // Known only when decidable without construction: nested composites and non-IR selectors cannot compose.
        value.put("composable", BindingCatalogSession.MACHINE.equals(selector)
                || !IR_IDENTIFIER.matcher(selector).matches() ? false : null);
        return value;
    }

    private record Probe(String machine, Map<String, Object> configuration, String basis,
                         BindingDocumentCompiler.AuthoredComponent component) { }

    /**
     * Probes one instance. Every failure is recorded as data and never aborts the export: construction failures,
     * linkage failures from a broken bundle and assertion or stack-overflow errors are contained to that probe.
     * Only resource-exhaustion errors of the JVM itself propagate.
     */
    private static Map<String, Object> probe(BindingCatalogSession session, String machine,
                                             Map<String, Object> authored, String basis,
                                             BindingDocumentCompiler.AuthoredComponent component, boolean wrapped) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("machineId", machine);
        instance.put("basis", basis);
        instance.put("authoredConfiguration", typedMap(authored));
        BindingDiagnostic.Location location = component == null ? BindingDiagnostic.Location.input("catalog")
                : BindingDiagnostic.Location.fromSegments("document",
                        wrapped ? component.path().under("composite") : component.path(), null, null);
        if (BindingCatalogSession.MACHINE.equals(machine)) {
            instance.put("status", "not-composable");
            instance.put("diagnostic", BindingDiagnostic.error("MACHINE_NOT_COMPOSABLE",
                    "nested declarative composites are unsupported", false, location));
            return instance;
        }
        if (!IR_IDENTIFIER.matcher(machine).matches()) {
            instance.put("status", "not-composable");
            instance.put("diagnostic", BindingDiagnostic.error("MACHINE_NOT_COMPOSABLE",
                    "machine selector is not a binding machine identifier", false, location));
            return instance;
        }
        AppStateMachine constructed;
        try {
            constructed = session.component(machine, authored);
        } catch (Throwable failure) {
            rethrowResourceExhaustion(failure);
            instance.put("status", "construction-failed");
            instance.put("diagnostic", BindingDiagnostic.error("MACHINE_CONSTRUCTION_FAILED", detail(failure), true,
                    location));
            return instance;
        }
        try {
            return describe(instance, constructed, session, machine, authored, location);
        } catch (Throwable failure) {
            rethrowResourceExhaustion(failure);
            // A kernel or descriptor callback failed after construction: record it for this probe only.
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("machineId", machine);
            failed.put("basis", basis);
            failed.put("authoredConfiguration", typedMap(authored));
            failed.put("status", "descriptor-failed");
            failed.put("diagnostic", BindingDiagnostic.error("KERNEL_CONTRACT_INVALID", detail(failure), true,
                    location));
            return failed;
        }
    }

    private static void rethrowResourceExhaustion(Throwable failure) {
        if (failure instanceof VirtualMachineError error && !(failure instanceof StackOverflowError)) throw error;
    }

    private static String detail(Throwable failure) {
        return BindingCli.diagnostic(failure instanceof Exception exception ? exception
                : new IllegalStateException(failure.getClass().getSimpleName()));
    }

    /**
     * Describes a constructed instance. The configuration descriptor comes from the authored-configuration kernel,
     * exactly as the compiler normalizes; every other descriptor comes from a kernel constructed with the
     * normalized configuration, which is what compile-time event typing and the profile use.
     */
    private static Map<String, Object> describe(Map<String, Object> instance, AppStateMachine constructed,
                                                BindingCatalogSession session, String machine,
                                                Map<String, Object> authored, BindingDiagnostic.Location location) {
        var kernelOption = constructed.transitionKernel();
        if (kernelOption.isEmpty()) {
            instance.put("status", "not-composable");
            instance.put("diagnostic", BindingDiagnostic.error("MACHINE_NOT_COMPOSABLE",
                    "catalog component has no transition kernel: " + machine, false, location));
            return instance;
        }
        TransitionKernel<?, ?> kernel = kernelOption.get();
        ConfigurationDescriptor descriptor = kernel.configuration();
        instance.put("configurationDescriptor", descriptor.settings().stream().map(setting -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", setting.name());
            value.put("type", type(setting.type()));
            value.put("required", setting.defaultValue() == null);
            value.put("default", setting.defaultValue() == null ? null : typed(setting.defaultValue()));
            return value;
        }).toList());
        Map<String, Object> normalized;
        try {
            normalized = descriptor.normalize(authored);
        } catch (IllegalArgumentException invalid) {
            instance.put("applicationVersion", constructed.capabilityManifest().applicationVersion());
            instance.put("status", "requires-configuration");
            instance.put("diagnostic", BindingDiagnostic.error("CONFIGURATION_NOT_NORMALIZED",
                    BindingCli.diagnostic(invalid), true, location));
            return instance;
        }
        if (!typedMap(normalized).equals(typedMap(authored))) {
            AppStateMachine normalizedMachine;
            try {
                normalizedMachine = session.component(machine, normalized);
            } catch (Throwable failure) {
                rethrowResourceExhaustion(failure);
                instance.put("status", "construction-failed");
                instance.put("diagnostic", BindingDiagnostic.error("MACHINE_CONSTRUCTION_FAILED", detail(failure),
                        true, location));
                return instance;
            }
            constructed = normalizedMachine;
            var normalizedKernel = constructed.transitionKernel();
            if (normalizedKernel.isEmpty()) {
                instance.put("status", "not-composable");
                instance.put("diagnostic", BindingDiagnostic.error("MACHINE_NOT_COMPOSABLE",
                        "catalog component has no transition kernel: " + machine, false, location));
                return instance;
            }
            kernel = normalizedKernel.get();
        }
        instance.put("applicationVersion", constructed.capabilityManifest().applicationVersion());
        instance.put("status", "available");
        instance.put("normalizedConfiguration", typedMap(normalized));
        instance.put("events", kernel.events().stream().map(event -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("eventId", event.eventId());
            value.put("fields", fields(event.fields()));
            return value;
        }).toList());
        instance.put("commands", kernel.commands().stream().map(command -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("commandName", command.commandName());
            value.put("layout", command.layout().name());
            value.put("opCode", Long.toString(command.opCode()));
            value.put("fields", fields(command.fields()));
            return value;
        }).toList());
        instance.put("rawBodyTarget", rawBodyTarget(kernel));
        instance.put("readParticipants", List.copyOf(kernel.readParticipants()));
        return instance;
    }

    /**
     * Raw-body forwarding restriction for a target kernel. Mirrors the profile rule that raw bytes may select any
     * command opcode, so any evidence field on any command forbids raw forwarding to that kernel.
     */
    static String rawBodyTarget(TransitionKernel<?, ?> kernel) {
        return kernel.commands().stream().flatMap(command -> command.fields().stream())
                .anyMatch(field -> field.role() == CommandDescriptor.Role.EVIDENCE) ? "forbidden-evidence" : "allowed";
    }

    private static List<Map<String, Object>> fields(List<CommandDescriptor.Field> fields) {
        return fields.stream().map(field -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", field.name());
            value.put("type", type(field.type()));
            value.put("required", field.required());
            value.put("role", field.role().name().toLowerCase(java.util.Locale.ROOT));
            return value;
        }).toList();
    }

    private static String type(org.yanoproject.api.appchain.transition.TransitionScalars.Type type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Canonical TypedScalar map in name order; values are Long, String, byte[] or Boolean. */
    static Map<String, Object> typedMap(Map<String, Object> values) {
        Map<String, Object> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(key, typed(value)));
        return result;
    }

    static Map<String, Object> typed(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Long number) { result.put("type", "integer"); result.put("value", Long.toString(number)); }
        else if (value instanceof String text) { result.put("type", "text"); result.put("value", text); }
        else if (value instanceof byte[] bytes) {
            result.put("type", "bytes");
            result.put("hex", HexFormat.of().formatHex(bytes));
        }
        else if (value instanceof Boolean flag) { result.put("type", "boolean"); result.put("value", flag); }
        else throw new IllegalArgumentException("unsupported configuration scalar");
        return result;
    }

    /** Order-insensitive identity of a machine plus its exact typed authored configuration. */
    private static String instanceKey(String machine, Map<String, Object> configuration) {
        try {
            return machine + "\u0000" + COMPACT.writeValueAsString(typedMap(configuration));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
