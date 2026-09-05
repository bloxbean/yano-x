package com.bloxbean.cardano.yano.appchain.explorer;

import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeCommitmentV1;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.ApprovalsContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.AuthenticatedMapContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.BalancesContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.DocTrailContract;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.KvRegistryContract;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed state subjects (ADR-037 §7.5) the explorer reads for its modules: canonical key
 * derivation from coordinates and contract-owned decoding of the authenticated value. Keys of
 * composite components are wrapped with the component prefix.
 */
public final class Subjects {
    public static final String DOCUMENT_HEAD = "document-head-v1";
    public static final String REGISTRY_ENTRY = "registry-entry-v1";
    public static final String ACCOUNT_BALANCE = "account-balance-v1";
    public static final String APPROVAL_OUTCOME = "basic-approval-outcome-v1";
    public static final String MAP_ENTRY = "authenticated-map-entry-v1";
    private static final HexFormat HEX = HexFormat.of();

    private Subjects() {
    }

    /** The subject a module's rows are checked against, or null for generic modules. */
    public static String forModule(String module) {
        return switch (module) {
            case StockModules.DOC_TRAIL -> DOCUMENT_HEAD;
            case StockModules.KV_REGISTRY -> REGISTRY_ENTRY;
            case StockModules.BALANCES -> ACCOUNT_BALANCE;
            case StockModules.APPROVALS -> APPROVAL_OUTCOME;
            case StockModules.AUTHENTICATED_MAP -> MAP_ENTRY;
            default -> null;
        };
    }

    /** Coordinates for a module row's subject id. */
    public static Map<String, String> coordinates(String module, String subject) {
        Map<String, String> coordinates = new LinkedHashMap<>();
        switch (module) {
            case StockModules.DOC_TRAIL -> coordinates.put("entity-id", subject);
            case StockModules.KV_REGISTRY -> coordinates.put("key", subject);
            case StockModules.BALANCES -> coordinates.put("account", subject);
            case StockModules.APPROVALS -> coordinates.put("proposal-id", subject);
            case StockModules.AUTHENTICATED_MAP -> {
                int slash = subject.indexOf('/');
                if (slash <= 0) throw usage("map subjects are collection/keyHex");
                coordinates.put("collection", subject.substring(0, slash));
                coordinates.put("key", subject.substring(slash + 1));
            }
            default -> throw usage("module " + module + " has no state subject");
        }
        return coordinates;
    }

    /** The physical state key for a subject; {@code componentId} is empty for plain chains. */
    public static byte[] key(String subjectId, Map<String, String> coordinates, String componentId) {
        byte[] local = switch (subjectId) {
            case DOCUMENT_HEAD -> DocTrailContract.entityKey(one(coordinates, "entity-id"));
            case REGISTRY_ENTRY -> hex(one(coordinates, "key"), 1, 256);
            case ACCOUNT_BALANCE -> BalancesContract.accountKey(one(coordinates, "account"));
            case APPROVAL_OUTCOME -> ApprovalsContract.itemKey(one(coordinates, "proposal-id"));
            case MAP_ENTRY -> AuthenticatedMapContract.canonicalKey(one(coordinates, "collection"),
                    hex(one(coordinates, "key"), 1, AuthenticatedMapContract.MAX_APPLICATION_KEY_BYTES));
            default -> throw usage("unknown subject " + subjectId);
        };
        return componentId == null || componentId.isEmpty()
                ? local : CompositeCommitmentV1.componentKey(componentId, local);
    }

    /** The decoded fact of a present value, as JSON-safe scalars. */
    public static Map<String, Object> decode(String subjectId, byte[] value) {
        Map<String, Object> fact = new LinkedHashMap<>();
        switch (subjectId) {
            case DOCUMENT_HEAD -> {
                DocTrailContract.Head head = DocTrailContract.decodeHead(value);
                fact.put("revision", head.count());
                fact.put("headDigestHex", HEX.formatHex(head.headHash()));
            }
            case REGISTRY_ENTRY -> {
                KvRegistryContract.Entry entry = KvRegistryContract.decodeEntry(value);
                fact.put("ownerHex", HEX.formatHex(entry.owner()));
                fact.put("valueHex", HEX.formatHex(entry.value()));
                fact.put("valueText", StockModules.printable(entry.value()));
            }
            case ACCOUNT_BALANCE -> fact.put("balance", BalancesContract.decodeBalance(value).toString());
            case APPROVAL_OUTCOME -> {
                ApprovalsContract.Item item = ApprovalsContract.decodeItem(value);
                fact.put("status", approvalStatus(item.status()));
                fact.put("proposerHex", HEX.formatHex(item.proposer()));
                fact.put("payloadDigestHex", HEX.formatHex(item.payloadHash()));
                fact.put("required", item.required());
                fact.put("deadlineMillis", item.deadlineMillis());
                List<String> approvers = new ArrayList<>();
                for (byte[] approver : item.approvers()) approvers.add(HEX.formatHex(approver));
                fact.put("approvalCount", approvers.size());
                fact.put("approversHex", approvers);
                fact.put("rejecterHex", HEX.formatHex(item.rejecter()));
            }
            case MAP_ENTRY -> {
                AuthenticatedMapContract.Entry entry = AuthenticatedMapContract.decodeEntry(value);
                fact.put("status", entry.status() == AuthenticatedMapContract.STATUS_ACTIVE ? "ACTIVE" : "REVOKED");
                fact.put("revision", entry.revision());
                fact.put("controllerHex", HEX.formatHex(entry.controller()));
                fact.put("valueHex", HEX.formatHex(entry.value()));
                fact.put("logicalValueHashHex", HEX.formatHex(entry.logicalValueHash()));
                fact.put("createdHeight", entry.createdHeight());
                fact.put("lastMutationHeight", entry.lastMutationHeight());
            }
            default -> throw usage("unknown subject " + subjectId);
        }
        return fact;
    }

    static String approvalStatus(int status) {
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "APPROVED";
            case 2 -> "REJECTED";
            case 3 -> "EXPIRED";
            default -> "STATUS_" + status;
        };
    }

    private static String one(Map<String, String> coordinates, String name) {
        String value = coordinates == null ? null : coordinates.get(name);
        if (value == null || value.isBlank()) throw usage("coordinate " + name + " is required");
        return value;
    }

    private static byte[] hex(String value, int minimum, int maximum) {
        if (value == null || (value.length() & 1) != 0 || !value.matches("[0-9a-f]+")) {
            throw usage("key coordinates must be lowercase hex");
        }
        byte[] decoded = HEX.parseHex(value);
        if (decoded.length < minimum || decoded.length > maximum) throw usage("key length out of bounds");
        return decoded;
    }

    private static ExplorerException usage(String message) {
        return new ExplorerException(ExplorerException.Error.USAGE, message);
    }
}
