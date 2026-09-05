package com.bloxbean.cardano.yano.appchain.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class YanoDeploymentCliTest {
    @Test
    void initCreatesAValidFiveNodeManifestAndIndependentOwnerOnlySeeds(@TempDir Path temporary) throws Exception {
        Path cluster = temporary.resolve("cluster");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();

        int exit = new YanoDeploymentCli().run(
                new String[]{"init", cluster.toString()},
                new PrintWriter(stdout, true), new PrintWriter(stderr, true));

        assertThat(exit).isZero();
        assertThat(stderr.toString()).isEmpty();
        DeploymentDocument document = new DeploymentLoader().load(cluster);
        assertThat(document.validate(false)).isEmpty();
        assertThat(document.nodes()).hasSize(5);
        assertThat(document.nodes().getFirst().roles()).contains("validator", "l1-bootstrap", "api-gateway");
        assertThat(document.validators()).extracting(DeploymentDocument.Node::memberPublicKey)
                .doesNotHaveDuplicates();
        assertThat(Files.getPosixFilePermissions(cluster.resolve("secrets")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        for (int index = 0; index < 5; index++) {
            Path seed = cluster.resolve("secrets/node-" + index + ".seed");
            assertThat(Files.readString(seed).trim()).matches("[0-9a-f]{64}");
            assertThat(Files.getPosixFilePermissions(seed))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void initRejectsUnsupportedNodeCountWithoutCreatingCluster(@TempDir Path temporary) {
        Path cluster = temporary.resolve("cluster");
        int exit = new YanoDeploymentCli().run(
                new String[]{"init", cluster.toString(), "--nodes", "4"},
                new PrintWriter(new StringWriter(), true), new PrintWriter(new StringWriter(), true));

        assertThat(exit).isEqualTo(YanoDeploymentCli.EXIT_INVALID);
        assertThat(cluster).doesNotExist();
    }
}
