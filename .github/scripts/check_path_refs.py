#!/usr/bin/env python3
"""#769: fail the build when docs or source cite a repo file path that does not exist.

Scans tracked .md, .kt, .swift and .yml files for repo-relative path tokens
(e.g. `android/app/.../ApiClient.kt`, `.github/workflows/ci.yml`) and checks each
resolves to a tracked file. A token resolves when it matches a tracked path from
the repo root, from the citing file's directory, or from the iOS/Android project
roots that docs use for shell commands (`cd ios/EthosProtocol && ... ../mull.yml`),
or when it is the trailing part of a tracked path (short forms like `ui/ViewModels.kt`).

Generated outputs that are cited but not committed go in
.github/path-refs-allowlist.txt, one token per line.
"""
import pathlib
import posixpath
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / ".github" / "path-refs-allowlist.txt"
EXTS = (".md", ".kt", ".swift", ".yml")
# Path tokens: at least one slash, ending in a checked extension. Leading ./ and ../ allowed.
TOKEN = re.compile(r"(?<![\w/.-])((?:\.{1,2}/)?(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\.(?:md|kt|swift|yml))(?![\w/-])")
# Bases a doc may use for relative paths in shell snippets.
BASES = ["", "ios/EthosProtocol", "android"]


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True)
    return out.stdout.split()


def load_allowlist() -> set[str]:
    if not ALLOWLIST.is_file():
        return set()
    return {
        line.strip()
        for line in ALLOWLIST.read_text().splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def resolves(token: str, citing_file: str, tracked: set[str], suffixes: list[str]) -> bool:
    candidates = set()
    for base in BASES:
        candidates.add(posixpath.normpath(posixpath.join(base, token)))
    candidates.add(posixpath.normpath(posixpath.join(posixpath.dirname(citing_file), token)))
    if any(c in tracked for c in candidates):
        return True
    clean = posixpath.normpath(token)
    return any(p.endswith("/" + clean) or p == clean for p in suffixes)


def main() -> int:
    files = tracked_files()
    tracked = set(files)
    allow = load_allowlist()
    scanned = [f for f in files if f.endswith(EXTS)]

    dangling: dict[str, list[str]] = {}
    for rel in scanned:
        path = ROOT / rel
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            for match in TOKEN.finditer(line):
                token = match.group(1)
                if token in allow or resolves(token, rel, tracked, files):
                    continue
                dangling.setdefault(token, []).append(f"{rel}:{lineno}")

    for token, locations in sorted(dangling.items()):
        for loc in locations:
            file, _, line = loc.rpartition(":")
            print(f"::error file={file},line={line}::Path '{token}' does not resolve to a tracked file")
    if dangling:
        print(f"{len(dangling)} dangling path reference(s). Fix the citation or add a generated-output entry to {ALLOWLIST.relative_to(ROOT)}.")
        return 1
    print(f"All path references resolve ({len(scanned)} files scanned).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
