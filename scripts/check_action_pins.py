#!/usr/bin/env python3
"""Reject GitHub Actions `uses:` references that are not pinned to a commit SHA.

A tag such as `actions/checkout@v7` can be moved to different code, so every
remote action must be referenced by a full 40-character commit SHA, with the
human-readable version kept in a trailing comment:

    uses: actions/checkout@<40-hex-sha> # v7

Local reusable workflows (`./.github/...`) and Docker images (`docker://`)
are not covered by this rule and are skipped.

Exceptions are listed in EXEMPT with the reason. Keep that list short and
remove an entry as soon as its action is fixed.

Usage: python3 scripts/check_action_pins.py [workflow_dir]
Exit status 0 when every remote action is pinned, 1 otherwise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")

# `uses: owner/repo[/path]@ref`, optionally quoted, with an optional comment.
USES_RE = re.compile(
    r"""^\s*(?:-\s+)?uses:\s*["']?"""
    r"""(?P<action>[A-Za-z0-9_.\-]+/[A-Za-z0-9_.\-/]+)"""
    r"""@(?P<ref>[^\s"']+)"""
)

# action -> reason it cannot be pinned yet.
EXEMPT: dict[str, str] = {
    # dependabot/enable-auto-merge returns 404 on GitHub, so there is no SHA
    # to pin to. Replace or fix the step in dependabot-auto-merge.yml.
    "dependabot/enable-auto-merge": "repository not found on GitHub (404)",
}


def check_file(path: Path) -> list[str]:
    problems: list[str] = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        # Skip full-line comments so documentation examples are not linted.
        if line.lstrip().startswith("#"):
            continue
        match = USES_RE.match(line)
        if not match:
            continue
        action, ref = match.group("action"), match.group("ref")
        if action.startswith("./") or action.startswith("docker://"):
            continue
        if action in EXEMPT:
            continue
        if not SHA_RE.match(ref):
            problems.append(
                f"{path}:{lineno}: `{action}@{ref}` is not pinned to a full commit SHA"
            )
    return problems


def main(argv: list[str]) -> int:
    workflow_dir = Path(argv[1]) if len(argv) > 1 else Path(".github/workflows")
    files = sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml"))
    if not files:
        print(f"No workflow files found in {workflow_dir}", file=sys.stderr)
        return 1

    problems: list[str] = []
    for path in files:
        problems.extend(check_file(path))

    if problems:
        print("Unpinned GitHub Actions references:")
        for problem in problems:
            print(f"  {problem}")
        print(
            "\nPin each action to its commit SHA, for example:\n"
            "  uses: actions/checkout@<sha> # v7"
        )
        return 1

    print(f"All remote actions in {len(files)} workflow file(s) are pinned to commit SHAs.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
