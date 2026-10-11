package org.yanoproject.x.devtools;

import org.yanoproject.catalog.ContributionKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a minimal resource-only plugin bundle JAR that Yano's catalog inspector accepts. */
final class PluginBundleFixture {
    private PluginBundleFixture() {
    }

    static void write(Path directory, String id, String dependency, String kind, String name) throws IOException {
        String provider = "example." + name.replace("-", "") + "." + id.replace(".", "").replace("-", "")
                + ".Provider";
        String dependencies = dependency == null ? "[]" : """
                [{"id":"%s","minVersion":"0.1.0","maxVersionExclusive":"0.2.0"}]""".formatted(dependency);
        String manifest = """
                {"schemaVersion":1,"id":"%s","version":"0.1.0",
                 "yanoApi":{"min":3,"max":3,"minLevel":1},
                 "dependencies":%s,
                 "contributions":[{"kind":"%s","name":"%s","provider":"%s"}]}
                """.formatted(id, dependencies, kind, name, provider);
        Path jar = directory.resolve(id + ".jar");
        Files.createDirectories(directory);
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            entry(zip, "META-INF/yano/plugins/" + id + ".json", manifest);
            entry(zip, "META-INF/services/" + ContributionKind.fromManifestKey(kind).serviceType().getName(),
                    provider + "\n");
            entry(zip, provider.replace('.', '/') + ".class", "");
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
