package org.yanoproject.x.appchain.formats;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The sealed {@code <app>.chain} file: a {@link Genesis} section that the chain fingerprint covers, and a
 * {@link Peer} section with addresses that may change without a new chain.
 */
public record SealedApp(String format, Genesis genesis, Map<String, Peer> peers) {
    public static final String FORMAT = "yano-appchain-chain-v1";
    private static final byte[] GENESIS_ID_DOMAIN =
            "yano-x-appchain-genesis-v1\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * Everything consensus depends on, fixed at seal. {@code bundles} maps every plugin bundle id of the
     * sealing distribution to the SHA-256 of its JAR; {@code settings} are node-wide settings.
     */
    public record Genesis(
            String name,
            String invite,
            String network,
            String yanoVersion,
            String yanoXVersion,
            String lead,
            String membership,
            int threshold,
            List<Member> members,
            Map<String, String> bundles,
            Map<String, String> settings,
            List<ChainGenesis> chains) {

        public Genesis {
            Names.name(name, "Application name");
            Names.invite(invite);
            Names.network(network);
            members = List.copyOf(members);
            chains = List.copyOf(chains);
            settings = new TreeMap<>(settings == null ? Map.of() : settings);
            bundles = new TreeMap<>(bundles == null ? Map.of() : bundles);
            if (members.isEmpty() || !members.getFirst().name().equals(lead)) {
                throw new FormatException("The lead must be the first member");
            }
            Set<String> names = new HashSet<>();
            Set<String> keys = new HashSet<>();
            for (Member member : members) {
                if (!names.add(member.name()) || !keys.add(member.publicKey())) {
                    throw new FormatException("Member '" + member.name() + "' or its key appears twice");
                }
            }
            if (threshold <= members.size() / 2 || threshold > members.size()) {
                throw new FormatException("Sealed threshold " + threshold + " is not safe for "
                        + members.size() + " members");
            }
            // A sealed value must never reference the joining node's environment, which holds its secrets.
            settings.forEach((key, value) -> requireLiteral(key, value));
            for (ChainGenesis chain : chains) {
                chain.settings().forEach((key, value) -> requireLiteral(chain.id() + " " + key, value));
                chain.nodeSettings().forEach((key, value) -> {
                    if (!key.equals("peers") && !key.equals("signing-key")) requireLiteral(chain.id() + " " + key, value);
                });
            }
            // The roster operators compare must be the member set every chain actually runs.
            String roster = String.join(",", members.stream().map(Member::publicKey).toList());
            for (ChainGenesis chain : chains) {
                Map<String, String> values = chain.settings();
                if (!chain.id().equals(values.get("chain-id"))
                        || !roster.equals(values.get("members"))
                        || !Integer.toString(threshold).equals(values.get("threshold"))
                        || "fixed".equals(values.get("sequencer.mode"))
                        && !members.getFirst().publicKey().equals(values.get("sequencer.proposer"))) {
                    throw new FormatException("Chain " + chain.id() + " runs a member set, threshold or proposer "
                            + "that differs from the sealed roster; do not join this file");
                }
            }
        }

        private static void requireLiteral(String key, String value) {
            if (value != null && (value.contains("${") || value.contains("$${"))) {
                throw new FormatException("Sealed setting " + key + " refers to the node's environment (" + value
                        + "); a sealed file may hold only literal values. Do not join this file");
            }
        }

        public Member member(String name) {
            return members.stream().filter(member -> member.name().equals(name)).findFirst().orElse(null);
        }

        public Member memberByKey(String publicKey) {
            return members.stream().filter(member -> member.publicKey().equals(publicKey))
                    .findFirst().orElse(null);
        }

        public ChainGenesis chain(String id) {
            return chains.stream().filter(chain -> chain.id().equals(id)).findFirst()
                    .orElseThrow(() -> new FormatException("Application " + name + " has no chain '" + id
                            + "'; it has " + String.join(", ", chains.stream().map(ChainGenesis::id).toList())));
        }
    }

    public record Member(String name, String publicKey) {
        public Member {
            Names.name(name, "Operator name");
            if (!Ed25519.isKey(publicKey)) throw new FormatException("Member " + name + " has an invalid key");
        }
    }

    /**
     * One chain: its exact consensus settings (relative to {@code yano.app-chain.chains[i].}), the node-local
     * settings template the recipe needs, and the plugin bundles it runs on.
     */
    public record ChainGenesis(
            String id,
            String recipe,
            Map<String, String> settings,
            Map<String, String> nodeSettings,
            List<String> bundles) {
        public ChainGenesis {
            Names.chainId(id);
            settings = new TreeMap<>(settings);
            nodeSettings = new TreeMap<>(nodeSettings == null ? Map.of() : nodeSettings);
            bundles = List.copyOf(bundles == null ? List.of() : bundles);
        }

        public String genesisId() {
            return settings.get("state.genesis-id");
        }
    }

    /** Where other members reach this operator. */
    public record Peer(String node, String http) {
        public Peer {
            Names.nodeAddress(node);
            Names.httpAddress(http);
        }
    }

    public SealedApp {
        if (!FORMAT.equals(format)) {
            throw new FormatException("Not a sealed application (format '" + format + "'); expected " + FORMAT);
        }
        peers = new TreeMap<>(peers);
        for (Member member : genesis.members()) {
            if (!peers.containsKey(member.name())) {
                throw new FormatException("No address for member " + member.name());
            }
        }
    }

    /** Full SHA-256 of the canonical genesis section. */
    public String fingerprint() {
        return Canonical.hex(Canonical.sha256(Canonical.bytes(genesis)));
    }

    /** The first 128 bits of the fingerprint in eight groups, for reading aloud. */
    public String displayFingerprint() {
        return display(fingerprint());
    }

    public static String display(String fingerprint) {
        return Canonical.grouped(fingerprint.substring(0, 32));
    }

    /** Accepts a full or displayed fingerprint, with or without dashes. */
    public boolean matches(String expected) {
        String normalized = expected == null ? "" : expected.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.length() >= 32 && fingerprint().startsWith(normalized);
    }

    /**
     * The D2 genesis id for a chain whose state machine does not derive its own:
     * {@code SHA-256(domain || invite || 0 || chainId || 0 || canonical(input))}.
     */
    public static String genesisId(String invite, String chainId, Object input) {
        return Canonical.hex(Canonical.sha256(GENESIS_ID_DOMAIN,
                invite.getBytes(StandardCharsets.US_ASCII), new byte[]{0},
                chainId.getBytes(StandardCharsets.UTF_8), new byte[]{0},
                Canonical.bytes(input)));
    }

    public String toJson() {
        return Canonical.pretty(this);
    }

    public static SealedApp parse(String json) {
        try {
            JsonNode node = Canonical.JSON.readTree(json);
            return Canonical.JSON.treeToValue(node, SealedApp.class);
        } catch (FormatException error) {
            throw error;
        } catch (IOException | IllegalArgumentException error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                if (cause.getCause() instanceof FormatException format) throw format;
                cause = cause.getCause();
            }
            throw new FormatException("Not a sealed application file written by `yano.sh appchain seal`: "
                    + error.getMessage(), error);
        }
    }
}
