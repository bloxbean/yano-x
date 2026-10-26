package org.yanoproject.x.appchain.cli;

import org.yanoproject.x.appchain.formats.SealedApp;

import java.util.Locale;

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
        }
        terminal.say("  chain fingerprint " + app.displayFingerprint());
    }
}
