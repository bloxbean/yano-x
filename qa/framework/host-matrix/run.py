#!/usr/bin/env python3
"""QA-only thin Maven host qualification; prepare freezes bytecode, run never compiles it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = HERE / "src/compat/PackagedHostCluster.java"
FRAMEWORK_NAMESPACE = "org.yanoproject.x.framework"
# ADR-064: fixtures compiled against hosts that embedded X are retired; only fixtures
# prepared against a Host API 1.2 host (Yano never depends on X) qualify.
FIXTURE_CONTRACT = "host-api-1.2"
OWNERS = {
    "org/yanoproject/runtime/assembly/YanoAssembly.class": "yano-runtime",
    "org/yanoproject/api/host/HostApi.class": "yano-host-api",
    "org/yanoproject/api/config/YanoConfig.class": "yano-node-api",
    "org/yanoproject/x/framework/runtime/appchain/AppLedgerRuntime.class": "yano-x-appchain-core",
    "org/yanoproject/x/framework/runtime/appchain/AppLedgerManager.class": "yano-x-appchain-core",
}
PROFILE_OWNERS = {
    "standalone": {
        "org/yanoproject/x/framework/standalone/StandaloneAppLedgerPlugin.class": "yano-x-appchain-standalone",
    },
    "cardano-construction": {
        "org/yanoproject/x/framework/cardano/CardanoAppLedgerPlugin.class": "yano-x-appchain-cardano",
        "org/yanoproject/x/framework/cardano/AppChainSubsystem.class": "yano-x-appchain-cardano",
        "org/yanoproject/x/framework/cardano/AppChainManager.class": "yano-x-appchain-cardano",
        "org/yanoproject/api/CardanoHostServices.class": "yano-cardano-api",
    },
}
SPECIAL_RESOURCES = {
    "META-INF/yano/plugins/org.yanoproject.x.app-ledger.json",
    "META-INF/plutus/AnchorThreadPolicy.plutus.json",
    "META-INF/plutus/AnchorValidator.plutus.json",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def checked(command, log, cwd, timeout):
    with log.open("wb") as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as interrupted:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            if isinstance(interrupted, KeyboardInterrupt):
                raise
            raise RuntimeError(f"Timed out; owned process group stopped: {log}") from None
    if code:
        raise RuntimeError(f"Command exited {code}; see {log}")


def request(matrix, label, profile="standalone"):
    row = dict(matrix[label])
    row["profile"] = profile
    row["frameworkVersion"] = matrix["frameworkVersion"]
    row["yaciCoreVersion"] = matrix["yaciCoreVersion"]
    row["repositories"] = matrix["repositories"]
    row.setdefault("foundationVersion", row["hostVersion"])
    return row


def resolve(specification, output, timeout):
    output.mkdir()
    inputs = output / "request.json"
    report = output / "resolved.json"
    write_json(inputs, specification)
    checked([str(ROOT / "gradlew"), "--no-daemon", "--no-parallel", "--console=plain",
             "--project-cache-dir", str(output / "gradle-cache"), "-p", str(HERE),
             "resolveHostInputs", f"-Prequest={inputs}", f"-Preport={report}"],
            output / "resolve.log", output, timeout)
    resolved = json.loads(report.read_text())
    verify_composition(resolved)
    return resolved


def verify_composition(resolved):
    profile = resolved["request"].get("profile", "standalone")
    expected_owners = OWNERS | PROFILE_OWNERS[profile]
    forbidden = ({"yano-x-appchain-cardano", "yano-x-appchain-cardano-api", "yano-x-appchain-anchor-onchain"}
                 if profile == "standalone" else {"yano-x-appchain-standalone"})
    owners = {}
    resources = {}
    for artifact in resolved["artifacts"]:
        path = Path(artifact["file"])
        if digest(path) != artifact["sha256"]:
            raise RuntimeError(f"Resolved input changed: {path}")
        if artifact["name"] in forbidden:
            raise RuntimeError(f"Forbidden activation artifact for {profile}: {path}")
        if not path.name.endswith(".jar"):
            raise RuntimeError(f"Expected ordinary published JAR: {path}")
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name == "META-INF/yano-plugin-index-v1.json":
                    raise RuntimeError(f"Input embeds an aggregate catalog; expected raw thin JAR: {path}")
                if name.startswith("org/yanoproject/") and name.endswith(".class"):
                    owners.setdefault(name, []).append(artifact)
                    if artifact["group"] == "org.yanoproject" and name.startswith((
                            "org/yanoproject/x/", "org/yanoproject/api/appchain/",
                            "org/yanoproject/runtime/appchain/")):
                        raise RuntimeError(f"Host artifact embeds framework class {name}: {path}")
                if name in SPECIAL_RESOURCES:
                    resources.setdefault(name, []).append(artifact)
    duplicates = [name for name, artifacts in owners.items() if len(artifacts) != 1]
    duplicates += [name for name, artifacts in resources.items() if len(artifacts) != 1]
    if duplicates:
        raise RuntimeError(f"Duplicate class/resource ownership: {duplicates[:20]}")
    for name, artifact_id in expected_owners.items():
        actual = owners.get(name, [])
        if len(actual) != 1 or actual[0]["name"] != artifact_id:
            raise RuntimeError(f"Unexpected owner for {name}: {actual}")
    if len(resources.get("META-INF/yano/plugins/org.yanoproject.x.app-ledger.json", [])) != 1:
        raise RuntimeError("Exactly one manifested framework activation root is required")
    if profile == "cardano-construction":
        for name in SPECIAL_RESOURCES - {"META-INF/yano/plugins/org.yanoproject.x.app-ledger.json"}:
            actual = resources.get(name, [])
            if len(actual) != 1 or actual[0]["name"] != "yano-x-appchain-anchor-onchain":
                raise RuntimeError(f"Missing or incorrect on-chain resource owner: {name}")
    resolved["ownershipVerified"] = True


def host_identity(resolved):
    return sorted((artifact["name"], artifact["version"], artifact["sha256"])
                  for artifact in resolved["artifacts"] if artifact["group"] == "org.yanoproject")


def prepare(matrix, output, timeout):
    baselines = {profile: resolve(request(matrix, "baseline", profile), output / f"baseline-{profile}", timeout)
                 for profile in matrix.get("profiles", ["standalone"])}
    resolved = baselines["standalone"]
    classes = output / "classes"
    classes.mkdir()
    empty_sourcepath = output / "empty-sourcepath"
    empty_sourcepath.mkdir()
    fixture = output / "packaged-host-fixture.jar"
    classpath = os.pathsep.join(artifact["file"] for artifact in resolved["artifacts"])
    checked(["javac", "-proc:none", "--release", "25", "-sourcepath", str(empty_sourcepath),
             "-classpath", classpath, "-d", str(classes), str(SOURCE)], output / "javac.log", output, timeout)
    checked(["jar", "--create", "--no-manifest", "--date=2026-01-01T00:00:00Z", "--file", str(fixture),
             "-C", str(classes), "."], output / "jar.log", output, timeout)
    provenance = {"schemaVersion": 1, "fixtureSha256": digest(fixture), "sourceSha256": digest(SOURCE),
                  "compiler": subprocess.check_output(["javac", "-version"], text=True).strip(),
                  "baselineRequest": resolved["request"], "compileInputs": resolved["artifacts"],
                  "baselineHostIdentity": host_identity(resolved),
                  "profileBaselines": {profile: {"hostIdentity": host_identity(baseline),
                                                  "inputs": baseline["artifacts"]}
                                       for profile, baseline in baselines.items()},
                  "frameworkNamespace": FRAMEWORK_NAMESPACE, "fixtureContract": FIXTURE_CONTRACT,
                  "provenance": "Exact supplied Maven artifacts; staged candidates are not claimed as public releases"}
    write_json(output / "provenance.json", provenance)
    shutil.rmtree(classes)
    print(f"Frozen fixture: {fixture}", flush=True)


def package_cell(resolved, fixture, directory, timeout):
    package = directory / "package"
    library = package / "lib"
    library.mkdir(parents=True)
    for artifact in resolved["artifacts"]:
        source = Path(artifact["file"])
        destination = library / source.name
        if destination.exists():
            raise RuntimeError(f"Ambiguous packaged artifact filename: {source.name}")
        shutil.copy2(source, destination)
        if digest(destination) != artifact["sha256"]:
            raise RuntimeError(f"Copied input differs: {source}")
    shutil.copy2(fixture, package / fixture.name)
    # The resolver generated the strict ARTIFACT_CLOSURE index with the shared plugin-index script.
    catalog = package / "catalog/META-INF/yano-plugin-index-v1.json"
    catalog.parent.mkdir(parents=True)
    shutil.copy2(resolved["pluginIndex"], catalog)
    write_json(package / "artifact-inputs.json", resolved)
    archive = directory / "packaged-host.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as target:
        for path in sorted(package.rglob("*")):
            if path.is_file():
                target.write(path, str(path.relative_to(package)))
    # Execute only the unpacked artifact, not project output folders or repository resources.
    execution = directory / "unpacked"
    with zipfile.ZipFile(archive) as packed:
        packed.extractall(execution)
    shutil.rmtree(package)
    classpath = [execution / "catalog", execution / fixture.name,
                 *sorted((execution / "lib").glob("*.jar"))]
    profile = resolved["request"].get("profile", "standalone")
    runtime_timeout = min(timeout, 60) if profile == "cardano-construction" else timeout
    checked(["java", "-cp", os.pathsep.join(map(str, classpath)), "compat.PackagedHostCluster",
             str(execution / "state"), profile], directory / "runtime.log", execution, runtime_timeout)
    result = json.loads((execution / "state/results.json").read_text())
    if not result.get("passed"):
        raise RuntimeError(f"Packaged cluster failed: {result}")
    from urllib.parse import unquote, urlparse
    by_name = {Path(artifact["file"]).name: artifact for artifact in resolved["artifacts"]}
    for name, origin in result["loadedOrigins"].items():
        path = Path(unquote(urlparse(origin).path)).resolve()
        expected = (OWNERS | PROFILE_OWNERS[resolved["request"].get("profile", "standalone")]).get(
            name.replace(".", "/") + ".class")
        if path.parent != (execution / "lib").resolve() or path.name not in by_name:
            raise RuntimeError(f"Class loaded outside packaged Maven inputs: {name}: {origin}")
        if expected and by_name[path.name]["name"] != expected:
            raise RuntimeError(f"Wrong loaded owner: {name}: {origin}")
        if digest(path) != by_name[path.name]["sha256"]:
            raise RuntimeError(f"Loaded artifact changed: {origin}")
    return {"passed": True, "packageSha256": digest(archive), "catalogSha256": digest(
            execution / "catalog/META-INF/yano-plugin-index-v1.json"), "runtime": result}


def run_matrix(matrix, fixture_directory, output, timeout):
    fixture = fixture_directory / "packaged-host-fixture.jar"
    provenance = json.loads((fixture_directory / "provenance.json").read_text())
    if (provenance.get("frameworkNamespace") != FRAMEWORK_NAMESPACE
            or provenance.get("fixtureContract") != FIXTURE_CONTRACT):
        raise RuntimeError("Fixture predates the Host API 1.2 contract (ADR-064): prepare a new baseline once "
                           "against a Host API 1.2 host")
    if digest(fixture) != provenance["fixtureSha256"] or digest(SOURCE) != provenance["sourceSha256"]:
        raise RuntimeError("Frozen fixture/source differs from explicit preparation provenance")
    report = {"schemaVersion": 1, "kind": "packaged-thin-host-qualification", "passed": False,
              "fixtureSha256": digest(fixture), "fixtureProvenance": provenance, "cells": []}
    requests = {}
    for label in ("baseline", "current"):
        for profile in matrix.get("profiles", ["standalone"]):
            specification = request(matrix, label, profile)
            key = json.dumps(specification, sort_keys=True)
            requests.setdefault(key, {"request": specification, "labels": []})["labels"].append(label)
    report["deduplicatedInitialBaseline"] = all(cell["labels"] == ["baseline", "current"]
                                                for cell in requests.values())
    try:
        for position, cell in enumerate(requests.values()):
            directory = output / f"cell-{position}"
            directory.mkdir()
            evidence = {"labels": cell["labels"], "request": cell["request"], "passed": False}
            report["cells"].append(evidence)
            started = time.monotonic()
            try:
                resolved = resolve(cell["request"], directory / "resolution", timeout)
                evidence["artifacts"] = resolved["artifacts"]
                profile = cell["request"]["profile"]
                profile_baseline = provenance.get("profileBaselines", {}).get(profile)
                if profile != "standalone" and profile_baseline is None:
                    raise RuntimeError(f"Frozen fixture has no prepared baseline for {profile}")
                frozen_inputs = profile_baseline["inputs"] if profile_baseline else provenance["compileInputs"]
                frozen_hashes = {(item["group"], item["name"], item["version"], item.get("classifier")):
                                 item["sha256"] for item in frozen_inputs}
                for artifact in resolved["artifacts"]:
                    coordinate = (artifact["group"], artifact["name"], artifact["version"], artifact.get("classifier"))
                    if coordinate in frozen_hashes and artifact["sha256"] != frozen_hashes[coordinate]:
                        raise RuntimeError(f"Previously frozen coordinate changed bytes: {coordinate}")
                frozen_host = (profile_baseline["hostIdentity"] if profile_baseline
                               else provenance["baselineHostIdentity"])
                if "baseline" in cell["labels"] and host_identity(resolved) != [tuple(item) for item in frozen_host]:
                    raise RuntimeError("Baseline host artifacts changed since fixture preparation")
                evidence.update(package_cell(resolved, fixture, directory, timeout))
            except Exception as failure:
                evidence["error"] = str(failure)
            evidence["elapsedSeconds"] = round(time.monotonic() - started, 2)
            write_json(output / "results.json", report)
        report["passed"] = all(cell["passed"] for cell in report["cells"])
    finally:
        write_json(output / "results.json", report)
    return report["passed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "run"))
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="New output directory outside the source checkout")
    parser.add_argument("--fixture", type=Path, help="Previously prepared immutable fixture directory (run only)")
    parser.add_argument("--timeout", type=int, default=180, help="Seconds per child process")
    args = parser.parse_args()
    matrix = json.loads(args.inputs.read_text())
    if matrix.get("schemaVersion") != 1 or not matrix.get("repositories"):
        parser.error("Expected schemaVersion=1 and explicit Maven repositories")
    profiles = matrix.get("profiles", ["standalone"])
    if (not isinstance(profiles, list) or "standalone" not in profiles
            or len(profiles) != len(set(profiles)) or not set(profiles) <= set(PROFILE_OWNERS)):
        parser.error("profiles must include standalone, optionally cardano-construction, without duplicates")
    output = args.out.resolve()
    if output.is_relative_to(ROOT) or output.exists():
        parser.error("Output must be a new directory outside the source checkout")
    if args.action == "run" and args.fixture is None:
        parser.error("run requires --fixture; it never compiles a replacement fixture")
    output.mkdir(parents=True)
    try:
        if args.action == "prepare":
            prepare(matrix, output, args.timeout)
            return 0
        return 0 if run_matrix(matrix, args.fixture.resolve(), output, args.timeout) else 1
    except Exception as failure:
        write_json(output / "failure.json", {"passed": False, "error": str(failure)})
        print(str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
