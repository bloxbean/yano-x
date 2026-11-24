package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class AssetGatewayGoldenVectorTest {
    @Test
    void committedVectorsMatchTheImplementation() throws IOException {
        Properties committed = new Properties();
        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/yano/contracts/asset-gateway/v1/golden-vectors.properties")) {
            assertThat(in).as("golden vectors resource").isNotNull();
            committed.load(in);
        }
        Map<String, String> committedMap = new LinkedHashMap<>();
        committed.stringPropertyNames().forEach(name -> committedMap.put(name, committed.getProperty(name)));
        assertThat(committedMap).isEqualTo(AssetGatewayGoldenVectorGenerator.vectors());
    }
}
