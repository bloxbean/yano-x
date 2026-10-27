#!/usr/bin/env python3
"""A stand-in for the distribution's yano.sh in the showcase script contract.

It writes the files the app-chain setup commands write (draft, member cards, node directories, sealed file,
chain indexes) closely enough for showcase.sh to run end to end, and logs every invocation to
$SHOWCASE_STUB_LOG. It checks what a real operator's command would refuse: a member key file that is not an
owner-only 64-hex seed, or a join without the sealed file's fingerprint.
"""

from __future__ import annotations

import json
import os
import pathlib
import re
import stat
import sys

FINGERPRINT = "abcd-0000-1111-2222-3333-4444-5555-6666"


def log(words: list[str]) -> None:
    with open(os.environ["SHOWCASE_STUB_LOG"], "a", encoding="utf-8") as stream:
        stream.write(" ".join(words) + "\n")


def options(args: list[str], repeated: tuple[str, ...] = ()) -> tuple[list[str], dict]:
    positional, values = [], {name: [] for name in repeated}
    index = 0
    while index < len(args):
        arg = args[index]
        if arg.startswith("--"):
            name = arg[2:]
            if index + 1 < len(args) and not args[index + 1].startswith("--"):
                value = args[index + 1]
                index += 2
            else:
                value = True
                index += 1
            if name in repeated:
                values[name].append(value)
            else:
                values[name] = value
        else:
            positional.append(arg)
            index += 1
    return positional, values


def template_chains(template: str) -> dict[str, dict[str, str]]:
    """Chain id -> its flat `settings:` entries, in template order."""
    chains: dict[str, dict[str, str]] = {}
    current = None
    in_settings = False
    for line in pathlib.Path(template).read_text(encoding="utf-8").splitlines():
        match = re.match(r"^  - id: (\S+)", line)
        if match:
            current = chains.setdefault(match.group(1), {})
            in_settings = False
        elif current is not None and re.match(r"^    settings:\s*$", line):
            in_settings = True
        elif in_settings and (setting := re.match(r'^      ([\w.-]+): "?([^"]*)"?$', line)):
            current[setting.group(1)] = setting.group(2)
        elif re.match(r"^    \S", line):
            in_settings = False
    return chains


def new(args: list[str]) -> None:
    positional, values = options(args, ("setting", "without", "chain", "answer"))
    output = pathlib.Path(values["output"])
    output.mkdir(parents=True, exist_ok=True)
    draft = {"name": positional[0], "template": values["from"], "network": values["network"],
             "operators": values["operators"].split(","), "threshold": values["threshold"],
             "settings": values["setting"], "without": values["without"]}
    (output / f"{positional[0]}.draft.yaml").write_text(json.dumps(draft), encoding="utf-8")


def request(args: list[str]) -> None:
    positional, values = options(args)
    node = pathlib.Path(values["node-dir"])
    if not (node / "node.json").exists():
        key = pathlib.Path(values["key"])
        mode = stat.S_IMODE(key.stat().st_mode)
        seed = key.read_text(encoding="ascii")
        if mode & 0o077 or not re.fullmatch(r"[0-9a-f]{64}\n", seed):
            sys.exit(f"member key file {key} is not an owner-only 64-hex seed")
        (node / "secrets").mkdir(parents=True, exist_ok=True)
        (node / "secrets" / "member.key").write_text(seed, encoding="ascii")
        (node / "secrets" / "api.key").write_text("contract-api-key\n", encoding="ascii")
        (node / "node.json").write_text(json.dumps({
            "name": values["name"], "httpPort": int(values["http-port"]),
            "nodePort": int(values["node-port"])}), encoding="utf-8")
    name = json.loads((node / "node.json").read_text(encoding="utf-8"))["name"]
    output = pathlib.Path(values.get("output", "."))
    (output / f"{name}.member").write_text(json.dumps({"name": name}), encoding="utf-8")


def seal(args: list[str]) -> None:
    draft_file = pathlib.Path(args[0])
    draft = json.loads(draft_file.read_text(encoding="utf-8"))
    chains = {chain: settings for chain, settings in template_chains(draft["template"]).items()
              if chain not in draft["without"]}
    sealed = {"genesis": {"name": draft["name"], "network": draft["network"],
                          "members": draft["operators"], "chains": [
        {"id": chain, "settings": {**settings, "state.genesis-id": "22" * 32}}
        for chain, settings in chains.items()]}}
    for setting in draft["settings"]:
        chain, rest = setting.split(".", 1)
        key, value = rest.split("=", 1)
        for entry in sealed["genesis"]["chains"]:
            if entry["id"] == chain:
                entry["settings"][key] = value
    draft_file.with_name(f"{draft['name']}.chain").write_text(json.dumps(sealed), encoding="utf-8")


def join(args: list[str]) -> None:
    positional, values = options(args)
    if values.get("expect-fingerprint") != FINGERPRINT:
        sys.exit("join needs --expect-fingerprint")
    sealed = json.loads(pathlib.Path(positional[0]).read_text(encoding="utf-8"))
    node = pathlib.Path(values["node-dir"])
    (node / "apps").mkdir(parents=True, exist_ok=True)
    (node / "config").mkdir(parents=True, exist_ok=True)
    indexes_file = node / "apps" / "chain-indexes.json"
    indexes = json.loads(indexes_file.read_text(encoding="utf-8")) if indexes_file.exists() else {}
    for chain in sealed["genesis"]["chains"]:
        indexes.setdefault(chain["id"], len(indexes))
    indexes_file.write_text(json.dumps(indexes), encoding="utf-8")
    (node / "apps" / f"{sealed['genesis']['name']}.chain").write_text(json.dumps(sealed), encoding="utf-8")


def inspect(args: list[str]) -> None:
    if "--json" in args:
        print(json.dumps({"kind": "chain", "displayFingerprint": FINGERPRINT}))
    else:
        print(f"{pathlib.Path(args[0]).name}  chain fingerprint {FINGERPRINT}")


def main() -> None:
    args = sys.argv[1:]
    log(["yano"] + args)
    if len(args) < 2 or args[0] != "appchain":
        return
    verb, rest = args[1], args[2:]
    handlers = {"new": new, "request": request, "seal": seal, "join": join, "inspect": inspect}
    if verb in handlers:
        handlers[verb](rest)


if __name__ == "__main__":
    main()
