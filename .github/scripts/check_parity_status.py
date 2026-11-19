#!/usr/bin/env python3
"""Lint PARITY.md status cells against tracking issues.

Checks the Feature matrix (the table headed `| Feature | iOS | Android | Notes |`):

1. Gap rows (❌ or ⚠️ on either platform) must cite a tracking issue. A note
   that says "no tracking issue yet" is reported.
2. A row whose notes cite `#N` is cross-checked against GitHub. If issue N is
   already closed, the status may be stale and the row is reported so someone
   re-audits it. This step needs GITHUB_TOKEN; without it, it is skipped.

Usage:
    GITHUB_TOKEN=... python3 .github/scripts/check_parity_status.py [--strict]

Without --strict, findings are printed as warnings and the exit code is 0.
With --strict, any finding exits 1.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PARITY = os.path.join(REPO_ROOT, "PARITY.md")
REPO = os.environ.get("GITHUB_REPOSITORY", "ethos-protocol/ethos-mobile")
GAP_MARKS = ("❌", "⚠️")
ISSUE_REF = re.compile(r"(?<![\w&/])#(\d+)\b")
NO_TRACKER = "no tracking issue yet"


def feature_rows(text: str) -> list[tuple[int, list[str]]]:
    """Return (line_number, cells) for each data row of the Feature matrix."""
    rows: list[tuple[int, list[str]]] = []
    in_matrix = False
    for lineno, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("|"):
            cells = [c.strip() for c in stripped.strip("|").split("|")]
            if cells[:4] == ["Feature", "iOS", "Android", "Notes"]:
                in_matrix = True
                continue
            if not in_matrix or set("".join(cells)) <= set("-: "):
                continue
            rows.append((lineno, cells))
        elif in_matrix and not stripped:
            # A blank line after the table ends the matrix.
            if rows:
                in_matrix = False
    return rows


def is_closed(number: int, token: str) -> bool | None:
    """Return True if the issue is closed, False if open, None if it cannot be read."""
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues/{number}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.load(resp).get("state") == "closed"
    except (urllib.error.URLError, json.JSONDecodeError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--strict", action="store_true", help="fail on any finding")
    args = parser.parse_args()

    with open(PARITY, encoding="utf-8") as fh:
        rows = feature_rows(fh.read())
    if not rows:
        print("check_parity_status: no Feature matrix rows found in PARITY.md", file=sys.stderr)
        return 1

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("check_parity_status: GITHUB_TOKEN not set, skipping closed-issue cross-check")

    findings: list[str] = []
    for lineno, cells in rows:
        if len(cells) < 4:
            continue
        feature, ios, android, notes = cells[0], cells[1], cells[2], cells[3]
        where = f"PARITY.md:{lineno} ({feature})"

        is_gap = any(mark in ios or mark in android for mark in GAP_MARKS)
        if is_gap and NO_TRACKER in notes:
            findings.append(f"{where}: gap has no tracking issue")

        if token:
            for ref in sorted({int(n) for n in ISSUE_REF.findall(notes)}):
                closed = is_closed(ref, token)
                if closed:
                    findings.append(f"{where}: cites closed issue #{ref}; re-audit its status")

    for f in findings:
        print(f"warning: {f}")
    if findings:
        print(f"check_parity_status: {len(findings)} finding(s)")
        return 1 if args.strict else 0
    print(f"check_parity_status: {len(rows)} row(s) checked, no findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
