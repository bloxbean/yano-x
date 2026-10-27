# Showcase CLI Direction

The current showcase needs Python 3, but only for small standard-library
helpers: canonical payload encoding/hashing, the catalog listing, the demo
signer, each node's `config/local.yaml`, and fractional load-report
calculations. Node setup itself already runs through the Java setup commands
(`yano.sh appchain new`, `request`, `seal`, `join`, `start`). It needs no
`pip`, virtual environment, `cryptography`, or network-installed package.

A packaged Java CLI is a sensible follow-up because Java 25 is already required
to run Yano. It would remove the second runtime, reuse the exact Java codecs and
validation rules, and make Windows/non-shell integration easier. This release
does not add that CLI and does not change Yano core.

The migration should keep `showcase.sh` as the stable operator facade and
replace helpers incrementally:

1. move the per-node `config/local.yaml` writer (`tools/showcase_local.py`)
   next to the setup commands;
2. implement canonical `encode` and `state-key` commands using the production
   state-machine contracts;
3. add typed `submit`, `wait-finalized`, `query`, and `verify-proof` commands
   on `appchain-client`;
4. migrate report arithmetic and machine-readable JSON output; and
5. retain curl examples as the protocol-level reference, even after the Java
   client becomes the convenient path.

The CLI should live in a showcase/devtools module, produce one dependency-closed
artifact in the ZIP, avoid a second configuration schema, never accept secrets
as casually echoed command arguments, and preserve Bash/curl interoperability.
Load generation may remain a purpose-built shell or external tool initially;
correct typed payloads and proof verification are more valuable to migrate
first than rewriting every benchmark loop.
