package org.yanoproject.runtime.plugins;

import org.yanoproject.catalog.ContributionKind;

/**
 * The application-ledger contribution kinds as the host loads them in production: descriptors and bindings
 * discovered on the test class path.
 */
final class AppLedgerPluginKinds {
    static final PluginKinds KINDS = PluginKinds.load(AppLedgerPluginKinds.class.getClassLoader());
    static final ContributionKind APP_STATE_MACHINE = kind("app-state-machine");
    static final ContributionKind AUTHENTICATED_MAP_VALIDATOR = kind("authenticated-map-validator");
    static final ContributionKind SEQUENCER_MODE = kind("sequencer-mode");
    static final ContributionKind L1_OBSERVER = kind("l1-observer");
    static final ContributionKind L1_EPOCH_OBSERVER = kind("l1-epoch-observer");
    static final ContributionKind OBSERVATION_PROVIDER = kind("observation-provider");
    static final ContributionKind SIGNER_PROVIDER = kind("signer-provider");
    static final ContributionKind EFFECT_EXECUTOR = kind("effect-executor");
    static final ContributionKind FINALIZED_SINK = kind("finalized-sink");
    static final ContributionKind DOMAIN_API = kind("domain-api");
    static final ContributionKind LOCAL_READ_MODEL = kind("local-read-model");

    private AppLedgerPluginKinds() {
    }

    private static ContributionKind kind(String key) {
        return KINDS.registry().fromManifestKey(key);
    }

    /** {@link PluginSpiFacades#provider} through the kind's production binding. */
    static Object provider(ContributionKind kind, Object delegate, ClassLoader loader, String bundleId,
                           String selector, String providerClass) {
        return PluginSpiFacades.provider(KINDS.binding(kind), delegate, loader, bundleId, selector, providerClass);
    }
}
