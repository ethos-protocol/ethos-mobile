#!/usr/bin/env python3
"""Tests for issue #759: timeouts and concurrency on the Dependabot workflows.

- dependabot-security-alerts.yml and dependabot-auto-merge.yml: every job
  declares a timeout-minutes value.
- dependabot-auto-merge.yml: a concurrency group keyed on the PR number, so
  runs for the same PR are serialised instead of racing when Dependabot pushes
  several PRs at once.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import unittest
from pathlib import Path

import yaml

WORKFLOWS = Path(__file__).resolve().parents[3] / ".github" / "workflows"
SECURITY_ALERTS = WORKFLOWS / "dependabot-security-alerts.yml"
AUTO_MERGE = WORKFLOWS / "dependabot-auto-merge.yml"


def _load(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


class DependabotTimeoutTests(unittest.TestCase):
    def test_every_job_has_a_timeout(self):
        for path in (SECURITY_ALERTS, AUTO_MERGE):
            for name, job in _load(path)["jobs"].items():
                with self.subTest(workflow=path.name, job=name):
                    self.assertIsInstance(job.get("timeout-minutes"), int)


class DependabotAutoMergeConcurrencyTests(unittest.TestCase):
    def setUp(self):
        self.workflow = _load(AUTO_MERGE)

    def test_concurrency_block_present(self):
        self.assertIn("concurrency", self.workflow)

    def test_group_is_keyed_on_pr_number(self):
        group = self.workflow["concurrency"]["group"]
        self.assertIn("github.event.pull_request.number", group)

    def test_group_is_scoped_to_auto_merge(self):
        group = self.workflow["concurrency"]["group"]
        self.assertTrue(group.startswith("dependabot-auto-merge-"))


if __name__ == "__main__":
    unittest.main()
