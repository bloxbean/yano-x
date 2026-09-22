package org.yanoproject.x.roles.contracts;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.yanoproject.x.roles.contracts.internal.RoleWorkflowCbor;

/**
 * Versioned declarative-approval transport: {@code [1, signedCommandBytes, actionBytes]}.
 *
 * <p>This is a new transport, not an extension of the frozen {@link SignedActorCommandV1} array. PROPOSE
 * carries bounded action bytes; every other operation carries an empty byte string. The actor signature
 * still signs the original statement and its scoped payload hash, never this wrapper. The target action
 * codec and authorizer must verify canonical action encoding and recompute that hash before consumption.
 * The role module deliberately does not know a product's action schema or manufacture its evidence.
 */
public record StagedActorCommandV1(SignedActorCommandV1 command, byte[] action) {
    public static final int MAX_ACTION_BYTES = 32_768;
    public static final int MAX_ENCODED_BYTES = 65_536;

    public StagedActorCommandV1 {
        if (command == null || action == null || action.length > MAX_ACTION_BYTES
                || (command.statement().action() == ActorStatementV1.Action.PROPOSE) != (action.length > 0)) {
            throw new IllegalArgumentException("PROPOSE requires an action; other operations must not supply one");
        }
        action = action.clone();
    }

    @Override public byte[] action() { return action.clone(); }

    /** Encodes the preferred-CBOR wrapper without changing the signed statement. */
    public byte[] encode() {
        Array value = new Array();
        value.add(new UnsignedInteger(1));
        value.add(new ByteString(command.encode()));
        value.add(new ByteString(action));
        byte[] encoded = RoleWorkflowCbor.encode(value);
        if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("staged command exceeds limit");
        return encoded;
    }

    /** Decodes a bounded, canonical wrapper; trailing, alternate, or unsupported forms fail closed. */
    public static StagedActorCommandV1 decode(byte[] encoded) {
        var fields = RoleWorkflowCbor.decodeArray(encoded, 3, MAX_ENCODED_BYTES, 8).getDataItems();
        OrganizationRecordV1.requireVersion(fields.getFirst());
        var result = new StagedActorCommandV1(SignedActorCommandV1.decode(RoleWorkflowCbor.bytes(fields.get(1))),
                RoleWorkflowCbor.bytes(fields.get(2)));
        RoleWorkflowCbor.requireCanonical(encoded, result.encode());
        return result;
    }

    /** Approval-owner local staging key; intentionally separate from frozen proposal and index records. */
    public static byte[] stateKey(String proposalId) {
        return ("q-action/v1/" + RoleWorkflowIdentifiers.id(proposalId, "proposalId"))
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
