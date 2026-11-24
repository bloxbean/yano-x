#!/usr/bin/env python3
"""Independent verifier for the ADR-059 golden vectors (M1 V1/V2 and the M3 deposit vectors).

It shares no code with the Java implementation. It has its own minimal deterministic CBOR
encoder (definite arrays, byte strings, text strings, unsigned integers, shortest form) and uses
hashlib.blake2b. Every committed vector is recomputed from the same documented inputs and
compared byte for byte. The M3 deposit vectors (Plutus-Data deposit datum, canonical Cardano
value, deposit claim and record) live in deposit-vectors.properties beside the golden vectors and
are recomputed the same way; deposit-datum-negative-vectors.properties must all be rejected by an
independent Plutus-Data reader. Usage: verify-asset-gateway-vectors.py <golden-vectors.properties>
"""
import hashlib
import sys

try:  # optional second oracle; the stdlib-only checks below always run
    import cbor2
except ImportError:  # pragma: no cover
    cbor2 = None

POLICY = bytes.fromhex("f0ff48bbb7bbe9d59a40f1ce90e9e9d0ff5002ec48f232b49ca0fb9a")
GENESIS = bytes.fromhex("6c1f3b1f9e5d2a0c4b8e7d6a5f4e3d2c1b0a99887766554433221100ffeeddcc")
TX = bytes.fromhex("8d3f1c0e2b4a69788796a5b4c3d2e1f00112233445566778899aabbccddeeff0")
KEY_HASH = bytes.fromhex("1c2b3a495867768594a3b2c1d0e0f00112233445566778899aabbcc0")
MAX_U64 = (1 << 64) - 1


def head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    for size, extra in ((1, 24), (2, 25), (4, 26), (8, 27)):
        if n < (1 << (8 * size)):
            return bytes([(major << 5) | extra]) + n.to_bytes(size, "big")
    # Bignum uint (> 2^64 - 1) is never produced by these vectors.
    raise ValueError("integer too large for a plain CBOR head")


def enc(value):
    if isinstance(value, bool):
        raise TypeError("booleans are not part of the contract subset")
    if isinstance(value, int):
        if value < 0:
            raise ValueError("negative integers are not part of the contract subset")
        return head(0, value)
    if isinstance(value, (bytes, bytearray)):
        return head(2, len(value)) + bytes(value)
    if isinstance(value, str):
        raw = value.encode("utf-8")
        return head(3, len(raw)) + raw
    if isinstance(value, list):
        return head(4, len(value)) + b"".join(enc(item) for item in value)
    raise TypeError(type(value))


def canonical(obj):
    """Cross-check the hand-written encoder against cbor2 in canonical mode."""
    mine = enc(obj)
    if cbor2 is not None:
        theirs = cbor2.dumps(obj, canonical=True)
        assert mine == theirs, f"encoder disagreement: {mine.hex()} vs {theirs.hex()}"
    return mine


def b2b(tag, fields):
    return hashlib.blake2b(tag.encode("ascii") + canonical(fields), digest_size=32).digest()


def asset(policy, name):
    return [policy, name]


def value(entries):
    # entries: list of (policy, name, quantity); canonical order = order of encoded asset id
    triples = [[p, n, q] for (p, n, q) in entries if q != 0]
    triples.sort(key=lambda t: enc([t[0], t[1]]))
    return triples


def compute():
    out = {}
    ada = asset(b"", b"")
    usdm = asset(POLICY, b"USDM")
    empty = asset(POLICY, b"")
    ff = asset(POLICY, b"\xff")
    zz = asset(POLICY, b"\x00\x00")
    name32 = asset(POLICY, b"\x00" * 32)
    out["asset.ada"] = enc(ada).hex()
    out["asset.usdm"] = enc(usdm).hex()
    out["asset.empty-name"] = enc(empty).hex()
    out["asset.ff"] = enc(ff).hex()
    out["asset.0000"] = enc(zz).hex()
    out["asset.name32"] = enc(name32).hex()
    assert enc(ff) < enc(zz), "length-first order: ff sorts before 00 00"

    mixed = value([(b"", b"", 2_000_000), (POLICY, b"\x00\x00", 1),
                   (POLICY, b"\xff", MAX_U64), (POLICY, b"USDM", 49_504_950)])
    out["value.mixed"] = canonical(mixed).hex()
    out["value.empty"] = enc([]).hex()

    domain = b2b("yano-asset-gateway-domain-v1\0",
                 [0, 1, "payments-1", GENESIS, "asset-payments", "anchored-claim-vault-v1", 1])
    out["id.domain"] = domain.hex()
    ada_vault = b2b("yano-asset-gateway-vault-v1\0", [domain, ada, 0])
    usdm_vault = b2b("yano-asset-gateway-vault-v1\0", [domain, usdm, 0])
    out["id.vault.ada"] = ada_vault.hex()
    out["id.vault.usdm"] = usdm_vault.hex()
    out["id.deposit"] = b2b("yano-asset-gateway-deposit-v1\0", [usdm_vault, TX, 3]).hex()
    recipient = bytes.fromhex("61") + KEY_HASH
    claim_value = value([(POLICY, b"USDM", 49_504_950)])
    claim = b2b("yano-asset-gateway-claim-v1\0",
                [domain, usdm_vault, 0, b"w/1", recipient, claim_value, 1_200_000, 7])
    out["id.claim"] = claim.hex()
    out["id.intent"] = hashlib.blake2b(
        b"yano-asset-gateway-intent-v1\0" + bytes.fromhex("83010203"), digest_size=32).hexdigest()
    out["id.escape"] = hashlib.blake2b(b"escape\0" + usdm_vault + KEY_HASH, digest_size=32).hexdigest()
    out["leaf.claim"] = enc([1, claim, usdm_vault, 0, claim_value, 1_200_000, recipient,
                             1_234, 1_764_000_000_000]).hex()
    out["leaf.exit"] = enc([1, KEY_HASH, usdm_vault, 100, 1_150_770]).hex()
    return out


ACCOUNT = bytes.fromhex("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c")
BLOCK = bytes.fromhex("b10c0000000000000000000000000000000000000000000000000000000000b1")
POLICY_B = bytes.fromhex("11" * 28)


def enc_map(entries):
    """Definite CBOR map, keys sorted by their encoded bytes (RFC 8949 4.2.1)."""
    pairs = sorted(((enc(k), enc_raw(v)) for k, v in entries), key=lambda kv: kv[0])
    return head(5, len(pairs)) + b"".join(k + v for k, v in pairs)


def enc_raw(value):
    return value if isinstance(value, RawCbor) else enc(value)


class RawCbor(bytes):
    """Already-encoded CBOR, embedded as is."""


def cardano_value(coin, assets):
    """value = coin / [coin, {policy: {name: qty}}] (Cardano ledger CDDL), canonical maps."""
    if not assets:
        return enc(coin)
    policies = {}
    for policy, name, qty in assets:
        policies.setdefault(policy, []).append((name, qty))
    inner = [(policy, RawCbor(enc_map(names))) for policy, names in policies.items()]
    return head(4, 2) + enc(coin) + enc_map(inner)


def plutus_deposit_datum(account, refund, key_hash):
    """#6.121([account, refund_address, refund_key_hash]) in definite form."""
    return bytes([0xd8, 121]) + enc([account, refund, key_hash])


def read_plutus_deposit(data):
    """Independent reader of DepositDatumV1: constructor 0 (tag 121) with three byte strings.

    Accepts a definite or indefinite field list and definite or chunked byte strings with
    shortest-form heads; rejects everything else, including trailing bytes."""
    pos = 0

    def byte():
        nonlocal pos
        if pos >= len(data):
            raise ValueError("truncated")
        pos += 1
        return data[pos - 1]

    def definite(h, limit):
        nonlocal pos
        if h >> 5 != 2:
            raise ValueError("not bytes")
        info = h & 0x1F
        if info < 24:
            n = info
        elif info == 24:
            n = byte()
            if n < 24:
                raise ValueError("non-shortest")
        else:
            raise ValueError("too long")
        if n > limit or pos + n > len(data):
            raise ValueError("length")
        pos += n
        return data[pos - n:pos]

    def bstr(limit):
        h = byte()
        if h != 0x5F:
            return definite(h, limit)
        out = b""
        while True:
            h = byte()
            if h == 0xFF:
                return out
            out += definite(h, limit - len(out))

    if byte() != 0xD8 or byte() != 121:
        raise ValueError("tag")
    lst = byte()
    if lst not in (0x83, 0x9F):
        raise ValueError("arity")
    fields = (bstr(28), bstr(64), bstr(28))
    if lst == 0x9F and byte() != 0xFF:
        raise ValueError("arity")
    if pos != len(data):
        raise ValueError("trailing")
    if len(fields[0]) != 28 or not 1 <= len(fields[1]) <= 64 or len(fields[2]) != 28:
        raise ValueError("field size")
    return fields


def compute_deposit(golden):
    out = {}
    refund = bytes.fromhex("60") + KEY_HASH
    datum = plutus_deposit_datum(ACCOUNT, refund, KEY_HASH)
    out["datum.deposit"] = datum.hex()
    indefinite = (bytes.fromhex("d8799f5f4e") + ACCOUNT[:14] + bytes.fromhex("4e") + ACCOUNT[14:]
                  + bytes.fromhex("ff581d") + refund + bytes.fromhex("581c") + KEY_HASH + bytes.fromhex("ff"))
    assert read_plutus_deposit(indefinite) == read_plutus_deposit(datum) == (ACCOUNT, refund, KEY_HASH)
    out["datum.deposit-indefinite"] = indefinite.hex()
    out["datum.floor-top-up"] = bytes([0xd8, 123, 0x80]).hex()

    out["value.ada-only"] = cardano_value(2_000_000, []).hex()
    multi = [(POLICY, b"USDM", 49_504_950), (POLICY, b"\xff", 1), (POLICY_B, b"", 7)]
    multi_cbor = cardano_value(1_500_000, multi)
    out["value.multi"] = multi_cbor.hex()
    multi_hash = hashlib.blake2b(multi_cbor, digest_size=32).digest()
    out["value.multi.hash"] = multi_hash.hex()
    single_hash = hashlib.blake2b(cardano_value(1_500_000, [(POLICY, b"USDM", 49_504_950)]),
                                  digest_size=32).digest()

    usdm_vault = bytes.fromhex(golden["id.vault.usdm"])

    def deposit_id(index):
        return b2b("yano-asset-gateway-deposit-v1\0", [usdm_vault, TX, index])

    def claim(index, shape, beneficiary, others, value_hash):
        return enc([1, deposit_id(index), usdm_vault, TX, index, 4_242_424, BLOCK, shape, beneficiary,
                    49_504_950, 1_500_000, others, value_hash]).hex()

    out["claim.deposit"] = claim(3, 0, [0, ACCOUNT], 0, single_hash)
    out["claim.malformed"] = claim(4, 0, [1, 1], 0, single_hash)
    out["claim.continuation"] = claim(0, 1, [2], 2, multi_hash)
    assert deposit_id(3).hex() == golden["id.deposit"], "deposit id agrees with the M1 vector"

    def record(status, reason, others, value_hash, credited, credited_lovelace):
        return enc([1, deposit_id(3), usdm_vault, TX, 3, 4_242_424, 77, 0, status, reason, ACCOUNT,
                    49_504_950, 1_500_000, others, value_hash, credited, credited_lovelace]).hex()

    out["record.credited"] = record(0, 0, 0, single_hash, 49_504_950, 1_500_000)
    out["record.uncredited"] = record(1, 7, 2, multi_hash, 0, 0)
    return out


def read_properties(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, val = line.partition("=")
            values[key] = val
    return values


def check_deposit(golden_path, golden):
    path = golden_path.replace("golden-vectors.properties", "deposit-vectors.properties")
    committed = read_properties(path)
    expected = compute_deposit(golden)
    failures = [k for k in sorted(set(expected) | set(committed)) if expected.get(k) != committed.get(k)]
    for key in failures:
        print(f"MISMATCH {key}: committed={committed.get(key)} independent={expected.get(key)}")
    if failures:
        sys.exit(1)
    print(f"ADR-059 deposit vectors verified independently: {len(expected)} values")
    negative = read_properties(golden_path.replace("golden-vectors.properties",
                                                   "deposit-datum-negative-vectors.properties"))
    for name, hexval in negative.items():
        try:
            read_plutus_deposit(bytes.fromhex(hexval))
        except ValueError:
            continue
        print(f"NEGATIVE DATUM ACCEPTED independently: {name}")
        sys.exit(1)
    print(f"ADR-059 negative deposit datums rejected independently: {len(negative)}")


def decode(data):
    """Minimal decoder for the contract subset; raises ValueError on anything outside it."""
    pos = 0

    def arg(info):
        nonlocal pos
        if info < 24:
            return info
        size = {24: 1, 25: 2, 26: 4, 27: 8}.get(info)
        if size is None or pos + size > len(data):
            raise ValueError("bad head")
        value = int.from_bytes(data[pos:pos + size], "big")
        pos += size
        return value

    def item(depth):
        nonlocal pos
        if depth > 8 or pos >= len(data):
            raise ValueError("depth or truncation")
        initial = data[pos]
        pos += 1
        major, info = initial >> 5, initial & 0x1F
        if major == 0:
            return arg(info)
        if major in (2, 3):
            n = arg(info)
            if pos + n > len(data):
                raise ValueError("truncated")
            raw = data[pos:pos + n]
            pos += n
            return raw if major == 2 else raw.decode("utf-8")
        if major == 4:
            return [item(depth + 1) for _ in range(arg(info))]
        raise ValueError("outside the contract subset")

    obj = item(1)
    if pos != len(data):
        raise ValueError("trailing bytes")
    return obj


def valid_asset_value(data):
    """Independent re-statement of the AssetValue rules (ADR-059 D3)."""
    try:
        obj = decode(data)
    except ValueError:
        return False
    if not isinstance(obj, list) or len(obj) > 16:
        return False
    previous = None
    for entry in obj:
        if not isinstance(entry, list) or len(entry) != 3:
            return False
        policy, name, qty = entry
        if not isinstance(policy, bytes) or not isinstance(name, bytes) or not isinstance(qty, int):
            return False
        if isinstance(qty, bool) or qty < 1 or qty > MAX_U64:
            return False
        if len(policy) not in (0, 28) or len(name) > 32 or (len(policy) == 0 and len(name) != 0):
            return False
        key = enc([policy, name])
        if previous is not None and previous >= key:
            return False
        previous = key
    return enc(obj) == data  # canonical: re-encoding must reproduce the input exactly


def check_negative(path):
    count = 0
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, _, hexval = line.partition("=")
            if valid_asset_value(bytes.fromhex(hexval)):
                print(f"NEGATIVE VECTOR ACCEPTED independently: {name}")
                sys.exit(1)
            count += 1
    print(f"ADR-059 negative vectors rejected independently: {count}")


def main(path):
    committed = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, _, val = line.partition("=")
            committed[key] = val
    expected = compute()
    failures = [k for k in sorted(set(expected) | set(committed))
                if expected.get(k) != committed.get(k)]
    for key in failures:
        print(f"MISMATCH {key}: committed={committed.get(key)} independent={expected.get(key)}")
    if failures:
        sys.exit(1)
    oracle = "with cbor2 cross-check" if cbor2 is not None else "stdlib only; cbor2 not installed"
    print(f"ADR-059 golden vectors verified independently ({oracle}): {len(expected)} values")
    check_negative(path.replace("golden-vectors.properties", "negative-vectors.properties"))
    check_deposit(path, committed)


if __name__ == "__main__":
    main(sys.argv[1])
