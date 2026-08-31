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
from datetime import datetime, timedelta, timezone
from pathlib import Path

PINS_KEY = "TLS_PUBLIC_KEY_PINS"
ENFORCED_CONFIGURATION = "release"
DEFAULT_EXPIRY_WARNING_DAYS = 60
DEFAULT_EXPIRY_FAIL_DAYS = 14


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


def parse_certificate_expiry(raw: str) -> datetime:
    """Parses YYYY-MM-DD or ISO-8601 timestamps into UTC-aware datetimes."""
    value = raw.strip()
    if not value:
        raise ValueError("certificate expiry must not be empty")

    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.strptime(value, "%Y-%m-%d")
        except ValueError as exc:
            raise ValueError(
                "certificate expiry must be ISO-8601 or YYYY-MM-DD, got "
                f"{raw!r}"
            ) from exc

    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    else:
        parsed = parsed.astimezone(timezone.utc)
    return parsed


def check_certificate_expiry(
    certificate_expiry: str | None,
    *,
    warning_days: int = DEFAULT_EXPIRY_WARNING_DAYS,
    fail_days: int = DEFAULT_EXPIRY_FAIL_DAYS,
) -> tuple[int, str | None]:
    """Returns (exit_code, message) for the configured certificate expiry date.

    A warning is emitted when the remaining lifetime falls inside the warning window.
    If the cert is within the fail window, the script exits non-zero to force rotation.
    """
    if not certificate_expiry:
        return 0, None

    try:
        expires_at = parse_certificate_expiry(certificate_expiry)
    except ValueError as exc:
        return 1, f"::error::Invalid certificate expiry '{certificate_expiry}': {exc}"

    now = datetime.now(timezone.utc)
    remaining = expires_at - now
    if remaining <= timedelta(0):
        return 1, (
            "::error::Configured certificate expiry is in the past: "
            f"{expires_at.isoformat()} (today is {now.isoformat()}). "
            "Rotate the certificate before shipping."
        )

    if remaining <= timedelta(days=warning_days):
        message = (
            f"::warning::Current certificate expires on {expires_at.date().isoformat()} "
            f"({remaining.days} days remaining). Rotate the certificate before it falls "
            f"within {warning_days} days."
        )
        if remaining <= timedelta(days=fail_days):
            return 1, message.replace("::warning::", "::error::")
        return 0, message

    return 0, None


def run(
    configuration: str,
    targets: list[tuple[str, str]],
    *,
    certificate_expiry: str | None = None,
    expiry_warning_days: int = DEFAULT_EXPIRY_WARNING_DAYS,
    expiry_fail_days: int = DEFAULT_EXPIRY_FAIL_DAYS,
) -> int:
    if configuration.strip().lower() != ENFORCED_CONFIGURATION:
        print(
            f"Skipping {PINS_KEY} check for '{configuration}' configuration — "
            f"pinning is only enforced for Release builds (empty pins "
            f"intentionally disable pinning for local dev, per PinningDelegate)."
        )
        return 0

    failures = [msg for name, path in targets for msg in [check_target(name, path)] if msg]

    expiry_code, expiry_message = check_certificate_expiry(
        certificate_expiry,
        warning_days=expiry_warning_days,
        fail_days=expiry_fail_days,
    )
    if expiry_message:
        print(expiry_message)

    if failures:
        print("TLS certificate pinning check FAILED for Release configuration:")
        for msg in failures:
            print(f"::error::{msg}")
        return 1

    if expiry_code != 0:
        return expiry_code

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
    parser.add_argument(
        "--certificate-expiry",
        default=None,
        help="Documented certificate expiry date in YYYY-MM-DD or ISO-8601 format. "
             "If set, the script warns when the cert is within the configured lead time.",
    )
    parser.add_argument(
        "--expiry-warning-days",
        type=int,
        default=DEFAULT_EXPIRY_WARNING_DAYS,
        help="Warn when the certificate has fewer than this many days remaining.",
    )
    parser.add_argument(
        "--expiry-fail-days",
        type=int,
        default=DEFAULT_EXPIRY_FAIL_DAYS,
        help="Fail the build when the certificate has fewer than this many days remaining.",
    )
    args = parser.parse_args(argv)
    return run(
        args.configuration,
        args.targets,
        certificate_expiry=args.certificate_expiry,
        expiry_warning_days=args.expiry_warning_days,
        expiry_fail_days=args.expiry_fail_days,
    )


if __name__ == "__main__":
    sys.exit(main())
