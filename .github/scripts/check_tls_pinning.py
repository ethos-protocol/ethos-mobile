#!/usr/bin/env python3
"""Fails CI if a Release build's Info.plist lacks TLS certificate pinning.

CertificatePinning.swift's PinningDelegate reads its pin set from each
target's own Info.plist (Bundle.main resolves independently per target — an
app extension does NOT inherit its host app's Info.plist) under the key
TLS_PUBLIC_KEY_PINS. An absent or empty array is treated as "pinning
disabled" — intentionally, so local-dev builds can hit a different host
without a live certificate to pin against. That same fallback means a
Release build silently ships with pinning off if the key is ever removed,
or a new target/config is added without it, with no signal to anyone.

This script re-reads a target's Info.plist the same way Bundle.main does
(plistlib, not string-matching) and fails if TLS_PUBLIC_KEY_PINS is missing,
empty, or not an array of non-empty strings. It is a no-op for any
--configuration other than Release, matching PinningDelegate's documented
"empty pins disables pinning for local dev" design.

Usage:
    check_tls_pinning.py --configuration Release \\
        --target EthosProtocol:EthosProtocol/Info.plist \\
        --target TTLWidget:TTLWidget/Info.plist
"""
from __future__ import annotations

import argparse
import plistlib
import sys
from pathlib import Path

PINS_KEY = "TLS_PUBLIC_KEY_PINS"
ENFORCED_CONFIGURATION = "release"


def parse_target(raw: str) -> tuple[str, str]:
    """Parses a --target NAME:PATH argument."""
    name, sep, path = raw.partition(":")
    if not sep or not name or not path:
        raise argparse.ArgumentTypeError(f"--target must be NAME:PATH, got {raw!r}")
    return name, path


def check_target(name: str, plist_path: str) -> str | None:
    """Returns a failure message for `name`, or None if its pins are configured."""
    path = Path(plist_path)
    if not path.is_file():
        return f"{name}: Info.plist not found at {plist_path}"

    try:
        with path.open("rb") as f:
            plist = plistlib.load(f)
    except Exception as exc:  # plistlib raises several exception types
        return f"{name}: could not parse {plist_path} as a plist ({exc})"

    pins = plist.get(PINS_KEY)
    if not pins:
        return (
            f"{name}: {PINS_KEY} is missing or empty in {plist_path}. "
            f"Release builds must ship at least one pinned SPKI hash (see "
            f"Sources/Services/CertificatePinning.swift) or certificate "
            f"pinning is silently disabled for this target."
        )
    if not isinstance(pins, list) or not all(isinstance(p, str) and p for p in pins):
        return (
            f"{name}: {PINS_KEY} in {plist_path} must be an array of "
            f"non-empty strings (Base64-encoded SPKI SHA-256 hashes)."
        )
    return None


def run(configuration: str, targets: list[tuple[str, str]]) -> int:
    if configuration.strip().lower() != ENFORCED_CONFIGURATION:
        print(
            f"Skipping {PINS_KEY} check for '{configuration}' configuration — "
            f"pinning is only enforced for Release builds (empty pins "
            f"intentionally disable pinning for local dev, per PinningDelegate)."
        )
        return 0

    failures = [msg for name, path in targets for msg in [check_target(name, path)] if msg]

    if failures:
        print("TLS certificate pinning check FAILED for Release configuration:")
        for msg in failures:
            print(f"::error::{msg}")
        return 1

    names = ", ".join(name for name, _ in targets)
    print(f"TLS certificate pinning check passed for: {names}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--configuration",
        required=True,
        help="Build configuration being checked (e.g. Debug, Release). Only "
        "'Release' is enforced; every other value is skipped.",
    )
    parser.add_argument(
        "--target",
        dest="targets",
        action="append",
        type=parse_target,
        required=True,
        metavar="NAME:PATH",
        help="Target name and path to its Info.plist. May be repeated.",
    )
    args = parser.parse_args(argv)
    return run(args.configuration, args.targets)


if __name__ == "__main__":
    sys.exit(main())
