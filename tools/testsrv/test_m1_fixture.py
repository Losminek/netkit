import json
import shutil
import stat
import threading
import time
import unittest
from pathlib import Path
from typing import Any

try:
    import m1_fixture as fixture
except ModuleNotFoundError:
    from testsrv import m1_fixture as fixture


class FixturePolicyTests(unittest.TestCase):
    def test_fixed_ports_and_loopback_policy(self) -> None:
        self.assertEqual(fixture.BIND_ADDRESS, "127.0.0.1")
        self.assertEqual((fixture.TCP_BANNER_PORT, fixture.TCP_CLOSED_PORT, fixture.TCP_SILENT_PORT),
                         (18081, 18082, 18083))
        self.assertEqual(fixture.SSH_PORT, 22222)
        self.assertEqual(fixture.EMULATOR_HOST, "10.0.2.2")
        self.assertEqual(fixture.MAX_DURATION_SECONDS, 600)
        self.assertEqual(fixture.MAX_ACTIVE_SESSIONS, 8)
        self.assertEqual(fixture.SSH_CHANNEL_ACCEPT_SECONDS, 120.0)

    def test_shell_policy_never_executes_arbitrary_input(self) -> None:
        self.assertIn(b"NETKIT_TEST_OK", fixture.shell_response("netkit-check"))
        self.assertIn(b"Command disabled", fixture.shell_response("uname -a"))
        self.assertIn(b"Command disabled", fixture.shell_response("netkit-check; id"))
        self.assertEqual(fixture.shell_response(""), b"\r\nfixture> ")

    def test_generated_config_and_private_material_are_only_under_private_tmp_dir(self) -> None:
        output = fixture.create_fixture(None, include_rotated_host_key=False)
        directory = Path(output["fixtureDir"])
        try:
            self.assertEqual(directory.parent, Path("/tmp"))
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o700)
            config_file = Path(output["configPath"])
            self.assertEqual(stat.S_IMODE(config_file.stat().st_mode), 0o600)
            config = json.loads(config_file.read_text(encoding="utf-8"))
            self.assertEqual(config["server"]["bindAddress"], "127.0.0.1")
            self.assertEqual(config["client"]["profiles"][0]["host"], "10.0.2.2")
            self.assertEqual(config["client"]["profiles"][0]["port"], 22222)
            self.assertEqual(config["client"]["profiles"][2]["authentication"], "KEY")
            self.assertTrue(Path(config["client"]["clientPrivateKeyPath"]).is_relative_to(directory))
            self.assertTrue(Path(config["server"]["hostKeyPath"]).is_relative_to(directory))
            for key_path in (Path(output["hostKeyPath"]), Path(output["clientKeyPath"])):
                self.assertEqual(stat.S_IMODE(key_path.stat().st_mode), 0o600)
                self.assertTrue(key_path.is_relative_to(directory))
            password = config["client"]["password"]
            self.assertTrue(password)
            self.assertNotIn("password", output)
            self.assertNotIn("clientPublicKey", output)
        finally:
            shutil.rmtree(directory)

    def test_session_cap_closes_excess_connection(self) -> None:
        class Request:
            closed = False

            def close(self) -> None:
                self.closed = True

            def shutdown(self, _how: int) -> None:
                raise OSError("test request is not a network socket")

        server = object.__new__(fixture.BannerServer)
        server._session_slots = threading.BoundedSemaphore(fixture.MAX_ACTIVE_SESSIONS)
        server.daemon_threads = True
        request = Request()
        try:
            for _ in range(fixture.MAX_ACTIVE_SESSIONS):
                self.assertTrue(server._session_slots.acquire(blocking=False))
            server.process_request(request, ("127.0.0.1", 12345))
            self.assertTrue(request.closed)
            self.assertFalse(server._session_slots.acquire(blocking=False))
        finally:
            for _ in range(fixture.MAX_ACTIVE_SESSIONS):
                server._session_slots.release()

    def test_session_slot_released_after_handler_exception(self) -> None:
        finished = threading.Event()

        class Request:
            def close(self) -> None:
                pass

            def shutdown(self, _how: int) -> None:
                raise OSError("test request is not a network socket")

        class RaisingHandler(fixture.socketserver.BaseRequestHandler):
            def handle(self) -> None:
                raise RuntimeError("synthetic test exception")

        class TestServer(fixture.BannerServer):
            def handle_error(self, request: Any, client_address: Any) -> None:
                super().handle_error(request, client_address)

            def process_request_thread(self, request: Any, client_address: Any) -> None:
                try:
                    super().process_request_thread(request, client_address)
                finally:
                    finished.set()

        server = object.__new__(TestServer)
        server._session_slots = threading.BoundedSemaphore(fixture.MAX_ACTIVE_SESSIONS)
        server.daemon_threads = True
        server.process_request(Request(), ("127.0.0.1", 12345))
        self.assertTrue(finished.wait(timeout=2))
        self.assertTrue(server._session_slots.acquire(blocking=False))
        server._session_slots.release()


if __name__ == "__main__":
    unittest.main()
