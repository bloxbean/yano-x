package org.yanoproject.x.appchain.formats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The coordinator's editable definition ({@code <app>.draft.yaml}). Member cards sign its invite code,
 * not its content, so it can change until seal.
 */
public record Draft(
        String name,
        String invite,
        String network,
        List<String> operators,
        String threshold,
        String membership,
        String sequencing,
        List<Chain> chains) {

    public static final List<String> THRESHOLDS = List.of("majority", "two-thirds", "all");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> FIELDS = Set.of(
            "name", "invite", "network", "operators", "threshold", "membership", "sequencing", "chains");
    private static final Set<String> CHAIN_FIELDS = Set.of(
            "id", "recipe", "capabilities", "answers", "bindings", "composite", "authenticatedMap",
            "anchor", "settings");

    /** One chain: one state machine by recipe, or a composite of several joined by bindings. */
    public record Chain(
            String id,
            String recipe,
            List<String> capabilities,
            Map<String, String> answers,
            String bindings,
            JsonNode composite,
            JsonNode authenticatedMap,
            Anchor anchor,
            Map<String, String> settings) {

        public Chain {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            answers = answers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answers));
            settings = settings == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            if (recipe == null || recipe.isBlank()) throw new FormatException("Chain '" + id + "' needs a recipe");
        }
    }

    /** Anchor policy only; who leads is a local role and is never sealed. */
    public record Anchor(String mode, int everyBlocks) {
    }

    /**
     * The reusable part of a draft for {@code new --from}: its chains, plus optional defaults. A template has
     * no invite or operators of its own; any draft can also serve as one.
     */
    public record Template(String network, String threshold, String membership, String sequencing,
                           List<Chain> chains) {
        public Template {
            if (network != null) Names.network(network);
            if (chains == null || chains.isEmpty()) throw new FormatException("The template defines no chains");
            chains = List.copyOf(chains);
        }
    }

    public Draft {
        Names.name(name, "Application name");
        Names.invite(invite);
        Names.network(network);
        if (operators == null || operators.isEmpty()) {
            throw new FormatException("The draft lists no operators; add them under 'operators:'");
        }
        if (operators.size() > Names.MAX_MEMBERS) {
            throw new FormatException("At most " + Names.MAX_MEMBERS + " operators are supported");
        }
        Set<String> seen = new HashSet<>();
        for (String operator : operators) {
            if (!seen.add(Names.name(operator, "Operator name"))) {
                throw new FormatException("Operator '" + operator + "' is listed twice");
            }
        }
        operators = List.copyOf(operators);
        threshold = threshold == null ? "majority" : threshold;
        thresholdFor(threshold, operators.size());
        membership = membership == null ? "governed" : membership;
        if (!List.of("governed", "static").contains(membership)) {
            throw new FormatException("membership must be 'governed' or 'static'");
        }
        sequencing = sequencing == null ? "fixed" : sequencing;
        if (!List.of("fixed", "rotating").contains(sequencing)) {
            throw new FormatException("sequencing must be 'fixed' (the lead proposes) or 'rotating'");
        }
        if (chains == null || chains.isEmpty()) throw new FormatException("The draft defines no chains");
        if (chains.size() > Names.MAX_CHAINS) {
            throw new FormatException("At most " + Names.MAX_CHAINS + " chains are supported");
        }
        Set<String> ids = new HashSet<>();
        for (Chain chain : chains) {
            if (!ids.add(Names.chainId(chain.id()))) {
                throw new FormatException("Chain '" + chain.id() + "' is defined twice");
            }
        }
        chains = List.copyOf(chains);
    }

    public String lead() {
        return operators.getFirst();
    }

    /** Signatures needed per block for {@code members} members. */
    public static int thresholdFor(String policy, int members) {
        return switch (policy) {
            case "majority" -> members / 2 + 1;
            case "two-thirds" -> (2 * members + 2) / 3;
            case "all" -> members;
            default -> {
                int value;
                try {
                    value = Integer.parseInt(policy);
                } catch (NumberFormatException invalid) {
                    throw new FormatException("threshold must be majority, two-thirds, all, or a number");
                }
                if (value <= members / 2 || value > members) {
                    throw new FormatException("threshold " + value + " is not safe for " + members
                            + " members; it must be more than half and at most all of them");
                }
                yield value;
            }
        };
    }

    public static Draft parse(String yaml) {
        ObjectNode object = root(yaml, "draft");
        return new Draft(
                text(object, "name"),
                text(object, "invite"),
                text(object, "network"),
                strings(object.path("operators")),
                optional(object, "threshold"),
                optional(object, "membership"),
                optional(object, "sequencing"),
                chains(object));
    }

    /** Reads a template; the name, invite and operators of a full draft are ignored. */
    public static Template template(String yaml) {
        ObjectNode object = root(yaml, "template");
        return new Template(optional(object, "network"), optional(object, "threshold"),
                optional(object, "membership"), optional(object, "sequencing"), chains(object));
    }

    private static ObjectNode root(String yaml, String what) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException error) {
            throw new FormatException("The " + what + " is not valid YAML: "
                    + error.getMessage().lines().findFirst().orElse(""), error);
        }
        if (!(root instanceof ObjectNode object)) throw new FormatException("The " + what + " must be a YAML mapping");
        unknown(object, FIELDS, what);
        return object;
    }

    private static String optional(ObjectNode object, String field) {
        return object.hasNonNull(field) ? object.get(field).asText() : null;
    }

    private static List<Chain> chains(ObjectNode object) {
        List<Chain> chains = new ArrayList<>();
        JsonNode chainNodes = object.path("chains");
        if (!chainNodes.isArray()) throw new FormatException("'chains' must be a list");
        for (JsonNode node : chainNodes) {
            if (!(node instanceof ObjectNode chain)) throw new FormatException("Each chain must be a mapping");
            unknown(chain, CHAIN_FIELDS, "chain");
            Anchor anchor = null;
            if (chain.hasNonNull("anchor")) {
                JsonNode policy = chain.get("anchor");
                String mode = policy.path("mode").asText("script");
                if (!List.of("script", "metadata").contains(mode)) {
                    throw new FormatException("anchor mode must be 'script' or 'metadata'");
                }
                int every = policy.path("every-blocks").asInt(10);
                if (every < 1) throw new FormatException("anchor every-blocks must be at least 1");
                anchor = new Anchor(mode, every);
            }
            chains.add(new Chain(
                    text(chain, "id"),
                    text(chain, "recipe"),
                    strings(chain.path("capabilities")),
                    map(chain.path("answers")),
                    chain.hasNonNull("bindings") ? chain.get("bindings").asText() : null,
                    chain.hasNonNull("composite") ? chain.get("composite") : null,
                    chain.hasNonNull("authenticatedMap") ? chain.get("authenticatedMap") : null,
                    anchor,
                    map(chain.path("settings"))));
        }
        return chains;
    }

    /** A commented YAML file for people; {@link #parse} reads it back to an equal draft. */
    public String toYaml() {
        StringBuilder out = new StringBuilder();
        out.append("# App-chain draft. Edit freely until `yano.sh appchain seal`.\n");
        out.append("# Operators send member cards made with: yano.sh appchain request ")
                .append(name).append(".draft.yaml\n");
        out.append("name: ").append(name).append('\n');
        out.append("invite: ").append(quote(invite)).append("          # written by `new`; do not edit\n");
        out.append("network: ").append(network).append("                # devnet | preview | preprod | mainnet\n");
        out.append("operators: [").append(String.join(", ", operators)).append("]    # the first is the lead\n");
        out.append("threshold: ").append(threshold)
                .append("            # signatures per block: majority | two-thirds | all | a number\n");
        out.append("membership: ").append(membership).append('\n');
        out.append("sequencing: ").append(sequencing)
                .append("              # fixed: the lead proposes every block | rotating: members take turns\n");
        out.append("chains:\n");
        for (Chain chain : chains) {
            out.append("  - id: ").append(Character.isLetter(chain.id().charAt(0)) ? chain.id() : quote(chain.id()))
                    .append('\n');
            out.append("    recipe: ").append(chain.recipe()).append('\n');
            if (!chain.capabilities().isEmpty()) {
                out.append("    capabilities: [").append(String.join(", ", chain.capabilities())).append("]\n");
            }
            if (!chain.answers().isEmpty()) {
                out.append("    answers:\n");
                chain.answers().forEach((key, value) -> out.append("      ").append(key).append(": ")
                        .append(quote(value)).append('\n'));
            }
            if (chain.bindings() != null) out.append("    bindings: ").append(quote(chain.bindings())).append('\n');
            if (chain.composite() != null || chain.authenticatedMap() != null) {
                ObjectNode extra = YAML.createObjectNode();
                if (chain.composite() != null) extra.set("composite", chain.composite());
                if (chain.authenticatedMap() != null) extra.set("authenticatedMap", chain.authenticatedMap());
                out.append(indent(yaml(extra), "    "));
            }
            if (chain.anchor() != null) {
                out.append("    anchor: {mode: ").append(chain.anchor().mode()).append(", every-blocks: ")
                        .append(chain.anchor().everyBlocks()).append("}\n");
            }
            if (!chain.settings().isEmpty()) {
                out.append("    settings:\n");
                chain.settings().forEach((key, value) -> out.append("      ").append(key).append(": ")
                        .append(quote(value)).append('\n'));
            }
        }
        return out.toString();
    }

    private static String yaml(JsonNode node) {
        try {
            return YAML.writeValueAsString(node).replaceFirst("^---\\s*\\n", "");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String indent(String text, String prefix) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) out.append(prefix).append(line).append('\n');
        return out.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void unknown(ObjectNode node, Set<String> allowed, String what) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new FormatException("Unknown " + what + " field '" + field + "'; allowed: "
                        + String.join(", ", allowed.stream().sorted().toList()));
            }
        });
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            throw new FormatException("Missing '" + field + "'");
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) return values;
        if (!node.isArray()) throw new FormatException("Expected a list, found " + node.getNodeType());
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static Map<String, String> map(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) return values;
        if (!node.isObject()) throw new FormatException("Expected a mapping, found " + node.getNodeType());
        node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }
}
