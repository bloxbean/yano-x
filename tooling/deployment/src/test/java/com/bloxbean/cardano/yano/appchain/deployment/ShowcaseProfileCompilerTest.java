package com.bloxbean.cardano.yano.appchain.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShowcaseProfileCompilerTest {
    @Test
    @EnabledIfSystemProperty(named = "yano.test.showcase-zip", matches = ".+")
    void compilesReleaseMatchedGenesisFromRealShowcase() throws Exception {
        ShowcaseArtifact.Metadata artifact = new ShowcaseArtifact().inspect(
                Path.of(System.getProperty("yano.test.showcase-zip")));
        List<String> members = List.of(
                "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
                "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
                "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1",
                "ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c",
                "6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1");
        String properties = new ShowcaseProfileCompiler().compile(artifact, members, 4);
        assertThat(properties.lines()).hasSize(8);
        assertThat(properties).contains("chains[8].machines.authenticated-map.genesis-cbor-hex=")
                .contains("chains[9].machines.authenticated-map.genesis-cbor-hex=");
    }
}
