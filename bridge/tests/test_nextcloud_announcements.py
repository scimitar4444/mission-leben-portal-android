from __future__ import annotations

import base64
import hashlib
import hmac
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mission_leben_bridge.nextcloud_announcements import (
    ANNOUNCEMENTS_PATH,
    NextcloudAnnouncementClient,
)


class AnnouncementHandler(BaseHTTPRequestHandler):
    secret = b"n" * 48
    received: dict[str, object] = {}

    def do_POST(self) -> None:  # noqa: N802
        body = self.rfile.read(int(self.headers["Content-Length"]))
        timestamp = self.headers["X-ML-Timestamp"]
        nonce = self.headers["X-ML-Nonce"]
        canonical = "\n".join(
            ("POST", ANNOUNCEMENTS_PATH, timestamp, nonce, hashlib.sha256(body).hexdigest())
        ).encode()
        expected = base64.urlsafe_b64encode(
            hmac.new(self.secret, canonical, hashlib.sha256).digest()
        ).rstrip(b"=").decode()
        if self.path != ANNOUNCEMENTS_PATH or not hmac.compare_digest(
            expected, self.headers["X-ML-Signature"]
        ):
            self.send_response(401)
            self.end_headers()
            return
        self.__class__.received = json.loads(body)
        response = json.dumps(
            {
                "announcements": [
                    {
                        "id": 7,
                        "subject": "Maintenance",
                        "message": "Sigma is unavailable until 18:00.",
                        "author": "IT",
                        "time": 1_700_000_000,
                        "delete_time": 0,
                    }
                ]
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, format_string: str, *args: object) -> None:
        return


class NextcloudAnnouncementClientTest(unittest.TestCase):
    def test_request_is_signed_and_contains_only_user_lookup_values(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), AnnouncementHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        # Keep production's HTTPS configuration requirement while using a local HTTP test server.
        class LocalClient(NextcloudAnnouncementClient):
            @property
            def configured(self) -> bool:
                return True

        client = LocalClient(
            f"http://127.0.0.1:{server.server_port}", AnnouncementHandler.secret
        )
        try:
            values = client.fetch(user_id="stable-id", email="user@example.invalid")
            self.assertEqual("Maintenance", values[0]["subject"])
            self.assertEqual(
                {"user_id": "stable-id", "email": "user@example.invalid"},
                AnnouncementHandler.received,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
