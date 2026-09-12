package org.yanoproject.x.devtools;

import org.yanoproject.appchain.config.EffectiveConfigValue;

import java.util.List;
import java.util.Map;

/** One deterministic SmallRye-resolved app-chain configuration stack. */
record ResolvedAppChainConfiguration(
        Map<String, EffectiveConfigValue> values,
        List<ResolvedConfigSource> sources,
        String profile,
        boolean environmentIncluded,
        boolean systemPropertiesIncluded) {

    ResolvedAppChainConfiguration {
        values = Map.copyOf(values);
        sources = List.copyOf(sources);
        profile = profile == null ? "" : profile;
    }
}
