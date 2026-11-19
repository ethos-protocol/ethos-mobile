#!/usr/bin/env python3
"""Tests for check_workflow_refs.py, run against temporary repository trees."""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "check_workflow_refs.py"

_spec = importlib.util.spec_from_file_location("check_workflow_refs", SCRIPT_PATH)
check_workflow_refs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_workflow_refs)


def write(root: Path, rel: str, text: str = "") -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class CheckWorkflowRefsTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_missing_repo_script_called_from_workflow_is_reported(self):
        write(self.root, ".github/workflows/app.yml",
              "jobs:\n  j:\n    steps:\n      - run: bash scripts/verify_missing.sh\n")
        problems = check_workflow_refs.find_missing_script_refs(self.root)
        self.assertEqual(len(problems), 1)
        self.assertIn("app.yml:4", problems[0])
        self.assertIn("scripts/verify_missing.sh", problems[0])

    def test_existing_script_passes(self):
        write(self.root, "scripts/verify_ok.sh", "#!/usr/bin/env bash\n")
        write(self.root, ".github/workflows/app.yml",
              "      - run: bash scripts/verify_ok.sh\n")
        self.assertEqual(check_workflow_refs.find_missing_script_refs(self.root), [])

    def test_github_scripts_path_is_checked(self):
        write(self.root, ".github/workflows/app.yml",
              "      - run: python3 .github/scripts/gone.py\n")
        problems = check_workflow_refs.find_missing_script_refs(self.root)
        self.assertEqual(len(problems), 1)
        self.assertIn(".github/scripts/gone.py", problems[0])

    def test_parent_relative_path_from_subdirectory_job_is_resolved_to_repo_root(self):
        write(self.root, ".github/scripts/present.py", "")
        write(self.root, ".github/workflows/app.yml",
              "      - run: python3 ../.github/scripts/present.py\n")
        self.assertEqual(check_workflow_refs.find_missing_script_refs(self.root), [])

    def test_non_script_paths_are_ignored(self):
        write(self.root, ".github/workflows/app.yml",
              '      paths:\n        - "android/app/src/main/java/**/SignatureVerifier.kt"\n'
              "        - shared/cert-pins.json\n")
        self.assertEqual(check_workflow_refs.find_missing_script_refs(self.root), [])

    def test_main_exit_codes(self):
        write(self.root, ".github/workflows/app.yml", "      - run: bash scripts/nope.sh\n")
        self.assertEqual(check_workflow_refs.main(["--root", str(self.root)]), 1)
        write(self.root, "scripts/nope.sh", "")
        self.assertEqual(check_workflow_refs.main(["--root", str(self.root)]), 0)


    def test_undefined_interpolation_in_github_script_is_reported(self):
        write(self.root, ".github/workflows/alert.yml", """\
jobs:
  j:
    steps:
      - uses: actions/github-script@v9
        with:
          script: |
            const title = "x";
            console.log(`Run: ${runUrl}`);
""")
        problems = check_workflow_refs.find_undefined_interpolations(self.root)
        self.assertEqual(len(problems), 1)
        self.assertIn("alert.yml:8", problems[0])
        self.assertIn("${runUrl}", problems[0])

    def test_defined_interpolations_pass(self):
        write(self.root, ".github/workflows/alert.yml", """\
jobs:
  j:
    steps:
      - uses: actions/github-script@v9
        with:
          script: |
            const runUrl = `${context.serverUrl}/run/${context.runId}`;
            const items = [1].map((issue) => `#${issue}`);
            const more = [1].map(async (x, i) => `${x}-${i}`);
            console.log(`${runUrl} ${items} ${more}`);
""")
        self.assertEqual(check_workflow_refs.find_undefined_interpolations(self.root), [])

    def test_interpolation_outside_github_script_is_ignored(self):
        write(self.root, ".github/workflows/build.yml", """\
jobs:
  j:
    steps:
      - run: echo "${SOME_VAR}"
        with:
          script: |
            console.log(`${missing}`);
""")
        self.assertEqual(check_workflow_refs.find_undefined_interpolations(self.root), [])

    def test_github_expression_syntax_is_not_checked_as_javascript(self):
        write(self.root, ".github/workflows/alert.yml", """\
jobs:
  j:
    steps:
      - uses: actions/github-script@v9
        with:
          script: |
            console.log("${{ github.sha }}");
""")
        self.assertEqual(check_workflow_refs.find_undefined_interpolations(self.root), [])

    def test_main_reports_undefined_interpolation_as_failure(self):
        write(self.root, ".github/workflows/alert.yml", """\
jobs:
  j:
    steps:
      - uses: actions/github-script@v9
        with:
          script: |
            console.log(`${runUrl}`);
""")
        self.assertEqual(check_workflow_refs.main(["--root", str(self.root)]), 1)


if __name__ == "__main__":
    unittest.main()
