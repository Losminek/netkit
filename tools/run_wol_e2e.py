#!/usr/bin/env python3
"""Run the NetKit WoL write/listener/restart/read flow against one emulator."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import signal
import shutil
import subprocess
import sys
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

APP_PACKAGE = "cz.netkit.app"
TEST_COMPONENT = "cz.netkit.app.test/cz.netkit.app.test.NetKitInstrumentation"
EXPECTED_MAC = "AA:BB:CC:DD:EE:FF"
EXPECTED_PACKET_HEX = "ff" * 6 + bytes.fromhex("aabbccddeeff").hex() * 16
RUN_ID_PATTERN = re.compile(r"^[a-f0-9]{32}$")
SERIAL_PATTERN = re.compile(r"^emulator-[0-9]+$")
PHASE_MARKERS: dict[str, tuple[str, ...]] = {
    "matrix": ("PASS: single WoL IME diagnostic matrix completed",),
    "write": (
        "PASS: four tabs present and feature controls open",
        "PASS: invalid MAC is rejected in the UI",
        "PASS: form save button remains above visible keyboard",
        "PASS: real keyboard input received by focused field",
        "PASS: first Back hides keyboard and preserves draft",
        "PASS: Back from dirty form prompts; keep editing preserves draft",
        "PASS: explicit discard leaves isolated store empty",
        "PASS: primary device created",
        "PASS: second device edited and saved",
        "PASS: second device deleted; primary record remains",
        "PASS: AndroidNetworkInfoProvider returned a Wi-Fi network before send",
        "PASS: UI reported packet sent for 10.0.2.2:9009",
    ),
    "read": (
        "PASS: persisted primary fields survived external force-stop and restart",
        "PASS: persisted primary record edited",
        "PASS: temporary record deleted; one edited primary record remains",
        "PASS: read phase left the primary record visible for screenshot",
        "PASS: screenshot read-phase.png saved in isolated test directory",
    ),
}


@dataclass(frozen=True)
class InstrumentationResult:
    passed: bool
    reason: str
    phase: str
    run_id: str
    result: str
    code: int | None
    markers: tuple[str, ...]


def validate_serial(serial: str) -> str:
    """Accept only explicit emulator serials; reject physical/network ADB targets."""
    if not SERIAL_PATTERN.fullmatch(serial):
        raise ValueError(f"serial must match emulator-<digits>, got {serial!r}")
    return serial


def parse_instrumentation_output(
    output: str,
    *,
    phase: str,
    run_id: str,
    exit_code: int,
) -> InstrumentationResult:
    """Require all independent Android instrumentation success signals and UI markers."""
    values: dict[str, str] = {}
    codes: list[int] = []
    for result_match in re.finditer(
        r"INSTRUMENTATION_RESULT:\s*([^=\r\n]+)=([^\r\n]*)", output
    ):
        values[result_match.group(1).strip()] = result_match.group(2).strip()
    for code_match in re.finditer(r"INSTRUMENTATION_CODE:\s*(-?[0-9]+)", output):
        codes.append(int(code_match.group(1)))

    markers = tuple(marker for marker in PHASE_MARKERS.get(phase, ()) if marker in output)
    observed_code = codes[-1] if codes else None
    failures: list[str] = []
    if phase not in PHASE_MARKERS:
        failures.append(f"unsupported phase {phase!r}")
    if exit_code != 0:
        failures.append(f"adb instrument exit code {exit_code}")
    if values.get("netkit.phase") != phase:
        failures.append(f"phase result {values.get('netkit.phase')!r} != {phase!r}")
    if values.get("netkit.runId") != run_id:
        failures.append("instrumentation runId does not match this isolated run")
    if values.get("netkit.result") != "PASS":
        failures.append(f"instrumentation result {values.get('netkit.result')!r} is not PASS")
    if observed_code != -1:
        failures.append(f"instrumentation code {observed_code!r} is not RESULT_OK (-1)")
    if "FAIL:" in output or "netkit.error=" in output:
        failures.append("failure marker present")
    missing = [marker for marker in PHASE_MARKERS.get(phase, ()) if marker not in output]
    if missing:
        failures.append("missing required PASS markers: " + "; ".join(missing))
    if f"runId={run_id}" not in output:
        failures.append("isolated store INFO marker missing")
    return InstrumentationResult(
        passed=not failures,
        reason="; ".join(failures) if failures else "all instrumentation signals and markers passed",
        phase=phase,
        run_id=run_id,
        result=values.get("netkit.result", ""),
        code=observed_code,
        markers=markers,
    )


def validate_store_snapshot(raw: bytes, *, stage: str, run_id: str) -> dict[str, Any]:
    """Validate the isolated JSON fixture at either side of the external restart."""
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{stage} test store is not valid UTF-8 JSON: {error}") from error
    if not isinstance(value, dict) or value.get("schema") != 2:
        raise ValueError(f"{stage} test store must contain schema 2")
    devices = value.get("devices")
    if not isinstance(devices, list) or len(devices) != 1:
        raise ValueError(f"{stage} test store must contain exactly one retained test record")
    device = devices[0]
    expected_name = f"NetKit-{run_id}-main"
    expected_note = "write phase persistence note" if stage == "write" else "read phase edited note"
    expected_fields = {
        "name": expected_name,
        "mac": EXPECTED_MAC,
        "targetMode": "CUSTOM",
        "customTarget": "10.0.2.2",
        "port": 9009,
        "probeHost": "192.0.2.10",
        "probePort": 22,
        "note": expected_note,
        "repeatCount": 3,
        "repeatIntervalMs": 250,
        "probeEnabled": False,
        "probeDelaySeconds": 20,
    }
    if not isinstance(device, dict):
        raise ValueError(f"{stage} device record is not an object")
    mismatches = [f"{key}={device.get(key)!r} expected {expected!r}"
                  for key, expected in expected_fields.items() if device.get(key) != expected]
    if mismatches:
        raise ValueError(f"{stage} record values mismatch: " + "; ".join(mismatches))
    if not isinstance(device.get("id"), str) or not device["id"]:
        raise ValueError(f"{stage} record has no id")
    return value


class E2EFailure(RuntimeError):
    """A failed orchestration step with evidence stored in the run directory."""


class Runner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.serial = validate_serial(args.serial)
        self.run_id = args.run_id or uuid.uuid4().hex
        if not RUN_ID_PATTERN.fullmatch(self.run_id):
            raise ValueError("run-id must be exactly 32 lowercase UUID hex characters")
        self.out_dir = Path(args.artifacts_dir).resolve() / self.run_id
        self.out_dir.mkdir(parents=True, exist_ok=False)
        self.journal = self.out_dir / "commands.jsonl"
        self.listener: subprocess.Popen[bytes] | None = None
        self.instrument_active = False
        self.cleanup_errors: list[str] = []
        self.completed = False
        self.target_validated = False
        self.listener_log = self.out_dir / "listener.log"
        self.adb_path = self._resolve_adb(args.adb)

    @staticmethod
    def _resolve_adb(explicit: str | None) -> str:
        if explicit:
            return explicit
        android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if android_home:
            candidate = Path(android_home) / "platform-tools" / "adb"
            if candidate.is_file():
                return str(candidate)
        fallback = Path.home() / ".local/share/rex-mobile-toolchain/sdk/platform-tools/adb"
        if fallback.is_file():
            return str(fallback)
        located = shutil.which("adb")
        if located:
            return located
        raise E2EFailure("adb not found; set ANDROID_HOME or pass --adb")

    def _journal(self, entry: dict[str, Any]) -> None:
        with self.journal.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n")

    def adb(
        self,
        *args: str,
        timeout: float = 30.0,
        binary: bool = False,
        check: bool = True,
    ) -> subprocess.CompletedProcess[Any]:
        command = [self.adb_path, "-s", self.serial, *args]
        if command[1:3] != ["-s", self.serial]:
            raise E2EFailure("internal error: adb command lacks explicit emulator serial")
        started = time.monotonic()
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=not binary,
                errors=None if binary else "replace",
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            self._journal({"argv": command, "timeout": timeout, "timed_out": True})
            raise E2EFailure(f"adb command timed out after {timeout:g}s: {shlex.join(command)}") from error
        stdout = result.stdout
        stderr = result.stderr
        self._journal({
            "argv": command,
            "duration_seconds": round(time.monotonic() - started, 3),
            "exit_code": result.returncode,
            "stdout_bytes": len(stdout) if isinstance(stdout, bytes) else len(stdout.encode("utf-8")),
            "stderr": stderr[-1000:] if isinstance(stderr, str) else repr(stderr[-1000:]),
        })
        if check and result.returncode != 0:
            detail = result.stderr if isinstance(result.stderr, str) else repr(result.stderr)
            raise E2EFailure(f"adb exit {result.returncode}: {shlex.join(command)}: {detail[-1000:]}")
        return result

    def validate_target(self) -> None:
        state = self.adb("get-state", timeout=10).stdout.strip()
        if state != "device":
            raise E2EFailure(f"target {self.serial} state is {state!r}, expected 'device'")
        self._journal({"target_validation": "PASS", "serial": self.serial, "state": state})

    def install_if_requested(self) -> None:
        if not self.args.install:
            return
        app_apk = Path(self.args.app_apk).resolve()
        test_apk = Path(self.args.test_apk).resolve()
        if not app_apk.is_file() or not test_apk.is_file():
            missing = app_apk if not app_apk.is_file() else test_apk
            raise E2EFailure(f"APK not found: {missing}")
        self.adb("install", "-r", str(app_apk), timeout=self.args.adb_timeout)
        self.adb("install", "-r", "-t", str(test_apk), timeout=self.args.adb_timeout)

    def start_listener(self) -> None:
        listener_path = Path(__file__).resolve().parent / "testsrv" / "wol_listener.py"
        if not listener_path.is_file():
            raise E2EFailure(f"listener script missing: {listener_path}")
        self._listener_stream = self.listener_log.open("wb")
        command = [sys.executable, "-I", str(listener_path), "--timeout", str(self.args.listener_timeout)]
        self._journal({"listener_argv": command, "bind": "127.0.0.1:9009", "expected_mac": EXPECTED_MAC})
        self.listener = subprocess.Popen(command, stdout=self._listener_stream, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            if self.listener.poll() is not None:
                self._listener_stream.flush()
                raise E2EFailure("UDP listener exited before binding: " + self.listener_log.read_text(encoding="utf-8"))
            self._listener_stream.flush()
            if "LISTENING 127.0.0.1:9009" in self.listener_log.read_text(encoding="utf-8", errors="replace"):
                return
            time.sleep(0.05)
        raise E2EFailure("UDP listener did not report bind within 10 seconds")

    def _stop_instrument_client(self, process: subprocess.Popen[bytes]) -> None:
        try:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        except (OSError, subprocess.TimeoutExpired) as error:
            self.cleanup_errors.append("instrument client: " + type(error).__name__)
        finally:
            self._journal({"instrument_client_exit": process.poll()})

    def run_phase(self, phase: str) -> InstrumentationResult:
        output_path = self.out_dir / f"instrumentation-{phase}.log"
        command = [self.adb_path, "-s", self.serial,
            "shell", "am", "instrument", "-w",
            "-e", "phase", phase, "-e", "runId", self.run_id, TEST_COMPONENT]
        self.instrument_active = True
        process: subprocess.Popen[bytes] | None = None
        exit_code = 1
        self._journal({"argv": command, "event": "start", "timeout": self.args.phase_timeout})
        try:
            # Persist output while the process runs: Ctrl-C/SIGTERM must not discard partial evidence.
            with output_path.open("wb") as stream:
                process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT)
                try:
                    exit_code = process.wait(timeout=self.args.phase_timeout)
                    self.instrument_active = exit_code != 0
                except subprocess.TimeoutExpired as error:
                    self._stop_instrument_client(process)
                    raise E2EFailure(f"{phase} instrumentation timed out; see {output_path}") from error
                except KeyboardInterrupt:
                    self._stop_instrument_client(process)
                    raise
        finally:
            output = output_path.read_text(encoding="utf-8", errors="replace") if output_path.exists() else ""
            output_path.write_text(output, encoding="utf-8")
            parsed = parse_instrumentation_output(output, phase=phase, run_id=self.run_id, exit_code=exit_code)
            self.instrument_active = self.instrument_active or parsed.code is None
            (self.out_dir / f"instrumentation-{phase}-summary.json").write_text(
                json.dumps(asdict(parsed), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            self._journal({"argv": command, "event": "finish", "exit_code": exit_code,
                           "instrumentation_may_be_running": self.instrument_active})
        if not parsed.passed:
            raise E2EFailure(f"{phase} instrumentation failed: {parsed.reason}; see {output_path}")
        return parsed

    def copy_store(self, stage: str) -> tuple[bytes, dict[str, Any]]:
        relative_path = f"files/netkit-test-data/{self.run_id}/wol-devices.json"
        result = self.adb(
            "exec-out", "run-as", APP_PACKAGE, "cat", relative_path,
            timeout=self.args.adb_timeout,
            binary=True,
        )
        raw: bytes = result.stdout
        value = validate_store_snapshot(raw, stage=stage, run_id=self.run_id)
        (self.out_dir / f"store-{stage}.json").write_bytes(raw)
        return raw, value

    def capture_screenshot(self) -> None:
        relative_path = f"files/netkit-test-data/{self.run_id}/read-phase.png"
        result = self.adb(
            "exec-out", "run-as", APP_PACKAGE, "cat", relative_path,
            timeout=self.args.adb_timeout,
            binary=True,
        )
        image: bytes = result.stdout
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise E2EFailure("instrumentation screenshot is missing or is not a PNG")
        (self.out_dir / "read-phase.png").write_bytes(image)

    def capture_keyboard_screenshot(self) -> None:
        relative_path = f"files/netkit-test-data/{self.run_id}/ime-save-visible.png"
        result = self.adb(
            "exec-out", "run-as", APP_PACKAGE, "cat", relative_path,
            timeout=self.args.adb_timeout,
            binary=True,
        )
        image: bytes = result.stdout
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise E2EFailure("keyboard visibility screenshot is missing or is not a PNG")
        (self.out_dir / "ime-save-visible.png").write_bytes(image)

    def wait_listener(self) -> None:
        if self.listener is None:
            raise E2EFailure("listener was not started")
        try:
            code = self.listener.wait(timeout=self.args.listener_timeout + 5)
        except subprocess.TimeoutExpired as error:
            raise E2EFailure("UDP listener did not finish before its timeout") from error
        self._listener_stream.flush()
        output = self.listener_log.read_text(encoding="utf-8", errors="replace")
        expected = "PASS: exact 102-byte WoL packet for AA:BB:CC:DD:EE:FF"
        payload_line = next((line for line in output.splitlines() if line.startswith("payload_hex=")), "")
        if (
            code != 0
            or expected not in output
            or "length=102" not in output
            or "packet_count=3" not in output
            or payload_line != f"payload_hex={EXPECTED_PACKET_HEX}"
            or "peer=127.0.0.1:" not in output
        ):
            raise E2EFailure(f"UDP listener failed (exit {code}); see {self.listener_log}")
        self._journal({"listener_exit": code, "packet_validation": "PASS", "length": 102, "peer": "127.0.0.1"})

    def cleanup_test_store(self) -> None:
        if self.args.keep_test_data:
            return
        relative_path = f"files/netkit-test-data/{self.run_id}"
        self.adb("shell", "run-as", APP_PACKAGE, "rm", "-rf", relative_path, timeout=min(10, self.args.adb_timeout))
        self._journal({"test_store_cleanup": "PASS", "path": relative_path})

    def run(self) -> dict[str, Any]:
        self.validate_target()
        self.target_validated = True
        self.install_if_requested()
        if getattr(self.args, "matrix", False):
            result = self.run_phase("matrix")
            self.capture_matrix_evidence()
            self.completed = True
            return {"result": "PASS", "matrix_completed": True, "feature_acceptance": "NOT_EVALUATED",
                    "serial": self.serial, "run_id": self.run_id, "matrix": asdict(result)}
        self.start_listener()
        write_result = self.run_phase("write")
        self.wait_listener()
        self.adb("shell", "am", "force-stop", APP_PACKAGE, timeout=10)
        _, write_store = self.copy_store("write")
        self.capture_keyboard_screenshot()
        read_result = self.run_phase("read")
        _, read_store = self.copy_store("read")
        if write_store["devices"][0]["id"] != read_store["devices"][0]["id"]:
            raise E2EFailure("read phase did not preserve the write-phase device ID")
        self.capture_screenshot()
        self.completed = True
        return {
            "result": "PASS",
            "serial": self.serial,
            "run_id": self.run_id,
            "write": asdict(write_result),
            "read": asdict(read_result),
            "write_device_count": len(write_store["devices"]),
            "read_device_count": len(read_store["devices"]),
            "listener": "PASS: 102-byte AA:BB:CC:DD:EE:FF packet from 127.0.0.1",
            "evidence_dir": str(self.out_dir),
        }

    def capture_matrix_evidence(self, deadline: float | None = None) -> None:
        for step in "abcdef":
            for suffix in (".png", "-tree.txt", "-window.txt", "-input-method.txt", "-logcat.txt"):
                name = f"matrix-{step}{suffix}"
                remaining = 5.0 if deadline is None else deadline - time.monotonic()
                if remaining <= 0:
                    self._journal({"diagnostic": name, "status": "deadline expired"})
                    return
                try:
                    result = self.adb("exec-out", "run-as", APP_PACKAGE, "cat",
                        f"files/netkit-test-data/{self.run_id}/{name}", binary=True, check=False, timeout=min(5, remaining))
                    valid = result.stdout.startswith(b"\x89PNG\r\n\x1a\n") if name.endswith(".png") else bool(result.stdout)
                    if result.returncode == 0 and valid:
                        (self.out_dir / name).write_bytes(result.stdout)
                    else:
                        self._journal({"diagnostic": name, "status": "unavailable"})
                except (E2EFailure, OSError) as error:
                    self._journal({"diagnostic": name, "error_type": type(error).__name__})

    def capture_failure_evidence(self) -> None:
        deadline = time.monotonic() + 20
        for name, args in (("failure.png", ("exec-out", "screencap", "-p")),
                           ("failure-window.txt", ("shell", "dumpsys", "window")),
                           ("failure-input-method.txt", ("shell", "dumpsys", "input_method")),
                           ("failure-logcat.txt", ("shell", "logcat", "-d", "-t", "500"))):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                result = self.adb(*args, binary=True, check=False, timeout=min(5, remaining))
                valid = result.stdout.startswith(b"\x89PNG\r\n\x1a\n") if name.endswith(".png") else not result.stdout.startswith(b"cat:")
                if result.returncode == 0 and valid:
                    (self.out_dir / name).write_bytes(result.stdout)
            except (E2EFailure, OSError) as error:
                self._journal({"diagnostic": name, "error_type": type(error).__name__})
        for name in ("failure.png", "failure-tree.txt", "ime-save-visible.png", "read-phase.png"):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                result = self.adb("exec-out", "run-as", APP_PACKAGE, "cat",
                    f"files/netkit-test-data/{self.run_id}/{name}", binary=True, check=False, timeout=min(3, remaining))
                valid = result.stdout.startswith(b"\x89PNG\r\n\x1a\n") if name.endswith(".png") else bool(result.stdout)
                if result.returncode == 0 and valid:
                    (self.out_dir / ("app-" + name)).write_bytes(result.stdout)
                else:
                    self._journal({"diagnostic": name, "status": "unavailable"})
            except (E2EFailure, OSError) as error:
                self._journal({"diagnostic": name, "error_type": type(error).__name__})
        if getattr(self.args, "matrix", False):
            try:
                self.capture_matrix_evidence(deadline)
            except (E2EFailure, OSError) as error:
                self._journal({"diagnostic": "matrix-artifacts", "error_type": type(error).__name__})

    def close(self) -> list[str]:
        errors = list(getattr(self, "cleanup_errors", []))
        # Independent cleanup steps: failed diagnostics must not suppress listener or device cleanup.
        if self.target_validated and not self.completed:
            try:
                self.capture_failure_evidence()
            except (E2EFailure, OSError, KeyboardInterrupt) as error:
                errors.append("diagnostics: " + type(error).__name__)
        if self.instrument_active:
            try:
                self.adb("shell", "am", "force-stop", APP_PACKAGE, timeout=10)
                observed = self.adb("shell", "pidof", APP_PACKAGE, timeout=5, check=False)
                if observed.returncode != 1 or observed.stdout.strip():
                    raise E2EFailure("instrumentation process did not stop or absence could not be verified")
                self.instrument_active = False
                self._journal({"instrument_cleanup": "PASS", "reason": "process absent after force-stop"})
            except (E2EFailure, OSError, KeyboardInterrupt) as error:
                errors.append("instrumentation cleanup: " + type(error).__name__)
                self._journal({"instrument_cleanup": "FAIL", "error_type": type(error).__name__})
        try:
            if self.listener is not None and self.listener.poll() is None:
                self.listener.terminate()
                try:
                    self.listener.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.listener.kill()
                    self.listener.wait(timeout=3)
        except (OSError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            errors.append("listener cleanup: " + type(error).__name__)
        finally:
            stream = getattr(self, "_listener_stream", None)
            if stream is not None:
                try:
                    stream.close()
                except OSError as error:
                    errors.append("listener log close: " + type(error).__name__)
        if self.target_validated:
            if self.instrument_active:
                errors.append("test store retained: instrumentation may still be running")
                self._journal({"test_store_cleanup": "FAIL", "reason": "instrumentation not confirmed stopped"})
            else:
                try:
                    self.cleanup_test_store()
                except (E2EFailure, OSError, KeyboardInterrupt) as error:
                    errors.append("test store cleanup: " + type(error).__name__)
                    self._journal({"test_store_cleanup": "FAIL", "error_type": type(error).__name__})
        return errors


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit emulator serial, e.g. emulator-5554")
    parser.add_argument("--run-id", help="optional lowercase UUID hex, otherwise generated")
    parser.add_argument("--adb", help="path to adb; otherwise use ANDROID_HOME or the shared toolchain")
    parser.add_argument("--matrix", action="store_true", help="run the single diagnostic matrix, not the WoL E2E")
    parser.add_argument("--install", action="store_true", help="install both supplied APKs to this emulator")
    parser.add_argument("--app-apk", default="androidApp/build/outputs/apk/debug/androidApp-debug.apk")
    parser.add_argument("--test-apk", default="androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk")
    parser.add_argument("--artifacts-dir", default="artifacts/review-fixes")
    parser.add_argument("--phase-timeout", type=float, default=120.0)
    parser.add_argument("--adb-timeout", type=float, default=30.0)
    parser.add_argument("--listener-timeout", type=float, default=180.0)
    parser.add_argument("--keep-test-data", action="store_true", help="keep only this run's isolated app-private fixture directory")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if sys.flags.isolated != 1:
        parser.error("run this tool as `python3 -I tools/run_wol_e2e.py ...`")
    if args.phase_timeout <= 0 or args.adb_timeout <= 0 or args.listener_timeout <= 0:
        parser.error("timeouts must be greater than zero")
    if args.install and (not args.app_apk or not args.test_apk):
        parser.error("--install requires --app-apk and --test-apk")
    runner: Runner | None = None
    summary: dict[str, Any] = {"result": "FAIL", "error": "run did not complete"}
    def interrupted(_number: int, _frame: Any) -> None:
        raise KeyboardInterrupt
    previous_term = signal.signal(signal.SIGTERM, interrupted)
    try:
        runner = Runner(args)
        (runner.out_dir / "summary.json").write_text(json.dumps(summary) + "\n", encoding="utf-8")
        summary = runner.run()
    except KeyboardInterrupt:
        summary = {"result": "FAIL", "error": "interrupted"}
    except (E2EFailure, ValueError, OSError) as error:
        summary = {"result": "FAIL", "error": str(error)}
    finally:
        try:
            if runner is not None:
                try:
                    cleanup_errors = runner.close()
                except (E2EFailure, OSError, KeyboardInterrupt) as error:
                    cleanup_errors = ["cleanup interrupted: " + type(error).__name__]
                summary.update({"serial": runner.serial, "run_id": runner.run_id, "evidence_dir": str(runner.out_dir),
                                "cleanup": {"passed": not cleanup_errors, "errors": cleanup_errors,
                                            "test_data_retained_by_request": args.keep_test_data}})
                if cleanup_errors:
                    summary["result"] = "FAIL"
                    summary["cleanup_error"] = "; ".join(cleanup_errors)
                # Publish the final result only after cleanup; never leave a stale PASS on cleanup failure.
                (runner.out_dir / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        finally:
            signal.signal(signal.SIGTERM, previous_term)
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
