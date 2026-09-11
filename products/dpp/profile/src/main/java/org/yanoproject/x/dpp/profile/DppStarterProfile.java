package org.yanoproject.x.dpp.profile;

import org.yanoproject.x.roles.contracts.ApprovalPolicyV1;
import org.yanoproject.x.roles.contracts.DirectRolePolicyV1;
import org.yanoproject.x.roles.contracts.RecordStatus;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapContract;
import org.yanoproject.x.stdlib.contracts.AuthenticatedMapSchema;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The ADR-051 §2.1 DPP starter profile: five collections on the governed authenticated map,
 * their policies, and the genesis-bound schemas that filter malformed values before
 * finalization. This is a prototype of the infrastructure a DPP registry needs (ADR-026
 * §5.1); nothing here enforces a DPP lifecycle rule, and nothing here executes on chain.
 */
public final class DppStarterProfile {
    public static final String PROFILE_ID = "dpp-starter-v1";
    public static final String PROTOTYPE_NOTICE =
            "DPP starter: a configuration-only prototype on the stock authenticated map "
                    + "(ADR-051). Not the DPP product of ADR-026; no conformance claim.";

    public static final String PRODUCTS = "products";
    public static final String VERSIONS = "product-versions";
    public static final String CLAIMS = "claims";
    public static final String EVENTS = "events";
    public static final String CERTIFICATES = "certificates";
    public static final List<String> COLLECTION_IDS =
            List.of(CERTIFICATES, CLAIMS, EVENTS, VERSIONS, PRODUCTS);

    public static final String MANUFACTURER_ROLE = "manufacturer";
    public static final String OPERATOR_ROLE = "operator";
    public static final String CLAIM_ISSUER_ROLE = "claim-issuer";
    public static final String CERTIFIER_ROLE = "certifier";
    public static final String AUDITOR_ROLE = "auditor";
    public static final String ADMIN_ROLE = "dpp-admin";

    public static final String MANUFACTURER_POLICY = "manufacturer-write";
    public static final String CLAIM_ISSUER_POLICY = "claim-issuer-write";
    public static final String OPERATOR_POLICY = "operator-write";
    public static final String CERTIFICATION_POLICY = "certification";
    public static final String CERTIFICATION_CLAUSE = "independent-auditors";
    public static final String AUTHORITY_ID = "dpp-admins";

    public static final String PRODUCT_SCHEMA = "dpp-product-v1";
    public static final String VERSION_SCHEMA = "dpp-version-v1";
    public static final String CLAIM_SCHEMA = "dpp-claim-v1";
    public static final String EVENT_SCHEMA = "dpp-event-v1";
    public static final String CERTIFICATE_SCHEMA = "dpp-certificate-v1";

    public static final int VALUE_VERSION = 1;
    public static final int MAX_KEY_BYTES = AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES;
    public static final int MAX_PRODUCT_VALUE_BYTES = 1_024;
    public static final int MAX_VERSION_VALUE_BYTES = 1_024;
    public static final int MAX_CLAIM_VALUE_BYTES = 8_192;
    public static final int MAX_EVENT_VALUE_BYTES = 1_024;
    public static final int MAX_CERTIFICATE_VALUE_BYTES = 1_024;
    public static final int MAX_ORGANIZATION_BYTES = 63;
    public static final int MAX_PROFILE_ID_BYTES = 64;
    public static final int MAX_MEDIA_TYPE_BYTES = 64;
    public static final int MAX_REFERENCE_BYTES = 512;
    public static final int MAX_CLAIM_TEXT_BYTES = 4_096;
    public static final int MAX_EVENT_TYPE_BYTES = 32;
    public static final int MAX_LOCATION_BYTES = 128;
    public static final int MAX_NOTE_BYTES = 256;
    public static final int MAX_CERTIFICATE_TYPE_BYTES = 64;
    public static final long MAX_HEIGHT = 1L << 62;
    public static final long MAX_VERSION = Integer.MAX_VALUE;
    public static final long MAX_BYTE_LENGTH = 1L << 40;
    public static final long MAX_OBSERVED_AT = 1L << 40;
    public static final long DIRECT_AUTHORIZATION_LIFETIME_BLOCKS = 100;
    public static final long CERTIFICATION_LIFETIME_BLOCKS = 600;
    public static final long ADMINISTRATOR_LIFETIME_BLOCKS = 1_000;

    /** Product statuses, ADR-026 §7.3; a revoked passport is the map tombstone. */
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_INACTIVE = 2;
    public static final int STATUS_REPLACED = 3;
    public static final int STATUS_RETIRED = 4;
    public static final List<String> STATUS_NAMES =
            List.of("DRAFT", "ACTIVE", "INACTIVE", "REPLACED", "RETIRED");

    public static final int VISIBILITY_PUBLIC = 0;
    public static final int VISIBILITY_COMMITTED = 1;

    public static final List<String> KNOWN_EVENT_TYPES = List.of(
            "MANUFACTURED", "SHIPPED", "RECEIVED", "INSPECTED", "REPAIRED", "RECYCLED");

    /**
     * Identifiers exclude {@code /}, the key separator. A product id is bounded to 64 bytes so
     * that every composite key stays within the map's 128-byte application-key bound.
     */
    public static final Pattern PRODUCT_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,64}");
    public static final Pattern CLAIM_TYPE = Pattern.compile("[A-Za-z0-9._-]{1,24}");
    public static final Pattern CLAIM_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,36}");
    public static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,63}");
    public static final Pattern CERTIFICATE_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,128}");
    public static final Pattern GTIN = Pattern.compile("[0-9]{8}|[0-9]{12,14}");
    public static final Pattern SERIAL = Pattern.compile("[A-Za-z0-9._-]{1,20}");
    private static final Pattern VERSION = Pattern.compile("[1-9][0-9]{0,9}");

    private DppStarterProfile() {
    }

    /** Collections sorted by id, exactly as the map genesis retains them. */
    public static List<AuthenticatedMapContract.CollectionDescriptor> collections() {
        return List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        CERTIFICATES, AuthenticatedMapContract.AUTH_APPROVAL, CERTIFICATION_POLICY,
                        false, MAX_KEY_BYTES, MAX_CERTIFICATE_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, CERTIFICATE_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        CLAIMS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, CLAIM_ISSUER_POLICY,
                        false, MAX_KEY_BYTES, MAX_CLAIM_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, CLAIM_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        EVENTS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, OPERATOR_POLICY,
                        false, MAX_KEY_BYTES, MAX_EVENT_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, EVENT_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        VERSIONS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, MANUFACTURER_POLICY,
                        false, MAX_KEY_BYTES, MAX_VERSION_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, VERSION_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        PRODUCTS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, MANUFACTURER_POLICY,
                        false, MAX_KEY_BYTES, MAX_PRODUCT_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, PRODUCT_SCHEMA));
    }

    public static List<AuthenticatedMapContract.ValidatorDescriptor> validators() {
        return List.of(
                AuthenticatedMapContract.ValidatorDescriptor.schema(CERTIFICATE_SCHEMA, certificateSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(CLAIM_SCHEMA, claimSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(EVENT_SCHEMA, eventSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(VERSION_SCHEMA, versionSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(PRODUCT_SCHEMA, productSchema()));
    }

    public static List<DirectRolePolicyV1> directPolicies() {
        return List.of(
                new DirectRolePolicyV1(CLAIM_ISSUER_POLICY, 1, RecordStatus.ACTIVE, CLAIM_ISSUER_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS),
                new DirectRolePolicyV1(MANUFACTURER_POLICY, 1, RecordStatus.ACTIVE, MANUFACTURER_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS),
                new DirectRolePolicyV1(OPERATOR_POLICY, 1, RecordStatus.ACTIVE, OPERATOR_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS));
    }

    /** Certification: a certifier proposes, two auditors from distinct organizations approve. */
    public static ApprovalPolicyV1 certificationPolicy() {
        return new ApprovalPolicyV1(
                CERTIFICATION_POLICY, 1, RecordStatus.ACTIVE, List.of(CERTIFIER_ROLE),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        CERTIFICATION_CLAUSE, AUDITOR_ROLE, 2,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, CERTIFICATION_LIFETIME_BLOCKS);
    }

    /** Policy id each collection is written under. */
    public static String policyOf(String collectionId) {
        return switch (collectionId) {
            case PRODUCTS, VERSIONS -> MANUFACTURER_POLICY;
            case CLAIMS -> CLAIM_ISSUER_POLICY;
            case EVENTS -> OPERATOR_POLICY;
            case CERTIFICATES -> CERTIFICATION_POLICY;
            default -> throw new IllegalArgumentException("unknown DPP collection " + collectionId);
        };
    }

    public static String roleOf(String collectionId) {
        return switch (policyOf(collectionId)) {
            case MANUFACTURER_POLICY -> MANUFACTURER_ROLE;
            case CLAIM_ISSUER_POLICY -> CLAIM_ISSUER_ROLE;
            case OPERATOR_POLICY -> OPERATOR_ROLE;
            default -> throw new IllegalArgumentException(
                    collectionId + " is written through the approval route, not a role");
        };
    }

    // ------------------------------------------------------------------ keys

    public static String requireProductId(String productId) {
        return require(productId, PRODUCT_ID, "product id");
    }

    public static String requireClaimType(String claimType) {
        return require(claimType, CLAIM_TYPE, "claim type");
    }

    public static String requireClaimId(String claimId) {
        return require(claimId, CLAIM_ID, "claim id");
    }

    public static String requireEventId(String eventId) {
        return require(eventId, EVENT_ID, "event id");
    }

    public static String requireCertificateId(String certificateId) {
        return require(certificateId, CERTIFICATE_ID, "certificate id");
    }

    public static long requireVersion(long version) {
        if (version < 1 || version > MAX_VERSION) {
            throw new IllegalArgumentException("version must be 1-" + MAX_VERSION);
        }
        return version;
    }

    public static int requireStatus(int status) {
        if (status < STATUS_DRAFT || status > STATUS_RETIRED) {
            throw new IllegalArgumentException("status must be 0-4 (" + STATUS_NAMES + ")");
        }
        return status;
    }

    public static int statusCode(String name) {
        int index = STATUS_NAMES.indexOf(Objects.requireNonNull(name, "status").toUpperCase());
        if (index < 0) {
            throw new IllegalArgumentException("status must be one of " + STATUS_NAMES);
        }
        return index;
    }

    public static String statusName(int status) {
        return STATUS_NAMES.get(requireStatus(status));
    }

    /**
     * The product id of a GS1 Digital Link path: {@code /01/<gtin>[/21/<serial>]}, the GTIN
     * zero-padded to 14 digits.
     */
    public static String gs1ProductId(String gtin, String serial) {
        if (gtin == null || !GTIN.matcher(gtin).matches()) {
            throw new IllegalArgumentException("GTIN must be 8, 12, 13, or 14 digits");
        }
        String padded = "0".repeat(14 - gtin.length()) + gtin;
        if (serial == null || serial.isEmpty()) {
            return "gtin:" + padded;
        }
        if (!SERIAL.matcher(serial).matches()) {
            throw new IllegalArgumentException("serial must match " + SERIAL.pattern());
        }
        return "gtin:" + padded + ":21:" + serial;
    }

    public static byte[] productKey(String productId) {
        return ascii(requireProductId(productId));
    }

    public static byte[] versionKey(String productId, long version) {
        return ascii(requireProductId(productId) + "/" + requireVersion(version));
    }

    public static byte[] claimKey(String productId, String claimType, String claimId) {
        return ascii(requireProductId(productId) + "/" + requireClaimType(claimType) + "/"
                + requireClaimId(claimId));
    }

    public static byte[] eventKey(String productId, String eventId) {
        return ascii(requireProductId(productId) + "/" + requireEventId(eventId));
    }

    public static byte[] certificateKey(String certificateId) {
        return ascii(requireCertificateId(certificateId));
    }

    public record VersionKey(String productId, long version) {
    }

    public record ClaimKey(String productId, String claimType, String claimId) {
    }

    public record EventKey(String productId, String eventId) {
    }

    public static VersionKey parseVersionKey(byte[] key) {
        String[] parts = split(key, 2, "version key must be <productId>/<version>");
        if (!VERSION.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("version must be a canonical positive decimal");
        }
        return new VersionKey(requireProductId(parts[0]), requireVersion(Long.parseLong(parts[1])));
    }

    public static ClaimKey parseClaimKey(byte[] key) {
        String[] parts = split(key, 3, "claim key must be <productId>/<claimType>/<claimId>");
        return new ClaimKey(requireProductId(parts[0]), requireClaimType(parts[1]),
                requireClaimId(parts[2]));
    }

    public static EventKey parseEventKey(byte[] key) {
        String[] parts = split(key, 2, "event key must be <productId>/<eventId>");
        return new EventKey(requireProductId(parts[0]), requireEventId(parts[1]));
    }

    /**
     * The product a key of {@code collection} belongs to, or null when the key does not carry
     * it (a certificate key names the certificate; its product is in the value).
     */
    public static String productIdOf(String collection, byte[] key) {
        try {
            return switch (collection) {
                case PRODUCTS -> requireProductId(text(key));
                case VERSIONS -> parseVersionKey(key).productId();
                case CLAIMS -> parseClaimKey(key).productId();
                case EVENTS -> parseEventKey(key).productId();
                default -> null;
            };
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public static String text(byte[] key) {
        return new String(Objects.requireNonNull(key, "key"), StandardCharsets.US_ASCII);
    }

    /** Whether a map genesis declares this profile: the five collections under its policies. */
    public static boolean matches(AuthenticatedMapContract.Genesis genesis) {
        if (genesis == null || genesis.governedGenesis() == null) return false;
        Map<String, AuthenticatedMapContract.CollectionDescriptor> declared =
                genesis.collections().stream().collect(Collectors.toMap(
                        AuthenticatedMapContract.CollectionDescriptor::id, descriptor -> descriptor,
                        (left, right) -> left));
        for (AuthenticatedMapContract.CollectionDescriptor expected : collections()) {
            AuthenticatedMapContract.CollectionDescriptor actual = declared.get(expected.id());
            if (actual == null
                    || actual.authorization() != expected.authorization()
                    || !actual.authorizationPolicyId().equals(expected.authorizationPolicyId())
                    || actual.restoreAllowed()
                    || actual.valueEncoding() != expected.valueEncoding()) {
                return false;
            }
        }
        return genesis.governedGenesis().approvalPolicy(CERTIFICATION_POLICY) != null
                && genesis.governedGenesis().directPolicy(MANUFACTURER_POLICY) != null
                && genesis.governedGenesis().directPolicy(CLAIM_ISSUER_POLICY) != null
                && genesis.governedGenesis().directPolicy(OPERATOR_POLICY) != null;
    }

    // ------------------------------------------------------------------ schemas

    static byte[] productSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(text(1, MAX_ORGANIZATION_BYTES)),
                required(unsigned(STATUS_DRAFT, STATUS_RETIRED)),
                required(unsigned(0, MAX_VERSION)),
                required(text(0, 64)),
                required(text(1, MAX_PROFILE_ID_BYTES))))).definition();
    }

    static byte[] versionSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(bytes(32, 32)),
                required(text(1, MAX_MEDIA_TYPE_BYTES)),
                required(text(0, MAX_REFERENCE_BYTES)),
                required(unsigned(0, MAX_BYTE_LENGTH))))).definition();
    }

    static byte[] claimSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(unsigned(VISIBILITY_PUBLIC, VISIBILITY_COMMITTED)),
                required(bytes(1, MAX_CLAIM_TEXT_BYTES)),
                required(text(1, MAX_ORGANIZATION_BYTES)),
                required(unsigned(0, MAX_HEIGHT)),
                required(unsigned(0, MAX_HEIGHT)),
                required(optionalDigest())))).definition();
    }

    static byte[] eventSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(text(1, MAX_EVENT_TYPE_BYTES)),
                required(text(1, MAX_ORGANIZATION_BYTES)),
                required(unsigned(0, MAX_OBSERVED_AT)),
                required(text(0, MAX_LOCATION_BYTES)),
                required(optionalDigest()),
                required(text(0, MAX_NOTE_BYTES))))).definition();
    }

    static byte[] certificateSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                required(versionNode()),
                required(text(1, 64)),
                required(text(1, MAX_CERTIFICATE_TYPE_BYTES)),
                required(text(1, MAX_ORGANIZATION_BYTES)),
                required(bytes(32, 32)),
                required(unsigned(0, MAX_HEIGHT)),
                required(unsigned(0, MAX_HEIGHT))))).definition();
    }

    private static AuthenticatedMapSchema.Occurrence required(AuthenticatedMapSchema.Node node) {
        return AuthenticatedMapSchema.Occurrence.required(node);
    }

    private static AuthenticatedMapSchema.Node versionNode() {
        return unsigned(VALUE_VERSION, VALUE_VERSION);
    }

    private static AuthenticatedMapSchema.Node text(int minimum, int maximum) {
        return new AuthenticatedMapSchema.TextNode(minimum, maximum, null);
    }

    private static AuthenticatedMapSchema.Node bytes(int minimum, int maximum) {
        return new AuthenticatedMapSchema.BytesNode(minimum, maximum, null);
    }

    /** An evidence digest is absent (empty bytes) or exactly 32 bytes. */
    private static AuthenticatedMapSchema.Node optionalDigest() {
        return new AuthenticatedMapSchema.ChoiceNode(List.of(bytes(0, 0), bytes(32, 32)));
    }

    private static AuthenticatedMapSchema.Node unsigned(long minimum, long maximum) {
        return new AuthenticatedMapSchema.IntegerNode(AuthenticatedMapSchema.INTEGER_UINT,
                BigInteger.valueOf(minimum), BigInteger.valueOf(maximum));
    }

    private static String require(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + pattern.pattern());
        }
        return value;
    }

    private static byte[] ascii(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key exceeds " + MAX_KEY_BYTES + " bytes");
        }
        return bytes;
    }

    private static String[] split(byte[] key, int parts, String message) {
        String[] split = text(key).split("/", -1);
        if (split.length != parts) {
            throw new IllegalArgumentException(message);
        }
        return split;
    }
}
