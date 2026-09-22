"""Bounded, read-only search over an Authentik-owned, short-lived projection."""
from __future__ import annotations

import json
import threading
import time
import unicodedata
from pathlib import Path


class DirectoryUnavailable(Exception):
    pass


class DirectoryDenied(Exception):
    pass


def search_key(value: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", value.casefold()) if not unicodedata.combining(c))


class EmployeeDirectory:
    MAX_AGE_SECONDS = 420
    PAGE_SIZE = 40

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._stamp = None
        self._data = None

    def _load(self):
        try:
            with self._lock:
                stat = self.path.stat()
                stamp = (stat.st_ino, stat.st_mtime_ns, stat.st_size)
                if stamp != self._stamp:
                    if stat.st_size > 8_000_000:
                        raise ValueError("oversized directory")
                    data = json.loads(self.path.read_text(encoding="utf-8"))
                    if data.get("version") != 1 or not isinstance(data.get("entries"), list):
                        raise ValueError("unsupported directory")
                    if any(not isinstance(data.get(key), dict) for key in ("facilities", "audience", "devices")):
                        raise ValueError("invalid directory")
                    # Precompute a search index once per snapshot, not per keystroke.
                    data["entries"] = sorted(data["entries"], key=lambda e: (search_key(e["name"]), e["id"]))
                    for entry in data["entries"]:
                        entry["_search"] = search_key(" ".join([
                            entry["name"], entry["email"], entry["department"], entry["job_title"],
                            *[data["facilities"][f] for f in entry["facilities"]],
                        ]))
                    self._data, self._stamp = data, stamp
                data = self._data
                if not -30 <= time.time() - data["generated_at"] <= self.MAX_AGE_SECONDS:
                    raise ValueError("stale directory")
                return data
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise DirectoryUnavailable("employee directory is temporarily unavailable") from error

    def search(self, subject: str, device_id: str, query: str = "", mine: bool = False, offset: int = 0):
        data = self._load()
        actor = data["audience"].get(subject)
        device = data["devices"].get(device_id)
        if not actor or not device:
            raise DirectoryDenied("employee or device is not authorized")
        own = set(actor["facilities"])
        if device.get("mode") == "personal":
            allowed = actor["kind"] == "person" and device.get("subject") == subject
        elif device.get("mode") == "shared":
            allowed = actor["kind"] in {"person", "shared"} and device.get("facility") in own
            # A shared tablet represents its single bound house, not all houses
            # a visiting employee may belong to.
            own = {device["facility"]}
        else:
            allowed = False
        if not allowed:
            raise DirectoryDenied("employee and device binding does not match")
        if len(query) > 100 or offset < 0 or offset > 100_000:
            raise ValueError("invalid directory query")
        words = search_key(query).split()
        matches = [entry for entry in data["entries"]
                   if (not mine or own.intersection(entry["facilities"]))
                   and all(word in entry["_search"] for word in words)]
        results = [{
            key: entry[key] for key in ("id", "name", "email", "phone", "mobile", "job_title", "department")
        } | {"facilities": [data["facilities"][f] for f in entry["facilities"]]}
            for entry in matches[offset:offset + self.PAGE_SIZE]]
        next_offset = offset + len(results)
        return {"results": results, "total": len(matches),
                "next_offset": next_offset if next_offset < len(matches) else None,
                "my_facilities": [data["facilities"][f] for f in sorted(own)],
                "updated_at": data["generated_at"]}
