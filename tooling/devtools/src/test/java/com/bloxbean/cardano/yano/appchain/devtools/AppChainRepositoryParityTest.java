package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.TemplateContract;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AppChainRepositoryParityTest {
    private static final Pattern LAUNCHER_INJECTED = Pattern.compile(
            "yano\\.app-chain\\.chains\\[(?:%s|\\$idx)]\\.([a-z0-9.-]+)");

    @Test
    void builtInContractMatchesEveryMaintainedLauncherInjectedProperty() throws Exception {
        String launcher = source("scripts/appchain-cluster/cluster.sh");
        Set<String> injected = new TreeSet<>();
        Matcher matcher = LAUNCHER_INJECTED.matcher(launcher);
        while (matcher.find()) {
            injected.add(matcher.group(1));
        }
        TemplateContract contract = new AppChainDescriptorLoader()
                .loadContract("builtin:cluster");
        Set<String> declared = contract.suppliedProperties().stream()
                .map(requirement -> requirement.propertyPattern()
                        .substring("yano.app-chain.chains[*].".length()))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(declared).isEqualTo(injected);
    }

    @Test
    void maintainedTemplateDeclaresThePackagedSchema() throws Exception {
        String firstLine = Files.readAllLines(repository()
                .resolve("config/application-appchain.yml")).getFirst();
        assertThat(firstLine).isEqualTo(
                "# yaml-language-server: $schema=./schema/appchain-runtime.schema.json");
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repository().resolve(relative));
    }

    private static Path repository() {
        return Path.of(System.getProperty("yano.test.repo-root"));
    }
}
