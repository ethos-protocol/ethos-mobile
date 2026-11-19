#!/usr/bin/env python3
"""Tests for the shared cert-pins schema (issue #737).

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
SHARED_PINS = REPO_ROOT / "shared" / "cert-pins.json"

sys.path.insert(0, str(SCRIPTS_DIR))
import shared_cert_pins  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "generate_cert_pins", SCRIPTS_DIR / "generate_cert_pins.py")
generate_cert_pins = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(generate_cert_pins)  # type: ignore[union-attr]

FINGERPRINT = "AB:" * 31 + "CD"
CURRENT = "Q3VycmVudFBpbgAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
BACKUP = "QmFja3VwUGluAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="


def document(pins=(CURRENT, BACKUP), fingerprint=FINGERPRINT):
    import json
    return json.dumps({"pins": list(pins), "release_signing_sha256": fingerprint})


class SharedPinSchemaTests(unittest.TestCase):
    def test_valid_document_parses(self):
        parsed = shared_cert_pins.parse_shared_pins(document())
        self.assertEqual(parsed["current"], CURRENT)
        self.assertEqual(parsed["backup"], BACKUP)
        self.assertEqual(parsed["release_signing_sha256"], FINGERPRINT)

    def test_document_without_pins_array_is_rejected(self):
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError, "'pins'"):
            shared_cert_pins.parse_shared_pins('{"release_signing_sha256": "AB"}')

    def test_document_with_wrong_pin_count_is_rejected(self):
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError, "exactly 2"):
            shared_cert_pins.parse_shared_pins(document(pins=(CURRENT,)))

    def test_identical_current_and_backup_is_rejected(self):
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError, "must differ"):
            shared_cert_pins.parse_shared_pins(document(pins=(CURRENT, CURRENT)))

    def test_missing_fingerprint_is_rejected(self):
        import json
        raw = json.dumps({"pins": [CURRENT, BACKUP]})
        with self.assertRaisesRegex(shared_cert_pins.SharedPinSchemaError,
                                    "release_signing_sha256"):
            shared_cert_pins.parse_shared_pins(raw)


class GeneratorUsesSharedSchemaTests(unittest.TestCase):
    def test_generator_loader_reads_pins_array_as_current_and_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cert-pins.json"
            path.write_text(document(), encoding="utf-8")
            pins = generate_cert_pins.load_shared_pins(path)
        self.assertEqual(pins, {"current": CURRENT, "backup": BACKUP})

    def test_generator_no_longer_reports_missing_current_key(self):
        # Regression for #737: the committed file has `pins` and no `current` key,
        # so the generator used to exit 2 with "missing or empty 'current' pin".
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cert-pins.json"
            path.write_text(document(), encoding="utf-8")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                rc = generate_cert_pins.main(
                    ["--check", "--shared", str(path),
                     "--android", str(Path(tmp) / "missing.kts"),
                     "--ios", str(Path(tmp) / "missing.yml")])
        self.assertNotEqual(rc, 2, err.getvalue())
        self.assertNotIn("missing or empty 'current'", err.getvalue())

    def test_monitor_runs_against_committed_pin_file(self):
        # Runs the CI gate against the file actually committed to the repo. The
        # committed all-zero fingerprint is rejected by design (#739), so the
        # assertion is the #737 regression only: no missing-'current' schema error.
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            generate_cert_pins.main(["--check", "--shared", str(SHARED_PINS)])
        self.assertNotIn("missing or empty 'current'", err.getvalue())


if __name__ == "__main__":
    unittest.main()
