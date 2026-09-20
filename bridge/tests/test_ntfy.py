from __future__ import annotations

import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from mission_leben_bridge.ntfy import NtfyError, NtfyManager, _base_url, _usernames


class ProvisioningNtfy(NtfyManager):
    def __init__(self) -> None:
        self.public_base_url = "https://push.example.invalid"
        self.internal_base_url = "http://ntfy:2586"
        self.auth_file = Path("/unused/user.db")
        self.binary = Path("/unused/ntfy")
        self._lock = threading.Lock()
        self.calls: list[tuple[str, ...]] = []

    def _run(self, *arguments: str, extra_env: dict[str, str] | None = None) -> str:
        self.calls.append(arguments)
        if arguments[:2] == ("token", "add"):
            username = arguments[-1]
            fill = "r" if username.startswith("mlr_") else "w"
            return f"token tk_{fill * 29} created"
        return "ok"


class PublishHandler(BaseHTTPRequestHandler):
    request_path = ""
    authorization = ""
    payload: dict[str, str] = {}

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        type(self).request_path = self.path
        type(self).authorization = self.headers.get("Authorization", "")
        length = int(self.headers.get("Content-Length", "0"))
        type(self).payload = json.loads(self.rfile.read(length))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"id":"message-id"}')

    def log_message(self, format: str, *args: object) -> None:
        return


class NtfyTest(unittest.TestCase):
    def test_device_usernames_are_stable_and_do_not_disclose_device_id(self) -> None:
        first = _usernames("11111111-1111-1111-1111-111111111111")
        second = _usernames("11111111-1111-1111-1111-111111111111")

        self.assertEqual(first, second)
        self.assertTrue(first[0].startswith("mlr_"))
        self.assertTrue(first[1].startswith("mlw_"))
        self.assertNotIn("11111111", first[0])

    def test_public_base_url_requires_https_and_no_credentials(self) -> None:
        self.assertEqual(
            "https://push.example.invalid/base",
            _base_url("https://push.example.invalid/base/", require_https=True),
        )
        with self.assertRaises(NtfyError):
            _base_url("http://push.example.invalid", require_https=True)
        with self.assertRaises(NtfyError):
            _base_url("https://user:secret@push.example.invalid", require_https=True)

    def test_provision_creates_separate_read_and_write_identities(self) -> None:
        manager = ProvisioningNtfy()

        credentials = manager.provision("device-1")

        self.assertEqual("tk_" + "r" * 29, credentials.subscribe_token)
        self.assertEqual("tk_" + "w" * 29, credentials.publish_token)
        self.assertNotEqual(credentials.reader_username, credentials.writer_username)
        self.assertIn(
            ("access", credentials.reader_username, credentials.topic, "read-only"),
            manager.calls,
        )
        self.assertIn(
            ("access", credentials.writer_username, credentials.topic, "write-only"),
            manager.calls,
        )

    def test_publish_uses_writer_token_and_only_opaque_payload(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), PublishHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                auth_file = root / "user.db"
                binary = root / "ntfy"
                auth_file.write_bytes(b"db")
                binary.write_bytes(b"binary")
                manager = NtfyManager(
                    "https://push.example.invalid",
                    f"http://127.0.0.1:{server.server_port}",
                    auth_file,
                    binary,
                )
                manager.send(
                    "ml-device-topic-0123456789",
                    "tk_" + "w" * 29,
                    {"action": "open_mail", "event_id": "opaque-event-1234"},
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual("/ml-device-topic-0123456789", PublishHandler.request_path)
        self.assertEqual("Bearer tk_" + "w" * 29, PublishHandler.authorization)
        self.assertEqual(
            {"action": "open_mail", "event_id": "opaque-event-1234"},
            PublishHandler.payload,
        )


if __name__ == "__main__":
    unittest.main()
