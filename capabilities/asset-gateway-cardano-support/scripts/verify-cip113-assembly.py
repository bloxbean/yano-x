#!/usr/bin/env python3
"""ADR-061 §10 P5: an independent check of the ledger orderings in assembled CIP-113 transactions.

Reads a JSON file of vectors written by ProgrammableAssemblyVectorsTest. For each transaction it decodes the CBOR
with its own minimal decoder, recomputes from first principles

  * the sorted input order and each PLB input's BaseSpendRedeemer{params_idx, wdrl_idx},
  * the sorted reference-input order, params_idx and every proof's node_idx,
  * the ledger order of withdrawals (script credentials before key credentials, bytewise) and wdrl_idx,
  * the transfer delegate's TransferRedeemer{proofs} (one proof per distinct policy, ascending),
  * every redeemer's index,

and compares them with the transaction's own redeemers. Exit status 0 when every vector agrees.
"""
import json
import sys


class Tagged:
    def __init__(self, tag, value):
        self.tag = tag
        self.value = value


def decode(data, at=0):
    first = data[at]
    major, info = first >> 5, first & 0x1F
    at += 1
    if info < 24:
        arg = info
    elif info in (24, 25, 26, 27):
        width = 1 << (info - 24)
        arg = int.from_bytes(data[at:at + width], "big")
        at += width
    elif info == 31:
        arg = None
    else:
        raise ValueError("unsupported CBOR head")
    if major == 0:
        return arg, at
    if major == 1:
        return -1 - arg, at
    if major in (2, 3):
        if arg is None:
            chunks = b""
            while data[at] != 0xFF:
                chunk, at = decode(data, at)
                chunks += chunk if isinstance(chunk, bytes) else chunk.encode()
            return chunks, at + 1
        raw = data[at:at + arg]
        return (raw if major == 2 else raw.decode()), at + arg
    if major == 4:
        items = []
        if arg is None:
            while data[at] != 0xFF:
                item, at = decode(data, at)
                items.append(item)
            return items, at + 1
        for _ in range(arg):
            item, at = decode(data, at)
            items.append(item)
        return items, at
    if major == 5:
        pairs = []
        count = arg
        while (count is None and data[at] != 0xFF) or (count is not None and len(pairs) < count):
            key, at = decode(data, at)
            value, at = decode(data, at)
            pairs.append((key, value))
        if count is None:
            at += 1
        return pairs, at
    if major == 6:
        value, at = decode(data, at)
        return Tagged(arg, value), at
    if major == 7:
        return {20: False, 21: True, 22: None}.get(info, arg), at
    raise ValueError("unsupported major type")


def untag_set(value):
    return value.value if isinstance(value, Tagged) and value.tag == 258 else value


def constr(value):
    """A Plutus Data constructor as (index, fields)."""
    if not isinstance(value, Tagged):
        raise ValueError("not a constructor")
    if 121 <= value.tag <= 127:
        return value.tag - 121, value.value
    if 1280 <= value.tag <= 1400:
        return value.tag - 1280 + 7, value.value
    if value.tag == 102:
        return value.value[0], value.value[1]
    raise ValueError("not a constructor tag")


SPEND = 0
REWARD = 3  # Conway redeemer tags: spend 0, mint 1, cert 2, reward 3, vote 4, propose 5


def outpoint(txid, index):
    return txid.hex() + "#" + str(index)


def ordered(outpoints):
    return sorted(outpoints, key=lambda o: (bytes.fromhex(o.split("#")[0]), int(o.split("#")[1])))


def check(vector):
    tx, _ = decode(bytes.fromhex(vector["cbor"]))
    body = dict(tx[0])
    witnesses = dict(tx[1])
    inputs = ordered([outpoint(i[0], i[1]) for i in untag_set(body[0])])
    references = ordered([outpoint(i[0], i[1]) for i in untag_set(body.get(18, []))])
    withdrawals = [account for account, _ in body.get(5, [])]
    # Ledger order: the credential kind (script before key, header bit 4), then the hash bytes.
    withdrawal_order = sorted(withdrawals, key=lambda a: (0 if (a[0] >> 4) & 1 else 1, a[1:]))
    dispatcher = bytes.fromhex(vector["dispatcher"])
    transfer = bytes.fromhex(vector["transfer"])
    wdrl_idx = [a[1:] for a in withdrawal_order].index(dispatcher)
    params_idx = references.index(vector["paramsRef"])

    redeemers = {}
    raw = witnesses.get(5, [])
    if isinstance(raw, list) and raw and isinstance(raw[0], tuple):
        for key, value in raw:
            redeemers[(key[0], key[1])] = value[0]
    else:
        for entry in raw:
            redeemers[(entry[0], entry[1])] = entry[2]

    errors = []
    for o in vector["plbInputs"]:
        index = inputs.index(o)
        data = redeemers.get((SPEND, index))
        if data is None or constr(data) != (0, [params_idx, wdrl_idx]):
            errors.append("PLB input %s at %d: expected BaseSpend(%d, %d), got %r"
                          % (o, index, params_idx, wdrl_idx, data))
    nodes = vector["nodes"]
    expected_proofs = []
    for policy in sorted(vector["policies"], key=bytes.fromhex):
        exact = [ref for ref, node in nodes.items() if node["key"] == policy]
        if exact:
            expected_proofs.append((0, [references.index(exact[0])]))
        else:
            covering = [ref for ref, node in nodes.items()
                        if bytes.fromhex(node["key"]) < bytes.fromhex(policy) < bytes.fromhex(node["next"])]
            expected_proofs.append((1, [references.index(covering[0])]))
    transfer_index = [a[1:] for a in withdrawal_order].index(transfer)
    transfer_data = redeemers.get((REWARD, transfer_index))
    got = [constr(p) for p in constr(transfer_data)[1][0]] if transfer_data is not None else None
    if got != expected_proofs:
        errors.append("TransferRedeemer at %d: expected %r, got %r" % (transfer_index, expected_proofs, got))
    dispatcher_data = redeemers.get((REWARD, wdrl_idx))
    if dispatcher_data is None or constr(dispatcher_data) != (0, []):
        errors.append("dispatcher at %d: expected TransferAct" % wdrl_idx)
    for script in vector.get("custody", []):
        index = [a[1:] for a in withdrawal_order].index(bytes.fromhex(script))
        if (REWARD, index) not in redeemers:
            errors.append("no reward redeemer for %s at %d" % (script, index))
    reward_redeemers = sorted(i for (tag, i) in redeemers if tag == REWARD)
    script_withdrawals = [i for i, a in enumerate(withdrawal_order) if (a[0] >> 4) & 1]
    if reward_redeemers != script_withdrawals:
        errors.append("reward redeemer indexes %r are not the script withdrawals' positions %r"
                      % (reward_redeemers, script_withdrawals))
    return errors


def main():
    vectors = json.load(open(sys.argv[1]))
    failed = 0
    for vector in vectors:
        errors = check(vector)
        if errors:
            failed += 1
            print("FAIL %s: %s" % (vector["name"], "; ".join(errors)))
        else:
            print("ok   %s" % vector["name"])
    print("%d vectors, %d failed" % (len(vectors), failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
