import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from run_m1_checks import Runner, parse_output, required_markers

RUN = '0123456789abcdef0123456789abcdef'


class BuildIdentityTests(unittest.TestCase):
    def runner(self, root, **kwargs):
        return Runner(adb='/sdk/platform-tools/adb', serial='emulator-5556', phase='lan',
                      run_id=RUN, output_dir=str(Path(root) / 'run'), git_sha='a' * 40, **kwargs)

    def test_hashes_installed_bytes_and_captures_both_packages(self):
        with tempfile.TemporaryDirectory() as root:
            runner = self.runner(root)
            installed = b'PK installed APK bytes'
            responses = [b'package:/data/app/~~abc/pkg/base.apk\n', installed,
                         b'versionCode=2 minSdk=29\n lastUpdateTime=2026-10-26 12:00:00\n'] * 2
            with patch.object(runner, '_adb', side_effect=responses) as adb:
                identity = runner.collect_build_identity()
            self.assertEqual(len(identity['packages']), 2)
            for value in identity['packages'].values():
                self.assertEqual(value['sha256'], hashlib.sha256(installed).hexdigest())
                self.assertEqual(value['versionCode'], 2)
                self.assertEqual(value['lastUpdateTime'], '2026-10-26 12:00:00')
            self.assertEqual(adb.call_count, 6)

    def test_mismatch_preserves_identity_and_does_not_start_instrumentation(self):
        with tempfile.TemporaryDirectory() as root:
            runner = self.runner(root, expect_apk_sha256='f' * 64)
            identity = {'packages': {'cz.netkit.app': {'sha256': 'b' * 64}}}
            with patch.object(runner, 'collect_build_identity', return_value=identity), patch('subprocess.Popen') as execute:
                result = runner.run()
            self.assertFalse(result.passed)
            self.assertIn('does not match', result.reason)
            execute.assert_not_called()
            self.assertEqual(json.loads(runner.summary_path.read_text())['build'], identity)

    def test_identity_is_written_before_instrumentation_and_interrupt_keeps_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            runner = self.runner(root)
            identity = {'packages': {'cz.netkit.app': {'sha256': 'b' * 64}}}
            def interrupt(*args, **kwargs):
                self.assertEqual(json.loads(runner.summary_path.read_text())['build'], identity)
                self.assertEqual(kwargs['errors'], 'replace')
                raise KeyboardInterrupt
            with patch.object(runner, 'collect_build_identity', return_value=identity), patch('subprocess.Popen', side_effect=interrupt):
                result = runner.run()
            self.assertFalse(result.passed)
            self.assertEqual(result.reason, 'interrupted')
            self.assertTrue(runner.instrumentation_log.exists())
            self.assertFalse(json.loads(runner.summary_path.read_text())['passed'])

    def test_rejects_missing_or_split_installed_apk(self):
        for response in (b'', b'package:/data/app/a/base.apk\npackage:/data/app/a/split.apk\n'):
            with tempfile.TemporaryDirectory() as root:
                runner = self.runner(root)
                with patch.object(runner, '_adb', return_value=response), self.assertRaises(ValueError):
                    runner.collect_build_identity()

    def test_glued_markers_with_raw_instrumentation_fields(self):
        output = ''.join(required_markers('lan', RUN)) + (
            '\nINSTRUMENTATION_RESULT: netkit.phase=lan\n'
            'INSTRUMENTATION_RESULT: netkit.result=PASS\n'
            f'INSTRUMENTATION_RESULT: netkit.runId={RUN}\nINSTRUMENTATION_CODE: -1\n')
        self.assertTrue(parse_output(output, phase='lan', run_id=RUN, exit_code=0).passed)

    def test_test_apk_mismatch_stops_before_instrumentation(self):
        with tempfile.TemporaryDirectory() as root:
            runner = self.runner(root, expect_test_apk_sha256='f' * 64)
            identity = {'packages': {'cz.netkit.app': {'sha256': 'b' * 64}, 'cz.netkit.app.test': {'sha256': 'c' * 64}}}
            with patch.object(runner, 'collect_build_identity', return_value=identity), patch('subprocess.Popen') as execute:
                result = runner.run()
            self.assertFalse(result.passed)
            self.assertIn('test APK', result.reason)
            execute.assert_not_called()
            self.assertEqual(json.loads(runner.summary_path.read_text())['build'], identity)
