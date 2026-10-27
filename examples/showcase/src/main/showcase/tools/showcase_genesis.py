#!/usr/bin/env python3
"""Pin one immutable devnet genesis before observer configuration or JVM startup."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import stat
import tempfile


MAX_BYTES = 1_048_576
PIN_NAME = "devnet-shelley-genesis.json"
OBSERVER_ID = re.compile(r"^yano\.app-chain\.chains\[\d+]\.observation\.l1-network-genesis-id=(.*)$")


class Refusal(ValueError):
    pass


def controlled_directory(path: pathlib.Path, *, owner_required: bool = False) -> None:
    info = path.lstat()
    owners = (os.getuid(),) if owner_required else (0, os.getuid())
    if not stat.S_ISDIR(info.st_mode) or info.st_uid not in owners or info.st_mode & 0o022:
        raise Refusal("genesis/configuration directory must be owner controlled")
    child = path.resolve(strict=True)
    while child.parent != child:
        parent = child.parent
        parent_info = parent.stat()
        if parent_info.st_uid not in (0, os.getuid()):
            raise Refusal("genesis/configuration directory has an untrusted ancestor")
        if parent_info.st_mode & 0o022:
            if not parent_info.st_mode & stat.S_ISVTX or child.stat().st_uid not in (0, os.getuid()):
                raise Refusal("genesis/configuration directory has a writable ancestor")
        child = parent


def read_regular(path: pathlib.Path, maximum: int = MAX_BYTES) -> bytes:
    controlled_directory(path.parent)
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode):
        raise Refusal("genesis/configuration must be a regular file, never a symbolic link")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if ((info.st_dev, info.st_ino) != (before.st_dev, before.st_ino)
                or not stat.S_ISREG(info.st_mode) or info.st_uid not in (0, os.getuid())
                or info.st_mode & 0o022 or not 1 <= info.st_size <= maximum):
            raise Refusal("genesis/configuration must be a bounded, owner-controlled regular file")
        result = source.read(maximum + 1)
        after = path.lstat()
        if (len(result) != info.st_size or len(result) > maximum
                or (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                != (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)):
            raise Refusal("genesis/configuration changed while reading")
        return result


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refusal("duplicate genesis property")
        result[key] = value
    return result


def invalid_constant(value: str) -> None:
    raise Refusal("non-finite genesis number")


def genesis(data: bytes) -> dict:
    value = json.loads(data, object_pairs_hook=unique_object, parse_constant=invalid_constant)
    if not isinstance(value, dict):
        raise Refusal("genesis must be a JSON object")
    start = value.get("systemStart")
    if not isinstance(start, str) or not start.endswith("Z"):
        raise Refusal("genesis must contain a UTC systemStart")
    parsed = datetime.datetime.fromisoformat(start[:-1] + "+00:00")
    if parsed.utcoffset() != datetime.timedelta(0) or parsed.timestamp() <= 0:
        raise Refusal("invalid genesis systemStart")
    return value


def validate_retained(root: pathlib.Path, data: bytes, pinned: bool) -> None:
    digest = hashlib.sha256(data).hexdigest()
    nodes = sorted(path for path in (root / "cluster").glob("node[0-9]*")
                   if re.fullmatch(r"node[0-9]+", path.name))
    overlays = sorted(path for path in (root / "node-config").glob("node[0-9]*.properties")
                      if re.fullmatch(r"node[0-9]+\.properties", path.name))
    if len(nodes) > 128 or len(overlays) > 128:
        raise Refusal("too many retained node configurations")
    for node in nodes:
        if node.is_symlink() or not node.is_dir():
            raise Refusal("retained node directory is not a regular directory")
        has_state = ((node / "chainstate/CURRENT").exists()
                     or next((node / "appchain-chainstate").glob("*/CURRENT"), None) is not None)
        if has_state and not pinned:
            raise Refusal("retained devnet state predates the immutable genesis pin; "
                             "preserve it and use a new instance, or restore its original pin")
        retained = node / "shelley-genesis.json"
        if retained.exists() or retained.is_symlink():
            if read_regular(retained) != data:
                raise Refusal("retained node genesis differs from the immutable devnet genesis")
        elif has_state:
            raise Refusal("retained node state is missing its original devnet genesis")
    for overlay in overlays:
        ids = []
        for line in read_regular(overlay, 40 * MAX_BYTES).decode("utf-8").splitlines():
            match = OBSERVER_ID.fullmatch(line)
            if match:
                ids.append(match.group(1))
        if not ids or any(value != digest for value in ids):
            raise Refusal("retained observer genesis identity differs from the immutable devnet genesis; "
                             "no retained configuration was rewritten")


def stage(root: pathlib.Path, template: pathlib.Path, custom: pathlib.Path | None) -> pathlib.Path:
    controlled_directory(root, owner_required=True)
    target = root / PIN_NAME
    pinned = target.exists() or target.is_symlink()
    if pinned:
        data = read_regular(target)
        genesis(data)
        if custom is not None and read_regular(custom) != data:
            raise Refusal("selected custom genesis differs from the immutable devnet genesis")
    elif custom is not None:
        data = read_regular(custom)
        genesis(data)
    else:
        document = genesis(read_regular(template))
        # Match the packaged launcher's stock-devnet normalization before
        # passing this as an immutable custom input. Explicit custom geometry
        # and every retained pin remain byte for byte unchanged.
        document["epochLength"] = 500
        # The launcher passes this exact timestamp to the host. The producer
        # therefore never rewrites these bytes after observer identities hash it.
        document["systemStart"] = datetime.datetime.now(datetime.timezone.utc).isoformat(
            timespec="seconds").replace("+00:00", "Z")
        data = (json.dumps(document, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
        if len(data) > MAX_BYTES:
            raise Refusal("generated devnet genesis exceeds its size bound")
    validate_retained(root, data, pinned)
    if not pinned:
        # Publish a complete file without replacing a concurrent first prepare.
        with tempfile.NamedTemporaryFile(prefix=".devnet-genesis-", dir=root) as temporary:
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
            try:
                os.link(temporary.name, target)
            except FileExistsError:
                if read_regular(target) != data:
                    raise Refusal("another prepare pinned a different devnet genesis; retry using that pin")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance-root", required=True, type=pathlib.Path)
    parser.add_argument("--template", required=True, type=pathlib.Path)
    parser.add_argument("--custom", type=pathlib.Path)
    args = parser.parse_args()
    stage(args.instance_root, args.template, args.custom)


if __name__ == "__main__":
    try:
        main()
    except Refusal as error:
        raise SystemExit(f"Devnet genesis preparation refused: {error}")
    except (OSError, ValueError, TypeError, OverflowError, RecursionError):
        # Parser/OS errors can contain untrusted file contents; report no bytes.
        raise SystemExit("Devnet genesis preparation refused: invalid or inaccessible genesis/configuration")
