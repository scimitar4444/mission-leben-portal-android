from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from typing import Any

from .security import sign_source_request


class BridgeSourceClient:
    def __init__(self, base_url: str, source: str, secret: bytes, timeout: float = 12.0):
        self.endpoint = base_url.rstrip("/") + "/internal/v1/events"
        self.source = source
        self.secret = secret
        self.timeout = timeout

    def publish(self, payload: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        timestamp = str(int(time.time()))
        request = urllib.request.Request(
            self.endpoint,
            data=body,
            headers={
                "Content-Type": "application/json",
                "X-ML-Source": self.source,
                "X-ML-Timestamp": timestamp,
                "X-ML-Signature": sign_source_request(self.secret, self.source, timestamp, body),
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            message = error.read(4096).decode(errors="replace")
            raise RuntimeError(f"bridge rejected event with HTTP {error.code}: {message}") from error
