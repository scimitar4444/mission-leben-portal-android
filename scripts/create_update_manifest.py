#!/usr/bin/env python3
"""Create the public OTA manifest for one already signed release APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


PACKAGE_NAME = "de.missionleben.portal"
ASSET_NAME = "mission-leben-zentral.apk"
TAG_PATTERN = re.compile(r"^v([0-9]+(?:\.[0-9]+){1,3}(?:[-+][0-9A-Za-z.-]+)?)$")
REPOSITORY_PATTERN = re.compile(r"^[0-9A-Za-z_.-]+/[0-9A-Za-z_.-]+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", default="scimitar4444/mission-leben-portal-android")
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    tag_match = TAG_PATTERN.fullmatch(args.tag)
    if not tag_match:
        raise SystemExit("tag must use the form v1.2.3")
    if not REPOSITORY_PATTERN.fullmatch(args.repository):
        raise SystemExit("invalid GitHub repository")
    if not args.apk.is_file() or not args.metadata.is_file():
        raise SystemExit("APK or output metadata is missing")

    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    elements = metadata.get("elements") or []
    if len(elements) != 1:
        raise SystemExit("expected exactly one APK output")
    element = elements[0]
    version_code = int(element["versionCode"])
    version_name = str(element["versionName"])
    if version_name != tag_match.group(1):
        raise SystemExit(f"tag {args.tag} does not match APK version {version_name}")

    payload = {
        "schema": 1,
        "packageName": PACKAGE_NAME,
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": (
            f"https://github.com/{args.repository}/releases/download/"
            f"{args.tag}/{ASSET_NAME}"
        ),
        "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "sizeBytes": args.apk.stat().st_size,
    }
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
