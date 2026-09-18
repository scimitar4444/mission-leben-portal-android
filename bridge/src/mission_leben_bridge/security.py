from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.exceptions import InvalidSignature


def base64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def base64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical_device_request(
    method: str,
    path: str,
    device_id: str,
    key_id: str,
    timestamp: str,
    nonce: str,
    body: bytes = b"",
) -> bytes:
    return "\n".join(
        (
            "ML-DEVICE-V1",
            method.upper(),
            path,
            device_id,
            key_id,
            timestamp,
            nonce,
            sha256_hex(body),
        )
    ).encode()


def public_key_from_jwk(jwk: dict[str, str]) -> ec.EllipticCurvePublicKey:
    if jwk.get("kty") != "EC" or jwk.get("crv") != "P-256":
        raise ValueError("only P-256 EC device keys are supported")
    x = int.from_bytes(base64url_decode(jwk["x"]), "big")
    y = int.from_bytes(base64url_decode(jwk["y"]), "big")
    return ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()


def device_key_id(jwk: dict[str, str]) -> str:
    encoded = public_key_from_jwk(jwk).public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(encoded).digest()[:12].hex()


def verify_device_signature(jwk: dict[str, str], signature: str, canonical: bytes) -> bool:
    try:
        public_key_from_jwk(jwk).verify(
            base64url_decode(signature),
            canonical,
            ec.ECDSA(hashes.SHA256()),
        )
        return True
    except (InvalidSignature, ValueError, KeyError):
        return False


def canonical_source_request(source: str, timestamp: str, body: bytes) -> bytes:
    return "\n".join(("ML-SOURCE-V1", source, timestamp, sha256_hex(body))).encode()


def sign_source_request(secret: bytes, source: str, timestamp: str, body: bytes) -> str:
    return base64url_encode(hmac.new(secret, canonical_source_request(source, timestamp, body), hashlib.sha256).digest())


def verify_source_request(
    secret: bytes,
    source: str,
    timestamp: str,
    body: bytes,
    signature: str,
    now: int | None = None,
) -> bool:
    try:
        request_time = int(timestamp)
    except ValueError:
        return False
    if abs((now or int(time.time())) - request_time) > 120:
        return False
    expected = sign_source_request(secret, source, timestamp, body)
    return hmac.compare_digest(expected, signature)


@dataclass(frozen=True)
class SecretBox:
    key: bytes

    def encrypt(self, value: str) -> str:
        nonce = __import__("os").urandom(12)
        ciphertext = AESGCM(self.key).encrypt(nonce, value.encode(), b"mission-leben-bridge-v1")
        return base64url_encode(nonce + ciphertext)

    def decrypt(self, value: str) -> str:
        raw = base64url_decode(value)
        return AESGCM(self.key).decrypt(raw[:12], raw[12:], b"mission-leben-bridge-v1").decode()


def compact_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
