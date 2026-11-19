#!/usr/bin/env python3
"""Check that repository paths cited in Markdown documents actually exist.

A path is considered "cited" when it appears in backticks (`path/to/file.ext`)
or as a relative Markdown link target ([text](path/to/file.ext)). Only paths
whose first segment is a top-level entry of the repository are checked, so
prose such as `and/or` or example URLs are ignored. Glob patterns, URLs and
pure anchors are skipped. Trailing `:line` or `#Lnn` suffixes are stripped.

Usage:
    python3 .github/scripts/check_doc_paths.py                 # shared/*.md and docs/*.md
    python3 .github/scripts/check_doc_paths.py docs/*.md       # explicit files

Exits 1 when any cited path does not exist, listing each broken reference.
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_PATTERNS = ["shared/*.md", "docs/*.md"]

CODE_SPAN = re.compile(r"`([^`\n]+)`")
MD_LINK = re.compile(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
PATH_SHAPE = re.compile(r"^[A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)*$")
LINE_SUFFIX = re.compile(r"(?::\d+(?:-\d+)?|#L\d+(?:-L?\d+)?)$")


def top_level_names() -> set[str]:
    """Names of files and directories at the repository root (hidden ones included)."""
    return set(os.listdir(REPO_ROOT))


def candidates(text: str) -> list[tuple[str, bool]]:
    """Return (raw_path, is_link) pairs found in the Markdown text."""
    found: list[tuple[str, bool]] = []
    for m in MD_LINK.finditer(text):
        found.append((m.group(1), True))
    for m in CODE_SPAN.finditer(text):
        found.append((m.group(1).strip(), False))
    return found


def normalise(raw: str) -> str | None:
    """Turn a raw reference into a repo-relative path, or None if it is not one."""
    if raw.startswith(("http://", "https://", "mailto:", "#")):
        return None
    path = LINE_SUFFIX.sub("", raw.split("#", 1)[0])
    if "*" in path or "?" in path or "{" in path or "..." in path:
        return None
    # Gradle/Xcode build outputs are generated, so they never exist in a checkout.
    if "build" in path.split("/"):
        return None
    if not PATH_SHAPE.match(path) or ("/" not in path and "." not in path):
        return None
    return path


BAD_GRADLE_EXT = re.compile(r"[\w\-]+\.gradle\.kt(?!s)\b")


def check_file(md_path: str, tops: set[str]) -> list[str]:
    """Return human-readable descriptions of broken references in one document."""
    with open(md_path, encoding="utf-8") as fh:
        text = fh.read()
    doc_dir = os.path.dirname(md_path)
    rel_doc = os.path.relpath(md_path, REPO_ROOT)
    problems: list[str] = []
    # Gradle Kotlin DSL build scripts end in .gradle.kts; a bare .kt is always a typo.
    for m in BAD_GRADLE_EXT.finditer(text):
        problems.append(f"{rel_doc}: build script must end in .gradle.kts: {m.group(0)}")
    seen: set[str] = set()
    for raw, is_link in candidates(text):
        path = normalise(raw)
        if path is None or path in seen:
            continue
        first = path.split("/", 1)[0]
        if first not in tops:
            # Relative links resolve against the document's own directory.
            if not is_link:
                continue
        seen.add(path)
        bases = [REPO_ROOT]
        if is_link:
            bases.insert(0, doc_dir)
        if not any(os.path.exists(os.path.join(base, path)) for base in bases):
            rel_doc = os.path.relpath(md_path, REPO_ROOT)
            problems.append(f"{rel_doc}: cited path does not exist: {raw}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("patterns", nargs="*", default=DEFAULT_PATTERNS,
                        help="Markdown files or glob patterns relative to the repo root")
    args = parser.parse_args()

    files: list[str] = []
    for pattern in args.patterns:
        matches = sorted(glob.glob(os.path.join(REPO_ROOT, pattern)))
        files.extend(m for m in matches if m.endswith(".md"))
    if not files:
        print("check_doc_paths: no Markdown files matched", file=sys.stderr)
        return 1

    tops = top_level_names()
    problems: list[str] = []
    for md in files:
        problems.extend(check_file(md, tops))

    if problems:
        print("Broken path references found:")
        for p in problems:
            print(f"  {p}")
        return 1
    print(f"check_doc_paths: {len(files)} file(s) checked, all cited paths exist")
    return 0


if __name__ == "__main__":
    sys.exit(main())
