#!/usr/bin/env python3
"""Validate release notes and merged PRs against PARITY.md known-gap tracking.

This script parses the "Known gaps" table in PARITY.md, locates parity-gap issue
numbers, and flags any release notes or merged PRs that claim a known gap is
closed while the table still lists it as open.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Iterable, Sequence

ISSUE_RE = re.compile(r"#(\d+)")
CLOSE_VERB_RE = re.compile(
    r"\b(?:closes?|closed|fix(?:es|ed)?|resolves?|resolved|implements?|implemented)\b",
    re.IGNORECASE,
)


def read_text(path: str | None, *, default: str = "") -> str:
    if path is None:
        return default
    try:
        return Path(path).read_text(encoding="utf-8")
    except FileNotFoundError:
        return default


def issue_numbers_from_text(text: str) -> set[int]:
    if not text:
        return set()
    return {int(match.group(1)) for match in ISSUE_RE.finditer(text)}


def extract_known_gap_issue_numbers(parity_markdown: str) -> set[int]:
    lines = parity_markdown.splitlines()
    in_known_gaps = False
    in_table = False
    issues: set[int] = set()

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("## Known gaps"):
            in_known_gaps = True
            continue
        if not in_known_gaps:
            continue
        if stripped.startswith("## "):
            break
        if stripped.startswith("| Gap |"):
            in_table = True
            continue
        if not in_table:
            continue
        if stripped.startswith("|") and "---" not in stripped:
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            if len(cells) < 3:
                continue
            tracking = cells[2]
            issues |= {n for n in issue_numbers_from_text(tracking) if n > 0}

    return issues


def sentence_has_close_claim(sentence: str) -> bool:
    text = sentence.strip()
    if not text:
        return False
    return bool(CLOSE_VERB_RE.search(text))


def find_release_note_gap_mismatches(release_notes: str, parity_markdown: str) -> set[int]:
    known_gaps = extract_known_gap_issue_numbers(parity_markdown)
    if not known_gaps or not release_notes:
        return set()

    mismatches: set[int] = set()
    sentences = re.split(r"(?<=[.!?])\s+|\n+", release_notes)

    for issue_number in sorted(known_gaps):
        for sentence in sentences:
            if f"#{issue_number}" not in sentence:
                continue
            if sentence_has_close_claim(sentence):
                mismatches.add(issue_number)
                break

    return mismatches


def collect_closed_gap_issues(prs: Sequence[dict], known_gap_issues: set[int]) -> set[int]:
    if not known_gap_issues:
        return set()

    closed: set[int] = set()
    for pr in prs:
        text_parts = [
            pr.get("title", ""),
            pr.get("body", ""),
            pr.get("description", ""),
        ]
        combined = "\n".join(part or "" for part in text_parts)
        if not combined:
            continue

        numbers = issue_numbers_from_text(combined)
        if not numbers:
            continue

        if not any(number in known_gap_issues for number in numbers):
            continue

        if not CLOSE_VERB_RE.search(combined):
            continue

        for number in numbers:
            if number in known_gap_issues:
                closed.add(number)

    return closed


def load_prs(path: str | None) -> list[dict]:
    if not path:
        return []
    payload = json.loads(read_text(path, default="[]"))
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if isinstance(payload, dict):
        items = payload.get("items") or payload.get("data") or payload.get("pull_requests") or []
        return [item for item in items if isinstance(item, dict)]
    return []


def format_issue_set(issues: Iterable[int]) -> str:
    return ", ".join(f"#{issue}" for issue in sorted(issues)) if issues else "none"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--parity-file", default="PARITY.md", help="Path to PARITY.md")
    parser.add_argument("--release-notes-file", help="Optional release notes file to validate")
    parser.add_argument("--prs-file", help="Optional merged PR JSON file; used to surface parity-gap closure claims")
    args = parser.parse_args(argv)

    parity_markdown = read_text(args.parity_file, default="")
    known_gaps = extract_known_gap_issue_numbers(parity_markdown)
    mismatches: set[int] = set()

    if args.release_notes_file:
        release_notes = read_text(args.release_notes_file, default="")
        mismatches |= find_release_note_gap_mismatches(release_notes, parity_markdown)

    prs = load_prs(args.prs_file)
    if prs:
        mismatches |= collect_closed_gap_issues(prs, known_gaps)

    if not mismatches:
        print(f"Parity-gap release-note check passed. Known gap issues in PARITY.md: {format_issue_set(known_gaps) or 'none'}.")
        return 0

    print(
        "::error:: Release notes / merged PRs claim to close parity gaps still listed in PARITY.md: "
        + format_issue_set(mismatches)
    )
    print("Update PARITY.md's 'Known gaps' table (remove or revise the row) before shipping the release.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
