#!/usr/bin/env python3
"""Tests for issue #758: timeouts on .github/workflows/release-notes-parity-check.yml.

Every job in the workflow must declare a timeout-minutes value so a stalled
GitHub API call on a release PR cannot hang the runner until the default
six-hour limit.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import unittest
from pathlib import Path

import yaml

WORKFLOW = (
    Path(__file__).resolve().parents[3] / ".github" / "workflows" / "release-notes-parity-check.yml"
)


class ReleaseNotesParityWorkflowTimeoutTests(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))

    def test_has_jobs(self):
        self.assertTrue(self.workflow["jobs"])

    def test_every_job_has_a_timeout(self):
        for name, job in self.workflow["jobs"].items():
            with self.subTest(job=name):
                timeout = job.get("timeout-minutes")
                self.assertIsInstance(timeout, int)
                self.assertGreater(timeout, 0)


if __name__ == "__main__":
    unittest.main()
