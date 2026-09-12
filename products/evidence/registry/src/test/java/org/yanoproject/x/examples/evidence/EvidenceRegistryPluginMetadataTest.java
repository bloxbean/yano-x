package org.yanoproject.x.examples.evidence;

import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.catalog.ContributionKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRegistryPluginMetadataTest {
    private static final String MANIFEST =
            "META-INF/yano/plugins/org.yanoproject.x.evidence-registry.json";

    @Test
    void servicesAndStrictManifestAdvertiseTheSameTwoProviders() throws Exception {
        AppStateMachineProvider machine = ServiceLoader.load(AppStateMachineProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> EvidenceContract.STATE_MACHINE_ID.equals(provider.id()))
                .findFirst()
                .orElseThrow();
        DomainApiProvider domain = ServiceLoader.load(DomainApiProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(provider -> EvidenceRegistryDomainApiProvider.BUNDLE_ID.equals(provider.id()))
                .findFirst()
                .orElseThrow();
        assertThat(machine).isInstanceOf(EvidenceRegistryStateMachineProvider.class);
        assertThat(domain).isInstanceOf(EvidenceRegistryDomainApiProvider.class);

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser().parse(MANIFEST, input);
            assertThat(manifest.id()).isEqualTo(EvidenceRegistryDomainApiProvider.BUNDLE_ID);
            assertThat(manifest.dependencies()).isEmpty();
            assertThat(manifest.contributions()).hasSize(2);
            assertThat(manifest.contributions()).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.APP_STATE_MACHINE);
                assertThat(contribution.name()).isEqualTo(EvidenceContract.STATE_MACHINE_ID);
                assertThat(contribution.provider()).isEqualTo(
                        EvidenceRegistryStateMachineProvider.class.getName());
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.DOMAIN_API);
                assertThat(contribution.name())
                        .isEqualTo(EvidenceRegistryDomainApiProvider.BUNDLE_ID);
                assertThat(contribution.provider()).isEqualTo(
                        EvidenceRegistryDomainApiProvider.class.getName());
            });
        }
    }
}
