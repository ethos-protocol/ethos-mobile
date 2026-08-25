#!/usr/bin/env python3
"""verify_cert_pins.py

#173 — Release-build gate against shipping placeholder certificate pins.

Reads the certificate-pin configuration that would actually be compiled into a
`release` build and fails if it is empty or looks like a placeholder, so a
forgotten pin surfaces in CI instead of as universal connection failures in
production.

The effective pin set is resolved the same way `CertificatePinner` resolves it
at runtime (see `android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt`):

  1. `BuildConfig.CERT_PINS` (comma-separated), supplied by the `ETHOS_CERT_PINS`
     environment variable / `ethos.certPins` Gradle property for release builds.
  2. When that is blank, the pin literals compiled into the source.

Only `release` is gated: `CertificatePinner` documents an empty pin set as
"pinning disabled", which is the intended configuration for debug builds pointed
at a local/dev host.

A pin set that was *explicitly configured* is always gated hard — a typo or a
placeholder in `ETHOS_CERT_PINS` must fail. `--warn-if-unconfigured` downgrades the
"nothing configured at all" case to a warning, for release builds that cannot reach
users anyway (the unsigned R8 smoke build CI runs on every PR).

Usage:
  verify_cert_pins.py --build-type release [--warn-if-unconfigured] \\
      --build-config app/build/generated/source/buildConfig/release/com/ethosprotocol/BuildConfig.java \\
      --source app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt

Exit codes:
  0 – configuration is a plausible real pin set (or the build type is not gated)
  1 – empty or placeholder pin configuration (details printed as ::error:: annotations)
"""

import argparse
import base64
import re
import sys

# A Base64-encoded SHA-256 digest: 43 payload characters plus one '=' pad.
PIN_PATTERN = re.compile(r"^[A-Za-z0-9+/]{43}=$")

CERT_PINS_PATTERN = re.compile(r'String\s+CERT_PINS\s*=\s*"([^"]*)"')
PINS_BLOCK = re.compile(
    r"(?:DEFAULT_PINS|PLACEHOLDER_PINS)[^=]*=\s*setOf\((.*?)\)", re.DOTALL)
QUOTED = re.compile(r'"([^"]*)"')
# Matches a string literal *or* a line comment, so comments can be stripped without
# mistaking the "//" inside a Base64 pin for the start of one.
STRING_OR_COMMENT = re.compile(r'"[^"\n]*"|//[^\n]*')


def error(message):
    print("::error::{}".format(message))


def warn(message):
    print("::warning::{}".format(message))


def read(path):
    """Returns the contents of `path`, or "" when it does not exist."""
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


def build_config_pins(path):
    """Returns the pins declared by BuildConfig.CERT_PINS, or [] when unset."""
    match = CERT_PINS_PATTERN.search(read(path))
    if not match:
        return []
    return [pin.strip() for pin in match.group(1).split(",") if pin.strip()]


def source_default_pins(path):
    """Returns the pin literals compiled into CertificatePinning.kt."""
    # Comments are stripped first: they contain both parentheses and quotes, either of
    # which would otherwise confuse the literal extraction below.
    source = STRING_OR_COMMENT.sub(
        lambda m: m.group(0) if m.group(0).startswith('"') else "", read(path))
    match = PINS_BLOCK.search(source)
    if not match:
        return []
    return [pin.strip() for pin in QUOTED.findall(match.group(1)) if pin.strip()]


def placeholder_reason(pin):
    """Returns why `pin` is unusable, or None when it looks like a real pin."""
    if "PLACEHOLDER" in pin.upper():
        return "contains a PLACEHOLDER sentinel"
    if not PIN_PATTERN.match(pin):
        return "is not a 44-character Base64 SHA-256 digest"
    body = pin.rstrip("=")
    if len(set(body)) == 1:
        return "is an all-repeated-character placeholder"
    try:
        decoded = base64.b64decode(pin, validate=True)
    except (ValueError, TypeError):
        return "is not valid Base64"
    if len(decoded) != 32:
        return "does not decode to a 32-byte SHA-256 digest"
    if len(set(decoded)) == 1:
        return "is an all-repeated-byte placeholder"
    return None


def verify(build_type, build_config, source, warn_if_unconfigured=False):
    if build_type != "release":
        print("Build type '{}' is not gated — pinning may legitimately be "
              "disabled outside release builds.".format(build_type))
        return 0

    pins = build_config_pins(build_config)
    configured = bool(pins)
    origin = "BuildConfig.CERT_PINS"
    if not configured:
        pins = source_default_pins(source)
        origin = "CertificatePinner compiled-in pins"

    # An explicitly configured pin set is always gated hard. Only the "nothing configured
    # anywhere" case can be downgraded, and only when the caller states this release build
    # is not shippable — an unsigned smoke build cannot reach users.
    advisory = warn_if_unconfigured and not configured
    annotate = warn if advisory else error
    suffix = (" Replace the placeholder pins (or set ETHOS_CERT_PINS) before cutting a "
              "release." if advisory else " A release build must not ship placeholder pins.")

    problems = []
    if not pins:
        problems.append(
            "No certificate pins configured for the release build. Set ETHOS_CERT_PINS "
            "(or -Pethos.certPins) to a comma-separated list of Base64 SHA-256 SPKI "
            "digests, or update CertificatePinner's compiled-in pins."
        )
    for pin in pins:
        reason = placeholder_reason(pin)
        if reason:
            problems.append(
                "Release certificate pin '{}' (from {}) {}.".format(pin, origin, reason)
            )

    if problems:
        for problem in problems:
            annotate(problem + suffix)
        return 0 if advisory else 1

    print("Release certificate pins OK ({} pin(s) from {}).".format(len(pins), origin))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", default="release",
                        help="Build type being verified; only 'release' is gated.")
    parser.add_argument("--build-config", required=True,
                        help="Path to the generated BuildConfig.java for that build type.")
    parser.add_argument("--source", required=True,
                        help="Path to CertificatePinning.kt (source of the compiled-in pins).")
    parser.add_argument("--warn-if-unconfigured", action="store_true",
                        help="Report (rather than fail on) placeholder pins when no pin set "
                             "was configured at all — for release builds that cannot ship.")
    args = parser.parse_args(argv)
    return verify(args.build_type, args.build_config, args.source, args.warn_if_unconfigured)


if __name__ == "__main__":
    sys.exit(main())
