#!/usr/bin/env python3
"""Tests that android/app/build.gradle.kts is a complete script (issue #679).

The scheduled dependency-check job failed because build.gradle.kts was truncated:
the `configurations.all { resolutionStrategy { ... } }` block was never closed, so
Gradle reported "Expecting '}'" before any dependency analysis could run. These
checks catch a truncated or unbalanced script without needing a Gradle run.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
BUILD_FILE = REPO_ROOT / "android" / "app" / "build.gradle.kts"


def strip_literals(line: str) -> str:
    """Remove string literals and line comments so braces inside them are ignored."""
    line = re.sub(r'"(\\.|[^"\\])*"', '""', line)
    return re.sub(r"//.*", "", line)


def brace_depth_at_end(text: str) -> int:
    depth = 0
    for line in text.splitlines():
        stripped = strip_literals(line)
        depth += stripped.count("{") - stripped.count("}")
    return depth


class BuildScriptIntegrityTests(unittest.TestCase):

    def setUp(self):
        self.assertTrue(BUILD_FILE.is_file(), f"missing: {BUILD_FILE}")
        self.text = BUILD_FILE.read_text(encoding="utf-8")

    def test_braces_are_balanced(self):
        self.assertEqual(brace_depth_at_end(self.text), 0,
                         "unbalanced braces: script is truncated or malformed")

    def test_never_depth_negative(self):
        depth = 0
        for n, line in enumerate(self.text.splitlines(), 1):
            depth += strip_literals(line).count("{") - strip_literals(line).count("}")
            self.assertGreaterEqual(depth, 0, f"unexpected closing brace at line {n}")

    def test_file_ends_with_closing_brace(self):
        last_code = [l for l in self.text.splitlines() if l.strip() and not l.strip().startswith("//")]
        self.assertTrue(last_code[-1].strip().endswith("}"),
                        f"script does not end with a closing brace: {last_code[-1]!r}")

    def test_hamcrest_force_is_inside_resolution_strategy(self):
        self.assertRegex(self.text, r"configurations\.all\s*\{[\s\S]*resolutionStrategy\s*\{[\s\S]*force\(\"org\.hamcrest:hamcrest:2\.2\"\)")


if __name__ == "__main__":
    unittest.main()
