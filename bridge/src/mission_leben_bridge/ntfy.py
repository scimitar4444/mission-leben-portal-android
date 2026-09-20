from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path


TOKEN = re.compile(r"^tk_[a-z0-9]{29}$")
TOPIC = re.compile(r"^[A-Za-z0-9_-]{16,80}$")
USERNAME = re.compile(r"^[a-z0-9_-]{4,32}$")


class NtfyError(Exception):
    def __init__(
        self,
        message: str,
        *,
        permanent_registration_failure: bool = False,
        missing_user: bool = False,
    ):
        super().__init__(message)
        self.permanent_registration_failure = permanent_registration_failure
        self.missing_user = missing_user


@dataclass(frozen=True)
class NtfyCredentials:
    public_base_url: str
    topic: str
    subscribe_token: str
    publish_token: str
    reader_username: str
    writer_username: str


class NullNtfyManager:
    configured = False

    def provision(self, device_id: str) -> NtfyCredentials:
        raise NtfyError("ntfy is not configured")

    def send(self, topic: str, publish_token: str, data: dict[str, str]) -> None:
        raise NtfyError("ntfy is not configured")

    def revoke(self, reader_username: str, writer_username: str) -> None:
        return


class NtfyManager:
    configured = True

    def __init__(
        self,
        public_base_url: str,
        internal_base_url: str,
        auth_file: Path,
        binary: Path = Path("/usr/local/bin/ntfy"),
    ):
        self.public_base_url = _base_url(public_base_url, require_https=True)
        self.internal_base_url = _base_url(internal_base_url, require_https=False)
        self.auth_file = auth_file
        self.binary = binary
        self._lock = threading.Lock()
        if not self.auth_file.is_file():
            raise NtfyError("ntfy auth database is not ready")
        if not self.binary.is_file():
            raise NtfyError("ntfy administration binary is unavailable")

    def provision(self, device_id: str) -> NtfyCredentials:
        reader, writer = _usernames(device_id)
        topic = "ml-" + secrets.token_urlsafe(24)
        if not TOPIC.fullmatch(topic):
            raise NtfyError("generated ntfy topic is invalid")
        with self._lock:
            self._remove_user(reader, ignore_missing=True)
            self._remove_user(writer, ignore_missing=True)
            try:
                self._add_user(reader)
                self._add_user(writer)
                self._run("access", reader, topic, "read-only")
                self._run("access", writer, topic, "write-only")
                subscribe_token = self._create_token(reader, "mission-leben-app")
                publish_token = self._create_token(writer, "mission-leben-bridge")
            except Exception:
                self._remove_user(reader, ignore_missing=True)
                self._remove_user(writer, ignore_missing=True)
                raise
        return NtfyCredentials(
            public_base_url=self.public_base_url,
            topic=topic,
            subscribe_token=subscribe_token,
            publish_token=publish_token,
            reader_username=reader,
            writer_username=writer,
        )

    def send(self, topic: str, publish_token: str, data: dict[str, str]) -> None:
        if not TOPIC.fullmatch(topic) or not TOKEN.fullmatch(publish_token):
            raise NtfyError("stored ntfy registration is invalid", permanent_registration_failure=True)
        body = json.dumps(data, separators=(",", ":"), sort_keys=True).encode()
        request = urllib.request.Request(
            f"{self.internal_base_url}/{urllib.parse.quote(topic, safe='-_')}",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {publish_token}",
                "Cache": "yes",
                "Content-Type": "text/plain; charset=utf-8",
                "User-Agent": "mission-leben-bridge/ntfy",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                if response.status not in {200, 201}:  # pragma: no cover - urllib raises first
                    raise NtfyError(f"ntfy returned HTTP {response.status}")
                response.read(4096)
        except urllib.error.HTTPError as error:
            permanent = error.code in {401, 403}
            raise NtfyError(
                f"ntfy returned HTTP {error.code}",
                permanent_registration_failure=permanent,
            ) from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise NtfyError("ntfy is temporarily unreachable") from error

    def revoke(self, reader_username: str, writer_username: str) -> None:
        with self._lock:
            self._remove_user(reader_username, ignore_missing=True)
            self._remove_user(writer_username, ignore_missing=True)

    def _add_user(self, username: str) -> None:
        password = secrets.token_urlsafe(32)
        self._run("user", "add", username, extra_env={"NTFY_PASSWORD": password})

    def _create_token(self, username: str, label: str) -> str:
        output = self._run("token", "add", f"--label={label}", username)
        match = re.search(r"\btk_[a-z0-9]{29}\b", output)
        if match is None:
            raise NtfyError("ntfy did not return a valid access token")
        return match.group(0)

    def _remove_user(self, username: str, *, ignore_missing: bool) -> None:
        if not USERNAME.fullmatch(username):
            if ignore_missing:
                return
            raise NtfyError("ntfy username is invalid")
        try:
            self._run("user", "remove", username)
        except NtfyError as error:
            if not (ignore_missing and error.missing_user):
                raise

    def _run(self, *arguments: str, extra_env: dict[str, str] | None = None) -> str:
        environment = os.environ.copy()
        environment["NTFY_AUTH_FILE"] = str(self.auth_file)
        environment.update(extra_env or {})
        try:
            result = subprocess.run(
                (str(self.binary), *arguments),
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
                env=environment,
            )
        except subprocess.CalledProcessError as error:
            output = f"{error.stdout or ''}\n{error.stderr or ''}".lower()
            raise NtfyError(
                "ntfy account operation failed",
                missing_user="does not exist" in output,
            ) from error
        except (OSError, subprocess.TimeoutExpired) as error:
            raise NtfyError("ntfy account operation failed") from error
        return (result.stdout + "\n" + result.stderr).strip()


def _usernames(device_id: str) -> tuple[str, str]:
    suffix = hashlib.sha256(device_id.encode()).hexdigest()[:24]
    return f"mlr_{suffix}", f"mlw_{suffix}"


def _base_url(value: str, *, require_https: bool) -> str:
    parsed = urllib.parse.urlsplit(value.strip().rstrip("/"))
    allowed_schemes = {"https"} if require_https else {"http", "https"}
    if parsed.scheme not in allowed_schemes or not parsed.hostname or parsed.query or parsed.fragment:
        raise NtfyError("ntfy base URL is invalid")
    if parsed.username or parsed.password:
        raise NtfyError("ntfy base URL must not contain credentials")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))
