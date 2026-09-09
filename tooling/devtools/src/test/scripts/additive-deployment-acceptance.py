#!/usr/bin/env python3
"""Exercise a fresh, private three-node devnet using an extracted Yano X JVM ZIP.

Requires Java 25, Python 3, curl and the release's tutorial encoder. Creates a new
work directory, stops only its own nodes, and retains all evidence/data afterward.
No public-network traffic, existing cluster adoption, or reset is performed.
"""
import argparse
import hashlib
import json
import os
import signal
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", type=Path)
    parser.add_argument("--http-base", type=int, default=19080)
    parser.add_argument("--server-base", type=int, default=24437)
    args = parser.parse_args()
    home = args.distribution.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix="yano-x-additive-acceptance-")).resolve()
    project = root / "application"
    env = dict(os.environ, YANO_HOME=str(home))
    print(f"Retained acceptance evidence: {root}", flush=True)

    def cli(*arguments):
        result = subprocess.run([str(home / "yano.sh"), "appchain", *map(str, arguments)],
                                env=env, text=True, capture_output=True, timeout=90)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def script(name):
        result = subprocess.run([str(project / "scripts" / name)], env=env,
                                text=True, capture_output=True, timeout=600)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        print(f"{name}: complete", flush=True)

    def api(chain, path, node=0, body=None):
        request = urllib.request.Request(
            f"http://127.0.0.1:{args.http_base + node}/api/v1/app-chain/chains/{chain}/{path}",
            data=json.dumps(body).encode() if body else None,
            headers={"X-API-Key": api_key, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def wait(operation):
        failure = None
        for _ in range(90):
            try:
                value = operation()
                if value:
                    return value
            except (OSError, ValueError) as error:
                failure = error
            time.sleep(1)
        raise RuntimeError(f"Timed out waiting for finality/catch-up: {failure}")

    def finalized(chain, message):
        result = api(chain, "messages/" + message)
        return result if result.get("height", 0) > 0 else None

    def encode(*arguments):
        return subprocess.check_output(
            ["python3", str(home / "docs/appchain/tutorials/tools/stdlib_command.py"), *arguments],
            text=True).strip()

    def check(chain, message, state_key=None):
        height = wait(lambda: finalized(chain, message))["height"]
        blocks = [wait(lambda n=node: api(chain, f"blocks/{height}", n)) for node in range(3)]
        assert len({block["stateRoot"] for block in blocks}) == 1
        assert all(block["certSignatures"] >= 2 for block in blocks)
        identities = [api(chain, "identity", node) for node in range(3)]
        assert identities[0] == identities[1] == identities[2]
        manifests = [api(chain, "status", node)["capabilityManifest"]["manifestDigest"] for node in range(3)]
        assert len(set(manifests)) == 1
        states = [api(chain, "state/identity", node) for node in range(3)]
        for field in ("profile", "genesisId", "formatFingerprint"):
            assert len({state[field] for state in states}) == 1
        if state_key is None:
            state_key = hashlib.sha256(b"~yano/finalized-message/v1/" + bytes.fromhex(message)).hexdigest()
        proof = api(chain, f"state/proof/{state_key}?height={height}")
        assert proof["stateRoot"] == blocks[0]["stateRoot"] and proof["proofWireHex"]
        assert proof["presence"] == "PRESENT"
        proof_file = root / f"{chain}-{height}-proof.json"
        proof_file.write_text(json.dumps(proof, indent=2))
        verification = json.loads(cli(
            "state", "verify", "--proof-file", proof_file, "--trusted-root", blocks[0]["stateRoot"],
            "--profile", proof["profile"], "--genesis-id", states[0]["genesisId"],
            "--chain", chain, "--height", height, "--root-source", "caller-pinned"))
        assert verification["valid"] is True
        print(f"{chain}: height={height}, three matching roots, certificate and proof verified", flush=True)
        return {"message": message, "height": height, "root": blocks[0]["stateRoot"],
                "genesis": states[0]["genesisId"], "profile": states[0]["profile"],
                "consensus": identities[0]["consensusProfileDigest"], "capabilityManifest": manifests[0],
                "proofWireHex": proof["proofWireHex"], "valueHex": proof.get("valueHex")}

    cli("init", "--non-interactive", "--recipe", "audit-log", "--network", "devnet", "--members", 3,
        "--name", "deployment-acceptance", "--chain-id", "orders", "--http-port-base", args.http_base,
        "--server-port-base", args.server_base, "--output", project)
    cli("prepare", project)
    api_key = dict(line.split("=", 1) for line in (project / "secrets/node0.env").read_text().splitlines())[
        "YANO_APPCHAIN_API_KEYS"]
    env["YANO_ACCEPTANCE_API_KEY"] = api_key
    cli("chain", "add", project, "--chain-id", "documents", "--recipe", "document-trail")
    plan = json.loads(cli("plan", project))
    assert plan["status"] == "PLAN_READY"
    cli("apply", project, "--plan", plan["digest"])
    try:
        script("start")
        order = api("orders", "messages", 1, {"topic": "orders", "body": "order A-100 created"})["messageId"]
        command = encode("doc-trail", "product-42", hashlib.sha256(b"certificate v1").hexdigest())
        document = api("documents", "messages", 2, {"topic": "documents", "bodyHex": command})["messageId"]
        before = {"orders": check("orders", order),
                  "documents": check("documents", document, b"e/product-42".hex())}
        (root / "before.json").write_text(json.dumps(before, indent=2))
        cli("chain", "add", project, "--chain-id", "registry", "--recipe", "owned-registry")
        plan = json.loads(cli("plan", project))
        assert plan["status"] == "PLAN_READY"
        assert {change["chainId"]: change["action"] for change in plan["chains"]} == {
            "orders": "UNCHANGED", "documents": "UNCHANGED", "registry": "ADD"}
        (root / "addition-plan.json").write_text(json.dumps(plan, indent=2))
        script("stop")
        cli("apply", project, "--plan", plan["digest"])
        script("start")
        for chain, old in before.items():
            assert check(chain, old["message"], b"e/product-42".hex() if chain == "documents" else None) == old
        command = encode("kv-registry", "put", "stock-1", "--value-text", "100")
        registry = api("registry", "messages", 1, {"topic": "stock", "bodyHex": command})["messageId"]
        check("registry", registry, b"stock-1".hex())
        peers = [value for node in range(3) for value in
                 ("--peer", f"http://127.0.0.1:{args.http_base + node}/api/v1/")]
        drift = json.loads(cli("drift", project, *peers, "--api-key-env", "YANO_ACCEPTANCE_API_KEY",
                               "--format", "json"))
        assert drift["status"] == "DRIFT_OK"
        (root / "drift.json").write_text(json.dumps(drift, indent=2))
        follower_pid = int((project / "run/node2.pid").read_text())
        os.kill(follower_pid, signal.SIGTERM)
        def stopped():
            try:
                os.kill(follower_pid, 0)
                return False
            except ProcessLookupError:
                return True
        wait(stopped)
        catchup_message = api("orders", "messages", 1, {"topic": "orders", "body": "catch-up"})["messageId"]
        wait(lambda: finalized("orders", catchup_message))
        with (project / "logs/node2-catchup.log").open("w") as log:
            follower = subprocess.Popen([str(project / "scripts/start-node"), "2"], env=env,
                                        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        threading.Thread(target=follower.wait, daemon=True).start()
        (project / "run/node2.pid").write_text(str(follower.pid))
        check("orders", catchup_message)
        script("stop")
        follower.wait(timeout=120)
        script("start")
        for chain, old in before.items():
            assert check(chain, old["message"], b"e/product-42".hex() if chain == "documents" else None) == old
        print("PASS: additive deployment preserves existing roots, identities and proofs across restarts", flush=True)
    finally:
        script("stop")


if __name__ == "__main__":
    main()
