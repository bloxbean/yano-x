package org.yanoproject.x.evidence.profile;

import org.junit.jupiter.api.Test;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.x.testkit.AppChainTestKinds;
import org.yanoproject.x.composite.CompositeStateMachine;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

import java.io.InputStream;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProfilePluginMetadataTest {
    private static final String BUNDLE_ID =
            "org.yanoproject.x.evidence-profile";
    private static final String MANIFEST =
            "META-INF/yano/plugins/" + BUNDLE_ID + ".json";

    @Test
    void servicesAndManifestExposeOnlyTheEvidenceProductProviders() throws Exception {
        var machines = ServiceLoader.load(AppStateMachineProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> provider.id().equals(CompositeStateMachine.ID)
                        || provider.id().equals(RoleEvidenceStateMachineProvider.ID))
                .toList();
        assertThat(machines).extracting(provider -> provider.getClass().getName())
                .containsExactlyInAnyOrder(
                        EvidenceCompositeStateMachineProvider.class.getName(),
                        RoleEvidenceStateMachineProvider.class.getName());

        DomainApiProvider domain = ServiceLoader.load(DomainApiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> BUNDLE_ID.equals(provider.id()))
                .findFirst()
                .orElseThrow();
        assertThat(domain).isInstanceOf(RoleEvidenceDomainApiProvider.class);

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser(AppChainTestKinds.classPathRegistry()).parse(MANIFEST, input);
            assertThat(manifest.id()).isEqualTo(BUNDLE_ID);
            assertThat(manifest.contributions()).hasSize(3);
            assertThat(manifest.contributions()).anySatisfy(contribution -> {
                assertThat(contribution.kind().manifestKey()).isEqualTo("app-state-machine");
                assertThat(contribution.name()).isEqualTo(CompositeStateMachine.ID);
                assertThat(contribution.provider()).isEqualTo(
                        EvidenceCompositeStateMachineProvider.class.getName());
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind().manifestKey()).isEqualTo("app-state-machine");
                assertThat(contribution.name()).isEqualTo(RoleEvidenceStateMachineProvider.ID);
                assertThat(contribution.provider()).isEqualTo(
                        RoleEvidenceStateMachineProvider.class.getName());
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind().manifestKey()).isEqualTo("domain-api");
                assertThat(contribution.name()).isEqualTo(BUNDLE_ID);
                assertThat(contribution.provider()).isEqualTo(
                        RoleEvidenceDomainApiProvider.class.getName());
            });
        }
    }
}
