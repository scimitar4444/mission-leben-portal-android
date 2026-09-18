#!/usr/bin/env python3
"""Render a root-only enrollment QR image without printing the token.

The Authentik EnrollmentToken is read from stdin. Install the optional helper
dependency with ``python -m pip install 'qrcode[pil]'`` in a temporary venv.
"""

import argparse
import os
from pathlib import Path
from urllib.parse import quote

import qrcode


parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()

token = input().strip()
if not 20 <= len(token) <= 512 or any(character.isspace() for character in token):
    raise SystemExit("stdin does not contain a valid Authentik enrollment token")

uri = "de.missionleben.portal://enroll?token=" + quote(token, safe="")
target = args.output.resolve()
target.parent.mkdir(parents=True, exist_ok=True)
temporary = target.with_suffix(target.suffix + ".tmp")

image = qrcode.make(uri)
image.save(temporary, format="PNG")
os.chmod(temporary, 0o600)
os.replace(temporary, target)
print(target)
