---
title: "Trust Registry"
description: "The Trust and Status Registry is a config-only product on the stock governed authenticated-map state machine. Its chain holds subjects, credential status…"
editUrl: "https://github.com/bloxbean/yano-x/edit/main/docs/site/products-trust-registry.md"
---
The Trust and Status Registry is a config-only product on the stock governed
`authenticated-map` state machine. Its chain holds subjects, credential status,
published status lists, issuers, and schemas; the `yano-trust` CLI answers
status questions with proofs bound to a certified block; `yano-trust serve`
speaks W3C Bitstring Status List and a TRQP-shaped authorization query without
ever processing JSON-LD; and a browser console reads it all back and, through an
operator gateway or a key unlocked in the tab itself, writes to it. Its domain tooling runs outside consensus; the stock authenticated-map
state machine applies governed writes and authorization rules on the app chain.

## The journey

1. **Generate.** A JSON descriptor names organizations, actors with their key
   proofs, and seeded issuers; `yano-trust genesis` turns it into the four node
   properties. The same descriptor, members, and threshold always give the same
   genesis id.
2. **Write.** Issuers set status bits and publish lists, registrars register
   subjects, each with a one-use actor authorization the chain consumes exactly
   once. Issuer onboarding goes through two independent registrar approvals.
3. **Answer.** `yano-trust status` gives inclusion, tombstone, or exclusion at
   the tip or any retained height, with every fact a state proof under one root
   and, for governed writes, who wrote the entry and under which policy.
4. **Serve.** Lists are projections replayed from the chain's applied writes;
   the served bitstring hashes to the value the chain holds.
5. **Verify.** An exported answer verifies offline against pinned members or a
   Cardano anchor datum, with the same exit codes as Attest.

## Run it

```bash
./gradlew :examples:showcase:installDist :products:trust-registry:cli:installDist
export TRUST_REGISTRY_YANO_HOME=$PWD/examples/showcase/build/install/yano-showcase/yano
products/trust-registry/harness/registry.sh up
eval "$(products/trust-registry/harness/registry.sh env)"
yano-trust put --url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN --actor issuer-a \
  --seed-file $YANO_TRUST_SEEDS/issuer-a.seed --list list-1 --index 5 --bit 1 \
  --genesis-id $YANO_TRUST_GENESIS_ID
yano-trust status --url $YANO_TRUST_URL --chain $YANO_TRUST_CHAIN --list list-1 --index 5 \
  --members $YANO_TRUST_MEMBERS
```

The full walkthrough, the data model, the proof states, and the console are in
the [user guide](https://github.com/bloxbean/yano-x/blob/main/docs/appchain/TRUST_REGISTRY.md);
the decision record is ADR-049.

## What it proves

The registry says what the consortium agreed about an identifier: that this
entry existed with this revision at this height under this root, that this key
had no entry, or that it was revoked; who wrote it and that their authorization
was consumed once; that the root was certified by the pinned members; and,
when anchored, that Cardano carries it. It never mints identifiers and never
asserts that a credential's claims are true.

Maturity: `preview`.
