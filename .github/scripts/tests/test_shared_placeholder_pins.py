#!/usr/bin/env python3
"""Tests that placeholder pins in shared/cert-pins.json fail a release gate (issue #738).

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import base64
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

_spec = importlib.util.spec_from_file_location(
    "verify_cert_pins", SCRIPTS_DIR / "verify_cert_pins.py")
verify_cert_pins = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verify_cert_pins)  # type: ignore[union-attr]

PLACEHOLDER_SHARED = {
    "pins": [
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
    ],
    "release_signing_sha256": "AB:" * 31 + "CD",
}


def _real_pin(seed: bytes) -> str:
    return base64.b64encode((seed * 32)[:32]).decode("ascii")


REAL_SHARED = {
    "pins": [_real_pin(b"\x01\x9c"), _real_pin(b"\x7e\x35")],
    "release_signing_sha256": "AB:" * 31 + "CD",
}


class SharedPlaceholderPinTests(unittest.TestCase):
    def _run(self, shared: dict):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            shared_path = tmp_path / "cert-pins.json"
            shared_path.write_text(json.dumps(shared), encoding="utf-8")
            # No BuildConfig and no source literals: the shared file alone is checked.
            build_config = tmp_path / "BuildConfig.java"
            build_config.write_text("", encoding="utf-8")
            source = tmp_path / "CertificatePinning.kt"
            source.write_text("", encoding="utf-8")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = verify_cert_pins.verify(
                    "release", str(build_config), str(source),
                    shared_pins_file=str(shared_path))
        return rc, out.getvalue()

    def test_placeholder_shared_pins_fail_release(self):
        rc, output = self._run(PLACEHOLDER_SHARED)
        self.assertEqual(rc, 1, output)
        self.assertIn("Shared pin", output)
        self.assertIn("all-repeated-character placeholder", output)

    def test_real_shared_pins_do_not_trip_placeholder_check(self):
        rc, output = self._run(REAL_SHARED)
        self.assertNotIn("Shared pin", output)
        self.assertNotIn("all-repeated", output)
        self.assertNotIn("PLACEHOLDER sentinel", output)


if __name__ == "__main__":
    unittest.main()
