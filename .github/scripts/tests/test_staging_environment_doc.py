#!/usr/bin/env python3
"""Tests for issue #760: docs/staging-environment.md must describe the real release flow.

The guide used to tell operators to run scripts/release.sh, which does not
exist. These tests check that:

- the guide no longer references scripts/release.sh;
- every release workflow the guide names exists in .github/workflows/;
- the guide's release step is tag-driven and does not claim a `needs:`
  dependency on the staging smoke test that the release workflows lack.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DOC = REPO_ROOT / "docs" / "staging-environment.md"
WORKFLOWS = REPO_ROOT / ".github" / "workflows"
RELEASE_WORKFLOWS = ["ios-app-store-release.yml", "android-play-store-release.yml"]


class StagingEnvironmentReleaseDocTests(unittest.TestCase):
    def setUp(self):
        self.text = DOC.read_text(encoding="utf-8")

    def test_does_not_reference_missing_release_script(self):
        self.assertNotIn("scripts/release.sh", self.text)
        self.assertFalse((REPO_ROOT / "scripts" / "release.sh").exists())

    def test_named_release_workflows_exist(self):
        for name in RELEASE_WORKFLOWS:
            with self.subTest(workflow=name):
                self.assertIn(name, self.text)
                self.assertTrue((WORKFLOWS / name).is_file())

    def test_does_not_claim_release_depends_on_smoke_test(self):
        self.assertNotIn("The release workflow depends on the staging smoke test", self.text)

    def test_release_is_tag_driven(self):
        self.assertIn("vX.Y.Z", self.text)


if __name__ == "__main__":
    unittest.main()
