#!/usr/bin/env python3
"""Tests for scripts/verify_signing_fingerprint.sh, run against temporary files."""
from __future__ import annotations

import base64
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT = REPO_ROOT / "scripts" / "verify_signing_fingerprint.sh"

REAL_FP = ":".join(f"{b:02X}" for b in range(32))
ZERO_FP = ":".join("00" for _ in range(32))
REAL_FP_B64 = base64.b64encode(bytes(range(32))).decode()


def verifier_source(value: str) -> str:
    return (
        "package com.ethosprotocol.security\n\n"
        "class SignatureVerifier {\n"
        "    companion object {\n"
        f'        const val EXPECTED_CERT_SHA256: String = "{value}"\n'
        "    }\n}\n"
    )


def assetlinks(fingerprint: str, package: str = "com.ethosprotocol") -> str:
    return json.dumps([
        {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
                "namespace": "android_app",
                "package_name": package,
                "sha256_cert_fingerprints": [fingerprint],
            },
        }
    ])


class VerifySigningFingerprintTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        self.pins = self.dir / "cert-pins.json"
        self.links = self.dir / "assetlinks.json"
        self.verifier = self.dir / "SignatureVerifier.kt"
        self.write_all(pin=REAL_FP, link_fp=REAL_FP, verifier_value=REAL_FP_B64)

    def tearDown(self):
        self._tmp.cleanup()

    def write_all(self, *, pin, link_fp, verifier_value):
        self.pins.write_text(json.dumps({"pins": [], "release_signing_sha256": pin}))
        self.links.write_text(assetlinks(link_fp))
        self.verifier.write_text(verifier_source(verifier_value))

    def run_script(self, env_extra=None):
        env = {
            "PATH": os.environ.get("PATH", ""),
            "CERT_PINS_FILE": str(self.pins),
            "ASSETLINKS_FILE": str(self.links),
            "SIGNATURE_VERIFIER_FILE": str(self.verifier),
            "PLAY_PACKAGE_NAME": "com.ethosprotocol",
        }
        env.update(env_extra or {})
        return subprocess.run(
            ["bash", str(SCRIPT)], env=env, capture_output=True, text=True, timeout=60
        )

    def test_consistent_real_fingerprint_passes(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASSED", result.stdout)

    def test_all_zero_placeholder_fingerprint_fails(self):
        self.write_all(pin=ZERO_FP, link_fp=ZERO_FP, verifier_value=REAL_FP_B64)
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("all-zero placeholder", result.stderr)

    def test_assetlinks_with_different_fingerprint_fails(self):
        other = ":".join(f"{b:02X}" for b in range(1, 33))
        self.write_all(pin=REAL_FP, link_fp=other, verifier_value=REAL_FP_B64)
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("assetlinks.json does not advertise", result.stderr)

    def test_verifier_constant_not_matching_pin_fails(self):
        wrong_b64 = base64.b64encode(bytes(range(1, 33))).decode()
        self.write_all(pin=REAL_FP, link_fp=REAL_FP, verifier_value=wrong_b64)
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("EXPECTED_CERT_SHA256 does not match", result.stderr)

    def test_blank_verifier_constant_warns_and_passes(self):
        self.write_all(pin=REAL_FP, link_fp=REAL_FP, verifier_value="")
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("WARN: EXPECTED_CERT_SHA256 is blank", result.stdout)

    def test_missing_cert_pins_file_fails(self):
        self.pins.unlink()
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("required file not found", result.stderr)

    def test_malformed_pin_fails(self):
        self.write_all(pin="not-a-fingerprint", link_fp=REAL_FP, verifier_value=REAL_FP_B64)
        result = self.run_script()
        self.assertEqual(result.returncode, 1)
        self.assertIn("not 32 colon-separated hex bytes", result.stderr)

    def test_play_credentials_present_warns_that_comparison_is_not_done(self):
        result = self.run_script({"PLAY_SERVICE_ACCOUNT_JSON": "{}"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Play App Signing comparison is not implemented", result.stdout)

    def test_play_credentials_absent_reports_skip(self):
        result = self.run_script()
        self.assertIn("SKIP: PLAY_SERVICE_ACCOUNT_JSON not set", result.stdout)


if __name__ == "__main__":
    unittest.main()
