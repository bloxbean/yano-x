package org.yanoproject.x.framework.host;

import org.junit.jupiter.api.Test;
import org.yanoproject.catalog.ContributionKind;
import org.yanoproject.catalog.ContributionKindRegistry;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AppLedgerKindBindingsTest {
    @Test
    void bindingOwnersSelectTheDescriptorKindsNextToTheBuiltIns() throws Exception {
        ContributionKindRegistry registry = ContributionKindRegistry.fromHostClassLoader(getClass().getClassLoader());
        Map<String, ContributionKind> kinds = registry.kinds().stream()
                .collect(Collectors.toMap(ContributionKind::manifestKey, kind -> kind));

        assertThat(kinds.keySet()).containsAll(ContributionKindRegistry.builtIns().kinds().stream()
                .map(ContributionKind::manifestKey).toList());
        assertThat(kinds.get("app-state-machine").serviceInterface())
                .isEqualTo("org.yanoproject.x.framework.api.appchain.AppStateMachineProvider");
        assertThat(registry.descriptorOwner(kinds.get("domain-api")))
                .contains(AppLedgerKindBindings.DESCRIPTOR_OWNER);
        // framework/cardano binds effect-executor; without its binding its descriptor is not read.
        assertThat(kinds).doesNotContainKey("effect-executor");
    }
}
