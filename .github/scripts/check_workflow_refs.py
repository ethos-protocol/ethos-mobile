#!/usr/bin/env python3
"""Static checks over .github/workflows/*.yml.

Checks:
- Every repo script a workflow calls (`scripts/...` or `.github/scripts/...`
  ending in .sh or .py) exists in the checkout. A workflow that calls a missing
  script fails at run time with exit 127, after the job has already started.
- Every `${name}` JavaScript interpolation inside a `github-script` block refers
  to something defined in that block. An undefined name throws ReferenceError at
  run time, which in the alert steps means the failure issue is never filed.
  GitHub expressions (`${{ ... }}`) are not JavaScript and are not checked.

Usage:
    python3 .github/scripts/check_workflow_refs.py [--root <repo-root>]

Exit codes:
    0 - all references resolve and all interpolations are defined
    1 - one or more problems were found
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Matches repo script paths, with an optional leading `../` (used by jobs whose
# working-directory is a subfolder) and an optional `.github/` prefix.
SCRIPT_REF_RE = re.compile(r"(?:\.\./)*((?:\.github/)?scripts/[\w./-]+\.(?:sh|py))")

# Globals available inside actions/github-script. Not defined in the script body.
JS_GLOBALS = {
    "context", "github", "core", "glob", "io", "exec", "require", "process",
    "console", "JSON", "Date", "Math", "String", "Number", "Boolean", "Array",
    "Object", "Promise", "Buffer", "encodeURIComponent", "decodeURIComponent",
    "Error", "RegExp", "Set", "Map", "undefined", "null", "true", "false",
}

INTERPOLATION_RE = re.compile(r"(?<!\$)\$\{(?!\{)([^}]*)\}")
ROOT_IDENT_RE = re.compile(r"^\s*([A-Za-z_]\w*)")
DECLARATION_RE = re.compile(r"\b(?:const|let|var|function|class)\s+([A-Za-z_]\w*)")
ARROW_PARAMS_RE = re.compile(r"\(([^()]*)\)\s*=>")
SINGLE_ARROW_PARAM_RE = re.compile(r"([A-Za-z_]\w*)\s*=>")


def workflow_files(root: Path):
    return sorted((root / ".github" / "workflows").glob("*.yml"))


def find_missing_script_refs(root: Path):
    problems = []
    for workflow in workflow_files(root):
        text = workflow.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            for match in SCRIPT_REF_RE.finditer(line):
                rel = match.group(1)
                if not (root / rel).is_file():
                    problems.append(
                        f"{workflow.relative_to(root)}:{line_no}: calls '{rel}', which does not exist"
                    )
    return problems


def extract_github_script_blocks(text: str):
    """Yield (first_body_line_number, body) for each `script: |` block."""
    lines = text.splitlines()
    for idx, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped != "script: |":
            continue
        # Only steps that run actions/github-script; `uses:` precedes `with:`.
        step_head = lines[max(0, idx - 6):idx]
        if not any("actions/github-script" in prev for prev in step_head):
            continue
        indent = len(line) - len(stripped)
        body = []
        for nxt in lines[idx + 1:]:
            if nxt.strip() and (len(nxt) - len(nxt.lstrip())) <= indent:
                break
            body.append(nxt)
        indents = [len(b) - len(b.lstrip()) for b in body if b.strip()]
        cut = min(indents) if indents else 0
        yield idx + 2, "\n".join(b[cut:] for b in body)


def defined_names(body: str) -> set[str]:
    names = set(DECLARATION_RE.findall(body))
    for params in ARROW_PARAMS_RE.findall(body):
        for part in params.split(","):
            ident = ROOT_IDENT_RE.match(part.replace("...", ""))
            if ident:
                names.add(ident.group(1))
    names.update(SINGLE_ARROW_PARAM_RE.findall(body))
    return names


def find_undefined_interpolations(root: Path):
    problems = []
    for workflow in workflow_files(root):
        text = workflow.read_text(encoding="utf-8")
        for first_line, body in extract_github_script_blocks(text):
            defined = defined_names(body)
            for offset, body_line in enumerate(body.splitlines()):
                for expr in INTERPOLATION_RE.findall(body_line):
                    ident = ROOT_IDENT_RE.match(expr)
                    if not ident:
                        continue
                    name = ident.group(1)
                    if name in JS_GLOBALS or name in defined:
                        continue
                    problems.append(
                        f"{workflow.relative_to(root)}:{first_line + offset}: "
                        f"'${{{name}}}' in a github-script block is not defined"
                    )
    return problems


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root (default: the checkout containing this script)",
    )
    args = parser.parse_args(argv)

    root = args.root.resolve()
    missing = find_missing_script_refs(root)
    undefined = find_undefined_interpolations(root)
    for problem in missing + undefined:
        print(f"::error::{problem}")
    if missing or undefined:
        print(f"{len(missing)} missing script reference(s), "
              f"{len(undefined)} undefined github-script interpolation(s).")
        return 1
    print("OK: workflow script references resolve and github-script interpolations are defined.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
