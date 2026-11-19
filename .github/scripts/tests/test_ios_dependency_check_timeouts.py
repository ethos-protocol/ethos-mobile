#!/usr/bin/env python3
"""Tests for issue #757: timeouts on .github/workflows/ios-dependency-check.yml.

Every job must declare a timeout-minutes value, and the Swift package
resolve step (the one that talks to the package registry) must have its own
timeout so a stalled registry response cannot hold the runner.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import unittest
from pathlib import Path

import yaml

WORKFLOW = Path(__file__).resolve().parents[3] / ".github" / "workflows" / "ios-dependency-check.yml"


class IosDependencyCheckTimeoutTests(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        self.jobs = self.workflow["jobs"]

    def test_every_job_has_a_timeout(self):
        for name, job in self.jobs.items():
            with self.subTest(job=name):
                self.assertIsInstance(job.get("timeout-minutes"), int)

    def test_resolve_step_is_capped(self):
        steps = self.jobs["dependency-check"]["steps"]
        resolve = next(s for s in steps if s.get("name") == "Resolve Swift package dependencies")
        self.assertIsInstance(resolve.get("timeout-minutes"), int)
        self.assertLess(resolve["timeout-minutes"], self.jobs["dependency-check"]["timeout-minutes"])


if __name__ == "__main__":
    unittest.main()
