#!/usr/bin/env python3
"""Independent verifier for the ADR-059 M1 golden vectors (V1/V2).

It shares no code with the Java implementation. It has its own minimal deterministic CBOR
encoder (definite arrays, byte strings, text strings, unsigned integers, shortest form) and uses
hashlib.blake2b. Every committed vector is recomputed from the same documented inputs and
compared byte for byte. Usage: verify-asset-gateway-vectors.py <golden-vectors.properties>
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


if __name__ == "__main__":
    main(sys.argv[1])
