package com.bloxbean.cardano.yano.appchain.feed.client;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-052 §5: no node, consensus runtime, or connector jar on the client's runtime classpath. */
class ClasspathFirewallTest {
    static final List<String> FORBIDDEN = List.of(
            "yano-appchain-core-", "yano-node", "yano-consensus", "yano-x-eutxo", "kafka", "aws-sdk");

    @Test
    void runtimeClasspathHoldsNoRuntimeOrConnector() {
        String classpath = System.getProperty("yano.feed.firewall.classpath");
        assertThat(classpath).as("classpath handed to the test by Gradle").isNotBlank();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                assertThat(name).as("runtime classpath entry").doesNotContain(forbidden);
            }
        }
    }
}
