package org.yanoproject.runtime.plugins;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.plugin.PluginTrustTier;
import org.yanoproject.catalog.ContributionKind;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The X descriptors reproduce the kind table, rules and reserved SYSTEM names Yano hard-coded at ebd3eeea4. */
class AppLedgerContributionKindsTest {
    private static final String API = "org.yanoproject.x.framework.api.";

    @Test
    void descriptorKindsMatchTheBaselineKindTable() {
        Map<String, ContributionKind> kinds = AppLedgerPluginKinds.KINDS.registry().kinds().stream()
                .collect(Collectors.toMap(ContributionKind::manifestKey, kind -> kind));
        assertThat(kinds.keySet()).containsExactlyInAnyOrder("node-plugin", "utxo-index-contributor", "health",
                "metrics", "app-state-machine", "authenticated-map-validator", "sequencer-mode", "membership-handoff-gate", "l1-observer",
                "l1-epoch-observer", "observation-provider", "signer-provider", "effect-executor",
                "finalized-sink", "domain-api", "local-read-model");

        expect(kinds, "app-state-machine", API + "appchain.AppStateMachineProvider", false,
                PluginTrustTier.CONSENSUS, "", Set.of("ordered-log"));
        expect(kinds, "authenticated-map-validator", API + "appchain.authmap.AuthenticatedMapValueValidatorFactory",
                true, PluginTrustTier.CONSENSUS, "", Set.of());
        expect(kinds, "sequencer-mode", API + "appchain.sequencer.SequencerModeProvider", false,
                PluginTrustTier.CONSENSUS, "", Set.of("fixed", "rotating"));
        expect(kinds, "membership-handoff-gate", API + "appchain.membership.MembershipHandoffGateProvider",
                false, PluginTrustTier.CONSENSUS, "", Set.of());
        expect(kinds, "l1-observer", API + "appchain.l1view.L1ObserverProvider", false,
                PluginTrustTier.CONSENSUS, "", Set.of("metadata-label", "address-deposit"));
        expect(kinds, "l1-epoch-observer", API + "appchain.l1view.L1EpochObserverProvider", false,
                PluginTrustTier.CONSENSUS, "", Set.of());
        expect(kinds, "observation-provider", API + "appchain.observation.ObservationProviderFactory", false,
                PluginTrustTier.PRIVILEGED_LOCAL, "", Set.of());
        expect(kinds, "signer-provider", API + "appchain.signer.SignerProviderFactory", false,
                PluginTrustTier.PRIVILEGED_LOCAL, ":", Set.of());
        expect(kinds, "effect-executor", "org.yanoproject.x.framework.cardano.api.effects.AppEffectExecutorFactory",
                false, PluginTrustTier.PRIVILEGED_LOCAL, ".", Set.of("webhook"));
        expect(kinds, "finalized-sink", API + "appchain.sink.FinalizedStreamSinkFactory", false,
                PluginTrustTier.AUXILIARY_LOCAL, ".", Set.of());
        expect(kinds, "domain-api", API + "plugin.domain.DomainApiProvider", true,
                PluginTrustTier.PRIVILEGED_LOCAL, "", Set.of());
        expect(kinds, "local-read-model", API + "plugin.domain.LocalReadModelProvider", true,
                PluginTrustTier.PRIVILEGED_LOCAL, "", Set.of());

        ContributionKind domain = kinds.get("domain-api");
        assertThat(domain.maxPerBundle()).isEqualTo(1);
        assertThat(domain.nameIsBundleId()).isTrue();
        assertThat(AppLedgerPluginKinds.KINDS.operationsTrackedKinds()).containsExactly("domain-api");
        kinds.values().stream().filter(kind -> !kind.manifestKey().equals("domain-api"))
                .filter(kind -> kind.serviceInterface().startsWith("org.yanoproject.x."))
                .forEach(kind -> assertThat(kind.nameIsBundleId()).as(kind.manifestKey()).isFalse());
    }

    private static void expect(Map<String, ContributionKind> kinds, String key, String serviceInterface,
                               boolean manifestRequired, PluginTrustTier trust, String forbiddenNameChars,
                               Set<String> reserved) {
        ContributionKind kind = kinds.get(key);
        assertThat(kind.serviceInterface()).as(key).isEqualTo(serviceInterface);
        assertThat(kind.manifestRequired()).as(key).isEqualTo(manifestRequired);
        assertThat(kind.trustTier()).as(key).isEqualTo(trust);
        assertThat(kind.forbiddenNameChars()).as(key).isEqualTo(forbiddenNameChars);
        assertThat(kind.reservedSystemNames()).as(key).isEqualTo(reserved);
    }
}
