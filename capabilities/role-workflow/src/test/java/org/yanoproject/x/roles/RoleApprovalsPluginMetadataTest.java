package org.yanoproject.x.roles;

import org.yanoproject.api.appchain.AppStateMachineProvider;
import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.catalog.ContributionKind;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class RoleApprovalsPluginMetadataTest {
    private static final String BUNDLE_ID =
            "org.yanoproject.x.role-workflow";
    private static final String MANIFEST =
            "META-INF/yano/plugins/" + BUNDLE_ID + ".json";

    @Test
    void servicesAndManifestExposeGenericRoleProductAndExplicitDeclarativeLeaves() throws Exception {
        AppStateMachineProvider machine = ServiceLoader.load(AppStateMachineProvider.class)
                .stream().map(ServiceLoader.Provider::get)
                .filter(provider -> RoleApprovalsStateMachineProvider.ID.equals(provider.id()))
                .findFirst().orElseThrow();
        DomainApiProvider domain = ServiceLoader.load(DomainApiProvider.class)
                .stream().map(ServiceLoader.Provider::get)
                .filter(provider -> BUNDLE_ID.equals(provider.id()))
                .findFirst().orElseThrow();
        assertThat(machine).isInstanceOf(RoleApprovalsStateMachineProvider.class);
        assertThat(domain).isInstanceOf(RoleApprovalsDomainApiProvider.class);

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser().parse(MANIFEST, input);
            assertThat(manifest.id()).isEqualTo(BUNDLE_ID);
            assertThat(manifest.yanoApi().minLevel()).isEqualTo(10);
            assertThat(manifest.contributions()).hasSize(4);
            assertThat(manifest.contributions().stream()
                    .filter(contribution -> contribution.kind() == ContributionKind.APP_STATE_MACHINE)
                    .map(contribution -> contribution.name()))
                    .containsExactlyInAnyOrder(RoleApprovalsStateMachineProvider.ID,
                            DeclarativeRoleProviders.ACTORS_ID, DeclarativeRoleProviders.APPROVALS_ID);
            assertThat(manifest.contributions()).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.APP_STATE_MACHINE);
                assertThat(contribution.name())
                        .isEqualTo(RoleApprovalsStateMachineProvider.ID);
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind()).isEqualTo(ContributionKind.DOMAIN_API);
                assertThat(contribution.name()).isEqualTo(BUNDLE_ID);
            });
        }
    }
}
