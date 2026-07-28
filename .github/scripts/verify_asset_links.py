#!/usr/bin/env python3
"""Fail the build if the live assetlinks.json drifts from AndroidManifest's asset_statements.

Compares the Digital Asset Links statements served from
https://ethos-protocol.app/.well-known/assetlinks.json against the
`asset_statements` string resource that AndroidManifest.xml points platform
passkey verification at. See android/app/src/main/res/values/strings.xml.

Note: strings.xml intentionally commits a placeholder sha256_cert_fingerprints
value (the real release cert fingerprint is never checked into git, mirroring
how release signing credentials are injected via CI secrets rather than
hardcoded). So this script cannot assert literal fingerprint equality until
that placeholder is resolved out-of-band; it instead validates that the
served fingerprints are well-formed and, once the placeholder has been
replaced with a real value, that they match exactly.
"""
import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

STRINGS_XML = "android/app/src/main/res/values/strings.xml"
ASSET_LINKS_URL = "https://ethos-protocol.app/.well-known/assetlinks.json"
FINGERPRINT_RE = re.compile(r"^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31}$")
PLACEHOLDER = "REPLACE_WITH_RELEASE_CERT_SHA256_FINGERPRINT"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def load_manifest_statements():
    try:
        tree = ET.parse(STRINGS_XML)
    except (OSError, ET.ParseError) as e:
        fail(f"could not read/parse {STRINGS_XML}: {e}")

    for el in tree.getroot().findall("string"):
        if el.get("name") == "asset_statements":
            raw = (el.text or "").strip()
            try:
                return json.loads(raw.replace('\\"', '"'))
            except json.JSONDecodeError as e:
                fail(f"asset_statements in {STRINGS_XML} is not valid JSON: {e}")

    fail(f"no asset_statements string resource found in {STRINGS_XML}")


def fetch_served_statements():
    try:
        with urllib.request.urlopen(ASSET_LINKS_URL, timeout=15) as resp:
            status = resp.status
            body = resp.read().decode("utf-8")
    except (urllib.error.URLError, TimeoutError) as e:
        fail(f"failed to fetch {ASSET_LINKS_URL}: {e}")

    if status != 200:
        fail(f"{ASSET_LINKS_URL} returned HTTP {status}")

    try:
        return json.loads(body)
    except json.JSONDecodeError as e:
        fail(f"{ASSET_LINKS_URL} did not return valid JSON: {e}")


def statement_key(stmt):
    target = stmt.get("target", {})
    return (target.get("namespace"), target.get("package_name"))


def main():
    manifest_statements = load_manifest_statements()
    served_statements = fetch_served_statements()

    manifest_by_key = {statement_key(s): s for s in manifest_statements}
    served_by_key = {statement_key(s): s for s in served_statements}

    errors = []
    for key, manifest_stmt in manifest_by_key.items():
        served_stmt = served_by_key.get(key)
        if served_stmt is None:
            errors.append(f"{ASSET_LINKS_URL} is missing a statement for {key}")
            continue

        manifest_relations = sorted(manifest_stmt.get("relation", []))
        served_relations = sorted(served_stmt.get("relation", []))
        if manifest_relations != served_relations:
            errors.append(
                f"relation mismatch for {key}: manifest={manifest_relations} served={served_relations}"
            )

        served_fingerprints = served_stmt.get("target", {}).get("sha256_cert_fingerprints", [])
        if not served_fingerprints:
            errors.append(f"{ASSET_LINKS_URL} has no sha256_cert_fingerprints for {key}")
        for fp in served_fingerprints:
            if not FINGERPRINT_RE.match(fp):
                errors.append(f"served fingerprint '{fp}' for {key} is not a well-formed SHA-256 fingerprint")

        manifest_fingerprints = manifest_stmt.get("target", {}).get("sha256_cert_fingerprints", [])
        if manifest_fingerprints != [PLACEHOLDER]:
            if sorted(manifest_fingerprints) != sorted(served_fingerprints):
                errors.append(
                    f"fingerprint mismatch for {key}: manifest={manifest_fingerprints} served={served_fingerprints}"
                )

    if errors:
        for e in errors:
            print(f"::error::{e}")
        sys.exit(1)

    print(f"OK: {ASSET_LINKS_URL} matches {STRINGS_XML}'s asset_statements")


if __name__ == "__main__":
    main()
