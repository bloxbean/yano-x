package org.yanoproject.x.devtools;

import org.yanoproject.api.appchain.AppStateMachine;
import org.yanoproject.api.appchain.AppStateMachineProvider;

/** Public no-argument fixture used to prove a reviewed JVM artifact is ServiceLoader-visible. */
public final class CustomCatalogFixtureProvider implements AppStateMachineProvider {
    @Override
    public String id() {
        return "custom-sample";
    }

    @Override
    public AppStateMachine create() {
        return null;
    }
}
