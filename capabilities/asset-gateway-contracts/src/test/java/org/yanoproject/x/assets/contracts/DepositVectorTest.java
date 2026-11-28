package org.yanoproject.x.assets.contracts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositVectorTest {
    private static final String BASE = "/META-INF/yano/contracts/asset-gateway/v1/";

    @Test
    void committedVectorsMatchTheImplementation() throws IOException {
        Properties committed = load("deposit-vectors.properties");
        Map<String, String> committedMap = new LinkedHashMap<>();
        committed.stringPropertyNames().forEach(name -> committedMap.put(name, committed.getProperty(name)));
        assertThat(committedMap).isEqualTo(DepositVectorGenerator.vectors());
    }

    @Test
    void committedCodecsRoundTripByteForByte() throws IOException {
        Properties committed = load("deposit-vectors.properties");
        HexFormat hex = HexFormat.of();
        for (String name : committed.stringPropertyNames()) {
            byte[] bytes = hex.parseHex(committed.getProperty(name));
            if (name.startsWith("claim.")) {
                assertThat(AssetDepositClaimV1.decode(bytes).encode()).as(name).isEqualTo(bytes);
            } else if (name.startsWith("record.")) {
                assertThat(DepositRecordV1.decode(bytes).encode()).as(name).isEqualTo(bytes);
            } else if (name.equals("datum.deposit")) {
                assertThat(DepositDatumV1.decode(bytes).encode()).as(name).isEqualTo(bytes);
            }
        }
    }

    @Test
    void everyNegativeDatumIsRejected() throws IOException {
        Properties negative = load("deposit-datum-negative-vectors.properties");
        assertThat(negative).hasSizeGreaterThanOrEqualTo(12);
        for (String name : negative.stringPropertyNames()) {
            byte[] bytes = HexFormat.of().parseHex(negative.getProperty(name));
            assertThatThrownBy(() -> DepositDatumV1.decode(bytes)).as(name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(BASE + name)) {
            assertThat(in).as(name).isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
