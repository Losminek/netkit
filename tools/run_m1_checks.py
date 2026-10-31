#!/usr/bin/env python3
"""Run one explicit NetKit M1 instrumentation phase on a selected emulator."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

TEST_COMPONENT = "cz.netkit.app.test/cz.netkit.app.test.M1Instrumentation"
MAX_TIMEOUT_SECONDS = 125
SERIAL_RE = re.compile(r"^emulator-[0-9]+$")
RUN_ID_RE = re.compile(r"^[0-9a-f]{32}$")
ADB_SUFFIX = Path("platform-tools") / "adb"

PHASE_MARKERS: dict[str, tuple[str, ...]] = {
    "wifi-denied": (
        "PHASE:wifi-denied:START",
        "WIFI:FOREGROUND_ACTIVITY:PASS",
        "WIFI:DENIED_GUARD:PASS",
        "PHASE:wifi-denied:PASS",
    ),
    "wifi": (
        "PHASE:wifi:START",
        "WIFI:FOREGROUND_ACTIVITY:PASS",
        "WIFI:CURRENT_CONNECTION:PASS",
    ),
    "lan-confirm": (
        "PHASE:lan-confirm:START",
        "LAN_CONFIRM:PINNED_PENDING:PASS",
        "LAN_CONFIRM:STALE_REJECTED:PASS",
        "LAN_CONFIRM:ACTUAL_RESULT_PUBLISHED:PASS",
        "PHASE:lan-confirm:PASS",
    ),
    "ssh-rotated": (
        "PHASE:ssh-rotated:START",
        "SSH:SERVER_ROTATION_REJECTED:PASS",
        "PHASE:ssh-rotated:PASS",
    ),
    "ssh-ui": (
        "PHASE:ssh-ui:START",
        "SSH_UI:PROFILE_VISIBLE:PASS",
        "SSH_UI:TOFU_CLICKED",
        "SSH_UI:HOST_TRUSTED:PASS",
        "SSH_UI:IME_PRIVACY_FLAGS:PASS",
        "SSH_UI:FLAG_SECURE_ON:PASS",
        "SSH_UI:FLAG_SECURE_OFF:PASS",
        "SSH_UI:INPUT_FOCUSED_IME_VISIBLE:PASS",
        "SSH_UI:INPUT_TYPED_FROM_KEYBOARD:PASS",
        "SSH_UI:DIRECT_REMOTE_ECHO:PASS",
        "SSH_UI:TERMINAL_READY:PASS",
        "SSH_UI:COMMAND_RESULT:PASS",
        "SSH_UI:CLOSED:PASS",
        "PHASE:ssh-ui:PASS",
    ),
    "ui": (
        "PHASE:ui:START",
        "UI:tab_wifi:CONTROLS:PASS",
        "UI:tab_lan:CONTROLS:PASS",
        "UI:tab_ssh:CONTROLS:PASS",
        "UI:tab_wol:CONTROLS:PASS",
        "PHASE:ui:PASS",
    ),
    "lan": (
        "PHASE:lan:START",
        "LAN:TCP_OPEN_BANNER:PASS",
        "LAN:TCP_CLOSED:PASS",
        "LAN:TCP_REFUSED_ERRNO:PASS",
        "LAN:CANCEL_CONNECTED:PASS",
        "LAN:CANCEL_UNDER_1S:PASS",
        "LAN:PING_HOST_REPLY:PASS",
        "PHASE:lan:PASS",
    ),
    "ssh": (
        "PHASE:ssh:START",
        "SSH:KEYSTORE_ROUNDTRIP_CIPHERTEXT:PASS",
        "SSH:TAMPER_FAILS_CLOSED:PASS",
        "SSH:PASSWORD_TOFU:PASS",
        "SSH:SHELL_MARKER:PASS",
        "SSH:CTRL_C:PASS",
        "SSH:RESIZE:PASS",
        "SSH:CLOSE:PASS",
        "SSH:WRONG_PASSWORD_REJECTED:PASS",
        "SSH:UNKNOWN_REJECTED_BEFORE_AUTH:PASS",
        "SSH:CHANGED_REJECTED_DESPITE_ACCEPT:PASS",
        "SSH:ED25519_KEY_AUTH:PASS",
        "SSH:KEYBOARD_INTERACTIVE:PASS",
        "PHASE:ssh:PASS",
    ),
}
PHASE_MARKERS["ssh-lifecycle"] = tuple(
    marker.replace("PHASE:ssh-ui:", "PHASE:ssh-lifecycle:")
    for marker in PHASE_MARKERS["ssh-ui"] if marker != "SSH_UI:CLOSED:PASS"
) + ("SSH_LIFECYCLE:HOME_RETURN_CONNECTED:PASS", "SSH_LIFECYCLE:BACKGROUND_EXPIRED:PASS")

PHASE_MARKERS["ssh-reconnect"] = tuple(
    marker.replace("PHASE:ssh-ui:", "PHASE:ssh-reconnect:") for marker in PHASE_MARKERS["ssh-ui"]
) + ("SSH_UI:PENDING_PROMPT_BANNER:PASS", "SSH_UI:RECONNECT_CANCEL_RETAINS_BUFFER:PASS", "SSH_UI:RECONNECT_CONFIRMED:PASS")

PHASE_MARKERS["ssh-legacy"] = ("PHASE:ssh-legacy:START", "SSH:LEGACY_ALGORITHMS_REJECTED:PASS", "PHASE:ssh-legacy:PASS")

PHASE_MARKERS["wol-e2e"] = ("WOL:E2E:PASS",)
PHASE_MARKERS["wol-ime-matrix"] = ("WOL:MATRIX:COMPLETE",)

PHASE_MARKERS["lan-discovery"] = ("PHASE:lan-discovery:START", "LAN_DISCOVERY:LOOPBACK_24_UNDER_20S:PASS", "PHASE:lan-discovery:PASS")

PHASE_MARKERS["lan-icmp-rtt"] = ("PHASE:lan-icmp-rtt:START", "LAN_ICMP_RTT:20_REPLIES:PASS", "PHASE:lan-icmp-rtt:PASS")

PHASE_MARKERS["wifi-freshness"] = ("PHASE:wifi-freshness:START", *("WIFI:FRESHNESS:" + name + ":UI_VERIFIED" for name in ("fresh", "aging", "within-five-minutes", "expired", "cache")), "WIFI:CHART:SYNTHETIC_SIX_STYLES:UI_VERIFIED", "PHASE:wifi-freshness:PASS")

UI_SCREENSHOTS = ("wifi", "lan", "ssh", "wol")
WIFI_PASS_MARKERS = ("WIFI:VIRTUAL_AP_RESULTS:PASS", "PHASE:wifi:PASS")
WIFI_SKIP_MARKERS = ("WIFI:SCAN:SKIP:THROTTLED_CONFIRMED", "PHASE:wifi:SKIP")


@dataclass(frozen=True)
class PhaseResult:
    passed: bool
    skipped: bool
    phase: str
    run_id: str
    adb_exit_code: int | None
    instrumentation_code: int | None
    instrumentation_result: str | None
    missing_markers: tuple[str, ...]
    reason: str


def validate_adb_path(value: str) -> str:
    path = Path(value)
    if not path.is_absolute() or path.name != ADB_SUFFIX.name or path.parent.name != ADB_SUFFIX.parent.name:
        raise ValueError("--adb must be an absolute SDK path ending in platform-tools/adb")
    return str(path)


def validate_serial(value: str) -> str:
    if not SERIAL_RE.fullmatch(value):
        raise ValueError("--serial must be an emulator serial such as emulator-5554")
    return value


def validate_run_id(value: str) -> str:
    if not RUN_ID_RE.fullmatch(value):
        raise ValueError("--run-id must be exactly 32 lowercase hexadecimal characters")
    return value


def build_command(adb: str, serial: str, phase: str, run_id: str) -> list[str]:
    adb_path = validate_adb_path(adb)
    target = validate_serial(serial)
    if phase not in PHASE_MARKERS:
        raise ValueError(f"unsupported phase: {phase}")
    run = validate_run_id(run_id)
    command = [adb_path, "-s", target, "shell", "am", "instrument", "-w", "-r",
               "-e", "phase", phase, "-e", "runId", run, TEST_COMPONENT]
    if command[1:3] != ["-s", target]:
        raise ValueError("internal error: adb command is missing its explicit emulator target")
    return command


def required_markers(phase: str, run_id: str) -> tuple[str, ...]:
    if phase not in PHASE_MARKERS:
        raise ValueError(f"unsupported phase: {phase}")
    markers = PHASE_MARKERS[phase]
    if phase == "wifi":
        return markers + WIFI_PASS_MARKERS
    if phase == "ui":
        screenshots = tuple(
            f"SCREENSHOT:files/m1-data/{run_id}/m1-evidence/ui-{name}.png" for name in UI_SCREENSHOTS
        )
        return markers + screenshots
    if phase == "ssh-ui":
        return markers + (f"SCREENSHOT:files/m1-data/{run_id}/m1-evidence/ssh-ui-terminal.png",)
    return markers


def _instrumentation_fields(output: str) -> tuple[dict[str, list[str]], list[int]]:
    fields: dict[str, list[str]] = {}
    for match in re.finditer(r"INSTRUMENTATION_RESULT:\s*([^=\r\n]+)=([^\r\n]*)", output):
        fields.setdefault(match.group(1).strip(), []).append(match.group(2).strip())
    codes = [int(match.group(1)) for match in re.finditer(r"INSTRUMENTATION_CODE:\s*(-?[0-9]+)", output)]
    return fields, codes


def parse_output(output: str, *, phase: str, run_id: str, exit_code: int) -> PhaseResult:
    if phase not in PHASE_MARKERS:
        raise ValueError(f"unsupported phase: {phase}")
    fields, codes = _instrumentation_fields(output)
    def unique_field(name: str) -> str | None:
        values = fields.get(name, [])
        if not values or len(set(values)) != 1:
            return None
        return values[0]

    result = unique_field("netkit.result")
    result_phase = unique_field("netkit.phase")
    result_run_id = unique_field("netkit.runId")
    instrumentation_code = codes[0] if len(codes) == 1 else None
    required = required_markers(phase, run_id)
    if phase == "wifi" and result == "SKIP":
        required = PHASE_MARKERS[phase] + WIFI_SKIP_MARKERS
    missing = tuple(marker for marker in required if marker not in output)
    reasons: list[str] = []
    if exit_code != 0:
        reasons.append(f"adb instrument exited {exit_code}")
    if result not in ("PASS", "SKIP") or (result == "SKIP" and phase != "wifi"):
        reasons.append(f"netkit.result is not an allowed outcome ({result!r})")
    if result_phase != phase:
        reasons.append(f"netkit.phase does not match ({result_phase!r})")
    if result_run_id != run_id:
        reasons.append("netkit.runId does not match the requested run ID")
    if len(codes) != 1 or instrumentation_code != -1:
        reasons.append(f"expected one instrumentation code RESULT_OK (-1), got {codes!r}")
    if missing:
        reasons.append("missing required phase markers: " + "; ".join(missing))
    if phase == "wifi":
        has_pass_branch = all(marker in output for marker in WIFI_PASS_MARKERS)
        has_skip_branch = all(marker in output for marker in WIFI_SKIP_MARKERS)
        if result == "PASS" and (not has_pass_branch or has_skip_branch):
            reasons.append("wifi PASS result does not match its terminal phase markers")
        if result == "SKIP" and (not has_skip_branch or has_pass_branch):
            reasons.append("wifi SKIP result does not match its terminal phase markers")
        skip_reason = unique_field("netkit.skipReason")
        if result == "SKIP" and skip_reason != "THROTTLED_CONFIRMED":
            reasons.append("wifi SKIP requires netkit.skipReason=THROTTLED_CONFIRMED")
    if (f"PHASE:{phase}:FAIL" in output or result == "FAIL"
            or "INSTRUMENTATION_FAILED:" in output or "INSTRUMENTATION_ABORTED:" in output):
        reasons.append("instrumentation failure marker is present")
    skipped = not reasons and result == "SKIP"
    passed = not reasons and result == "PASS"
    success_reason = ("phase was explicitly skipped: confirmed Wi-Fi throttling"
                      if skipped else "all required signals and phase markers passed")
    return PhaseResult(passed, skipped, phase, run_id, exit_code, instrumentation_code, result,
                       missing, "; ".join(reasons) if reasons else success_reason)


def _text(value: str | bytes | None) -> str:
    if value is None:
        return ""
    return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value


class Runner:
    def __init__(self, *, adb: str, serial: str, phase: str, run_id: str, output_dir: str,
                 git_sha: str = "unknown", expect_apk_sha256: str | None = None,
                 expect_test_apk_sha256: str | None = None) -> None:
        self.adb = validate_adb_path(adb)
        self.serial = validate_serial(serial)
        if phase not in PHASE_MARKERS:
            raise ValueError(f"unsupported phase: {phase}")
        if git_sha != "unknown" and not re.fullmatch(r"[0-9a-f]{40}", git_sha):
            raise ValueError("--git-sha must be a full commit SHA")
        if expect_apk_sha256 is not None and not re.fullmatch(r"[0-9a-f]{64}", expect_apk_sha256):
            raise ValueError("--expect-apk-sha256 must be a lowercase SHA-256")
        if expect_test_apk_sha256 is not None and not re.fullmatch(r"[0-9a-f]{64}", expect_test_apk_sha256):
            raise ValueError("--expect-test-apk-sha256 must be a lowercase SHA-256")
        self.expect_test_apk_sha256 = expect_test_apk_sha256
        self.aborted = False
        self.git_sha = git_sha
        self.expect_apk_sha256 = expect_apk_sha256
        self.timeout = 395 if phase in ("ssh-lifecycle", "wol-e2e", "wol-ime-matrix") else MAX_TIMEOUT_SECONDS
        self.command_count = 0
        self.phase = phase
        self.run_id = validate_run_id(run_id)
        self.output_dir = Path(output_dir).resolve()
        self.output_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
        self.output_dir.chmod(0o700)
        self.command_log = self.output_dir / "adb-commands.log"
        self.instrumentation_log = self.output_dir / "instrumentation.log"
        self.summary_path = self.output_dir / "summary.json"

    def _log_command(self, record: dict[str, Any]) -> None:
        with self.command_log.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
        self.command_log.chmod(0o600)

    def _adb(self, arguments: list[str], *, timeout: int = 20) -> bytes:
        command = [self.adb, "-s", self.serial, *arguments]
        self.command_count += 1
        self._log_command({"event": "start", "argv": command, "timeout_seconds": timeout})
        completed = subprocess.run(command, check=False, capture_output=True, timeout=timeout)
        self._log_command({"event": "finish", "argv": command, "adb_exit_code": completed.returncode})
        if completed.returncode != 0:
            raise ValueError("ADB evidence command failed")
        return completed.stdout

    def collect_build_identity(self) -> dict[str, Any]:
        packages: dict[str, Any] = {}
        for package in ("cz.netkit.app", "cz.netkit.app.test"):
            paths = _text(self._adb(["shell", "pm", "path", package])).splitlines()
            apk_paths = [line.removeprefix("package:").strip() for line in paths if line.startswith("package:")]
            if len(apk_paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+\.apk", apk_paths[0]):
                raise ValueError("Expected one installed APK for " + package)
            apk_bytes = self._adb(["exec-out", "cat", apk_paths[0]])
            if not apk_bytes.startswith(b"PK"):
                raise ValueError("Installed APK is not a ZIP for " + package)
            digest = hashlib.sha256(apk_bytes).hexdigest()
            details = _text(self._adb(["shell", "dumpsys", "package", package]))
            version = re.search(r"\bversionCode=(\d+)", details)
            updated = re.search(r"\blastUpdateTime=([^\r\n]+)", details)
            if version is None or updated is None:
                raise ValueError("Missing package version/update evidence for " + package)
            packages[package] = {"sha256": digest, "versionCode": int(version.group(1)),
                                 "lastUpdateTime": updated.group(1).strip()}
        return {"git_sha": self.git_sha, "serial": self.serial, "packages": packages}

    def _write_summary(self, summary: dict[str, Any]) -> None:
        self.summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        self.summary_path.chmod(0o600)

    def _stop_client(self, process: subprocess.Popen[str], partial: str = "") -> str:
        """Drain only the client created by this runner; never stop an Android app."""
        try:
            if process.poll() is None:
                process.terminate()
            try:
                stdout, stderr = process.communicate(timeout=60 if self.phase in ("wol-e2e", "wol-ime-matrix") else 2)
            except subprocess.TimeoutExpired as error:
                partial = _text(error.stdout) + _text(error.stderr) or partial
                process.kill()
                stdout, stderr = process.communicate(timeout=2)
            # communicate() after timeout/interruption returns all buffered output,
            # including the prefix already attached to TimeoutExpired. Do not duplicate it.
            return _text(stdout) + _text(stderr) or partial
        except (OSError, subprocess.TimeoutExpired) as error:
            if isinstance(error, subprocess.TimeoutExpired):
                partial = _text(error.stdout) + _text(error.stderr) or partial
            self._log_command({"event": "client_cleanup_error", "reason": type(error).__name__})
            return partial
        finally:
            self._log_command({"event": "client_stopped", "client_pid": process.pid,
                               "client_exit_code": process.poll()})

    def _observe_app_process(self) -> list[dict[str, Any]]:
        """Observe for at most five seconds; pidof is not a session-state oracle."""
        observations: list[dict[str, Any]] = []
        started = time.monotonic()
        deadline = started + 5
        while (remaining := deadline - time.monotonic()) > 0:
            command = [self.adb, "-s", self.serial, "shell", "pidof", "cz.netkit.app"]
            self.command_count += 1
            self._log_command({"event": "start", "argv": command, "timeout_seconds": remaining})
            try:
                completed = subprocess.run(command, check=False, capture_output=True, timeout=remaining)
                pids = _text(completed.stdout).strip()
                state = "running" if completed.returncode == 0 and pids else (
                    "absent" if completed.returncode == 1 and not pids else "unavailable")
                observation = {"elapsed_seconds": round(time.monotonic() - started, 3),
                               "state": state, "pids": pids, "adb_exit_code": completed.returncode}
            except (OSError, subprocess.TimeoutExpired) as error:
                observation = {"elapsed_seconds": round(time.monotonic() - started, 3),
                               "state": "unavailable", "reason": type(error).__name__}
            observations.append(observation)
            self._log_command({"event": "process_observation", "argv": command, **observation})
            if observation["state"] == "absent":
                break
            time.sleep(max(0, min(0.25, deadline - time.monotonic())))
        return observations

    def run(self) -> PhaseResult:
        command = build_command(self.adb, self.serial, self.phase, self.run_id)
        if self.phase in ("wol-e2e", "wol-ime-matrix"):
            command = [sys.executable, "-I", str(Path(__file__).with_name("run_wol_e2e.py")),
                       "--adb", self.adb, "--serial", self.serial, "--run-id", self.run_id,
                       "--artifacts-dir", str(self.output_dir / "wol")]
            if self.phase == "wol-ime-matrix":
                command.append("--matrix")
        started = time.monotonic()
        output = ""
        process: subprocess.Popen[str] | None = None
        identity: dict[str, Any] = {"git_sha": self.git_sha, "serial": self.serial}
        result = PhaseResult(False, False, self.phase, self.run_id, None, None, None,
                             required_markers(self.phase, self.run_id), "run interrupted before completion")
        self._write_summary({**asdict(result), "build": identity})
        try:
            identity = self.collect_build_identity()
            self._write_summary({**asdict(result), "build": identity, "state": "READY"})
            if (self.expect_apk_sha256 is not None and
                    identity["packages"]["cz.netkit.app"]["sha256"] != self.expect_apk_sha256):
                raise ValueError("Installed application APK SHA-256 does not match expected build")
            if (self.expect_test_apk_sha256 is not None and
                    identity["packages"]["cz.netkit.app.test"]["sha256"] != self.expect_test_apk_sha256):
                raise ValueError("Installed test APK SHA-256 does not match expected build")
            self.command_count += 1
            self._log_command({"event": "start", "argv": command, "timeout_seconds": self.timeout})
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       text=True, errors="replace")
            stdout, stderr = process.communicate(timeout=self.timeout)
            output = _text(stdout) + _text(stderr)
            completed = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
            if self.phase == "wol-ime-matrix":
                child = json.loads((self.output_dir / "wol" / self.run_id / "summary.json").read_text())
                passed = (completed.returncode == 0 and child.get("result") == "PASS"
                          and child.get("run_id") == self.run_id and child.get("serial") == self.serial
                          and child.get("matrix_completed") is True and child.get("matrix", {}).get("passed") is True)
                result = PhaseResult(passed, False, self.phase, self.run_id, completed.returncode,
                                     -1 if passed else None, "PASS" if passed else "FAIL", () if passed else ("WOL:MATRIX:COMPLETE",),
                                     "Diagnostic matrix completed; focus/IME results are in child log, not feature acceptance" if passed else "Matrix incomplete; see child evidence")
            elif self.phase == "wol-e2e":
                child = json.loads((self.output_dir / "wol" / self.run_id / "summary.json").read_text())
                passed = (completed.returncode == 0 and child.get("result") == "PASS"
                          and child.get("run_id") == self.run_id and child.get("serial") == self.serial
                          and child.get("write", {}).get("passed") is True and child.get("read", {}).get("passed") is True)
                result = PhaseResult(passed, False, self.phase, self.run_id, completed.returncode,
                                     -1 if passed else None, "PASS" if passed else "FAIL", () if passed else ("WOL:E2E:PASS",),
                                     "WoL UI, listener and external restart passed" if passed else "WoL nested runner failed; see wol evidence")
            else:
                result = parse_output(output, phase=self.phase, run_id=self.run_id, exit_code=completed.returncode)
        except subprocess.TimeoutExpired as error:
            self.aborted = True
            output = _text(error.stdout) + _text(error.stderr)
            result = PhaseResult(False, False, self.phase, self.run_id, None, None, None,
                                 required_markers(self.phase, self.run_id), "ADB timed out; instrumentation may still be running")
            if process is not None:
                output = self._stop_client(process, output)
                identity["process_after_abort"] = self._observe_app_process()
        except (OSError, ValueError) as error:
            result = PhaseResult(False, False, self.phase, self.run_id, None, None, None,
                                 required_markers(self.phase, self.run_id), str(error))
        except KeyboardInterrupt:
            self.aborted = True
            if process is not None:
                output = self._stop_client(process, output)
                identity["process_after_abort"] = self._observe_app_process()
            result = PhaseResult(False, False, self.phase, self.run_id, None, None, None,
                                 required_markers(self.phase, self.run_id), "interrupted")
        finally:
            self.instrumentation_log.write_text(output, encoding="utf-8")
            self.instrumentation_log.chmod(0o600)
            self._log_command({"event": "finish", "argv": command, "adb_exit_code": result.adb_exit_code,
                               "duration_seconds": round(time.monotonic() - started, 3)})
            self._write_summary({**asdict(result), "build": identity, "command_count": self.command_count,
                                 "output_dir": str(self.output_dir), "instrumentation_may_be_running": self.aborted})
        return result


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, help="absolute SDK path ending in platform-tools/adb")
    parser.add_argument("--serial", required=True, help="explicit emulator serial only")
    parser.add_argument("--phase", required=True, choices=tuple(PHASE_MARKERS))
    parser.add_argument("--run-id", required=True, help="32 lowercase hex characters")
    parser.add_argument("--output-dir", required=True, help="new output directory for this run")
    parser.add_argument("--git-sha", required=True, help="full source commit SHA")
    parser.add_argument("--expect-apk-sha256", help="require installed application APK digest")
    parser.add_argument("--expect-test-apk-sha256", help="require installed test APK digest")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        runner = Runner(**vars(parse_args(argv)))
        result = runner.run()
    except (OSError, ValueError) as error:
        print(json.dumps({"passed": False, "reason": str(error)}, ensure_ascii=False))
        return 1
    print(json.dumps(asdict(result), ensure_ascii=False))
    return 0 if result.passed else 3 if result.skipped else 1


if __name__ == "__main__":
    raise SystemExit(main())
