#!/usr/bin/env python3
"""Private, loopback-only TCP/SSH test fixture for NetKit M1."""

from __future__ import annotations

import argparse
import hmac
import io
import json
import os
import queue
import secrets
import signal
import socket
import socketserver
import stat
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

import paramiko  # pyright: ignore[reportMissingModuleSource]
from paramiko.common import AUTH_FAILED, AUTH_SUCCESSFUL, OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED, OPEN_SUCCEEDED  # pyright: ignore[reportMissingModuleSource]
from paramiko.server import InteractiveQuery  # pyright: ignore[reportMissingModuleSource]
from cryptography.hazmat.primitives import serialization  # pyright: ignore[reportMissingImports]
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey  # pyright: ignore[reportMissingImports]

BIND_ADDRESS = "127.0.0.1"
EMULATOR_HOST = "10.0.2.2"
TCP_BANNER_PORT = 18081
TCP_CLOSED_PORT = 18082
TCP_SILENT_PORT = 18083
SSH_PORT = 22222
MAX_DURATION_SECONDS = 600
MAX_ACTIVE_SESSIONS = 8
SSH_CHANNEL_ACCEPT_SECONDS = 120.0
USERNAME = "netkit-test"
TCP_BANNER = b"NETKIT_TCP\r\n"
READY_LINE = b"\x1b[1;32mNETKIT_FIXTURE_READY\x1b[0m\r\nfixture> "
SUCCESS_LINE = b"\r\n\x1b[1;32mNETKIT_TEST_OK\x1b[0m\r\nfixture> "
INTERRUPT_LINE = b"\r\n^C\r\nNETKIT_INTERRUPT_OK\r\nfixture> "
DISABLED_LINE = b"\r\nCommand disabled in NetKit fixture.\r\nfixture> "
RESIZE_PREFIX = b"\r\nNETKIT_RESIZE_OK "


def config_path(fixture_dir: Path) -> Path:
    return fixture_dir / "m1-fixture.json"


def create_private_directory(requested: str | None) -> Path:
    if requested is None:
        directory = Path(tempfile.mkdtemp(prefix="netkit-fixture-", dir="/tmp"))
    else:
        candidate = Path(requested).absolute()
        if candidate.parent.resolve() != Path("/tmp").resolve():
            raise ValueError("Fixture directory must be a new direct child of /tmp")
        candidate.mkdir(mode=0o700, parents=False, exist_ok=False)
        directory = candidate
    directory.chmod(0o700)
    if directory.is_symlink() or directory.resolve().parent != Path("/tmp").resolve():
        raise ValueError("Unsafe fixture directory")
    return directory


def write_private_key(key: paramiko.PKey, path: Path) -> None:
    previous_umask = os.umask(0o077)
    try:
        key.write_private_key_file(str(path))
    finally:
        os.umask(previous_umask)
    path.chmod(0o600)


def write_private_bytes(content: bytes, path: Path) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(content)
    path.chmod(0o600)


def fingerprint(key: paramiko.PKey) -> str:
    import base64
    import hashlib

    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def shell_response(command: str) -> bytes:
    """Pure response policy; never converts user input into an OS command."""
    if command == "netkit-check":
        return SUCCESS_LINE
    if command:
        return DISABLED_LINE
    return b"\r\nfixture> "


def create_fixture(requested_dir: str | None, include_rotated_host_key: bool) -> dict[str, Any]:
    fixture_dir = create_private_directory(requested_dir)
    credential_value = secrets.token_urlsafe(24)
    host_key = paramiko.RSAKey.generate(3072)
    signing_key = Ed25519PrivateKey.generate()
    private_key_bytes = signing_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.OpenSSH,
        encryption_algorithm=serialization.NoEncryption(),
    )
    client_key = paramiko.Ed25519Key.from_private_key(io.StringIO(private_key_bytes.decode("ascii")))
    host_path = fixture_dir / "ssh_host_rsa"
    client_key_path = fixture_dir / "ssh_client_ed25519"
    write_private_key(host_key, host_path)
    write_private_bytes(private_key_bytes, client_key_path)
    rotated_host_path: Path | None = None
    rotated_host_fingerprint: str | None = None
    rotated_host_public_key: str | None = None
    if include_rotated_host_key:
        rotated_key = paramiko.RSAKey.generate(3072)
        rotated_host_path = fixture_dir / "ssh_host_rsa_rotated"
        write_private_key(rotated_key, rotated_host_path)
        rotated_host_fingerprint = fingerprint(rotated_key)
        rotated_host_public_key = rotated_key.get_base64()

    public_key = f"{client_key.get_name()} {client_key.get_base64()}"
    cfg: dict[str, Any] = {
        "schema": "netkit-m1-ssh-fixture/v1",
        "server": {
            "bindAddress": BIND_ADDRESS,
            "emulatorHost": EMULATOR_HOST,
            "tcpBannerPort": TCP_BANNER_PORT,
            "tcpClosedPort": TCP_CLOSED_PORT,
            "tcpSilentPort": TCP_SILENT_PORT,
            "sshPort": SSH_PORT,
            "username": USERNAME,
            "hostKeyPath": str(host_path),
            "hostKeyAlgorithm": host_key.get_name(),
            "hostKeyPublicKey": host_key.get_base64(),
            "hostKeyFingerprint": fingerprint(host_key),
            "rotatedHostKeyPath": str(rotated_host_path) if rotated_host_path else None,
            "rotatedHostKeyAlgorithm": "ssh-rsa" if rotated_host_path else None,
            "rotatedHostKeyPublicKey": rotated_host_public_key,
            "rotatedHostKeyFingerprint": rotated_host_fingerprint,
        },
        "client": {
            "profiles": [
                {"id": "fixture-password", "name": "NetKit fixture password", "host": EMULATOR_HOST,
                 "port": SSH_PORT, "username": USERNAME, "authentication": "PASSWORD", "password": credential_value,
                 "secretRef": None, "keyRef": None, "startupCommand": "", "keepaliveSeconds": 10},
                {"id": "fixture-interactive", "name": "NetKit fixture keyboard interactive", "host": EMULATOR_HOST,
                 "port": SSH_PORT, "username": USERNAME, "authentication": "INTERACTIVE", "password": credential_value,
                 "secretRef": None, "keyRef": None, "startupCommand": "", "keepaliveSeconds": 10},
                {"id": "fixture-key", "name": "NetKit fixture public key", "host": EMULATOR_HOST,
                 "port": SSH_PORT, "username": USERNAME, "authentication": "KEY", "password": None,
                 "secretRef": None, "keyRef": None, "privateKeyPath": str(client_key_path),
                 "publicKey": public_key, "startupCommand": "", "keepaliveSeconds": 10},
            ],
            "password": credential_value,
            "clientPrivateKeyPath": str(client_key_path),
            "clientPublicKey": public_key,
        },
        "host": EMULATOR_HOST,
        "sshPort": SSH_PORT,
        "username": USERNAME,
        "password": credential_value,
        "clientPrivateKeyPath": str(client_key_path),
        "hostKeyAlgorithm": host_key.get_name(),
        "hostKeyPublicKey": host_key.get_base64(),
        "hostKeyFingerprint": fingerprint(host_key),
        "expected": {
            "tcpBanner": TCP_BANNER.decode("ascii").strip(),
            "sshReadyMarker": "NETKIT_FIXTURE_READY",
            "command": "netkit-check",
            "commandMarker": "NETKIT_TEST_OK",
            "interruptMarker": "NETKIT_INTERRUPT_OK",
            "resizeMarker": "NETKIT_RESIZE_OK",
            "execRequests": "rejected; no commands are executed",
        },
    }
    destination = config_path(fixture_dir)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(destination, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(cfg, stream, indent=2)
        stream.write("\n")
    destination.chmod(0o600)
    return {
        "fixtureDir": str(fixture_dir),
        "configPath": str(destination),
        "hostKeyPath": str(host_path),
        "clientKeyPath": str(client_key_path),
        "rotatedHostKeyPath": str(rotated_host_path) if rotated_host_path else None,
        "bindAddress": BIND_ADDRESS,
        "emulatorHost": EMULATOR_HOST,
        "tcpBannerPort": TCP_BANNER_PORT,
        "tcpClosedPort": TCP_CLOSED_PORT,
        "tcpSilentPort": TCP_SILENT_PORT,
        "sshPort": SSH_PORT,
        "hostKeyFingerprint": cfg["server"]["hostKeyFingerprint"],
        "rotatedHostKeyFingerprint": rotated_host_fingerprint,
        "username": USERNAME,
        "authModes": ["PASSWORD", "KEY", "INTERACTIVE"],
        "maxServiceDurationSeconds": MAX_DURATION_SECONDS,
    }


class BannerHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(2.0)
        try:
            self.request.sendall(TCP_BANNER)
            print("TCP_BANNER_SENT", flush=True)
            try:
                self.request.recv(256)
            except (TimeoutError, OSError):
                pass
        except OSError:
            pass


class BannerServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8
    max_active_sessions = MAX_ACTIVE_SESSIONS

    def __init__(self, address: tuple[str, int], handler: type[socketserver.BaseRequestHandler],
                 bind_and_activate: bool = True) -> None:
        super().__init__(address, handler, bind_and_activate=bind_and_activate)
        self._session_slots = threading.BoundedSemaphore(self.max_active_sessions)

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._session_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        worker = threading.Thread(target=self.process_request_thread,
                                  args=(request, client_address), daemon=self.daemon_threads)
        try:
            worker.start()
        except BaseException:
            self.shutdown_request(request)
            self._session_slots.release()
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        try:
            try:
                self.finish_request(request, client_address)
            except Exception:
                self.handle_error(request, client_address)
            finally:
                self.shutdown_request(request)
        finally:
            self._session_slots.release()

    def handle_error(self, request: Any, client_address: Any) -> None:
        # The default server handler prints a traceback which could expose Paramiko input data.
        del request, client_address


class SilentHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        print("SILENT_ACCEPTED", flush=True)
        self.request.settimeout(0.2)
        deadline = time.monotonic() + 5.0
        stop_event: threading.Event = self.server.stop_event  # type: ignore[attr-defined]
        while not stop_event.is_set() and time.monotonic() < deadline:
            try:
                if not self.request.recv(256):
                    print("SILENT_EOF", flush=True)
                    return
            except socket.timeout:
                continue
            except OSError:
                return


class SilentServer(BannerServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address: tuple[str, int], stop_event: threading.Event) -> None:
        super().__init__(address, SilentHandler)
        self.stop_event = stop_event


class FixtureAuth(paramiko.ServerInterface):
    def __init__(self, config: dict[str, Any], stop_event: threading.Event) -> None:
        self.username = config["server"]["username"]
        self.password = config["client"]["password"]
        self.authorized_key = paramiko.Ed25519Key.from_private_key_file(config["client"]["clientPrivateKeyPath"]).asbytes()
        self.stop_event = stop_event
        self.resize_events: queue.Queue[tuple[int, int]] = queue.Queue(maxsize=8)

    def get_allowed_auths(self, username: str) -> str:
        return "publickey,password,keyboard-interactive" if username == self.username else ""

    def check_auth_password(self, username: str, password: str) -> int:
        if username == self.username and hmac.compare_digest(password, self.password):
            print("AUTH_OK:PASSWORD", flush=True)
            return AUTH_SUCCESSFUL
        print("AUTH_FAILED", flush=True)
        return AUTH_FAILED

    def check_auth_publickey(self, username: str, key: paramiko.PKey) -> int:
        if username == self.username and hmac.compare_digest(key.asbytes(), self.authorized_key):
            print("AUTH_OK:KEY", flush=True)
            return AUTH_SUCCESSFUL
        print("AUTH_FAILED", flush=True)
        return AUTH_FAILED

    def check_auth_interactive(self, username: str, submethods: str) -> InteractiveQuery | int:
        if username != self.username:
            print("AUTH_FAILED", flush=True)
            return AUTH_FAILED
        return InteractiveQuery("NetKit local fixture", "", ("Password: ", False))

    def check_auth_interactive_response(self, responses: list[str]) -> int:
        if len(responses) == 1 and hmac.compare_digest(responses[0], self.password):
            print("AUTH_OK:INTERACTIVE", flush=True)
            return AUTH_SUCCESSFUL
        print("AUTH_FAILED", flush=True)
        return AUTH_FAILED

    def check_channel_request(self, kind: str, chanid: int) -> int:
        if kind == "session":
            return OPEN_SUCCEEDED
        return OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel: paramiko.Channel, command: bytes) -> bool:
        del channel, command
        return False

    def check_channel_pty_request(self, channel: paramiko.Channel, term: bytes, width: int, height: int,
                                  pixelwidth: int, pixelheight: int, modes: bytes) -> bool:
        del channel, term, width, height, pixelwidth, pixelheight, modes
        return True

    def check_channel_shell_request(self, channel: paramiko.Channel) -> bool:
        del channel
        return True

    def check_channel_window_change_request(self, channel: paramiko.Channel, width: int, height: int,
                                            pixelwidth: int, pixelheight: int) -> bool:
        del channel, pixelwidth, pixelheight
        print("SSH_RESIZE", flush=True)
        try:
            self.resize_events.put_nowait((width, height))
        except queue.Full:
            try:
                self.resize_events.get_nowait()
                self.resize_events.put_nowait((width, height))
            except queue.Empty:
                pass
        return True


class SshHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        stop_event: threading.Event = self.server.stop_event  # type: ignore[attr-defined]
        host_key: paramiko.PKey = self.server.host_key  # type: ignore[attr-defined]
        config: dict[str, Any] = self.server.config  # type: ignore[attr-defined]
        transport = paramiko.Transport(self.request)
        transport.add_server_key(host_key)
        if getattr(self.server, "legacy_algorithms", False):
            transport.get_security_options().ciphers = ("aes128-cbc",)
        auth = FixtureAuth(config, stop_event)
        try:
            transport.start_server(server=auth)
            deadline = time.monotonic() + SSH_CHANNEL_ACCEPT_SECONDS
            channel = None
            while not stop_event.is_set() and time.monotonic() < deadline:
                channel = transport.accept(timeout=min(0.2, max(0.01, deadline - time.monotonic())))
                if channel is not None:
                    break
            if channel is None:
                return
            channel.settimeout(0.2)
            channel.sendall(READY_LINE)
            line_buffer = bytearray()
            skip_line_feed = False
            while not stop_event.is_set() and transport.is_active() and not channel.closed:
                try:
                    data = channel.recv(1024)
                except socket.timeout:
                    data = None
                except OSError:
                    break
                if data == b"":
                    break  # EOF is final even if the peer has not closed its read side.
                if data is not None:
                    print(f"SSH_INPUT_BYTES:{len(data)}", flush=True)
                    for byte in data:
                        if byte == 3:  # Terminal Ctrl+C; do not execute or spawn a process.
                            line_buffer.clear()
                            channel.sendall(INTERRUPT_LINE)
                            skip_line_feed = False
                        elif byte in (10, 13):
                            if byte == 10 and skip_line_feed:
                                skip_line_feed = False
                                continue
                            command = bytes(line_buffer).decode("utf-8", errors="ignore").strip()
                            line_buffer.clear()
                            skip_line_feed = byte == 13
                            channel.sendall(shell_response(command))
                            if command == "netkit-check":
                                print("SSH_COMMAND_OK", flush=True)
                        elif byte >= 32 and byte != 127:
                            if len(line_buffer) < 256:
                                line_buffer.append(byte)
                            channel.send(bytes((byte,)))
                try:
                    width, height = auth.resize_events.get_nowait()
                    # Redraw the edit buffer after the asynchronous acknowledgement.
                    # Keep the command intact and visible before the user presses Enter.
                    channel.sendall(RESIZE_PREFIX + f"{width}x{height}\r\nfixture> ".encode("ascii")
                                    + bytes(line_buffer))
                except queue.Empty:
                    pass
        except (OSError, paramiko.SSHException, EOFError):
            pass
        finally:
            transport.close()
            print("SSH_SESSION_CLOSED", flush=True)


class SshServer(BannerServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address: tuple[str, int], config: dict[str, Any], host_key_path: str,
                 stop_event: threading.Event) -> None:
        super().__init__(address, SshHandler)
        self.legacy_algorithms = False
        self.config = config
        self.host_key = paramiko.RSAKey.from_private_key_file(host_key_path)
        self.stop_event = stop_event


def serve_fixture(fixture_dir_arg: str, host_key_choice: str, duration: int) -> None:
    if not 1 <= duration <= MAX_DURATION_SECONDS:
        raise ValueError(f"Duration must be 1..{MAX_DURATION_SECONDS} seconds")
    fixture_dir = Path(fixture_dir_arg).resolve(strict=True)
    if fixture_dir.parent != Path("/tmp").resolve() or not fixture_dir.name.startswith("netkit-fixture-"):
        raise ValueError("Refusing to read fixture files outside a private NetKit /tmp fixture directory")
    if stat.S_IMODE(fixture_dir.stat().st_mode) != 0o700:
        raise ValueError("Fixture directory must have mode 0700")
    config_file = config_path(fixture_dir)
    if stat.S_IMODE(config_file.stat().st_mode) != 0o600:
        raise ValueError("Fixture config must have mode 0600")
    config = json.loads(config_file.read_text(encoding="utf-8"))
    if config.get("schema") != "netkit-m1-ssh-fixture/v1":
        raise ValueError("Unsupported fixture config")
    if config["server"]["bindAddress"] != BIND_ADDRESS:
        raise ValueError("Fixture bind address must remain loopback-only")
    if host_key_choice == "rotated":
        host_key_path = config["server"].get("rotatedHostKeyPath")
        if not host_key_path:
            raise ValueError("Rotated host key was not generated; create with --generate-rotated-host-key")
    else:
        host_key_path = config["server"]["hostKeyPath"]
    private_files = [Path(host_key_path), Path(config["client"]["clientPrivateKeyPath"])]
    private_files.extend(Path(value) for value in (config["server"].get("rotatedHostKeyPath"),) if value)
    for private_file in private_files:
        if private_file.is_symlink() or private_file.resolve(strict=True).parent != fixture_dir:
            raise ValueError("Private fixture key must be a direct file in the fixture directory")
        if stat.S_IMODE(private_file.stat().st_mode) != 0o600:
            raise ValueError("Private fixture keys must have mode 0600")

    stop_event = threading.Event()
    banner_server = BannerServer((BIND_ADDRESS, TCP_BANNER_PORT), BannerHandler)
    closed_port_reservation = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    closed_port_reservation.bind((BIND_ADDRESS, TCP_CLOSED_PORT))  # Bound but deliberately not listening.
    silent_server = SilentServer((BIND_ADDRESS, TCP_SILENT_PORT), stop_event)
    ssh_server = SshServer((BIND_ADDRESS, SSH_PORT), config, host_key_path, stop_event)
    ssh_server.legacy_algorithms = host_key_choice == "legacy"
    servers: list[socketserver.ThreadingTCPServer] = [banner_server, silent_server, ssh_server]
    workers = [threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True)
               for server in servers]
    for worker in workers:
        worker.start()

    print(json.dumps({"event": "started", "bindAddress": BIND_ADDRESS,
                      "tcpBannerPort": TCP_BANNER_PORT, "sshPort": SSH_PORT,
                          "tcpClosedPort": TCP_CLOSED_PORT, "tcpSilentPort": TCP_SILENT_PORT,
                      "hostKeyChoice": host_key_choice, "maxDurationSeconds": duration}), flush=True)
    stopped = threading.Event()

    def request_stop(_signum: int, _frame: Any) -> None:
        stopped.set()

    previous_sigint = signal.signal(signal.SIGINT, request_stop)
    previous_sigterm = signal.signal(signal.SIGTERM, request_stop)
    try:
        stopped.wait(duration)
    finally:
        stop_event.set()
        for server in servers:
            server.shutdown()
        for server in servers:
            server.server_close()
        closed_port_reservation.close()
        for worker in workers:
            worker.join(timeout=3.0)
        signal.signal(signal.SIGINT, previous_sigint)
        signal.signal(signal.SIGTERM, previous_sigterm)
        print(json.dumps({"event": "stopped", "cleanup": "listeners closed; session workers daemonized"}), flush=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create", help="Create private SSH keys and client config under /tmp")
    create.add_argument("--output-dir", help="New direct child of /tmp; default creates a private temporary directory")
    create.add_argument("--generate-rotated-host-key", action="store_true",
                        help="Also create a separate alternate RSA host key; it never replaces the primary key")
    serve = subparsers.add_parser("serve", help="Start bounded TCP banner and SSH fixture listeners")
    serve.add_argument("--fixture-dir", required=True)
    serve.add_argument("--host-key", choices=("primary", "rotated", "legacy"), default="primary")
    serve.add_argument("--duration", type=int, default=MAX_DURATION_SECONDS)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.command == "create":
            print(json.dumps(create_fixture(args.output_dir, args.generate_rotated_host_key), indent=2), flush=True)
        elif args.command == "serve":
            serve_fixture(args.fixture_dir, args.host_key, args.duration)
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr, flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
