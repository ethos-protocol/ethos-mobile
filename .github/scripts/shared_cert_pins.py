#!/usr/bin/env python3
"""Single definition of the shared certificate pin file schema (issue #737).

``shared/cert-pins.json`` is the canonical pin source. Its schema is defined here
and nowhere else, so the generator, the verifier and the monitor cannot disagree
about which keys exist.

Schema::

    {
      "pins": ["<current base64 sha256 spki>", "<backup base64 sha256 spki>"],
      "release_signing_sha256": "AA:BB:...:FF"   # 32 colon-separated hex bytes
    }

``pins[0]`` is the current pin and ``pins[1]`` is the backup pin. The two must
differ, because the backup exists to allow rotation.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

import re
import sys

PIN_COUNT = 2
CURRENT, BACKUP = 0, 1
FINGERPRINT_PATTERN = re.compile(r"^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}$")


class SharedPinSchemaError(ValueError):
    """Raised when the shared pin file does not match the schema."""


def parse_shared_pins(raw_text: str, source: str = "cert-pins.json") -> Dict[str, object]:
    """Parse and validate the shared pin file text.

    Returns a dict with ``current``, ``backup`` and ``release_signing_sha256``.
    """
    try:
        raw = json.loads(raw_text)
    except json.JSONDecodeError as exc:
        raise SharedPinSchemaError(f"Invalid JSON in {source}: {exc}") from exc

    if not isinstance(raw, dict):
        raise SharedPinSchemaError(f"{source} must contain a JSON object")

    pins = raw.get("pins")
    if not isinstance(pins, list) or len(pins) != PIN_COUNT:
        raise SharedPinSchemaError(
            f"{source}: 'pins' must be an array of exactly {PIN_COUNT} entries "
            "(current, backup)"
        )
    cleaned: List[str] = []
    for index, value in enumerate(pins):
        name = ("current", "backup")[index]
        if not isinstance(value, str) or not value.strip():
            raise SharedPinSchemaError(f"{source}: missing or empty '{name}' pin")
        cleaned.append(value.strip())

    if cleaned[CURRENT] == cleaned[BACKUP]:
        raise SharedPinSchemaError(
            f"{source}: 'current' and 'backup' pins must differ "
            "(backup exists to allow rotation)"
        )

    fingerprint = raw.get("release_signing_sha256")
    if not isinstance(fingerprint, str) or not fingerprint.strip():
        raise SharedPinSchemaError(f"{source}: missing 'release_signing_sha256'")
    fingerprint = fingerprint.strip()
    validate_fingerprint(fingerprint, source)

    return {
        "current": cleaned[CURRENT],
        "backup": cleaned[BACKUP],
        "release_signing_sha256": fingerprint,
    }


def validate_fingerprint(fingerprint: str, source: str = "cert-pins.json") -> None:
    """Reject a malformed or all-zero release signing fingerprint (issue #739).

    An all-zero value can never match a real APK signature, and a check that compares
    against it fails silently, so it is refused here rather than compared.
    """
    if not FINGERPRINT_PATTERN.match(fingerprint):
        raise SharedPinSchemaError(
            f"{source}: 'release_signing_sha256' must be 32 colon-separated hex bytes")
    if set(fingerprint.replace(":", "")) == {"0"}:
        raise SharedPinSchemaError(
            f"{source}: 'release_signing_sha256' is all zeros (placeholder). "
            "Set the Play App Signing SHA-256 before this check can pass.")


def load_shared_pins(path: Path) -> Dict[str, object]:
    """Read ``path`` and validate it against the schema."""
    if not path.is_file():
        raise SharedPinSchemaError(f"Shared pin file not found: {path}")
    return parse_shared_pins(path.read_text(encoding="utf-8"), source=str(path))


def main(argv: List[str]) -> int:
    """CLI used by shell gates: ``shared_cert_pins.py <cert-pins.json>``. Exit 1 on error."""
    if len(argv) != 1:
        print("usage: shared_cert_pins.py <cert-pins.json>", file=sys.stderr)
        return 2
    try:
        load_shared_pins(Path(argv[0]))
    except SharedPinSchemaError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
