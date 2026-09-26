package org.yanoproject.x.stdlib;

import org.yanoproject.api.plugin.domain.DomainApiProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapPluginMetadataTest {
    @Test
    void requiresHostStatelessAdmissionDelegation() throws IOException {
        try (var manifest = getClass().getResourceAsStream(
                "/META-INF/yano/plugins/org.yanoproject.x.stdlib.json")) {
            assertThat(manifest).isNotNull();
            assertThat(new String(manifest.readAllBytes(), StandardCharsets.UTF_8))
                    .containsPattern("\"minLevel\"\\s*:\\s*11\\b");
        }
    }

    @Test
    void publishesFirstPartyDomainApiProvider() {
        DomainApiProvider provider = ServiceLoader.load(DomainApiProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> AuthenticatedMapDomainApiProvider.ID.equals(value.id()))
                .findFirst().orElseThrow();

        assertThat(provider).isInstanceOf(AuthenticatedMapDomainApiProvider.class);
        assertThat(AuthenticatedMapPluginMetadataTest.class.getClassLoader().getResource(
                "META-INF/yano/plugins/org.yanoproject.x.stdlib.json"))
                .isNotNull();
    }
}
