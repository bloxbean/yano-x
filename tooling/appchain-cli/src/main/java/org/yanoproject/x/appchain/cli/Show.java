package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.SealedApp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** How applications and members are shown, so every command shows them the same way. */
final class Show {
    private Show() {
    }

    /** First 16 hex characters of a member key, compared on the call together with the fingerprint. */
    static String keyId(String publicKey) {
        return publicKey.substring(0, 16);
    }

    static void sealed(Terminal terminal, SealedApp app) {
        SealedApp.Genesis genesis = app.genesis();
        terminal.say("Application " + genesis.name() + " on " + genesis.network()
                + " (Yano " + genesis.yanoVersion() + ", Yano X " + genesis.yanoXVersion() + ")");
        terminal.say(String.format(Locale.ROOT, "  %d members, %d signatures per block, %s membership",
                genesis.members().size(), genesis.threshold(), genesis.membership()));
        for (SealedApp.Member member : genesis.members()) {
            SealedApp.Peer peer = app.peers().get(member.name());
            terminal.say(String.format(Locale.ROOT, "  %-12s key %s  %s%s", member.name(), keyId(member.publicKey()),
                    peer == null ? "" : peer.node(), member.name().equals(genesis.lead()) ? "  (lead)" : ""));
        }
        for (SealedApp.ChainGenesis chain : genesis.chains()) {
            terminal.say(String.format(Locale.ROOT, "  chain %-14s %-22s genesis %s", chain.id(), chain.recipe(),
                    chain.genesisId() == null ? "-" : chain.genesisId().substring(0, 16) + "…"));
            String topics = topics(genesis, chain.settings());
            if (topics != null) terminal.say("        topics: " + topics);
        }
        terminal.say("  chain fingerprint " + app.displayFingerprint());
    }

    /** A sealed chain's topic policy in words, or {@code null} when topics are off. */
    static String topics(SealedApp.Genesis genesis, Map<String, String> settings) {
        if (!"enabled".equals(settings.get("topics.mode"))) return null;
        String keys = settings.getOrDefault("topics.creation.registrars.keys", "");
        String who;
        if (!keys.isBlank()) {
            List<String> registrars = Arrays.asList(keys.split(","));
            List<String> names = new ArrayList<>(genesis.members().stream()
                    .filter(member -> registrars.contains(member.publicKey())).map(SealedApp.Member::name).toList());
            registrars.stream().filter(key -> genesis.members().stream().noneMatch(member -> member.publicKey()
                    .equals(key))).forEach(key -> names.add("key " + keyId(key)));
            String threshold = settings.getOrDefault("topics.creation.registrars.threshold", "1");
            who = "namespaces created by " + ("1".equals(threshold) ? "" : threshold + " of ")
                    + String.join(", ", names)
                    + " or by member vote";
        } else if ("true".equals(settings.get("topics.creation.open-claims"))) {
            who = "namespaces claimed first-come by anyone";
        } else {
            who = "namespaces created by member vote";
        }
        return who + "; unregistered topics " + ("open".equals(settings.get("topics.unregistered-topics"))
                ? "open" : "refused");
    }
}
