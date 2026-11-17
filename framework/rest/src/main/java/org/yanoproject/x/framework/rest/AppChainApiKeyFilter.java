package org.yanoproject.x.framework.rest;

import org.yanoproject.app.spi.ApiKeys;
import org.yanoproject.x.framework.config.AppChainPropertyKeys;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiAccess;
import org.yanoproject.x.framework.api.plugin.domain.DomainApiGateway;
import org.yanoproject.x.framework.api.plugin.domain.DomainHttpMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * API-key authentication for the app-chain REST surface
 * (ADR app-layer/006 E4.1). Disabled by default — when
 * {@code yano.app-chain.api.auth.enabled=true}, every request matched to an
 * app-chain resource method must carry a configured key in the
 * {@code X-API-Key} header. With keys configured but broad auth disabled,
 * reads and submissions remain public while privileged operations still
 * require an unscoped full key. Snapshot lifecycle administration uses the
 * independently configured {@code yano.app-chain.api.snapshot-admin-keys} realm.
 * A key entry of the form {@code key=topicA|topicB}
 * is a <b>submit-only, topic-restricted</b> key when broad authentication is
 * enabled: it may READ freely and SUBMIT only to the listed topics — it may
 * NOT call any other state-changing operation (admin pause/drain/anchor, key
 * rotation, or the effect operations requeue/cancel/claim/report, which can
 * move real funds). An unscoped key ({@code key} with no {@code =topics}) is a
 * full key.
 * Yano's own {@code PluginOperationsAuthFilter} protects the plugin-operations
 * resource with the same keys.
 * <p>
 * Scoping is by the <em>matched resource class</em> ({@link ResourceInfo}), not
 * a URL substring — renaming or re-rooting the resource cannot silently
 * un-protect it, and URI variants (trailing slash, matrix params) cannot slip
 * past the submit-topic check.
 */
@Provider
public class AppChainApiKeyFilter implements ContainerRequestFilter {

    private static final int QUERY_JSON_MAX_BYTES = 132 * 1024;
    private static final int DOMAIN_BODY_MAX_BYTES = 64 * 1024;
    private static final int SNAPSHOT_VERIFY_JSON_MAX_BYTES = 8 * 1024 * 1024 + 4_096;

    // Package-private overrides keep isolated unit tests independent of the
    // global MP Config provider. Production leaves them null and resolves the
    // runtime configuration lazily, which is essential for native images:
    // API keys must not be captured or embedded during static initialization.
    Boolean authEnabled;
    Optional<String> apiKeysConfig;
    Optional<String> snapshotAdminKeysConfig;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DomainApiGateway domainApis;

    @Context
    ResourceInfo resourceInfo;

    @Context
    UriInfo uriInfo;

    private volatile Map<String, Set<String>> parsedKeys;
    private volatile Set<String> parsedSnapshotAdminKeys;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isProtectedResource()) {
            return;
        }

        if (!enforceCanonicalExtensionPath(requestContext)) {
            return;
        }

        if (!enforceRawEntityBound(requestContext)) {
            return;
        }

        AppChainAccess.Level access = access(requestContext);
        // INTERNAL domain routes are deliberately indistinguishable from an
        // unknown path and never become HTTP capabilities.
        if (access == AppChainAccess.Level.INTERNAL) {
            abortDomainNotFound(requestContext);
            return;
        }
        if (access == AppChainAccess.Level.SNAPSHOT_ADMIN) {
            Set<String> adminKeys = snapshotAdminKeys();
            if (adminKeys.isEmpty()) {
                requestContext.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", "AUTH_UNAVAILABLE",
                                "error", "Snapshot administration requires a configured "
                                        + "snapshot-admin API key")).build());
                return;
            }
            String key = requestContext.getHeaderString(ApiKeys.HEADER);
            if (key == null || !adminKeys.contains(key)) {
                requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("error", "Missing or invalid snapshot-admin API key")).build());
            }
            return;
        }
        if (access == AppChainAccess.Level.PRIVILEGED
                && !privilegedAuthenticationAvailable()) {
            // A privileged capability is never made anonymous by an operator
            // omitting or disabling API-key configuration. Plugin routes are
            // hidden as absent; statically known host operations report the
            // configuration problem without invoking their resource method.
            if (isDomainApiResource()) {
                abortDomainNotFound(requestContext);
            } else {
                requestContext.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", "AUTH_UNAVAILABLE",
                                "error", "Privileged plugin/app-chain operations require "
                                        + "a configured unscoped full API key"))
                        .build());
            }
            return;
        }
        // A configured full key is enough to expose privileged capabilities
        // safely without forcing read/submit authentication. Broad auth keeps
        // its existing whole-surface semantics when explicitly enabled.
        if (access != AppChainAccess.Level.PRIVILEGED && !authEnabled()) {
            return;
        }

        String apiKey = requestContext.getHeaderString(ApiKeys.HEADER);
        Map<String, Set<String>> keys = keys();
        if (apiKey == null || !keys.containsKey(apiKey)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Missing or invalid " + ApiKeys.HEADER)).build());
            return;
        }

        Set<String> allowedTopics = keys.get(apiKey);
        if (allowedTopics.isEmpty()) {
            return; // full key — no further restriction
        }
        // A topic-scoped key may read freely, may submit only to its declared
        // topics, and may never invoke a privileged or internal operation.
        // This classification is semantic: ADR-011.3's generic query is a
        // read even though its bounded parameter transport uses POST.
        switch (access) {
            case READ -> {
                return;
            }
            case SUBMIT -> enforceTopicRestriction(requestContext, allowedTopics);
            case SNAPSHOT_ADMIN, PRIVILEGED, INTERNAL -> requestContext.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("error", "This API key is submit-only "
                                    + "(topic-restricted) and may not call privileged "
                                    + "operations"))
                            .build());
        }
    }

    private AppChainAccess.Level access(ContainerRequestContext requestContext) {
        if (isDomainApiResource()) {
            return domainAccess(requestContext);
        }
        java.lang.reflect.Method method = resourceInfo != null
                ? resourceInfo.getResourceMethod() : null;
        AppChainAccess declared = method != null
                ? method.getAnnotation(AppChainAccess.class) : null;
        if (declared != null) {
            return declared.value();
        }
        return switch (requestContext.getMethod().toUpperCase(java.util.Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> AppChainAccess.Level.READ;
            default -> AppChainAccess.Level.PRIVILEGED;
        };
    }

    /** True when the matched resource class is protected by this credential realm. */
    private boolean isProtectedResource() {
        Class<?> resourceClass = resourceInfo != null ? resourceInfo.getResourceClass() : null;
        return resourceClass == AppChainResource.class
                || resourceClass == AppChainResource.ChainScopedResource.class
                || resourceClass == PluginDomainResource.class;
    }

    private boolean isDomainApiResource() {
        return resourceInfo != null
                && resourceInfo.getResourceClass() == PluginDomainResource.class;
    }

    private boolean isCommittedQueryResource() {
        return resourceInfo != null
                && resourceInfo.getResourceClass()
                        == AppChainResource.ChainScopedResource.class
                && resourceInfo.getResourceMethod() != null
                && resourceInfo.getResourceMethod().getName().equals("query");
    }

    /**
     * RESTEasy removes matrix parameters and one trailing slash while matching
     * a resource. Validate the still-encoded request path before trusting the
     * normalized {@code @PathParam} values, otherwise aliases such as
     * {@code status;x=1} can reach the canonical {@code status} handler.
     */
    private boolean enforceCanonicalExtensionPath(
            ContainerRequestContext requestContext
    ) {
        boolean domain = isDomainApiResource();
        boolean query = isCommittedQueryResource();
        if (!domain && !query) {
            return true;
        }
        if (!ApiKeys.isCanonical(ApiKeys.rawPath(uriInfo))) {
            return abortNonCanonicalExtensionPath(requestContext, domain);
        }
        return true;
    }

    private static boolean abortNonCanonicalExtensionPath(
            ContainerRequestContext requestContext,
            boolean domain
    ) {
        if (domain) {
            abortDomainNotFound(requestContext);
        } else {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "INVALID_REQUEST",
                            "error", "App-chain query path is invalid"))
                    .build());
        }
        return false;
    }

    private AppChainAccess.Level domainAccess(ContainerRequestContext requestContext) {
        try {
            DomainHttpMethod method = switch (requestContext.getMethod()
                    .toUpperCase(java.util.Locale.ROOT)) {
                case "GET" -> DomainHttpMethod.GET;
                case "POST" -> DomainHttpMethod.POST;
                default -> null;
            };
            if (method == null || uriInfo == null) {
                return AppChainAccess.Level.PRIVILEGED;
            }
            // false preserves the raw encoded form. The registry accepts only
            // canonical unreserved ASCII and rejects every '%' before any
            // path value can be interpreted a second time.
            var parameters = uriInfo.getPathParameters(false);
            String bundleId = parameters.getFirst("bundleId");
            String path = parameters.getFirst("path");
            if (bundleId == null || path == null) {
                return AppChainAccess.Level.PRIVILEGED;
            }
            DomainApiAccess declared = domainApis.access(bundleId, method, path)
                    .orElse(null);
            if (declared == null) {
                return AppChainAccess.Level.INTERNAL;
            }
            return switch (declared) {
                case READ -> AppChainAccess.Level.READ;
                case PRIVILEGED -> AppChainAccess.Level.PRIVILEGED;
                case INTERNAL -> AppChainAccess.Level.INTERNAL;
            };
        } catch (RuntimeException invalidOrUnavailable) {
            // Unknown/unclassifiable plugin operations fail closed and are
            // indistinguishable from INTERNAL or absent routes over HTTP.
            return AppChainAccess.Level.INTERNAL;
        }
    }

    private boolean privilegedAuthenticationAvailable() {
        return ApiKeys.hasFullKey(keys());
    }

    private static void abortDomainNotFound(ContainerRequestContext requestContext) {
        requestContext.abortWith(Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "No domain API route matches the request"))
                .build());
    }

    private void enforceTopicRestriction(ContainerRequestContext requestContext, Set<String> allowedTopics) {
        try {
            byte[] body = requestContext.getEntityStream().readAllBytes();
            // restore the entity for the resource method
            requestContext.setEntityStream(new ByteArrayInputStream(body));
            JsonNode json = objectMapper.readTree(body);
            String topic = json != null && json.hasNonNull("topic") ? json.get("topic").asText() : "";
            if (!allowedTopics.contains(topic)) {
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("error", "API key is not allowed to submit to topic '"
                                + topic + "'")).build());
            }
        } catch (Exception e) {
            requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("error", "Unreadable request body")).build());
        }
    }

    /**
     * Bound entities before JSON or byte-array deserialization. Resource-level
     * decoded limits remain the second line of defense.
     */
    private boolean enforceRawEntityBound(ContainerRequestContext requestContext) {
        int limit = 0;
        if (isDomainApiResource()) {
            if ("GET".equalsIgnoreCase(requestContext.getMethod())
                    && requestContext.hasEntity()) {
                requestContext.abortWith(Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("code", "INVALID_REQUEST",
                                "error", "GET domain API requests must not contain a body"))
                        .build());
                return false;
            }
            if ("POST".equalsIgnoreCase(requestContext.getMethod())) {
                limit = DOMAIN_BODY_MAX_BYTES;
            }
        } else if (isCommittedQueryResource()) {
            limit = QUERY_JSON_MAX_BYTES;
        } else if (isSnapshotProofVerificationResource()) {
            limit = SNAPSHOT_VERIFY_JSON_MAX_BYTES;
        }
        return limit == 0 || ApiKeys.boundEntity(requestContext, limit);
    }

    private boolean isSnapshotProofVerificationResource() {
        java.lang.reflect.Method method = resourceInfo != null
                ? resourceInfo.getResourceMethod() : null;
        return method != null && "verifySnapshotProof".equals(method.getName());
    }

    /** Parse "key1,key2=topicA|topicB" into key → allowed submit topics ("" empty = all). */
    private Map<String, Set<String>> keys() {
        Map<String, Set<String>> current = parsedKeys;
        if (current != null) {
            return current;
        }
        Map<String, Set<String>> parsed = ApiKeys.parse(apiKeysConfig().orElse(""));
        parsedKeys = parsed;
        return parsed;
    }

    private boolean authEnabled() {
        return authEnabled != null ? authEnabled : ConfigProvider.getConfig()
                .getOptionalValue(
                        AppChainPropertyKeys.API_AUTH_ENABLED, Boolean.class)
                .orElse(false);
    }

    private Optional<String> apiKeysConfig() {
        return apiKeysConfig != null ? apiKeysConfig : ConfigProvider.getConfig()
                .getOptionalValue(AppChainPropertyKeys.API_KEYS, String.class);
    }

    private Set<String> snapshotAdminKeys() {
        Set<String> current = parsedSnapshotAdminKeys;
        if (current != null) return current;
        Set<String> parsed = ApiKeys.parseSet(snapshotAdminKeysConfig().orElse(""));
        parsedSnapshotAdminKeys = parsed;
        return parsed;
    }

    private Optional<String> snapshotAdminKeysConfig() {
        return snapshotAdminKeysConfig != null ? snapshotAdminKeysConfig
                : ConfigProvider.getConfig().getOptionalValue(
                AppChainPropertyKeys.SNAPSHOT_ADMIN_API_KEYS, String.class);
    }

}
