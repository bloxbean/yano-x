package org.yanoproject.x.framework.rest;

/**
 * The app-chain OpenAPI document group (ADR-040). Resources annotated with
 * {@code @Extension(name = AppChainApiGroup.APP_CHAIN, value = "")} appear in the
 * {@code /q/openapi-app-chain} document, configured as the {@code app-chain} scan profile.
 */
public final class AppChainApiGroup {

    /** App-chain surface: chain-scoped messages, state proofs, effects, plugin domain routes. */
    public static final String APP_CHAIN = "x-smallrye-profile-app-chain";

    private AppChainApiGroup() {
    }
}
