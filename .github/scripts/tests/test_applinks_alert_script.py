#!/usr/bin/env python3
"""Runs the failure-alert github-script of the App Links workflows under Node.

The scripts are extracted from the real workflow files, then executed with
stubbed `github` and `context` objects. A missing definition (for example an
undefined `runUrl`) surfaces here as a ReferenceError, and the test checks that
the alert issue is created and links to the workflow run.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO_ROOT / ".github" / "workflows"
NODE = shutil.which("node")

HARNESS = """
// Script logging goes to stderr so stdout carries only the recorded calls.
console.log = (...args) => process.stderr.write(args.join(" ") + "\\n");
const context = {
  serverUrl: "https://github.com",
  repo: { owner: "acme", repo: "app" },
  runId: 4242,
};
const created = [];
const github = {
  rest: {
    search: {
      issuesAndPullRequests: async () => ({ data: { items: EXISTING } }),
    },
    issues: {
      create: async (args) => { created.push(args); return {}; },
    },
  },
};
(async () => {
%(script)s
})().then(() => {
  process.stdout.write(JSON.stringify(created));
}).catch((err) => {
  process.stderr.write(String(err && err.stack || err));
  process.exit(1);
});
"""


def extract_github_script(workflow: Path) -> str:
    """Return the body of the first `script: |` block in a workflow file."""
    lines = workflow.read_text(encoding="utf-8").splitlines()
    for idx, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped == "script: |":
            indent = len(line) - len(stripped)
            body = []
            for nxt in lines[idx + 1:]:
                if nxt.strip() and (len(nxt) - len(nxt.lstrip())) <= indent:
                    break
                body.append(nxt)
            # Remove the common block indentation.
            indents = [len(b) - len(b.lstrip()) for b in body if b.strip()]
            cut = min(indents) if indents else 0
            return "\n".join(b[cut:] for b in body)
    raise AssertionError(f"no github-script block found in {workflow}")


@unittest.skipIf(NODE is None, "node is required to execute github-script blocks")
class AppLinksAlertScriptTests(unittest.TestCase):
    WORKFLOW = "android-applinks-verify.yml"
    ALERT_TITLE = "⚠️ assetlinks.json verification failed (scheduled check)"
    ALERT_TITLE_FRAGMENT = "assetlinks.json verification failed"

    def run_alert(self, existing_titles=()):
        script = extract_github_script(WORKFLOWS / self.WORKFLOW)
        existing = [{"title": t} for t in existing_titles]
        source = HARNESS.replace("EXISTING", json.dumps(existing)) % {"script": script}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "alert.js"
            path.write_text(source, encoding="utf-8")
            result = subprocess.run(
                [NODE, str(path)], capture_output=True, text=True, timeout=60
            )
        self.assertEqual(result.returncode, 0, f"script threw:\n{result.stderr}")
        return json.loads(result.stdout or "[]")

    def test_failure_alert_is_created_and_links_to_the_run(self):
        created = self.run_alert()
        self.assertEqual(len(created), 1)
        body = created[0]["body"]
        self.assertIn("https://github.com/acme/app/actions/runs/4242", body)
        self.assertIn(self.ALERT_TITLE_FRAGMENT, created[0]["title"])

    def test_existing_open_alert_is_not_duplicated(self):
        self.assertEqual(self.run_alert(existing_titles=[self.ALERT_TITLE]), [])


@unittest.skipIf(NODE is None, "node is required to execute github-script blocks")
class IosAppLinksAlertScriptTests(AppLinksAlertScriptTests):
    WORKFLOW = "ios-applinks-verify.yml"
    ALERT_TITLE = "⚠️ apple-app-site-association verification failed (scheduled check)"
    ALERT_TITLE_FRAGMENT = "apple-app-site-association verification failed"


if __name__ == "__main__":
    unittest.main()
