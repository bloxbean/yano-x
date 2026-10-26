package org.yanoproject.x.appchain.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The chains this node leads anchoring for (a local role, never sealed) and the settings that makes the node
 * apply. The wallet seed lives in {@code secrets/anchor.key}, separate from the member key.
 */
final class Anchoring {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record Lead(String app, String chain, String submittedBootstrap) {
    }

    private Anchoring() {
    }

    static List<Lead> leads(NodeDir node) {
        Path file = node.path("anchor.json");
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return List.of(JSON.readValue(file.toFile(), Lead[].class));
        } catch (IOException error) {
            throw CliException.failure("Cannot read " + file + ": " + error.getMessage());
        }
    }

    static void save(NodeDir node, List<Lead> leads) {
        try {
            NodeDir.write(node.path("anchor.json"), JSON.writeValueAsString(leads) + "\n");
        } catch (IOException error) {
            throw CliException.failure("Cannot write anchor.json: " + error.getMessage());
        }
    }

    static void put(NodeDir node, Lead lead) {
        List<Lead> leads = new ArrayList<>(leads(node));
        leads.removeIf(existing -> existing.app().equals(lead.app()) && existing.chain().equals(lead.chain()));
        leads.add(lead);
        save(node, leads);
    }

    static void remove(NodeDir node, String app, String chain) {
        List<Lead> leads = new ArrayList<>(leads(node));
        leads.removeIf(existing -> existing.app().equals(app) && existing.chain().equals(chain));
        save(node, leads);
    }

    static Lead lead(NodeDir node, String app, String chain) {
        return leads(node).stream().filter(lead -> lead.app().equals(app) && lead.chain().equals(chain))
                .findFirst().orElse(null);
    }

    static boolean leadsAny(NodeDir node) {
        return !leads(node).isEmpty();
    }

    /** Node-local anchor settings for one chain, relative to its prefix; empty unless this node leads it. */
    static Map<String, String> chainSettings(NodeDir node, String app, String chain) {
        Lead lead = lead(node, app, chain);
        Map<String, String> settings = new TreeMap<>();
        if (lead == null) return settings;
        settings.put("anchor.enabled", "true");
        settings.put("anchor.signing-key", "${YANO_APPCHAIN_ANCHOR_KEY}");
        return settings;
    }

    static boolean hasWallet(NodeDir node) {
        return Files.isRegularFile(node.path("secrets", "anchor.key"));
    }
}
