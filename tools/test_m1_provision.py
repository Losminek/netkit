import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch, Mock
from m1_provision import Provisioner, fixture_evidence


class ProvisionTests(unittest.TestCase):
    def test_cross_check_requires_independent_server_auth_and_banner(self):
        self.assertFalse(fixture_evidence('ssh', 'SSH_SESSION_CLOSED')[0])
        self.assertTrue(fixture_evidence('ssh', 'AUTH_OK:PASSWORD AUTH_OK:KEY AUTH_OK:INTERACTIVE SSH_RESIZE SSH_SESSION_CLOSED SSH_INPUT_BYTES:1 SSH_COMMAND_OK AUTH_FAILED')[0])
        self.assertFalse(fixture_evidence('ssh-legacy', 'AUTH_OK:PASSWORD SSH_SESSION_CLOSED')[0])
        self.assertFalse(fixture_evidence('lan', 'TCP_BANNER_SENT SILENT_ACCEPTED')[0])
        self.assertTrue(fixture_evidence('lan', 'TCP_BANNER_SENT SILENT_ACCEPTED SILENT_EOF')[0])

    def test_guard_refuses_shared_avd_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Provisioner('/sdk/platform-tools/adb', 'emulator-5554', Path(directory))
            with patch.object(tool, 'command', return_value=b'rex-api35\nOK\n') as command:
                with self.assertRaises(ValueError):
                    tool.guard()
            command.assert_called_once_with(['emu', 'avd', 'name'])

    def test_command_log_omits_stdin_and_scopes_serial(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Provisioner('/sdk/platform-tools/adb', 'emulator-5556', Path(directory))
            payload = b'private input should not be logged'
            with patch('m1_provision.subprocess.run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as execute:
                tool.command(['exec-in', 'run-as', 'cz.netkit.app', 'cat'], payload)
            logged = (Path(directory) / 'adb-commands.log').read_text()
            self.assertNotIn(payload.decode(), logged)
            self.assertEqual(json.loads(logged)['argv'][1:3], ['-s', 'emulator-5556'])
            self.assertEqual(execute.call_args.kwargs['input'], payload)

    def test_cleanup_rejects_unowned_path(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Provisioner('/sdk/platform-tools/adb', 'emulator-5556', Path(directory))
            tool.receipt.write_text(json.dumps({'serial': 'emulator-5556', 'fixture_dir': '/tmp/not-owned'}))
            with self.assertRaises(ValueError):
                tool.directory()

    def test_host_cleanup_happens_even_when_device_guard_fails_and_receipt_allows_retry(self):
        with tempfile.TemporaryDirectory() as state, tempfile.TemporaryDirectory(prefix='netkit-fixture-', dir='/tmp') as owned:
            tool = Provisioner('/sdk/platform-tools/adb', 'emulator-5556', Path(state))
            directory = Path(owned)
            (directory / 'private-material').write_text('ephemeral test content')
            tool.receipt.write_text(json.dumps({'serial': 'emulator-5556', 'fixture_dir': owned}))
            with patch.object(tool, 'guard', side_effect=RuntimeError('device unavailable')), patch.object(tool, 'command') as command:
                with self.assertRaises(RuntimeError): tool.cleanup()
                command.assert_not_called()
            self.assertFalse(directory.exists())
            self.assertTrue(tool.receipt.exists())
            with patch.object(tool, 'guard'), patch.object(tool, 'command', return_value=b''):
                tool.cleanup()
            self.assertFalse(tool.receipt.exists())

    def test_input_and_command_markers_cannot_be_replaced_by_auth_success(self):
        self.assertFalse(fixture_evidence('ssh-ui', 'AUTH_OK:PASSWORD SSH_SESSION_CLOSED')[0])
        self.assertTrue(fixture_evidence('ssh-ui', 'AUTH_OK:PASSWORD SSH_SESSION_CLOSED SSH_INPUT_BYTES:12 SSH_COMMAND_OK')[0])

    def test_failed_instrumentation_stops_series_and_closes_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            tool = Provisioner('/sdk/platform-tools/adb', 'emulator-5556', Path(directory))
            summary = Path(directory) / 'run-summary.json'
            summary.write_text(json.dumps({'passed': False, 'reason': 'timeout'}))
            run = Mock(summary_path=summary)
            run.run.return_value = SimpleNamespace(passed=False, skipped=False, reason='timeout')
            server = Mock()
            server.poll.return_value = None
            def start_server(*args, **kwargs):
                kwargs['stdout'].write(b'{"event": "started"}\n')
                kwargs['stdout'].flush()
                return server
            with patch.object(tool, 'guard'), patch.object(tool, 'directory', return_value=Path(directory)), \
                    patch.object(tool, 'command', return_value=b''), \
                    patch('m1_provision.subprocess.Popen', side_effect=start_server), \
                    patch('m1_provision.runner_module.Runner', return_value=run) as runner:
                self.assertFalse(tool.run_all(['ui', 'lan'], 3, 'a' * 40, 'b' * 64, 'c' * 64))
            self.assertEqual(runner.call_count, 1)
            self.assertEqual(runner.call_args.kwargs['expect_test_apk_sha256'], 'c' * 64)
            server.terminate.assert_called_once()
            server.wait.assert_called_once_with(timeout=10)
