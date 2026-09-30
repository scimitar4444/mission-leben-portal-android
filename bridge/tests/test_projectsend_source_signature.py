from __future__ import annotations

import time
import unittest
from types import SimpleNamespace

from mission_leben_bridge.http_api import BridgeRequestHandler
from mission_leben_bridge.security import sign_source_request
from mission_leben_bridge.service import ApiError


class ProjectSendSourceSignatureTest(unittest.TestCase):
    def test_projectsend_requires_its_own_key(self) -> None:
        body = b'{"event_type":"open_documents"}'
        timestamp = str(int(time.time()))
        old_key = b"o" * 32
        projectsend_key = b"p" * 32
        handler = SimpleNamespace(
            server=SimpleNamespace(settings=SimpleNamespace(
                internal_hmac_secret=old_key,
                projectsend_hmac_secret=projectsend_key,
            )),
            headers={
                "X-ML-Source": "projectsend",
                "X-ML-Timestamp": timestamp,
                "X-ML-Signature": sign_source_request(projectsend_key, "projectsend", timestamp, body),
            },
        )
        self.assertEqual("projectsend", BridgeRequestHandler._verify_internal(handler, body))
        handler.headers["X-ML-Signature"] = sign_source_request(
            old_key, "projectsend", timestamp, body
        )
        with self.assertRaises(ApiError):
            BridgeRequestHandler._verify_internal(handler, body)
        handler.server.settings.projectsend_hmac_secret = None
        with self.assertRaises(ApiError):
            BridgeRequestHandler._verify_internal(handler, body)


if __name__ == "__main__":
    unittest.main()
