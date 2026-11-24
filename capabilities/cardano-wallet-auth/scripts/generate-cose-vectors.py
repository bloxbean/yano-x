#!/usr/bin/env python3
"""Independent oracle for ADR-059 V3: builds CIP-30 signData results with a separate Ed25519
implementation (python `cryptography`) and a separate CBOR encoder, then writes one line per
vector: name|accept-or-reject|cose_sign1_hex|cose_key_hex|kind|key_hash_hex.

The Java tests (CoseOracleVectorTest) must reach exactly the same verdicts. Keys are derived
from fixed seeds, so the output is deterministic (Ed25519 signatures are deterministic).
Usage: generate-cose-vectors.py <output-file>   (requires: pip install cryptography)
"""
import hashlib
import sys

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


def head(major, n):
    if n < 24:
        return bytes([(major << 5) | n])
    for size, extra in ((1, 24), (2, 25), (4, 26), (8, 27)):
        if n < (1 << (8 * size)):
            return bytes([(major << 5) | extra]) + n.to_bytes(size, "big")
    raise ValueError(n)


def enc(v):
    if v is None:
        return b"\xf6"
    if v is True:
        return b"\xf5"
    if v is False:
        return b"\xf4"
    if isinstance(v, int):
        return head(0, v) if v >= 0 else head(1, -1 - v)
    if isinstance(v, bytes):
        return head(2, len(v)) + v
    if isinstance(v, str):
        raw = v.encode()
        return head(3, len(raw)) + raw
    if isinstance(v, list):
        return head(4, len(v)) + b"".join(enc(x) for x in v)
    if isinstance(v, Pairs):  # ordered map, possibly with duplicates
        return head(5, len(v.items)) + b"".join(enc(k) + enc(x) for k, x in v.items)
    raise TypeError(type(v))


class Pairs:
    def __init__(self, *items):
        self.items = list(items)


def key(seed_byte):
    sk = Ed25519PrivateKey.from_private_bytes(bytes([seed_byte]) * 32)
    pk = sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    return sk, pk


def h224(b):
    return hashlib.blake2b(b, digest_size=28).digest()


GENESIS = bytes(range(32))


def envelope(account_id, action="TRANSFER"):
    return enc(["yano-intent-v1", 1, 0, "payments-1", GENESIS, "asset-payments", action,
                account_id, [0], bytes(range(16)), 1_764_000_600_000, b"\x83\x01\x02\x03"])


def account_id(kind_code, key_hash):
    return h224(b"acct\x00" + bytes([kind_code]) + key_hash)


def sig_structure(protected, payload, aad=b""):
    return enc(["Signature1", protected, aad, payload])


def cose_key(pk, crv=6):
    return enc(Pairs((1, 1), (3, -8), (-1, crv), (-2, pk)))


def sign1(sk, protected, payload, unprotected=None, tag=None, aad=b"", sign_over=None, payload_field="same"):
    to_sign = sig_structure(sign_over if sign_over is not None else protected, payload, aad)
    sig = sk.sign(to_sign)
    body = [protected, unprotected if unprotected is not None else Pairs(),
            payload if payload_field == "same" else payload_field, sig]
    encoded = enc(body)
    if tag is not None:
        encoded = head(6, tag) + encoded
    return encoded


def main(out_path):
    sk_a, pk_a = key(0x11)
    sk_b, pk_b = key(0x22)
    sk_s, pk_s = key(0x33)  # stake key
    ha, hb, hs = h224(pk_a), h224(pk_b), h224(pk_s)
    enterprise = bytes([0x60]) + ha
    base_kk = bytes([0x00]) + ha + hs
    base_ks = bytes([0x20]) + ha + hs
    reward = bytes([0xE0]) + hs
    script_pay = bytes([0x70]) + ha
    byron = bytes([0x82]) + ha

    def prot(addr):
        return enc(Pairs((1, -8), ("address", addr)))

    acct_pay = account_id(0, ha)
    acct_stake = account_id(1, hs)
    p_pay = envelope(acct_pay)
    p_stake = envelope(acct_stake)
    vectors = []

    def add(name, expect, sign1_bytes, key_bytes, kind="", kh=b""):
        vectors.append(f"{name}|{expect}|{sign1_bytes.hex()}|{key_bytes.hex()}|{kind}|{kh.hex()}")

    add("enterprise", "accept", sign1(sk_a, prot(enterprise), p_pay), cose_key(pk_a), "PAYMENT_KEY", ha)
    add("base-key-key", "accept", sign1(sk_a, prot(base_kk), p_pay), cose_key(pk_a), "PAYMENT_KEY", ha)
    add("base-key-script-stake", "accept", sign1(sk_a, prot(base_ks), p_pay), cose_key(pk_a), "PAYMENT_KEY", ha)
    add("reward-stake-key", "accept", sign1(sk_s, prot(reward), p_stake), cose_key(pk_s), "STAKE_KEY", hs)
    add("tag-18", "accept", sign1(sk_a, prot(enterprise), p_pay, tag=18), cose_key(pk_a), "PAYMENT_KEY", ha)
    reversed_prot = enc(Pairs(("address", enterprise), (1, -8)))
    add("noncanonical-protected-signed-as-received", "accept",
        sign1(sk_a, reversed_prot, p_pay), cose_key(pk_a), "PAYMENT_KEY", ha)
    add("hashed-false", "accept", sign1(sk_a, prot(enterprise), p_pay, unprotected=Pairs(("hashed", False))),
        cose_key(pk_a), "PAYMENT_KEY", ha)

    add("signed-over-reserialized-protected", "reject",
        sign1(sk_a, reversed_prot, p_pay, sign_over=prot(enterprise)), cose_key(pk_a))
    add("hashed-true", "reject", sign1(sk_a, prot(enterprise), p_pay, unprotected=Pairs(("hashed", True))),
        cose_key(pk_a))
    add("nonempty-external-aad", "reject", sign1(sk_a, prot(enterprise), p_pay, aad=b"x"), cose_key(pk_a))
    add("key-not-address-credential", "reject", sign1(sk_b, prot(enterprise), p_pay), cose_key(pk_b))
    tampered = bytearray(sign1(sk_a, prot(enterprise), p_pay))
    tampered[-70] ^= 0x01  # a payload byte (the 64-byte signature trails it)
    add("payload-tampered", "reject", bytes(tampered), cose_key(pk_a))
    add("crit-header", "reject", sign1(sk_a, enc(Pairs((1, -8), (2, [1]), ("address", enterprise))), p_pay),
        cose_key(pk_a))
    add("duplicate-protected-label", "reject",
        sign1(sk_a, enc(Pairs((1, -8), (1, -8), ("address", enterprise))), p_pay), cose_key(pk_a))
    add("script-payment-address", "reject", sign1(sk_a, prot(script_pay), p_pay), cose_key(pk_a))
    add("byron-address", "reject", sign1(sk_a, prot(byron), p_pay), cose_key(pk_a))
    add("wrong-crv", "reject", sign1(sk_a, prot(enterprise), p_pay), cose_key(pk_a, crv=7))
    add("nil-payload", "reject", sign1(sk_a, prot(enterprise), p_pay, payload_field=None), cose_key(pk_a))
    add("other-tag", "reject", sign1(sk_a, prot(enterprise), p_pay, tag=17), cose_key(pk_a))

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("# ADR-059 V3 COSE oracle vectors, generated by scripts/generate-cose-vectors.py\n")
        out.write("# name|expect|cose_sign1|cose_key|kind|key_hash\n")
        for line in vectors:
            out.write(line + "\n")
    print(f"wrote {len(vectors)} vectors")


if __name__ == "__main__":
    main(sys.argv[1])
