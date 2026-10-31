"""Exercise the actual fixture handler through a local socket pair, including half-close."""
import io
import json
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace

from testsrv import m1_fixture as fixture


class FixtureServerTests(unittest.TestCase):
    def test_real_handler_auth_command_resize_and_half_close(self):
        info = fixture.create_fixture(None, False)
        directory = Path(info['fixtureDir'])
        left, right = socket.socketpair()
        client = fixture.paramiko.Transport(left)
        captured = io.StringIO()
        try:
            config = json.loads(Path(info['configPath']).read_text())
            server = SimpleNamespace(stop_event=threading.Event(), config=config,
                host_key=fixture.paramiko.RSAKey.from_private_key_file(info['hostKeyPath']))
            with redirect_stdout(captured):
                thread = threading.Thread(target=fixture.SshHandler, args=(right, ('127.0.0.1', 0), server), daemon=True)
                thread.start()
                client.start_client(timeout=5)
                self.assertEqual(client.get_remote_server_key(), server.host_key)
                client.auth_password(config['server']['username'], config['client']['password'])
                channel = client.open_session(timeout=5)
                channel.get_pty()
                channel.invoke_shell()
                channel.settimeout(2)
                output = channel.recv(4096)
                self.assertIn(b'NETKIT_FIXTURE_READY', output)
                channel.sendall(b'netkit-check\r')
                deadline = time.monotonic() + 3
                while b'NETKIT_TEST_OK' not in output and time.monotonic() < deadline:
                    output += channel.recv(4096)
                self.assertIn(b'NETKIT_TEST_OK', output)
                channel.resize_pty(width=90, height=30)
                deadline = time.monotonic() + 3
                while b'NETKIT_RESIZE_OK' not in output and time.monotonic() < deadline:
                    output += channel.recv(4096)
                self.assertIn(b'NETKIT_RESIZE_OK', output)
                channel.shutdown_write()
                thread.join(timeout=2)
                self.assertFalse(thread.is_alive(), 'Server must exit on write EOF while client read side remains open')
            for marker in ('AUTH_OK:PASSWORD', 'SSH_RESIZE', 'SSH_SESSION_CLOSED', 'SSH_INPUT_BYTES:', 'SSH_COMMAND_OK'):
                self.assertIn(marker, captured.getvalue())
        finally:
            client.close()
            left.close()
            right.close()
            shutil.rmtree(directory)

    def test_resize_redraws_partial_remote_echo_without_submitting_command(self):
        info = fixture.create_fixture(None, False)
        directory = Path(info['fixtureDir'])
        left, right = socket.socketpair()
        client = fixture.paramiko.Transport(left)
        try:
            config = json.loads(Path(info['configPath']).read_text())
            server = SimpleNamespace(stop_event=threading.Event(), config=config,
                host_key=fixture.paramiko.RSAKey.from_private_key_file(info['hostKeyPath']))
            thread = threading.Thread(target=fixture.SshHandler,
                args=(right, ('127.0.0.1', 0), server), daemon=True)
            thread.start()
            client.start_client(timeout=5)
            client.auth_password(config['server']['username'], config['client']['password'])
            channel = client.open_session(timeout=5)
            channel.get_pty()
            channel.invoke_shell()
            channel.settimeout(2)

            def receive_until(marker):
                output = b''
                deadline = time.monotonic() + 3
                while marker not in output and time.monotonic() < deadline:
                    data = channel.recv(4096)
                    if not data:
                        break
                    output += data
                self.assertIn(marker, output)
                return output

            receive_until(b'fixture> ')
            channel.sendall(b'netkit-chec')
            before_resize = receive_until(b'netkit-chec')
            self.assertNotIn(b'NETKIT_TEST_OK', before_resize)
            channel.resize_pty(width=90, height=30)
            after_resize = receive_until(b'NETKIT_RESIZE_OK 90x30\r\nfixture> netkit-chec')
            self.assertNotIn(b'NETKIT_TEST_OK', after_resize)
            channel.sendall(b'k')
            remaining_echo = receive_until(b'k')
            self.assertIn(b'fixture> netkit-check', after_resize + remaining_echo)
            self.assertNotIn(b'NETKIT_TEST_OK', remaining_echo)
            channel.sendall(b'\r')
            completed = receive_until(b'NETKIT_TEST_OK')
            self.assertNotIn(b'Command disabled', completed)
            channel.shutdown_write()
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())
        finally:
            client.close()
            left.close()
            right.close()
            shutil.rmtree(directory)

    def test_real_serve_fixture_binds_loopback_authenticates_and_logs_command(self):
        info = fixture.create_fixture(None, False)
        directory = Path(info['fixtureDir'])
        process = None
        client = None
        try:
            config = json.loads(Path(info['configPath']).read_text())
            with tempfile.TemporaryFile() as log:
                process = subprocess.Popen([sys.executable, '-I', str(Path(fixture.__file__)),
                    'serve', '--fixture-dir', str(directory), '--host-key', 'primary', '--duration', '20'],
                    stdout=log, stderr=log)
                def output():
                    log.seek(0); return log.read().decode(errors='replace')
                deadline = time.monotonic() + 8
                while '"event": "started"' not in output() and time.monotonic() < deadline:
                    self.assertIsNone(process.poll(), 'fixture exited before listening')
                    time.sleep(.05)
                self.assertIn('"event": "started"', output())
                with socket.create_connection(('127.0.0.1', fixture.TCP_BANNER_PORT), timeout=2) as banner:
                    self.assertTrue(banner.recv(1024))
                transport_socket = socket.create_connection(('127.0.0.1', fixture.SSH_PORT), timeout=2)
                client = fixture.paramiko.Transport(transport_socket)
                client.start_client(timeout=5)
                with self.assertRaises(fixture.paramiko.AuthenticationException):
                    client.auth_password(config['server']['username'], config['client']['password'] + '-invalid')
                client.auth_password(config['server']['username'], config['client']['password'])
                channel = client.open_session(timeout=5); channel.get_pty(); channel.invoke_shell(); channel.settimeout(2)
                received = channel.recv(4096)
                channel.sendall(b'netkit-check\r')
                deadline = time.monotonic() + 3
                while b'NETKIT_TEST_OK' not in received and time.monotonic() < deadline:
                    received += channel.recv(4096)
                self.assertIn(b'NETKIT_TEST_OK', received)
                channel.shutdown_write(); client.close(); client = None
                process.terminate(); process.wait(timeout=5)
                text = output()
                for marker in ('TCP_BANNER_SENT', 'AUTH_FAILED', 'AUTH_OK:PASSWORD', 'SSH_INPUT_BYTES:', 'SSH_COMMAND_OK', 'SSH_SESSION_CLOSED'):
                    self.assertIn(marker, text)
                self.assertNotIn(config['client']['password'], text)
        finally:
            if client is not None: client.close()
            if process is not None and process.poll() is None:
                process.terminate(); process.wait(timeout=5)
            shutil.rmtree(directory)
