package org.yanoproject.x.roles;

import org.junit.jupiter.api.Test;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.x.testkit.AppChainTestKinds;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

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
            var manifest = new BundleManifestParser(AppChainTestKinds.classPathRegistry()).parse(MANIFEST, input);
            assertThat(manifest.id()).isEqualTo(BUNDLE_ID);
            assertThat(manifest.yanoApi().minLevel()).isEqualTo(12);
            assertThat(manifest.contributions()).hasSize(4);
            assertThat(manifest.contributions().stream()
                    .filter(contribution -> "app-state-machine".equals(contribution.kind().manifestKey()))
                    .map(contribution -> contribution.name()))
                    .containsExactlyInAnyOrder(RoleApprovalsStateMachineProvider.ID,
                            DeclarativeRoleProviders.ACTORS_ID, DeclarativeRoleProviders.APPROVALS_ID);
            assertThat(manifest.contributions()).anySatisfy(contribution -> {
                assertThat(contribution.kind().manifestKey()).isEqualTo("app-state-machine");
                assertThat(contribution.name())
                        .isEqualTo(RoleApprovalsStateMachineProvider.ID);
            }).anySatisfy(contribution -> {
                assertThat(contribution.kind().manifestKey()).isEqualTo("domain-api");
                assertThat(contribution.name()).isEqualTo(BUNDLE_ID);
            });
        }
    }
}
