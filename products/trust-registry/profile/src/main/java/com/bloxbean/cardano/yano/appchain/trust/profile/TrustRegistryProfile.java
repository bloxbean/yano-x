package com.bloxbean.cardano.yano.appchain.trust.profile;

import com.bloxbean.cardano.yano.appchain.roles.contracts.ApprovalPolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.DirectRolePolicyV1;
import com.bloxbean.cardano.yano.appchain.roles.contracts.RecordStatus;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapSchema;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The ADR-049 §2.1 registry profile: five collections on the governed authenticated
 * map, their policies, and the genesis-bound schemas that filter malformed values
 * before finalization. Nothing here executes on chain; the profile only fixes what
 * a registry genesis declares and how keys and values are laid out.
 */
public final class TrustRegistryProfile {
    public static final String PROFILE_ID = "trust-registry-v1";

    public static final String SUBJECTS = "subjects";
    public static final String STATUS = "status";
    public static final String STATUS_LISTS = "status-lists";
    public static final String ISSUERS = "issuers";
    public static final String SCHEMAS = "schemas";

    public static final String REGISTRAR_ROLE = "registrar";
    public static final String ISSUER_ROLE = "issuer";
    public static final String ADMIN_ROLE = "registry-admin";

    public static final String REGISTRAR_POLICY = "registrar-write";
    public static final String ISSUER_POLICY = "issuer-write";
    public static final String ONBOARDING_POLICY = "issuer-onboarding";
    public static final String ONBOARDING_CLAUSE = "independent-registrars";
    public static final String AUTHORITY_ID = "registry-admins";

    public static final String SUBJECT_SCHEMA = "subject-v1";
    public static final String STATUS_SCHEMA = "status-v1";
    public static final String STATUS_LIST_SCHEMA = "status-list-v1";
    public static final String ISSUER_SCHEMA = "issuer-v1";

    public static final int VALUE_VERSION = 1;
    public static final int MAX_SUBJECT_KEY_BYTES = 128;
    public static final int MAX_STATUS_KEY_BYTES = 128;
    public static final int MAX_LIST_KEY_BYTES = 64;
    public static final int MAX_ISSUER_KEY_BYTES = 128;
    public static final int MAX_SCHEMA_KEY_BYTES = 64;
    public static final int MAX_SUBJECT_VALUE_BYTES = 8_192;
    public static final int MAX_STATUS_VALUE_BYTES = 256;
    public static final int MAX_LIST_VALUE_BYTES = 1_024;
    public static final int MAX_ISSUER_VALUE_BYTES = 8_192;
    public static final int MAX_SCHEMA_VALUE_BYTES = 65_536;
    public static final int MAX_TEXT_BYTES = 128;
    public static final int MAX_AUTHORIZATIONS = 64;
    public static final int MAX_REASON_CODE = 65_535;
    /** Bitstring Status List minimum; the profile does not allow smaller lists. */
    public static final long MIN_BIT_LENGTH = 131_072;
    public static final long MAX_BIT_LENGTH = 1_048_576;
    public static final long MAX_HEIGHT = 1L << 62;
    public static final long DIRECT_AUTHORIZATION_LIFETIME_BLOCKS = 100;
    public static final long ONBOARDING_LIFETIME_BLOCKS = 600;
    public static final long ADMINISTRATOR_LIFETIME_BLOCKS = 1_000;

    /** Registry identifiers: opaque ids, hashes, and DID-like names; never a path separator. */
    public static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9._:~-]{1,128}");
    public static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,64}");
    public static final Pattern SCHEMA_ID = Pattern.compile("[A-Za-z0-9._:~-]{1,64}");

    private static final Pattern STATUS_INDEX = Pattern.compile("0|[1-9][0-9]{0,6}");

    private TrustRegistryProfile() {
    }

    /** Collections sorted by id, exactly as the map genesis retains them. */
    public static List<AuthenticatedMapContract.CollectionDescriptor> collections() {
        return List.of(
                new AuthenticatedMapContract.CollectionDescriptor(
                        ISSUERS, AuthenticatedMapContract.AUTH_APPROVAL, ONBOARDING_POLICY,
                        false, MAX_ISSUER_KEY_BYTES, MAX_ISSUER_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, ISSUER_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        SCHEMAS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, REGISTRAR_POLICY,
                        false, MAX_SCHEMA_KEY_BYTES, MAX_SCHEMA_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_OPAQUE, ""),
                new AuthenticatedMapContract.CollectionDescriptor(
                        STATUS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, ISSUER_POLICY,
                        false, MAX_STATUS_KEY_BYTES, MAX_STATUS_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, STATUS_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        STATUS_LISTS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, ISSUER_POLICY,
                        false, MAX_LIST_KEY_BYTES, MAX_LIST_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, STATUS_LIST_SCHEMA),
                new AuthenticatedMapContract.CollectionDescriptor(
                        SUBJECTS, AuthenticatedMapContract.AUTH_GOVERNED_ROLE, REGISTRAR_POLICY,
                        false, MAX_SUBJECT_KEY_BYTES, MAX_SUBJECT_VALUE_BYTES,
                        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR, SUBJECT_SCHEMA));
    }

    public static List<AuthenticatedMapContract.ValidatorDescriptor> validators() {
        return List.of(
                AuthenticatedMapContract.ValidatorDescriptor.schema(ISSUER_SCHEMA, issuerSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(STATUS_SCHEMA, statusSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        STATUS_LIST_SCHEMA, statusListSchema()),
                AuthenticatedMapContract.ValidatorDescriptor.schema(
                        SUBJECT_SCHEMA, subjectSchema()));
    }

    public static List<DirectRolePolicyV1> directPolicies() {
        return List.of(
                new DirectRolePolicyV1(ISSUER_POLICY, 1, RecordStatus.ACTIVE, ISSUER_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS),
                new DirectRolePolicyV1(REGISTRAR_POLICY, 1, RecordStatus.ACTIVE, REGISTRAR_ROLE,
                        DIRECT_AUTHORIZATION_LIFETIME_BLOCKS));
    }

    /** Issuer onboarding: a registrar proposes, two registrars from distinct organizations approve. */
    public static ApprovalPolicyV1 onboardingPolicy() {
        return new ApprovalPolicyV1(
                ONBOARDING_POLICY, 1, RecordStatus.ACTIVE, List.of(REGISTRAR_ROLE),
                List.of(new ApprovalPolicyV1.RequiredClause(
                        ONBOARDING_CLAUSE, REGISTRAR_ROLE, 2,
                        ApprovalPolicyV1.DistinctBy.ORGANIZATION)),
                ApprovalPolicyV1.RejectionMode.ANY_ELIGIBLE, ONBOARDING_LIFETIME_BLOCKS);
    }

    /** Policy id each collection is written under, for discovery and for choosing the signing role. */
    public static String policyOf(String collectionId) {
        return switch (collectionId) {
            case SUBJECTS, SCHEMAS -> REGISTRAR_POLICY;
            case STATUS, STATUS_LISTS -> ISSUER_POLICY;
            case ISSUERS -> ONBOARDING_POLICY;
            default -> throw new IllegalArgumentException(
                    "unknown registry collection " + collectionId);
        };
    }

    public static String roleOf(String collectionId) {
        return switch (policyOf(collectionId)) {
            case REGISTRAR_POLICY -> REGISTRAR_ROLE;
            case ISSUER_POLICY -> ISSUER_ROLE;
            default -> throw new IllegalArgumentException(
                    collectionId + " is written through the approval route, not a role");
        };
    }

    public static byte[] subjectKey(String subjectId) {
        return identifierKey(subjectId, IDENTIFIER, "subject id");
    }

    public static byte[] issuerKey(String entityId) {
        return identifierKey(entityId, IDENTIFIER, "issuer entity id");
    }

    public static byte[] listKey(String listId) {
        return identifierKey(listId, LIST_ID, "status list id");
    }

    public static byte[] schemaKey(String schemaId) {
        return identifierKey(schemaId, SCHEMA_ID, "schema id");
    }

    /** {@code <listId>/<index>}: one entry per credential index of a status list. */
    public static byte[] statusKey(String listId, long index) {
        requireListId(listId);
        if (index < 0 || index >= MAX_BIT_LENGTH) {
            throw new IllegalArgumentException("status index must be within the list bound");
        }
        return (listId + "/" + index).getBytes(StandardCharsets.US_ASCII);
    }

    public static StatusKey parseStatusKey(byte[] key) {
        String text = new String(Objects.requireNonNull(key, "key"), StandardCharsets.US_ASCII);
        int separator = text.lastIndexOf('/');
        if (separator <= 0 || separator == text.length() - 1) {
            throw new IllegalArgumentException("status key must be <listId>/<index>");
        }
        String listId = text.substring(0, separator);
        String index = text.substring(separator + 1);
        requireListId(listId);
        if (!STATUS_INDEX.matcher(index).matches()) {
            throw new IllegalArgumentException("status index must be a canonical decimal");
        }
        long parsed = Long.parseLong(index);
        if (parsed >= MAX_BIT_LENGTH) {
            throw new IllegalArgumentException("status index must be within the list bound");
        }
        return new StatusKey(listId, parsed);
    }

    public static String requireListId(String listId) {
        if (listId == null || !LIST_ID.matcher(listId).matches()) {
            throw new IllegalArgumentException(
                    "status list id must match " + LIST_ID.pattern());
        }
        return listId;
    }

    public static String requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }

    /** Whether a map genesis declares this profile: the five collections under the profile's policies. */
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
        return genesis.governedGenesis().approvalPolicy(ONBOARDING_POLICY) != null
                && genesis.governedGenesis().directPolicy(ISSUER_POLICY) != null
                && genesis.governedGenesis().directPolicy(REGISTRAR_POLICY) != null;
    }

    public record StatusKey(String listId, long index) {
    }

    static byte[] subjectSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                AuthenticatedMapSchema.Occurrence.required(versionNode()),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.TextNode(1, 63, null)),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.TextNode(1, 64, null)),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.BytesNode(32, 32, null))))).definition();
    }

    static byte[] statusSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                AuthenticatedMapSchema.Occurrence.required(versionNode()),
                AuthenticatedMapSchema.Occurrence.required(unsigned(0, 1)),
                AuthenticatedMapSchema.Occurrence.required(
                        unsigned(0, MAX_REASON_CODE))))).definition();
    }

    static byte[] statusListSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                AuthenticatedMapSchema.Occurrence.required(versionNode()),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.TextNode(1, 32, null)),
                AuthenticatedMapSchema.Occurrence.required(
                        unsigned(MIN_BIT_LENGTH, MAX_BIT_LENGTH)),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.BytesNode(32, 32, null)),
                AuthenticatedMapSchema.Occurrence.required(
                        unsigned(0, MAX_HEIGHT))))).definition();
    }

    static byte[] issuerSchema() {
        return AuthenticatedMapSchema.of(new AuthenticatedMapSchema.ArrayNode(List.of(
                AuthenticatedMapSchema.Occurrence.required(versionNode()),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.TextNode(1, MAX_TEXT_BYTES, null)),
                AuthenticatedMapSchema.Occurrence.required(
                        new AuthenticatedMapSchema.ArrayNode(List.of(
                                new AuthenticatedMapSchema.Occurrence(0, MAX_AUTHORIZATIONS,
                                        new AuthenticatedMapSchema.TextNode(
                                                1, MAX_TEXT_BYTES, null))))),
                AuthenticatedMapSchema.Occurrence.required(unsigned(0, MAX_HEIGHT)),
                AuthenticatedMapSchema.Occurrence.required(
                        unsigned(0, MAX_HEIGHT))))).definition();
    }

    private static AuthenticatedMapSchema.Node versionNode() {
        return unsigned(VALUE_VERSION, VALUE_VERSION);
    }

    private static AuthenticatedMapSchema.Node unsigned(long minimum, long maximum) {
        return new AuthenticatedMapSchema.IntegerNode(AuthenticatedMapSchema.INTEGER_UINT,
                BigInteger.valueOf(minimum), BigInteger.valueOf(maximum));
    }

    private static byte[] identifierKey(String value, Pattern pattern, String name) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + pattern.pattern());
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
