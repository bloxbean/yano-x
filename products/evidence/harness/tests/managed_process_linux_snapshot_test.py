#!/usr/bin/env python3
"""Regression tests for identity-consistent Linux process snapshots."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest import mock


TOOL = Path(__file__).resolve().parents[1] / "tools" / "managed_process.py"
SPEC = importlib.util.spec_from_file_location("managed_process", TOOL)
assert SPEC is not None and SPEC.loader is not None
managed_process = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = managed_process
SPEC.loader.exec_module(managed_process)


class LinuxSnapshotTest(unittest.TestCase):
    PID = 123

    def test_exit_between_stat_and_cmdline_is_observed_as_zombie(self) -> None:
        active = (1000, "linux:42", False)
        zombie = (1000, "linux:42", True)
        with (
            mock.patch.object(
                managed_process, "linux_process_info", side_effect=(active, zombie, zombie)
            ),
            mock.patch.object(managed_process.Path, "read_bytes", return_value=b""),
            mock.patch.object(managed_process.time, "sleep") as sleep,
        ):
            snapshot = managed_process.linux_snapshot(self.PID, include_argv=True)

        self.assertEqual(
            snapshot,
            managed_process.ProcessSnapshot(
                self.PID, 1000, "linux:42", None, zombie=True
            ),
        )
        sleep.assert_called_once_with(managed_process.LINUX_SNAPSHOT_RETRY_SECONDS)

    def test_pid_identity_change_retries_without_mixing_argv(self) -> None:
        old = (1000, "linux:42", False)
        current = (1000, "linux:84", False)
        with (
            mock.patch.object(
                managed_process,
                "linux_process_info",
                side_effect=(old, current, current, current),
            ),
            mock.patch.object(
                managed_process.Path,
                "read_bytes",
                side_effect=(b"old\0argv\0", b"/bin/sleep\0" b"300\0"),
            ),
            mock.patch.object(managed_process.time, "sleep") as sleep,
        ):
            snapshot = managed_process.linux_snapshot(self.PID, include_argv=True)

        self.assertEqual(
            snapshot,
            managed_process.ProcessSnapshot(
                self.PID, 1000, "linux:84", ("/bin/sleep", "300")
            ),
        )
        sleep.assert_called_once_with(managed_process.LINUX_SNAPSHOT_RETRY_SECONDS)

    def test_empty_cmdline_is_retried_during_exit_transition(self) -> None:
        active = (1000, "linux:42", False)
        zombie = (1000, "linux:42", True)
        with (
            mock.patch.object(
                managed_process,
                "linux_process_info",
                side_effect=(active, active, zombie),
            ),
            mock.patch.object(managed_process.Path, "read_bytes", return_value=b""),
            mock.patch.object(managed_process.time, "sleep") as sleep,
        ):
            snapshot = managed_process.linux_snapshot(self.PID, include_argv=True)

        self.assertEqual(
            snapshot,
            managed_process.ProcessSnapshot(
                self.PID, 1000, "linux:42", None, zombie=True
            ),
        )
        sleep.assert_called_once_with(managed_process.LINUX_SNAPSHOT_RETRY_SECONDS)


if __name__ == "__main__":
    unittest.main()
