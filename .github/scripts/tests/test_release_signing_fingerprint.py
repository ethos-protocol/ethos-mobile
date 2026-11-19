#!/usr/bin/env python3
"""Tests that an all-zero release_signing_sha256 fails CI (issue #739).

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import contextlib
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))
import shared_cert_pins  # noqa: E402

PINS = [
    "Q3VycmVudFBpbgAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "QmFja3VwUGluAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
]
ZERO = ":".join(["00"] * 32)
REAL = ":".join(["AB"] * 31 + ["CD"])


def _doc(fingerprint):
    return json.dumps({"pins": PINS, "release_signing_sha256": fingerprint})


class ReleaseSigningFingerprintTests(unittest.TestCase):
    def test_all_zero_fingerprint_is_rejected(self):
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError, "all zeros"):
            shared_cert_pins.parse_shared_pins(_doc(ZERO))

    def test_real_fingerprint_is_accepted(self):
        parsed = shared_cert_pins.parse_shared_pins(_doc(REAL))
        self.assertEqual(parsed["release_signing_sha256"], REAL)

    def test_malformed_fingerprint_is_rejected(self):
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError, "32 colon-separated"):
            shared_cert_pins.parse_shared_pins(_doc("ABCD"))

    def test_cli_exits_nonzero_for_all_zero_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cert-pins.json"
            path.write_text(_doc(ZERO), encoding="utf-8")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                rc = shared_cert_pins.main([str(path)])
        self.assertEqual(rc, 1)
        self.assertIn("all zeros", err.getvalue())

    def test_cli_exits_zero_for_real_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cert-pins.json"
            path.write_text(_doc(REAL), encoding="utf-8")
            self.assertEqual(shared_cert_pins.main([str(path)]), 0)


if __name__ == "__main__":
    unittest.main()
