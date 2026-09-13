package org.yanoproject.x.devtools;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import org.yanoproject.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainAnsibleExporterTest {
    @TempDir Path temporary;

    @Test
    void exportsTheSameApplicationForExistingVmsWithRetainedIdentityAndSecretBoundaries() throws Exception {
        var properties = AppChainPropertyRegistry.framework();
        var catalog = new AppChainProjectCatalog(properties);
        var resolver = new AppChainProjectResolver(properties, catalog);
        var renderer = new AppChainProjectRenderer(catalog, resolver);
        var keys = List.of("01", "02", "03").stream().map(value -> HexFormat.of().formatHex(
                KeyGenUtil.getPublicKeyFromPrivateKey(HexFormat.of().parseHex(value.repeat(32))))).toList();
        var topology = new AppChainProjectModel.Topology(3, keys,
                List.of("a.example", "b.example", "c.example"), "two-thirds", "fixed", "static", null, null);
        var blueprint = new AppChainProjectModel.Blueprint(AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND, new AppChainProjectModel.Metadata("operator-demo"),
                new AppChainProjectModel.Spec("test", "preprod", new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"), List.of(
                        new AppChainProjectModel.ChainIntent("orders", "audit-log", List.of(), Map.of(), topology),
                        new AppChainProjectModel.ChainIntent("documents", "document-trail", List.of(), Map.of(), topology))));
        Path project = temporary.resolve("project");
        renderer.initialize(project, blueprint);
        Path output = temporary.resolve("ansible");
        var export = new AppChainGitOpsExporter(renderer, resolver)
                .export(project, AppChainGitOpsExporter.Target.ANSIBLE, output);
        assertThat(export.target()).isEqualTo("ansible");
        assertThat(Files.readString(output.resolve("files/shared-consensus.yaml")))
                .contains("orders", "documents", "doc-trail", "ordered-log");
        String playbook = Files.readString(output.resolve("deploy.yaml"));
        assertThat(playbook).contains(renderer.readLock(project).blueprintDigest());
        assertThat(playbook).contains("sha256", "Reject unplanned revision", "no_log: true", "diff: false",
                "Refuse adoption of unmarked retained stores", "Verify controller deployment files");
        assertThat(playbook.indexOf("Record exact application"))
                .isLessThan(playbook.indexOf("Create retained storage root"));
        assertThat(playbook).doesNotContain("appchain reset", "ansible.builtin.shell:", "state: absent", "%s", "@PROJECT@");
        assertThat(Files.readString(output.resolve("templates/yano.service.j2")))
                .contains("User=yano", "EnvironmentFile=", "ProtectSystem=strict", "start:preprod");
        var process = new ProcessBuilder("python3", output.resolve("verify.py").toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor()).isZero();
        Files.writeString(output.resolve("files/node0.yaml"), "tampered");
        process = new ProcessBuilder("python3", output.resolve("verify.py").toString())
                .redirectErrorStream(true).start();
        assertThat(process.waitFor()).isNotZero();
    }
}
