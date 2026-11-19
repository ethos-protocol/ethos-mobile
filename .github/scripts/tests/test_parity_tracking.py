#!/usr/bin/env python3
"""Tests for PARITY.md tracking links (issue #658).

Every row in the "Known gaps (summary)" table must point at a tracking issue.
A "TBD" placeholder makes the table unusable for tracking progress, so the
test fails if one remains.

Run with:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
PARITY_PATH = REPO_ROOT / "PARITY.md"
KNOWN_GAPS_HEADING = "## Known gaps (summary)"


def known_gap_rows(text: str) -> list[list[str]]:
    """Return the cells of each data row in the Known gaps summary table."""
    section = text.split(KNOWN_GAPS_HEADING, 1)[1]
    rows = []
    for line in section.splitlines():
        if not line.startswith("|"):
            if rows:
                break
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if cells and set(cells[0]) <= {"-", ":", " "}:
            continue  # separator row
        rows.append(cells)
    return rows[1:]  # drop the header row


class ParityTrackingTests(unittest.TestCase):

    def setUp(self):
        self.assertTrue(PARITY_PATH.is_file(), f"missing: {PARITY_PATH}")
        self.text = PARITY_PATH.read_text(encoding="utf-8")
        self.rows = known_gap_rows(self.text)

    def test_known_gaps_table_has_rows(self):
        self.assertTrue(self.rows, "Known gaps table not found or empty")

    def test_no_tbd_placeholders_remain(self):
        self.assertNotIn("TBD", self.text)

    def test_every_known_gap_row_links_an_issue(self):
        for cells in self.rows:
            with self.subTest(gap=cells[0]):
                self.assertEqual(len(cells), 3, f"unexpected column count: {cells}")
                self.assertRegex(cells[2], r"#\d+", f"no issue link in: {cells[0]}")


if __name__ == "__main__":
    unittest.main()
