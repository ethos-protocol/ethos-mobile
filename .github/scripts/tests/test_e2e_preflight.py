#!/usr/bin/env python3
"""Tests for .github/scripts/e2e_preflight.sh (issue #740).

A fake `curl` on PATH controls the HTTP status and exit code, so each failure mode
the workflow can hit is exercised without the network.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "e2e_preflight.sh"
HAS_BASH = shutil.which("bash") is not None


class E2EPreflightTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bin = Path(self.tmp.name) / "bin"
        self.bin.mkdir()
        self.output_file = Path(self.tmp.name) / "github_output"

    def tearDown(self):
        self.tmp.cleanup()

    def _fake_curl(self, http_code: str, exit_code: int):
        fake = self.bin / "curl"
        fake.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s' '{http_code}'\n"
            f"exit {exit_code}\n",
            encoding="utf-8",
        )
        fake.chmod(0o755)

    def _run(self, url):
        env = {
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "GITHUB_OUTPUT": str(self.output_file),
        }
        if url is not None:
            env["E2E_API_BASE_URL"] = url
        return subprocess.run(["bash", str(SCRIPT)], env=env,
                              capture_output=True, text=True, timeout=30)

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_empty_setting_fails_and_names_the_setting(self):
        self._fake_curl("200", 0)
        result = self._run("")
        self.assertEqual(result.returncode, 1)
        self.assertIn("E2E_API_BASE_URL", result.stderr)
        self.assertIn("is empty", result.stderr)

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_unset_setting_fails_and_names_the_setting(self):
        self._fake_curl("200", 0)
        result = self._run(None)
        self.assertEqual(result.returncode, 1)
        self.assertIn("E2E_API_BASE_URL", result.stderr)

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_non_http_url_is_rejected_by_name(self):
        self._fake_curl("200", 0)
        result = self._run("ftp://staging.example.test")
        self.assertEqual(result.returncode, 1)
        self.assertIn("not a valid http(s) URL", result.stderr)

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_healthy_host_prints_resolved_host_and_writes_output(self):
        self._fake_curl("200", 0)
        result = self._run("https://staging.example.test")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Resolved staging API host: staging.example.test", result.stdout)
        self.assertIn("api_url=https://staging.example.test",
                      self.output_file.read_text(encoding="utf-8"))

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_unresolvable_host_fails_with_dns_message(self):
        # curl exit 6 is the exact failure from the Cross-Platform E2E run.
        self._fake_curl("000", 6)
        result = self._run("https://staging.example.test")
        self.assertEqual(result.returncode, 2)
        self.assertIn("could not be resolved", result.stderr)
        self.assertIn("staging.example.test", result.stderr)
        self.assertIn("Resolved staging API host", result.stdout)

    @unittest.skipUnless(HAS_BASH, "bash is required")
    def test_unhealthy_api_fails_with_http_status(self):
        self._fake_curl("503", 0)
        result = self._run("https://staging.example.test")
        self.assertEqual(result.returncode, 3)
        self.assertIn("HTTP 503", result.stderr)


if __name__ == "__main__":
    unittest.main()
