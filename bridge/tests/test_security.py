from __future__ import annotations

import time
import unittest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from mission_leben_bridge.security import (
    base64url_encode,
    canonical_device_request,
    device_key_id,
    sign_source_request,
    verify_device_signature,
    verify_source_request,
)


class SecurityTest(unittest.TestCase):
    def test_android_device_signature_round_trip(self) -> None:
        private_key = ec.generate_private_key(ec.SECP256R1())
        public = private_key.public_key().public_numbers()
        jwk = {
            "kty": "EC",
            "crv": "P-256",
            "x": base64url_encode(public.x.to_bytes(32, "big")),
            "y": base64url_encode(public.y.to_bytes(32, "big")),
        }
        jwk["kid"] = device_key_id(jwk)
        canonical = canonical_device_request(
            "GET",
            "/v1/notifications/01JABCDEF0123456789XYZABCD",
            "20d25a03-0287-41af-afd0-74020546d7c1",
            jwk["kid"],
            "1789682400",
            "0123456789abcdef",
        )
        signature = base64url_encode(private_key.sign(canonical, ec.ECDSA(hashes.SHA256())))
        self.assertTrue(verify_device_signature(jwk, signature, canonical))
        self.assertFalse(verify_device_signature(jwk, signature, canonical + b"!"))

    def test_source_signature_has_short_replay_window(self) -> None:
        secret = b"s" * 32
        body = b'{"event_type":"open_mail"}'
        timestamp = str(int(time.time()))
        signature = sign_source_request(secret, "zimbra", timestamp, body)
        self.assertTrue(verify_source_request(secret, "zimbra", timestamp, body, signature))
        self.assertFalse(verify_source_request(secret, "nextcloud", timestamp, body, signature))
        self.assertFalse(
            verify_source_request(secret, "zimbra", timestamp, body, signature, now=int(timestamp) + 121)
        )


if __name__ == "__main__":
    unittest.main()
