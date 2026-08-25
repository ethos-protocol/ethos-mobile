#!/usr/bin/env python3
"""Tests for check_tls_pinning.py.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import importlib.util
import io
import contextlib
import plistlib
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "check_tls_pinning.py"
REPO_ROOT = Path(__file__).resolve().parents[3]

_spec = importlib.util.spec_from_file_location("check_tls_pinning", SCRIPT_PATH)
check_tls_pinning = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_tls_pinning)  # type: ignore[union-attr]


def write_plist(directory: Path, name: str, contents: dict) -> str:
    path = directory / name
    with path.open("wb") as f:
        plistlib.dump(contents, f)
    return str(path)


def run_main(argv: list[str]) -> tuple[int, str]:
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = check_tls_pinning.main(argv)
    return code, buf.getvalue()


BASE_PLIST = {
    "CFBundleName": "EthosProtocol",
    "CFBundleIdentifier": "$(PRODUCT_BUNDLE_IDENTIFIER)",
}


class CheckTargetTests(unittest.TestCase):
    def test_fails_against_current_repo_info_plists(self):
        """The checked-in Info.plists (pre-Issue-2-fix) don't carry
        TLS_PUBLIC_KEY_PINS yet — the Release check must fail against them
        exactly as it would in CI today."""
        ios_root = REPO_ROOT / "ios" / "EthosProtocol"
        app_plist = ios_root / "EthosProtocol" / "Info.plist"
        widget_plist = ios_root / "TTLWidget" / "Info.plist"
        self.assertTrue(app_plist.is_file(), f"fixture missing: {app_plist}")
        self.assertTrue(widget_plist.is_file(), f"fixture missing: {widget_plist}")

        code, output = run_main([
            "--configuration", "Release",
            "--target", f"EthosProtocol:{app_plist}",
            "--target", f"TTLWidget:{widget_plist}",
        ])

        self.assertEqual(code, 1)
        self.assertIn("EthosProtocol", output)
        self.assertIn("TTLWidget", output)
        self.assertIn("TLS_PUBLIC_KEY_PINS", output)

    def test_passes_once_non_empty_pins_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            plist_path = write_plist(Path(tmp), "Info.plist", {
                **BASE_PLIST,
                "TLS_PUBLIC_KEY_PINS": ["k1Vw6WsE9scmn9tRAWjOTNTWyfPpWWx3fV1c/dCLwyQ="],
            })

            code, output = run_main([
                "--configuration", "Release",
                "--target", f"EthosProtocol:{plist_path}",
            ])

            self.assertEqual(code, 0)
            self.assertIn("passed", output)

    def test_fails_when_pins_array_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            plist_path = write_plist(Path(tmp), "Info.plist", {
                **BASE_PLIST,
                "TLS_PUBLIC_KEY_PINS": [],
            })

            code, output = run_main([
                "--configuration", "Release",
                "--target", f"EthosProtocol:{plist_path}",
            ])

            self.assertEqual(code, 1)
            self.assertIn("EthosProtocol", output)

    def test_fails_when_pins_contain_blank_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            plist_path = write_plist(Path(tmp), "Info.plist", {
                **BASE_PLIST,
                "TLS_PUBLIC_KEY_PINS": [""],
            })

            code, _output = run_main([
                "--configuration", "Release",
                "--target", f"EthosProtocol:{plist_path}",
            ])

            self.assertEqual(code, 1)

    def test_fails_when_plist_file_is_missing(self):
        code, output = run_main([
            "--configuration", "Release",
            "--target", "EthosProtocol:/nonexistent/Info.plist",
        ])

        self.assertEqual(code, 1)
        self.assertIn("not found", output)

    def test_names_only_the_failing_target_when_others_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = write_plist(Path(tmp), "Good-Info.plist", {
                **BASE_PLIST,
                "TLS_PUBLIC_KEY_PINS": ["k1Vw6WsE9scmn9tRAWjOTNTWyfPpWWx3fV1c/dCLwyQ="],
            })
            bad = write_plist(Path(tmp), "Bad-Info.plist", BASE_PLIST)

            code, output = run_main([
                "--configuration", "Release",
                "--target", f"EthosProtocol:{good}",
                "--target", f"TTLWidget:{bad}",
            ])

            self.assertEqual(code, 1)
            self.assertIn("TTLWidget", output)
            self.assertNotIn("EthosProtocol: TLS_PUBLIC_KEY_PINS", output)


class DebugConfigurationTests(unittest.TestCase):
    def test_debug_configuration_does_not_fail_when_pins_are_absent(self):
        """Matches PinningDelegate's documented design: an empty/absent pin
        set intentionally disables pinning for local-dev builds, so the gate
        must not block a Debug configuration lacking the key."""
        with tempfile.TemporaryDirectory() as tmp:
            plist_path = write_plist(Path(tmp), "Info.plist", BASE_PLIST)

            code, output = run_main([
                "--configuration", "Debug",
                "--target", f"EthosProtocol:{plist_path}",
            ])

            self.assertEqual(code, 0)
            self.assertIn("Skipping", output)

    def test_debug_configuration_check_is_case_insensitive(self):
        with tempfile.TemporaryDirectory() as tmp:
            plist_path = write_plist(Path(tmp), "Info.plist", BASE_PLIST)

            code, _output = run_main([
                "--configuration", "debug",
                "--target", f"EthosProtocol:{plist_path}",
            ])

            self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
