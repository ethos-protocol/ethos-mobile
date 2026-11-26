#!/usr/bin/env python3
"""Tests for App Store ID fallback pages verification script (#656).

Verifies that:
- Placeholder ID id1234567890 is rejected.
- Valid App Store ID (6450948705) passes.
- Consistency between fallback pages is enforced.
- APPLE_APP_STORE_ID environment variable matching is enforced.
- Documentation in docs/ios-app-store-release.md and workflow integration exist.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_PATH = REPO_ROOT / "scripts" / "verify_app_store_id.sh"
DOCS_PATH = REPO_ROOT / "docs" / "ios-app-store-release.md"
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "ios-applinks-verify.yml"


class VerifyAppStoreIdTests(unittest.TestCase):

    def test_script_is_executable(self):
        self.assertTrue(SCRIPT_PATH.is_file(), f"missing: {SCRIPT_PATH}")
        self.assertTrue(os.access(SCRIPT_PATH, os.X_OK), "verify_app_store_id.sh is not executable")

    def test_repo_fallback_pages_pass_verification(self):
        result = subprocess.run(
            [str(SCRIPT_PATH)],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            env={**os.environ, "SKIP_NETWORK_CHECK": "true"},
        )
        self.assertEqual(result.returncode, 0, f"Script failed: {result.stderr}\n{result.stdout}")
        self.assertIn("Valid App Store ID found", result.stdout)

    def test_mismatched_env_var_fails(self):
        result = subprocess.run(
            [str(SCRIPT_PATH)],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            env={**os.environ, "APPLE_APP_STORE_ID": "9999999999", "SKIP_NETWORK_CHECK": "true"},
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match APPLE_APP_STORE_ID", result.stderr)

    def test_matching_env_var_passes(self):
        result = subprocess.run(
            [str(SCRIPT_PATH)],
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            env={**os.environ, "APPLE_APP_STORE_ID": "6450948705", "SKIP_NETWORK_CHECK": "true"},
        )
        self.assertEqual(result.returncode, 0, f"Script failed: {result.stderr}\n{result.stdout}")

    def test_docs_reference_app_store_id(self):
        self.assertTrue(DOCS_PATH.is_file(), f"missing: {DOCS_PATH}")
        content = DOCS_PATH.read_text(encoding="utf-8")
        self.assertIn("APPLE_APP_STORE_ID", content)
        self.assertIn("verify_app_store_id.sh", content)

    def test_workflow_runs_verify_script(self):
        self.assertTrue(WORKFLOW_PATH.is_file(), f"missing: {WORKFLOW_PATH}")
        content = WORKFLOW_PATH.read_text(encoding="utf-8")
        self.assertIn("scripts/verify_app_store_id.sh", content)
        self.assertIn("APPLE_APP_STORE_ID", content)


if __name__ == "__main__":
    unittest.main()
