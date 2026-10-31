#!/usr/bin/env python3
"""Provision/run/clean private NetKit fixtures on the isolated netkit-api35 emulator."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
from typing import Any


def local_module(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError('Module unavailable')
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


ROOT = Path(__file__).resolve().parent.parent
runner_module = local_module('netkit_m1_runner', ROOT / 'tools/run_m1_checks.py')
fixture_module = local_module('netkit_m1_fixture', ROOT / 'tools/testsrv/m1_fixture.py')


def fixture_evidence(phase: str, log: str) -> tuple[bool, list[str]]:
    required = {
        'lan': ['TCP_BANNER_SENT', 'SILENT_ACCEPTED', 'SILENT_EOF'],
        'ssh': ['AUTH_OK:PASSWORD', 'AUTH_OK:KEY', 'AUTH_OK:INTERACTIVE', 'SSH_RESIZE', 'SSH_SESSION_CLOSED', 'SSH_INPUT_BYTES:', 'SSH_COMMAND_OK', 'AUTH_FAILED'],
        'ssh-ui': ['AUTH_OK:PASSWORD', 'SSH_SESSION_CLOSED', 'SSH_INPUT_BYTES:', 'SSH_COMMAND_OK'],
        'ssh-lifecycle': ['AUTH_OK:PASSWORD', 'SSH_SESSION_CLOSED'],
        'ssh-reconnect': ['AUTH_OK:PASSWORD', 'SSH_SESSION_CLOSED', 'SSH_INPUT_BYTES:', 'SSH_COMMAND_OK'],
        'ssh-legacy': ['SSH_SESSION_CLOSED'],
        'ssh-rotated': ['SSH_SESSION_CLOSED'],
    }.get(phase, [])
    missing = [marker for marker in required if marker not in log]
    if phase in ('ssh-legacy', 'ssh-rotated') and 'AUTH_OK:' in log:
        missing.append('unexpected authentication on rejected server')
    return not missing, missing


class Provisioner:
    def __init__(self, adb: str, serial: str, state: Path):
        self.adb = runner_module.validate_adb_path(adb)
        self.serial = runner_module.validate_serial(serial)
        self.state = state.resolve()
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.state.chmod(0o700)
        self.receipt = self.state / 'provision.json'

    def command(self, args: list[str], data: bytes | None = None, timeout: int = 30) -> bytes:
        command = [self.adb, '-s', self.serial, *args]
        with (self.state / 'adb-commands.log').open('a') as log:
            log.write(json.dumps({'argv': command}) + '\n')
        result = subprocess.run(command, input=data, capture_output=True, timeout=timeout, check=False)
        if result.returncode != 0:
            raise RuntimeError('ADB command failed (output withheld)')
        return result.stdout

    def guard(self) -> None:
        name = self.command(['emu', 'avd', 'name']).decode(errors='replace').splitlines()[0]
        if name != 'netkit-api35':
            raise ValueError('Only netkit-api35 may be provisioned')
        if self.command(['shell', 'getprop', 'sys.boot_completed']).strip() != b'1':
            raise ValueError('Emulator has not booted')
        windows = self.command(['shell', 'dumpsys', 'window']).decode(errors='replace')
        if any(token in windows for token in ('mShowingLockscreen=true', 'isStatusBarKeyguard=true', 'mKeyguardShowing=true')):
            raise ValueError('Emulator is locked; do not automate unlocking')

    def provision(self, app_apk: Path, test_apk: Path) -> None:
        self.guard()
        if self.receipt.exists():
            raise ValueError('Provision receipt exists; clean this owned fixture first')
        for apk in (app_apk, test_apk):
            if not apk.is_file():
                raise ValueError('Missing APK')
            self.command(['install', '-r', str(apk.resolve())], timeout=60)
        # Credentials exist only in mode0600 files/stdin; never argv, output, or receipt.
        public = fixture_module.create_fixture(None, include_rotated_host_key=True)
        directory = Path(public['fixtureDir'])
        self.receipt.write_text(json.dumps({'fixture_dir': str(directory), 'serial': self.serial}) + '\n')
        self.receipt.chmod(0o600)
        for source, target in ((Path(public['configPath']), 'm1-fixture.json'),
                               (Path(public['clientKeyPath']), 'm1-client-key')):
            self.command(['exec-in', 'run-as', 'cz.netkit.app', 'sh', '-c',
                          f'umask 077; mkdir -p files; cat > files/{target}'], source.read_bytes())

    def directory(self) -> Path:
        receipt = json.loads(self.receipt.read_text())
        if receipt['serial'] != self.serial:
            raise ValueError('Receipt belongs to another emulator')
        directory = Path(receipt['fixture_dir'])
        if directory.parent != Path('/tmp') or not directory.name.startswith('netkit-fixture-') or directory.is_symlink():
            raise ValueError('Unsafe fixture directory')
        return directory

    def cleanup(self) -> None:
        # Validate ownership before deleting anything, even if the device is unavailable.
        directory = self.directory()
        device_cleaned = False
        try:
            self.guard()
            self.command(['shell', 'run-as', 'cz.netkit.app', 'rm', '-f',
                          'files/m1-fixture.json', 'files/m1-client-key'])
            device_cleaned = True
        finally:
            if directory.exists():
                shutil.rmtree(directory)
            # Keep the non-secret receipt if device cleanup must be retried.
            if device_cleaned:
                self.receipt.unlink()

    def run_all(self, phases: list[str], repeats: int, git_sha: str, expected: str, expected_test: str | None = None) -> bool:
        self.guard()
        directory = self.directory()
        all_ok = True
        for phase in phases:
            key = 'rotated' if phase == 'ssh-rotated' else 'legacy' if phase == 'ssh-legacy' else 'primary'
            for attempt in range(1, repeats + 1):
                log_path = self.state / f'fixture-{phase}-{attempt}.log'
                with log_path.open('wb') as fixture_log:
                    server = subprocess.Popen([sys.executable, '-I', str(ROOT / 'tools/testsrv/m1_fixture.py'),
                        'serve', '--fixture-dir', str(directory), '--host-key', key, '--duration', '600'],
                        stdout=fixture_log, stderr=fixture_log)
                    try:
                        deadline = time.monotonic() + 10
                        while time.monotonic() < deadline:
                            if server.poll() is not None:
                                raise RuntimeError('Fixture failed to start')
                            if b'"event": "started"' in log_path.read_bytes():
                                break
                            time.sleep(0.1)
                        else:
                            raise TimeoutError('Fixture readiness timeout')
                        self.guard()
                        for permission in ('ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'):
                            action = 'revoke' if phase == 'wifi-denied' else 'grant'
                            self.command(['shell', 'pm', action, 'cz.netkit.app', 'android.permission.' + permission])
                        run = runner_module.Runner(adb=self.adb, serial=self.serial, phase=phase,
                            run_id=uuid.uuid4().hex, output_dir=str(self.state / f'{phase}-{attempt}-{uuid.uuid4().hex[:8]}'),
                            git_sha=git_sha, expect_apk_sha256=expected, expect_test_apk_sha256=expected_test)
                        fixture_offset = log_path.stat().st_size
                        result = run.run()
                        fixture_delta = log_path.read_bytes()[fixture_offset:].decode(errors='replace')
                        evidence_ok, missing = fixture_evidence(phase, fixture_delta)
                        summary = json.loads(run.summary_path.read_text())
                        summary['fixture_cross_check'] = {'passed': evidence_ok, 'missing': missing, 'log': str(log_path), 'offset': fixture_offset}
                        if not evidence_ok:
                            summary['passed'] = False
                            summary['reason'] += '; fixture evidence missing: ' + ', '.join(missing)
                        run.summary_path.write_text(json.dumps(summary, indent=2) + '\n')
                        print(json.dumps({'phase': phase, 'attempt': attempt, 'passed': result.passed and evidence_ok,
                                          'skipped': result.skipped, 'reason': result.reason}), flush=True)
                        all_ok = all_ok and (result.passed or result.skipped) and evidence_ok
                        if not all_ok:
                            return False  # Fail fast: never launch the next instrumentation after an uncertain failure.
                    finally:
                        server.terminate()
                        try:
                            server.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            server.kill()
                            server.wait(timeout=5)
        return all_ok


def main() -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('provision', 'run-all', 'cleanup'))
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--state-dir', required=True, type=Path)
    parser.add_argument('--app-apk', type=Path, default=ROOT / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk')
    parser.add_argument('--test-apk', type=Path, default=ROOT / 'androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk')
    parser.add_argument('--phases', default='ui,lan,lan-confirm,wifi,wifi-denied,ssh,ssh-ui,ssh-rotated,ssh-legacy,wol-e2e')
    parser.add_argument('--repeats', type=int, choices=(1, 2, 3), default=3)
    parser.add_argument('--git-sha')
    parser.add_argument('--expect-apk-sha256')
    parser.add_argument('--expect-test-apk-sha256')
    args = parser.parse_args()
    tool = Provisioner(args.adb, args.serial, args.state_dir)
    if args.action == 'provision':
        tool.provision(args.app_apk, args.test_apk)
    elif args.action == 'cleanup':
        tool.cleanup()
    else:
        if not args.git_sha or not args.expect_apk_sha256:
            parser.error('run-all requires --git-sha and --expect-apk-sha256')
        return 0 if tool.run_all(args.phases.split(','), args.repeats, args.git_sha, args.expect_apk_sha256, args.expect_test_apk_sha256) else 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
