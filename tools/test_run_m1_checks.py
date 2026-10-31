import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import m1_provision

from run_m1_checks import (
    MAX_TIMEOUT_SECONDS,
    PHASE_MARKERS,
    Runner,
    build_command,
    parse_output,
    required_markers,
    validate_adb_path,
    validate_run_id,
    validate_serial,
)


RUN_ID = "0123456789abcdef0123456789abcdef"
ADB = "/sdk/android/platform-tools/adb"


def passing_output(phase: str) -> str:
    markers = "\n".join(required_markers(phase, RUN_ID))
    return (
        markers
        + "\nINSTRUMENTATION_RESULT: netkit.result=PASS"
        + f"\nINSTRUMENTATION_RESULT: netkit.phase={phase}"
        + f"\nINSTRUMENTATION_RESULT: netkit.runId={RUN_ID}"
        + "\nINSTRUMENTATION_CODE: -1\n"
    )


def skipped_wifi_output() -> str:
    return (
        "PHASE:wifi:START\nWIFI:FOREGROUND_ACTIVITY:PASS\nWIFI:CURRENT_CONNECTION:PASS\n"
        "WIFI:SCAN:SKIP:THROTTLED_CONFIRMED\nPHASE:wifi:SKIP\n"
        "INSTRUMENTATION_RESULT: netkit.result=SKIP\n"
        "INSTRUMENTATION_RESULT: netkit.phase=wifi\n"
        f"INSTRUMENTATION_RESULT: netkit.runId={RUN_ID}\n"
        "INSTRUMENTATION_RESULT: netkit.skipReason=THROTTLED_CONFIRMED\n"
        "INSTRUMENTATION_CODE: -1\n"
    )


class InputAndCommandTests(unittest.TestCase):
    def test_requires_absolute_sdk_adb_and_emulator_serial(self) -> None:
        self.assertEqual(validate_adb_path(ADB), ADB)
        self.assertEqual(validate_serial("emulator-5554"), "emulator-5554")
        self.assertEqual(validate_run_id(RUN_ID), RUN_ID)
        for value in ("adb", "relative/platform-tools/adb", "/usr/bin/adb", "", "/tmp/adb"):
            with self.subTest(adb=value), self.assertRaises(ValueError):
                validate_adb_path(value)
        for value in ("0123456789ABCDEF", "localhost:5555", "emulator", "emulator-x", ""):
            with self.subTest(serial=value), self.assertRaises(ValueError):
                validate_serial(value)
        for value in ("f" * 31, "F" * 32, "f" * 33, "g" * 32):
            with self.subTest(run_id=value), self.assertRaises(ValueError):
                validate_run_id(value)

    def test_command_has_explicit_target_and_only_instrumentation_action(self) -> None:
        command = build_command(ADB, "emulator-5554", "lan", RUN_ID)
        self.assertEqual(command[1:3], ["-s", "emulator-5554"])
        self.assertEqual(command[3:7], ["shell", "am", "instrument", "-w"])
        self.assertIn("cz.netkit.app.test/cz.netkit.app.test.M1Instrumentation", command)
        self.assertNotIn("install", command)
        self.assertNotIn("push", command)
        self.assertNotIn("start", command)


class OutputParserTests(unittest.TestCase):
    def test_requires_result_phase_run_id_code_and_every_phase_marker(self) -> None:
        for phase in PHASE_MARKERS:
            with self.subTest(phase=phase):
                result = parse_output(passing_output(phase), phase=phase, run_id=RUN_ID, exit_code=0)
                self.assertTrue(result.passed, result.reason)
                self.assertEqual(result.missing_markers, ())
                self.assertEqual(result.instrumentation_code, -1)

    def test_rejects_missing_markers_even_if_instrumentation_says_pass(self) -> None:
        output = passing_output("lan").replace("LAN:TCP_CLOSED:PASS", "")
        result = parse_output(output, phase="lan", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertEqual(result.missing_markers, ("LAN:TCP_CLOSED:PASS",))

    def test_rejects_nonzero_adb_exit_bad_result_phase_runid_or_code(self) -> None:
        cases = (
            (passing_output("ui"), 2, RUN_ID, -1, 2),
            (passing_output("ui").replace("netkit.result=PASS", "netkit.result=FAIL"), 0, RUN_ID, -1, 1),
            (passing_output("ui").replace("netkit.phase=ui", "netkit.phase=ssh"), 0, RUN_ID, -1, 1),
            (passing_output("ui").replace(f"netkit.runId={RUN_ID}", f"netkit.runId={'f' * 32}"), 0, RUN_ID, -1, 1),
            (passing_output("ui").replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"), 0, RUN_ID, 0, 1),
        )
        for output, exit_code, run_id, expected_code, expected_exit in cases:
            with self.subTest(exit_code=exit_code, expected_code=expected_code):
                result = parse_output(output, phase="ui", run_id=run_id, exit_code=exit_code)
                self.assertFalse(result.passed)

    def test_rejects_conflicting_duplicate_result_fields(self) -> None:
        output = passing_output("ssh") + "\nINSTRUMENTATION_RESULT: netkit.result=PASS\nINSTRUMENTATION_RESULT: netkit.result=FAIL\n"
        result = parse_output(output, phase="ssh", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertIn("netkit.result is not an allowed outcome", result.reason)

    def test_wifi_skip_is_a_distinct_valid_nonpass_outcome(self) -> None:
        result = parse_output(skipped_wifi_output(), phase="wifi", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertTrue(result.skipped)
        self.assertEqual(result.instrumentation_result, "SKIP")

    def test_wifi_skip_without_reason_or_matching_markers_fails_closed(self) -> None:
        output = skipped_wifi_output().replace(
            "netkit.skipReason=THROTTLED_CONFIRMED", "netkit.skipReason=OTHER"
        )
        result = parse_output(output, phase="wifi", run_id=RUN_ID, exit_code=0)
        self.assertFalse(result.passed)
        self.assertFalse(result.skipped)
        self.assertIn("skipReason", result.reason)

    def test_phase_fail_marker_rejects_otherwise_complete_pass(self) -> None:
        for phase in ("wifi", "wifi-denied"):
            with self.subTest(phase=phase):
                output = passing_output(phase) + f"\nPHASE:{phase}:FAIL\n"
                result = parse_output(output, phase=phase, run_id=RUN_ID, exit_code=0)
                self.assertFalse(result.passed)
                self.assertIn("failure marker", result.reason)


class RunnerTests(unittest.TestCase):
    def setUp(self) -> None:
        identity = {"serial": "emulator-5554", "git_sha": "a" * 40,
                    "packages": {"cz.netkit.app": {"sha256": "b" * 64}}}
        patcher = patch.object(Runner, "collect_build_identity", return_value=identity)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_runner_issues_one_scoped_command_and_writes_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output_dir = Path(temporary) / "run"
            runner = Runner(adb=ADB, serial="emulator-5554", phase="ui", run_id=RUN_ID,
                            output_dir=str(output_dir))
            client = Mock(returncode=0)
            client.communicate.return_value = (passing_output("ui"), "")
            with patch("run_m1_checks.subprocess.Popen", return_value=client) as run_adb:
                result = runner.run()
            self.assertTrue(result.passed, result.reason)
            run_adb.assert_called_once()
            args, kwargs = run_adb.call_args
            command = args[0]
            self.assertEqual(command[1:3], ["-s", "emulator-5554"])
            client.communicate.assert_called_once_with(timeout=MAX_TIMEOUT_SECONDS)
            self.assertEqual(kwargs["stdout"], subprocess.PIPE)
            self.assertEqual(kwargs["stderr"], subprocess.PIPE)
            self.assertEqual(kwargs["errors"], "replace")
            logged = (output_dir / "adb-commands.log").read_text(encoding="utf-8")
            self.assertIn('"-s", "emulator-5554"', logged)
            self.assertIn('"event": "finish"', logged)
            self.assertEqual((output_dir / "instrumentation.log").read_text(encoding="utf-8"), passing_output("ui"))
            self.assertTrue(json.loads((output_dir / "summary.json").read_text(encoding="utf-8"))["passed"])

    def test_timeout_fails_closed_and_keeps_partial_output_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output_dir = Path(temporary) / "run"
            runner = Runner(adb=ADB, serial="emulator-5554", phase="ssh", run_id=RUN_ID,
                            output_dir=str(output_dir))
            timeout = subprocess.TimeoutExpired([ADB], MAX_TIMEOUT_SECONDS, output="partial", stderr="")
            client = Mock(pid=123, returncode=-15)
            client.poll.side_effect = [None, -15]
            client.communicate.side_effect = [timeout, ("partial", "")]
            with patch("run_m1_checks.subprocess.Popen", return_value=client) as run_adb, \
                    patch.object(runner, "_observe_app_process", return_value=[{"state": "absent"}]):
                result = runner.run()
            client.terminate.assert_called_once()
            client.kill.assert_not_called()
            self.assertFalse(result.passed)
            self.assertIn("timed out", result.reason)
            self.assertEqual(run_adb.call_count, 1)  # no instrumentation retry
            self.assertEqual((output_dir / "instrumentation.log").read_text(encoding="utf-8"), "partial")

    def test_nonzero_exit_is_fail_even_with_all_markers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output_dir = Path(temporary) / "run"
            runner = Runner(adb=ADB, serial="emulator-5554", phase="lan", run_id=RUN_ID,
                            output_dir=str(output_dir))
            client = Mock(returncode=1)
            client.communicate.return_value = (passing_output("lan"), "")
            with patch("run_m1_checks.subprocess.Popen", return_value=client):
                result = runner.run()
            self.assertFalse(result.passed)
            self.assertIn("exited 1", result.reason)

    def test_interrupt_drains_partial_output_and_records_process_observation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = Runner(adb=ADB, serial="emulator-5554", phase="ssh", run_id=RUN_ID,
                            output_dir=str(Path(temporary) / "run"))
            client = Mock(pid=123, returncode=-15)
            client.poll.side_effect = [None, -15]
            client.communicate.side_effect = [KeyboardInterrupt(), (b"prefix\xfftail", b"diagnostic")]
            absent = subprocess.CompletedProcess([], 1, b"", b"")
            with patch("run_m1_checks.subprocess.Popen", return_value=client) as launch, \
                    patch("run_m1_checks.subprocess.run", return_value=absent) as observe:
                result = runner.run()
            self.assertEqual(result.reason, "interrupted")
            self.assertFalse(result.passed)
            launch.assert_called_once()
            client.terminate.assert_called_once()
            self.assertEqual(runner.instrumentation_log.read_text(), "prefix\ufffdtaildiagnostic")
            summary = json.loads(runner.summary_path.read_text())
            self.assertEqual(summary["build"]["process_after_abort"][0]["state"], "absent")
            self.assertEqual(observe.call_args.args[0],
                             [ADB, "-s", "emulator-5554", "shell", "pidof", "cz.netkit.app"])
            self.assertNotIn("force-stop", runner.command_log.read_text())

    def test_timeout_drains_complete_output_without_duplicate_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = Runner(adb=ADB, serial="emulator-5554", phase="ssh", run_id=RUN_ID,
                            output_dir=str(Path(temporary) / "run"))
            client = Mock(pid=123, returncode=-9)
            client.poll.side_effect = [None, -9]
            client.communicate.side_effect = [
                subprocess.TimeoutExpired([], 125, output=b"prefix"),
                subprocess.TimeoutExpired([], 2, output=b"prefix-more"),
                ("prefix-more-final", "stderr"),
            ]
            with patch("run_m1_checks.subprocess.Popen", return_value=client), \
                    patch.object(runner, "_observe_app_process", return_value=[]):
                result = runner.run()
            self.assertFalse(result.passed)
            client.terminate.assert_called_once()
            client.kill.assert_called_once()  # only the owned client PID
            self.assertEqual(runner.instrumentation_log.read_text(), "prefix-more-finalstderr")

    def test_pidof_polls_until_absent_and_records_each_explicit_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = Runner(adb=ADB, serial="emulator-5554", phase="lan", run_id=RUN_ID,
                            output_dir=str(Path(temporary) / "run"))
            with patch("run_m1_checks.subprocess.run", side_effect=[
                subprocess.CompletedProcess([], 0, b"451\n", b""),
                subprocess.CompletedProcess([], 1, b"", b""),
            ]) as execute, patch("run_m1_checks.time.sleep"):
                observations = runner._observe_app_process()
            self.assertEqual([item["state"] for item in observations], ["running", "absent"])
            self.assertEqual(execute.call_count, 2)
            for call in execute.call_args_list:
                self.assertEqual(call.args[0][1:3], ["-s", "emulator-5554"])
                self.assertGreater(call.kwargs["timeout"], 0)
                self.assertLessEqual(call.kwargs["timeout"], 5)
            records = [json.loads(line) for line in runner.command_log.read_text().splitlines()]
            self.assertEqual(sum(item["event"] == "process_observation" for item in records), 2)

    def test_pidof_stops_at_five_seconds_even_if_app_remains(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            runner = Runner(adb=ADB, serial="emulator-5554", phase="lan", run_id=RUN_ID,
                            output_dir=str(Path(temporary) / "run"))
            with patch("run_m1_checks.subprocess.run", return_value=
                       subprocess.CompletedProcess([], 0, b"451", b"")) as execute, \
                    patch("run_m1_checks.time.monotonic", side_effect=[0, 0, 4.9, 5, 5]), \
                    patch("run_m1_checks.time.sleep"):
                observations = runner._observe_app_process()
            self.assertEqual(len(observations), 1)
            execute.assert_called_once()
            self.assertEqual(observations[0]["state"], "running")

    def test_output_directory_must_be_new(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(FileExistsError):
                Runner(adb=ADB, serial="emulator-5554", phase="lan", run_id=RUN_ID,
                       output_dir=temporary)


class ProvisionRepeatTests(unittest.TestCase):
    def test_each_lifecycle_repeat_owns_a_new_bounded_fixture_and_log(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            provisioner = m1_provision.Provisioner(ADB, "emulator-5554", root / "state")
            clients = []
            def launch(command, *, stdout, stderr):
                self.assertEqual(command[-2:], ["--duration", "600"])
                self.assertIs(stdout, stderr)
                stdout.write(b'{"event": "started"}\n')
                stdout.flush()
                client = Mock()
                client.poll.return_value = None
                clients.append(client)
                return client
            def make_runner(**kwargs):
                output = Path(kwargs["output_dir"])
                output.mkdir()
                run = Mock()
                run.summary_path = output / "summary.json"
                run.summary_path.write_text(json.dumps({"passed": True, "reason": "fixture test"}))
                run.run.return_value = Mock(passed=True, skipped=False, reason="fixture test")
                return run
            with patch.object(provisioner, "guard"), \
                    patch.object(provisioner, "directory", return_value=root / "fixture"), \
                    patch.object(provisioner, "command"), \
                    patch("m1_provision.subprocess.Popen", side_effect=launch), \
                    patch.object(m1_provision.runner_module, "Runner", side_effect=make_runner), \
                    patch("m1_provision.fixture_evidence", return_value=(True, [])):
                self.assertTrue(provisioner.run_all(["ssh-lifecycle"], 3, "a" * 40, "b" * 64))
            self.assertEqual(len(clients), 3)
            for client in clients:
                client.terminate.assert_called_once()
                client.wait.assert_called_once_with(timeout=10)
                client.kill.assert_not_called()
            self.assertEqual(sorted(path.name for path in provisioner.state.glob("fixture-*.log")),
                             [f"fixture-ssh-lifecycle-{number}.log" for number in range(1, 4)])
            summaries = [json.loads(path.read_text()) for path in provisioner.state.glob("ssh-lifecycle-*/summary.json")]
            self.assertEqual(len({summary["fixture_cross_check"]["log"] for summary in summaries}), 3)


if __name__ == "__main__":
    unittest.main()
