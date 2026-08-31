#!/usr/bin/env python3
"""Tests for release_notes_parity_check.py."""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "release_notes_parity_check.py"

_spec = importlib.util.spec_from_file_location("release_notes_parity_check", SCRIPT_PATH)
release_notes_parity_check = importlib.util.module_from_spec(_spec)


class ReleaseNotesParityCheckTests(unittest.TestCase):
    def _load_module(self):
        if _spec.loader is None:
            raise AssertionError("Could not load release_notes_parity_check module")
        _spec.loader.exec_module(release_notes_parity_check)

    def test_extract_known_gap_numbers_from_parity_markdown(self):
        self._load_module()
        sample = """
## Known gaps (summary)

| Gap | Platform missing feature | Tracking |
|-----|--------------------------|---------|
| Deposit / Withdraw screens | Android | #87 |
| Manage Beneficiary screen | Android | #87 |
| TOTP re-verify copy (\"Scan URI\" shown without URI) | Android | #115 |
| Stellar address validation (StrKey + checksum) | Android | #113 / #71 |
| Check-in reminder lead-time scaling | Android | TBD |
| Offline check-in queue | iOS | TBD |
"""
        gaps = release_notes_parity_check.extract_known_gap_issue_numbers(sample)
        self.assertEqual(gaps, {87, 115, 113, 71})

    def test_detects_closed_gap_prs_from_merged_prs(self):
        self._load_module()
        prs = [
            {
                "number": 201,
                "title": "Implement Android deposit flow",
                "body": "Closes #87 and updates PARITY.md.",
            },
            {
                "number": 202,
                "title": "Fix #115 copy regression",
                "body": "Adds regression test.",
            },
            {"number": 203, "title": "Unrelated cleanup", "body": "No ticket refs"},
        ]
        closed = release_notes_parity_check.collect_closed_gap_issues(prs, {87, 115})
        self.assertEqual(closed, {87, 115})

    def test_release_notes_claiming_closed_gap_but_not_removed_from_parity_table_is_flagged(self):
        self._load_module()
        parity = """
## Known gaps (summary)

| Gap | Platform missing feature | Tracking |
|-----|--------------------------|---------|
| Deposit / Withdraw screens | Android | #87 |
| TOTP re-verify copy (\"Scan URI\" shown without URI) | Android | #115 |
"""
        notes = "Fixed #87 and #115 in the release."
        flagged = release_notes_parity_check.find_release_note_gap_mismatches(notes, parity)
        self.assertEqual(flagged, {87, 115})


if __name__ == "__main__":
    unittest.main()
