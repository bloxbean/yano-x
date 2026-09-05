package com.bloxbean.cardano.yano.appchain.trust.profile;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-049 §5: no JSON-LD or RDF processor may sit on the product's runtime classpath. */
class ClasspathFirewallTest {
    static final List<String> FORBIDDEN = List.of("jsonld", "json-ld", "rdf4j", "jena", "titanium");

    @Test
    void runtimeClasspathHoldsNoLinkedDataProcessor() {
        String classpath = System.getProperty("yano.trust.firewall.classpath");
        assertThat(classpath).as("classpath handed to the test by Gradle").isNotBlank();
        for (String entry : classpath.split(File.pathSeparator)) {
            String name = new File(entry).getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN) {
                assertThat(name).as("runtime classpath entry").doesNotContain(forbidden);
            }
        }
    }
}
