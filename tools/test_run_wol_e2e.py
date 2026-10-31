"""Unit checks for the E2E harness result parser and its fixture validation."""

from __future__ import annotations

import json
import io
import signal
import subprocess
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from typing import BinaryIO, cast
from unittest.mock import MagicMock, patch

from run_wol_e2e import (
    EXPECTED_MAC,
    E2EFailure,
    PHASE_MARKERS,
    Runner,
    main,
    parse_instrumentation_output,
    validate_serial,
    validate_store_snapshot,
)


RUN_ID = "0123456789abcdef0123456789abcdef"


def passing_output(phase: str) -> str:
    markers = "".join(PHASE_MARKERS[phase])
    return (
        f"INFO: isolated test store enabled runId={RUN_ID}"
        f"{markers}"
        f"INSTRUMENTATION_RESULT: netkit.phase={phase}\n"
        f"INSTRUMENTATION_RESULT: netkit.runId={RUN_ID}\n"
        "INSTRUMENTATION_RESULT: netkit.result=PASS\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


class InstrumentationParserTests(unittest.TestCase):
    def test_accepts_all_signals_and_required_markers(self) -> None:
        result = parse_instrumentation_output(
            passing_output("write"), phase="write", run_id=RUN_ID, exit_code=0
        )
        self.assertTrue(result.passed, result.reason)
        self.assertEqual(result.code, -1)
        self.assertEqual(len(result.markers), len(PHASE_MARKERS["write"]))

    def test_rejects_forged_pass_marker_without_instrumentation_result(self) -> None:
        result = parse_instrumentation_output(
            "PASS: all done", phase="write", run_id=RUN_ID, exit_code=0
        )
        self.assertFalse(result.passed)
        self.assertIn("instrumentation result", result.reason)
        self.assertIn("instrumentation code", result.reason)

    def test_rejects_result_pass_with_bad_code(self) -> None:
        output = passing_output("read").replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")
        result = parse_instrumentation_output(output, phase="read", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertIn("RESULT_OK (-1)", result.reason)

    def test_rejects_nonzero_adb_exit_even_when_output_says_pass(self) -> None:
        result = parse_instrumentation_output(
            passing_output("read"), phase="read", run_id=RUN_ID, exit_code=1
        )
        self.assertFalse(result.passed)
        self.assertIn("adb instrument exit code 1", result.reason)

    def test_rejects_explicit_instrumentation_failure_with_pass_result(self) -> None:
        output = passing_output("write") + "FAIL: later instrumentation failure"
        result = parse_instrumentation_output(output, phase="write", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertIn("failure marker", result.reason)

    def test_rejects_malformed_or_missing_result_fields(self) -> None:
        output = passing_output("write").replace("INSTRUMENTATION_RESULT: netkit.runId=", "BROKEN_RESULT=")
        result = parse_instrumentation_output(output, phase="write", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertIn("runId", result.reason)

    def test_rejects_wrong_run_id(self) -> None:
        result = parse_instrumentation_output(
            passing_output("write"), phase="write", run_id="f" * 32, exit_code=0
        )
        self.assertFalse(result.passed)
        self.assertIn("runId does not match", result.reason)

    def test_phase_timeout_is_failed_and_retains_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary)
            runner.target_validated = True
            process = MagicMock()
            process.wait.side_effect = [subprocess.TimeoutExpired(["mock"], 0.01), 0]
            process.poll.side_effect = [None, 0]
            def started(_command: list[str], **kwargs: object) -> MagicMock:
                stream = cast(BinaryIO, kwargs["stdout"])
                stream.write(b"partial instrument output\xff")
                stream.flush()
                return process
            with patch("run_wol_e2e.subprocess.Popen", side_effect=started), self.assertRaises(E2EFailure):
                runner.run_phase("write")
            self.assertTrue(runner.instrument_active)
            self.assertEqual((runner.out_dir / "instrumentation-write.log").read_text(), "partial instrument output\ufffd")
            self.assertFalse(json.loads((runner.out_dir / "instrumentation-write-summary.json").read_text())["passed"])
            with patch.object(runner, "adb", side_effect=E2EFailure("mock cleanup failure")):
                errors = runner.close()
            self.assertTrue(runner.instrument_active)  # Cannot claim stopped when force-stop failed.
            self.assertTrue(errors)
            self.assertIn('"instrument_cleanup": "FAIL"', runner.journal.read_text())
            self.assertIn('"test_store_cleanup": "FAIL"', runner.journal.read_text())
            self.assertIn('"diagnostic": "failure.png"', runner.journal.read_text())


def make_runner(temporary: str) -> Runner:
    return Runner(Namespace(serial="emulator-5554", run_id=RUN_ID, artifacts_dir=temporary,
        adb="/mock/adb", phase_timeout=0.01, adb_timeout=1, keep_test_data=False, matrix=False))


class FailureCleanupTests(unittest.TestCase):
    def test_interrupt_keeps_partial_log_and_stops_client(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary)
            process = MagicMock()
            process.wait.side_effect = [KeyboardInterrupt(), 0]
            process.poll.side_effect = [None, 0]
            def started(_command: list[str], **kwargs: object) -> MagicMock:
                stream = cast(BinaryIO, kwargs["stdout"])
                stream.write(b"before interrupt")
                stream.flush()
                return process
            with patch("run_wol_e2e.subprocess.Popen", side_effect=started), self.assertRaises(KeyboardInterrupt):
                runner.run_phase("write")
            process.terminate.assert_called_once()
            self.assertEqual((runner.out_dir / "instrumentation-write.log").read_text(), "before interrupt")
            self.assertFalse(json.loads((runner.out_dir / "instrumentation-write-summary.json").read_text())["passed"])

    def test_cleanup_failure_replaces_success_with_final_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary)
            runner.target_validated = True; runner.completed = True
            with patch("run_wol_e2e.Runner", return_value=runner), \
                 patch.object(runner, "run", return_value={"result": "PASS"}), \
                 patch.object(runner, "cleanup_test_store", side_effect=E2EFailure("fixture cleanup refused")), \
                 patch("sys.stdout", new_callable=io.StringIO) as output:
                code = main(["--serial", "emulator-5554", "--adb", "/mock/adb"])
            summary = json.loads((runner.out_dir / "summary.json").read_text())
            self.assertEqual(code, 1); self.assertEqual(summary["result"], "FAIL")
            self.assertFalse(summary["cleanup"]["passed"])
            self.assertEqual(json.loads(output.getvalue())["result"], "FAIL")

    def test_main_interrupt_writes_failure_and_cleans_up(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary); runner.target_validated = True
            original_handler = signal.getsignal(signal.SIGTERM)
            with patch("run_wol_e2e.Runner", return_value=runner), \
                 patch.object(runner, "run", side_effect=KeyboardInterrupt), \
                 patch.object(runner, "capture_failure_evidence") as diagnostics, \
                 patch.object(runner, "cleanup_test_store") as cleanup, \
                 patch("sys.stdout", new_callable=io.StringIO):
                code = main(["--serial", "emulator-5554", "--adb", "/mock/adb"])
            summary = json.loads((runner.out_dir / "summary.json").read_text())
            self.assertEqual(code, 1); self.assertEqual(summary["error"], "interrupted")
            diagnostics.assert_called_once(); cleanup.assert_called_once()
            self.assertEqual(signal.getsignal(signal.SIGTERM), original_handler)

    def test_diagnostic_error_does_not_skip_listener_or_store_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary); runner.target_validated = True
            runner.listener = MagicMock(); runner.listener.poll.return_value = None
            with patch.object(runner, "capture_failure_evidence", side_effect=OSError()), \
                 patch.object(runner, "cleanup_test_store") as cleanup:
                errors = runner.close()
            runner.listener.terminate.assert_called_once(); cleanup.assert_called_once()
            self.assertTrue(any("diagnostics" in item for item in errors))

    def test_failure_artifacts_are_copied_even_when_optional_files_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary)
            png = b"\x89PNG\r\n\x1a\nfixture"
            def adb(*args: str, **_kwargs: object) -> subprocess.CompletedProcess[bytes]:
                if args[:2] == ("exec-out", "screencap") or args[-1].endswith("/failure.png"):
                    return subprocess.CompletedProcess(args, 0, png, b"")
                if args[-1].endswith("/failure-tree.txt"):
                    return subprocess.CompletedProcess(args, 0, b"node enabled=true focused=false", b"")
                return subprocess.CompletedProcess(args, 1, b"", b"unavailable")
            with patch.object(runner, "adb", side_effect=adb):
                runner.capture_failure_evidence()
            self.assertEqual((runner.out_dir / "failure.png").read_bytes(), png)
            self.assertEqual((runner.out_dir / "app-failure-tree.txt").read_text(), "node enabled=true focused=false")

    def test_abort_confirms_process_absent_before_removing_store(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = make_runner(temporary); runner.target_validated = True; runner.instrument_active = True
            results = [subprocess.CompletedProcess([], 0, "", ""), subprocess.CompletedProcess([], 1, "", "")]
            with patch.object(runner, "capture_failure_evidence"), \
                 patch.object(runner, "adb", side_effect=results), \
                 patch.object(runner, "cleanup_test_store") as cleanup:
                errors = runner.close()
            self.assertFalse(errors); self.assertFalse(runner.instrument_active); cleanup.assert_called_once()


class TargetAndFixtureTests(unittest.TestCase):
    def test_accepts_only_emulator_serials(self) -> None:
        self.assertEqual(validate_serial("emulator-5554"), "emulator-5554")
        for serial in ("0123456789ABCDEF", "localhost:5555", "emulator", "emulator-abc", ""):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                validate_serial(serial)

    def test_accepts_isolated_write_and_read_json_snapshots(self) -> None:
        base = {
            "id": "device-1",
            "name": f"NetKit-{RUN_ID}-main",
            "mac": EXPECTED_MAC,
            "targetMode": "CUSTOM",
            "customTarget": "10.0.2.2",
            "port": 9009,
            "probeHost": "192.0.2.10",
            "probePort": 22,
            "createdAt": 100,
            "updatedAt": 100,
            "repeatCount": 3, "repeatIntervalMs": 250, "probeEnabled": False, "probeDelaySeconds": 20,
        }
        write_raw = json.dumps({"schema": 2, "devices": [{**base, "note": "write phase persistence note"}]}).encode()
        read_raw = json.dumps({"schema": 2, "devices": [{**base, "note": "read phase edited note"}]}).encode()
        self.assertEqual(len(validate_store_snapshot(write_raw, stage="write", run_id=RUN_ID)["devices"]), 1)
        self.assertEqual(len(validate_store_snapshot(read_raw, stage="read", run_id=RUN_ID)["devices"]), 1)

    def test_rejects_malformed_or_unexpected_store_snapshot(self) -> None:
        with self.assertRaisesRegex(ValueError, "valid UTF-8 JSON"):
            validate_store_snapshot(b"{", stage="write", run_id=RUN_ID)
        with self.assertRaisesRegex(ValueError, "exactly one"):
            validate_store_snapshot(b'{"schema":2,"devices":[]}', stage="write", run_id=RUN_ID)


if __name__ == "__main__":
    unittest.main()
