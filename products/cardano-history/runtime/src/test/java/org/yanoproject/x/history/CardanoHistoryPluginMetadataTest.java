package org.yanoproject.x.history;

import org.junit.jupiter.api.Test;
import org.yanoproject.catalog.BundleManifestParser;
import org.yanoproject.x.testkit.AppChainTestKinds;
import org.yanoproject.x.framework.api.appchain.AppStateMachineProvider;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiProvider;

import java.io.InputStream;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class CardanoHistoryPluginMetadataTest {
    @Test
    void publishesOneStateMachineProviderAndManifest() throws Exception {
        AppStateMachineProvider provider = ServiceLoader.load(AppStateMachineProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> CardanoHistoryProduct.STATE_MACHINE_ID.equals(value.id()))
                .findFirst().orElseThrow();
        assertThat(provider).isInstanceOf(CardanoHistoryStateMachineProvider.class);
        assertThat(ServiceLoader.load(DomainApiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> CardanoHistoryProduct.BUNDLE_ID.equals(value.id())))
                .hasSize(1);
        String path = "META-INF/yano/plugins/" + CardanoHistoryProduct.BUNDLE_ID + ".json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            var manifest = new BundleManifestParser(AppChainTestKinds.classPathRegistry()).parse(path, input);
            assertThat(manifest.id()).isEqualTo(CardanoHistoryProduct.BUNDLE_ID);
            assertThat(manifest.contributions()).extracting(contribution -> contribution.kind().manifestKey())
                    .containsExactlyInAnyOrder(
                            "app-state-machine",
                            "domain-api");
            assertThat(manifest.contributions()).extracting(contribution -> contribution.name())
                    .containsExactlyInAnyOrder(
                            CardanoHistoryProduct.STATE_MACHINE_ID,
                            CardanoHistoryProduct.BUNDLE_ID);
        }
    }
}
