#!/usr/bin/env python3
"""Bounded input and immutable-publication contracts for devnet genesis pinning."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
TOOL = Path(__file__).resolve().parents[2] / "main/showcase/tools/showcase_genesis.py"
spec = importlib.util.spec_from_file_location("showcase_genesis", TOOL)
genesis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(genesis)


class GenesisContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="showcase-genesis-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "instance"
        self.root.mkdir(mode=0o700)
        self.template = self.base / "template.json"
        self.original = (b'{"systemStart":"2020-01-01T00:00:00Z",'
                         b'"epochLength":1200,"securityParam":100,"activeSlotsCoeff":1.0}\n')
        self.template.write_bytes(self.original)

    def stage(self, custom=None):
        return genesis.stage(self.root, self.template, custom)

    def test_stock_pins_current_timestamp_once_and_keeps_template(self):
        target = self.stage()
        data = target.read_bytes()
        self.assertNotEqual(data, self.original)
        expected = json.loads(self.original)
        expected["systemStart"] = json.loads(data)["systemStart"]
        expected["epochLength"] = 500
        self.assertEqual(json.loads(data), expected)
        self.assertEqual(self.template.read_bytes(), self.original)
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.stage().read_bytes(), data)

    def test_custom_bytes_and_timestamp_remain_exact(self):
        target = self.stage(self.template)
        self.assertEqual(target.read_bytes(), self.original)
        self.assertEqual(json.loads(target.read_bytes())["epochLength"], 1200)
        self.assertEqual(self.stage(self.template).read_bytes(), self.original)
        self.template.write_bytes(self.original + b" ")
        with self.assertRaisesRegex(genesis.Refusal, "selected custom genesis differs"):
            self.stage(self.template)
        self.assertEqual(target.read_bytes(), self.original)

    def test_concurrent_prepare_never_replaces_winning_identity(self):
        target = self.root / genesis.PIN_NAME
        winning = self.original + b" "
        def concurrent_link(source, destination):
            target.write_bytes(winning)
            raise FileExistsError()
        with patch.object(genesis.os, "link", side_effect=concurrent_link):
            with self.assertRaisesRegex(genesis.Refusal, "another prepare pinned"):
                self.stage()
        self.assertEqual(target.read_bytes(), winning)
        self.assertEqual(list(self.root.glob(".devnet-genesis-*")), [])

    def test_all_retained_nodes_and_observer_overlays_are_checked(self):
        target = self.stage()
        data = target.read_bytes()
        for i in (0, 1, 2):
            node = self.root / "cluster" / f"node{i}"
            node.mkdir(parents=True)
            (node / "shelley-genesis.json").write_bytes(data)
        overlay_dir = self.root / "node-config"
        overlay_dir.mkdir()
        overlay = overlay_dir / "node2.properties"
        prefix = "yano.app-chain.chains[12].observation.l1-network-genesis-id="
        overlay.write_text(prefix + hashlib.sha256(data).hexdigest() + "\n")
        self.assertEqual(self.stage().read_bytes(), data)
        overlay.write_text(prefix + "00" * 32 + "\n")
        with self.assertRaisesRegex(genesis.Refusal, "retained observer genesis identity differs"):
            self.stage()
        self.assertEqual(overlay.read_text(), prefix + "00" * 32 + "\n")
        self.assertEqual(target.read_bytes(), data)

    def test_missing_pin_never_adopts_legacy_persisted_state(self):
        state = self.root / "cluster/node1/appchain-chainstate/history"
        state.mkdir(parents=True)
        (state / "CURRENT").touch()
        with self.assertRaisesRegex(genesis.Refusal, "predates the immutable genesis pin"):
            self.stage()
        self.assertFalse((self.root / genesis.PIN_NAME).exists())

    def test_live_launcher_sidecars_are_not_node_directories_or_overlays(self):
        target = self.stage()
        data = target.read_bytes()
        cluster = self.root / "cluster"
        overlays = self.root / "node-config"
        cluster.mkdir()
        overlays.mkdir()
        digest = hashlib.sha256(data).hexdigest()
        for index in (0, 1, 2, 12):
            node = cluster / f"node{index}"
            node.mkdir()
            (node / "shelley-genesis.json").write_bytes(data)
            (overlays / f"node{index}.properties").write_text(
                f"yano.app-chain.chains[12].observation.l1-network-genesis-id={digest}\n")
            # Exact sidecar names produced by the packaged cluster.sh helpers.
            for suffix in (".pid", ".pid.meta", ".launch", ".pid.tmp.123", ".pid.meta.tmp.123"):
                (cluster / f"node{index}{suffix}").write_text("123\n")
            (overlays / f"node{index}-private.properties").write_text("unrelated-private-overlay\n")
        self.assertEqual(self.stage().read_bytes(), data)
        (cluster / "node12/shelley-genesis.json").write_bytes(data + b" ")
        with self.assertRaisesRegex(genesis.Refusal, "retained node genesis differs"):
            self.stage()
        self.assertEqual(target.read_bytes(), data)

    def test_exact_numeric_node_names_still_reject_files_and_symlinks(self):
        self.stage()
        cluster = self.root / "cluster"
        cluster.mkdir()
        node = cluster / "node12"
        node.write_text("not-a-directory\n")
        with self.assertRaisesRegex(genesis.Refusal, "retained node directory is not"):
            self.stage()
        node.unlink()
        node.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(genesis.Refusal, "retained node directory is not"):
            self.stage()

    def test_symlink_fifo_writable_source_and_parent_are_refused(self):
        source = self.base / "custom.json"
        source.symlink_to(self.template)
        with self.assertRaises(genesis.Refusal):
            self.stage(source)
        source.unlink()
        os.mkfifo(source)
        with self.assertRaises(genesis.Refusal):
            self.stage(source)
        source.unlink()
        source.write_bytes(self.original)
        source.chmod(0o666)
        with self.assertRaises(genesis.Refusal):
            self.stage(source)
        source.chmod(0o600)
        self.base.chmod(0o777)
        try:
            with self.assertRaises(genesis.Refusal):
                self.stage(source)
        finally:
            self.base.chmod(0o700)

    def test_bounded_unique_json_and_timestamp_required(self):
        for data in (b"{}", b"x" * (genesis.MAX_BYTES + 1),
                     b'{"systemStart":"2020-01-01T00:00:00Z","systemStart":"2021-01-01T00:00:00Z"}',
                     b'{"systemStart":"2020-01-01T00:00:00Z","x":NaN}'):
            with self.subTest(size=len(data)):
                self.template.write_bytes(data)
                with self.assertRaises((genesis.Refusal, ValueError)):
                    self.stage(self.template)
                self.assertFalse((self.root / genesis.PIN_NAME).exists())

    def test_parser_failures_do_not_reflect_input(self):
        self.template.write_text(json.dumps({"systemStart": "PRIVATE-SEED-MATERIALZ"}))
        result = subprocess.run([sys.executable, str(TOOL), "--instance-root", str(self.root),
                                 "--template", str(self.template), "--custom", str(self.template)],
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("PRIVATE-SEED-MATERIAL", result.stderr + result.stdout)

    @unittest.skipIf(os.getuid() == 0, "root is already the launcher user")
    def test_root_owned_release_sources_are_readable_but_destination_is_user_owned(self):
        original_lstat = Path.lstat
        original_fstat = os.fstat
        def root_owned(info):
            values = list(info)
            values[4] = 0
            return os.stat_result(values)
        with patch.object(Path, "lstat", lambda path: root_owned(original_lstat(path))), \
                patch.object(genesis.os, "fstat", lambda fd: root_owned(original_fstat(fd))):
            self.assertEqual(genesis.read_regular(self.template), self.original)
            with self.assertRaisesRegex(genesis.Refusal, "directory must be owner controlled"):
                self.stage()


if __name__ == "__main__":
    unittest.main()
