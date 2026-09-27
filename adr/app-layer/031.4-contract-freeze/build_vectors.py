#!/usr/bin/env python3
"""Independently builds the declarative-binding golden vectors (ADR-031.3, amended by ADR-031.4).

This encoder shares no code with the Java BindingCbor codec: it uses cbor2 with explicit
canonical map ordering (encoded text length, then unsigned UTF-8 bytes), definite lengths and
shortest integer forms. The Java codecs must reproduce these bytes exactly (ADR-031.4 Phase 2).
"""
import sys
import cbor2

Z = bytes(32)
D = bytes([0x11]) * 32


class M(dict):
    """Marker for a CBOR map; keys are canonicalized at encode time."""


def canon(value):
    if isinstance(value, M):
        keys = sorted(value.keys(), key=lambda k: (len(k.encode("utf-8")), k.encode("utf-8")))
        return {k: canon(value[k]) for k in keys}
    if isinstance(value, list):
        return [canon(v) for v in value]
    return value


def enc(value):
    return cbor2.dumps(canon(value), canonical=True)


def expr(result_type, node):
    return [1, result_type, node]


def field(scope, name):
    return [1, scope, name]


def lit(value):
    return [0, value]


def call(op, *args):
    return [2, op, list(args)]


EVENT, COMMAND, PARAMS, CONFIG, CONTEXT, FACTS, READS, WRITE = range(8)


def read(name, field_name):
    return [1, READS, name, field_name]


def read_value(name, field_name):
    return [1, READS, name, "value", field_name]


def element(field_name):
    return [1, WRITE, field_name]


def element_value(field_name):
    return [1, WRITE, "value", field_name]


def all_writes(body):
    return [3, 0, body]


def any_write(body):
    return [3, 1, body]
INTEGER, TEXT, BYTES, BOOLEAN, BINDING = range(5)
LIMITS_ORIGINAL = [8, 32, 4096, 4096, 2, 8, 4096, 128, 16, 4096, 262144, 4194304, 4]
LIMITS_DEFAULT = [8, 32, 4096, 65536, 2, 8, 65536, 128, 16, 65536, 1048576, 33554432, 4]
IR_HEAD = [1, "yano-x-binding-functions-v1", "yano-x-cel-v1"]

vectors = {}
roots = {}

# Restricted expressions: an event threshold and a verified-role membership test.
vectors["expression.threshold"] = expr(3, call("ge", field(EVENT, "amount"), lit(10)))
roots["expression.threshold"] = "binding-expression-v1"
vectors["expression.fact-role"] = expr(3, call("in", field(PARAMS, "role"), field(FACTS, "roles")))
roots["expression.fact-role"] = "binding-expression-v1"

# ADR-031.4: a governed limit read from a schema-typed map record, the write-view quantifier with
# startsWith over coverage fields, and size.
vectors["expression.read-value"] = expr(3, call("le", field(COMMAND, "amount"), read_value("limits", "max")))
roots["expression.read-value"] = "binding-expression-v1"
vectors["expression.write-scope"] = expr(3, all_writes(call("or",
    call("ne", element("collection"), field(PARAMS, "collection")),
    call("and", call("eq", element("coverage"), lit("direct")),
         call("startsWith", element("keyText"), call("concat", element("actorOrganizationId"), lit("/")))))))
roots["expression.write-scope"] = "binding-expression-v1"
vectors["expression.size"] = expr(3, call("le", call("size", field(EVENT, "memo")), lit(64)))
roots["expression.size"] = "binding-expression-v1"
vectors["expression.write-role"] = expr(3, all_writes(call("or",
    call("and", call("ne", element("op"), lit("REVOKE")), call("ne", element("op"), lit("RESTORE"))),
    call("and", call("eq", element("coverage"), lit("direct")), call("in", field(PARAMS, "role"),
                                                                        element("actorRoles"))))))
roots["expression.write-role"] = "binding-expression-v1"

# The historical forwarding document in the amended layout: no rules, no attachments.
vectors["ir.forward"] = IR_HEAD + [1, [
    ["source", "test", "source.v1", M(), 0, 1, []],
    ["target", "test", "target.v1", M(), 0, 1, []],
], [], [
    ["forward", "source", "composite.command-accepted.v1", [], [0, "target", "append", [2, "body"]]],
], LIMITS_ORIGINAL]
roots["ir.forward"] = "binding-ir-v1"

# A wire-only document (machine ids are "test"; it is not a buildable profile) with every scope at
# every legal use site, both clause kinds, parameter defaults of each kind, byte-wise parameter order
# ("aa" before "b") against length-first attachment-map key order, a binding-typed parameter, a
# lookup keyed on a fact, and "in" over a kernel-declared text set.
rules = [
    ["actor-registered", "ACTOR_NOT_REGISTERED", None, [], [], [
        [1, "suppliers", [2, "utf8-bytes", [[0, FACTS, "actorId"]]], [0]],
    ]],
    ["flagged", "FLAGGED", None, [["aa", INTEGER, None], ["b", BOOLEAN, True]], [], [
        [2, expr(3, call("or", field(PARAMS, "b"), call("gt", field(PARAMS, "aa"), lit(0))))],
    ]],
    ["only-via-binding", "DIRECT_SUBMISSION_FORBIDDEN", None, [["binding", BINDING, None]], [], [
        [2, expr(3, call("and", field(CONTEXT, "derived"),
                         call("eq", field(CONTEXT, "binding"), field(PARAMS, "binding"))))],
    ]],
    ["operator-for-direct-writes", "ROLE_REQUIRED", None, [["role", TEXT, None]], [], [
        [2, expr(3, call("or", call("eq", field(FACTS, "directActorCount"), lit(0)),
                         call("in", field(PARAMS, "role"), field(FACTS, "roles"))))],
    ]],
    ["registered-sender", "NOT_REGISTERED", None, [["tier", BYTES, b"\x01"]], [], [
        [1, "suppliers", [0, CONTEXT, "sender"], [0]],
        [1, "suppliers", [0, CONTEXT, "sender"], [3, PARAMS, "tier"]],
    ]],
    ["transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer", [["maxAmount", INTEGER, 10000]], [], [
        [2, expr(3, call("and", call("gt", field(COMMAND, "amount"), lit(0)),
                         call("le", field(COMMAND, "amount"), field(PARAMS, "maxAmount"))))],
        [2, expr(3, call("ne", field(CONFIG, "minter"), lit("")))],
    ]],
]
vectors["ir.admission"] = IR_HEAD + [1, [
    ["suppliers", "test", "suppliers.command.v1", M({"value-format": "utf8"}), 0, 1, [
        ["flagged", M({"b": True, "aa": 1})],
    ]],
    ["wallet", "test", "wallet.command.v1", M({"minter": "treasury"}), 0, 1, [
        ["transfer-limit", M({"maxAmount": 10000})],
        ["registered-sender", M({"tier": b"\x01"})],
    ]],
    ["registry", "test", "registry.command.v1", M(), 0, 1, [
        ["flagged", M({"b": False, "aa": 2})],
        ["operator-for-direct-writes", M({"role": "operator"})],
        ["actor-registered", M()],
    ]],
    ["audit", "test", "audit.command.v1", M(), 0, 1, [
        ["only-via-binding", M({"binding": "wallet-to-audit"})],
    ]],
], rules, [
    ["wallet-to-audit", "wallet", "recorded.v1", [
        [0, "amount", 5, 1],
        [2, expr(3, call("not", field(CONTEXT, "derived")))],
        [1, "suppliers", [0, CONTEXT, "sender"], [3, EVENT, "memo"]],
    ], [0, "audit", "append", [1,
        ["entityId", [0, EVENT, "to"]],
        ["entryHash", [2, "blake2b-256", [[0, EVENT, "memo"]]]],
        ["reference", [3, expr(1, call("concat", lit("wallet:"), field(CONTEXT, "binding")))]],
    ]]],
], LIMITS_DEFAULT]
roots["ir.admission"] = "binding-ir-v1"


# ADR-031.4: a wire-only document with reads (literal, context and parameter keys; entry and value
# fields; "present"), content and coverage quantifiers, "all" and "exists", startsWith and size.
typed_rules = [
    ["feed-open-and-in-range", "OBSERVATION_OUT_OF_RANGE", None, [], [
        ["feed", "registry", "feeds", [1, "main"]],
    ], [
        [2, expr(3, call("and", read("feed", "present"), call("eq", read_value("feed", "status"), lit("OPEN"))))],
        [2, expr(3, all_writes(call("or", call("ne", element("collection"), lit("observations")),
                                    call("and", call("ge", element_value("price"), read_value("feed", "min")),
                                         call("le", element_value("price"), read_value("feed", "max"))))))],
    ]],
    ["governed-transfer-limit", "TRANSFER_LIMIT_EXCEEDED", "transfer", [], [
        ["limits", "registry", "settings", [1, "transfer"]],
    ], [
        [2, expr(3, call("le", field(COMMAND, "amount"), read_value("limits", "max")))],
    ]],
    ["insert-only-observations", "OBSERVATION_NOT_INSERT", None, [["collection", TEXT, "observations"]], [], [
        [2, expr(3, all_writes(call("or", call("ne", element("collection"), field(PARAMS, "collection")),
                                    call("eq", element("op"), lit("PUT_IF_ABSENT")))))],
    ]],
    ["no-empty-revoke", "EMPTY_KEY", None, [], [], [
        [2, expr(3, call("not", any_write(call("and", call("eq", element("op"), lit("REVOKE")),
                                                  call("eq", call("size", element("key")), lit(0))))))],
    ]],
    ["tier-limit", "TIER_LIMIT_EXCEEDED", "transfer", [["tiers", TEXT, "holders"]], [
        ["holder", "registry", "holders", [0, CONTEXT, "sender"]],
        ["tier", "registry", "tiers", [2, "utf8-bytes", [[0, PARAMS, "tiers"]]]],
    ], [
        [2, expr(3, call("and", read("holder", "present"), call("eq", read("holder", "status"), lit("ACTIVE"))))],
        [2, expr(3, call("le", field(COMMAND, "amount"), read_value("holder", "maxTransfer")))],
    ]],
]
vectors["ir.typed-views"] = IR_HEAD + [1, [
    ["registry", "test", "registry.command.v1", M(), 0, 1, [
        ["insert-only-observations", M({"collection": "observations"})],
        ["no-empty-revoke", M()],
        ["feed-open-and-in-range", M()],
    ]],
    ["token", "test", "token.command.v1", M({"minter": ""}), 0, 1, [
        ["governed-transfer-limit", M()],
        ["tier-limit", M({"tiers": "holders"})],
    ]],
], typed_rules, [], LIMITS_DEFAULT]
roots["ir.typed-views"] = "binding-ir-v1"


def step(ordinal, depth, binding, component, message, events, conditions, rules_trace, status, code, raw):
    return [ordinal, depth, binding, component, message, events, conditions, rules_trace, status, code, raw]


NO_RULES = [0, None]
vectors["receipt.accepted"] = [1, Z, 1, "ACCEPTED", None, "", [
    step(0, 0, None, "wallet", Z, ["recorded.v1", "composite.command-accepted.v1"], [], [1, None],
         "PLANNED", "", False)]]
roots["receipt.accepted"] = "binding-receipt-v1"
vectors["receipt.rejected"] = [1, Z, 1, "REJECTED", 1, "AUTHORIZATION", [
    step(1, 1, "forward", "target", Z, [], [], NO_RULES, "REJECTED", "AUTHORIZATION", True)]]
roots["receipt.rejected"] = "binding-receipt-v1"
vectors["receipt.rule-denied"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_DENIED", [
    step(0, 0, None, "orders", Z, [], [], [0, ["registered-supplier", 0, "NOT_A_REGISTERED_SUPPLIER", None]],
         "REJECTED", "ADMISSION_RULE_DENIED", False)]]
roots["receipt.rule-denied"] = "binding-receipt-v1"
vectors["receipt.rule-error"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_ERROR", [
    step(0, 0, None, "wallet", Z, [], [], [0, ["transfer-limit", 0, None, None]],
         "REJECTED", "ADMISSION_RULE_ERROR", False)]]
roots["receipt.rule-error"] = "binding-receipt-v1"
vectors["receipt.rule-input"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_INPUT", [
    step(0, 0, None, "wallet", Z, [], [], [0, ["transfer-limit", -1, None, None]],
         "REJECTED", "ADMISSION_RULE_INPUT", False)]]
roots["receipt.rule-input"] = "binding-receipt-v1"
vectors["receipt.fact-denied"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_DENIED", [
    step(0, 0, None, "registry", Z, [], [], [0, ["operator-for-direct-writes", 0, "ROLE_REQUIRED", None]],
         "REJECTED", "ADMISSION_RULE_DENIED", False)]]
roots["receipt.fact-denied"] = "binding-receipt-v1"
vectors["receipt.derived-denied"] = [1, Z, 7, "REJECTED", 1, "ADMISSION_RULE_DENIED", [
    step(0, 0, None, "orders", Z, ["kv-registry.entry-put.v1", "composite.command-accepted.v1"],
         [["order-to-approval", -1]], [1, None], "PLANNED", "", False),
    step(1, 1, "order-to-approval", "approvals", D, [], [], [0, ["minimum-quorum", 0, "QUORUM_TOO_LOW", None]],
         "REJECTED", "ADMISSION_RULE_DENIED", False)]]
roots["receipt.derived-denied"] = "binding-receipt-v1"
vectors["receipt.rule-capacity"] = [1, Z, 7, "REJECTED", 0, "EXPRESSION_CAPACITY_EXCEEDED", [
    step(0, 0, None, "wallet", Z, [], [], [1, ["registered-sender", 1, None, None]],
         "REJECTED", "EXPRESSION_CAPACITY_EXCEEDED", False)]]
roots["receipt.rule-capacity"] = "binding-receipt-v1"
vectors["receipt.fact-input"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_INPUT", [
    step(0, 0, None, "registry", Z, [], [], [1, ["operator-for-direct-writes", -1, None, None]],
         "REJECTED", "ADMISSION_RULE_INPUT", False)]]
roots["receipt.fact-input"] = "binding-receipt-v1"
vectors["receipt.write-denied"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_DENIED", [
    step(0, 0, None, "registry", Z, [], [], [0, ["insert-only-observations", 0, "OBSERVATION_NOT_INSERT", 3]],
         "REJECTED", "ADMISSION_RULE_DENIED", False)]]
roots["receipt.write-denied"] = "binding-receipt-v1"
vectors["receipt.write-error"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_ERROR", [
    step(0, 0, None, "registry", Z, [], [], [2, ["feed-open-and-in-range", 1, None, 127]],
         "REJECTED", "ADMISSION_RULE_ERROR", False)]]
roots["receipt.write-error"] = "binding-receipt-v1"
vectors["receipt.read-error"] = [1, Z, 7, "REJECTED", 0, "ADMISSION_RULE_ERROR", [
    step(0, 0, None, "token", Z, [], [], [1, ["tier-limit", -1, None, None]],
         "REJECTED", "ADMISSION_RULE_ERROR", False)]]
roots["receipt.read-error"] = "binding-receipt-v1"

out = ["# Frozen canonical wire vectors; each vector has a CDDL root for independent validation.",
       "# ADR-031.3 and ADR-031.4 amend the v1 layouts in place; amended structures written before",
       "# them fail strict decode."]
for name in sorted(vectors):
    out.append(f"{name}.cddl-root={roots[name]}")
    out.append(f"{name}={enc(vectors[name]).hex()}")
sys.stdout.write("\n".join(out) + "\n")
