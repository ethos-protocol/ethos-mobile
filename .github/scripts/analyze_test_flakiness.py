#!/usr/bin/env python3
"""
Analyzes instrumented test output to identify flaky tests.

Usage:
    python3 analyze_test_flakiness.py <test-output.log>

Looks for patterns like:
    - Same test appearing in multiple run attempts
    - Tests retried due to transient failures
    - Consistent timeout/resource exhaustion errors

Output:
    - Prints identified flaky tests and retry counts
    - Suggests tests to quarantine or investigate further
"""

import sys
import re
from collections import defaultdict
from pathlib import Path


def parse_gradle_test_output(log_content: str) -> dict:
    """Parse Gradle test output to extract test results and failures."""
    results = {
        "passed": [],
        "failed": [],
        "flaky_candidates": defaultdict(int),  # test_name -> failure_count
        "timeouts": [],
        "resource_errors": []
    }

    # Pattern for failed tests (e.g., "FAILED com.ethosprotocol.VaultListTest.testRefresh")
    failed_pattern = r"FAILED\s+(com\.ethosprotocol\.[^ ]+)"
    for match in re.finditer(failed_pattern, log_content):
        test_name = match.group(1)
        results["failed"].append(test_name)
        results["flaky_candidates"][test_name] += 1

    # Pattern for timeout errors
    timeout_pattern = r"(.*)\s+.*?(TimeoutException|timeout|timed out)"
    for match in re.finditer(timeout_pattern, log_content, re.IGNORECASE):
        results["timeouts"].append(match.group(1).strip())

    # Pattern for resource exhaustion
    resource_pattern = r"(.*)\s+.*(OutOfMemory|resource exhausted|ENOMEM|EAGAIN)"
    for match in re.finditer(resource_pattern, log_content, re.IGNORECASE):
        results["resource_errors"].append(match.group(1).strip())

    # Count passed tests
    passed_pattern = r"(\d+) passed"
    passed_match = re.search(passed_pattern, log_content)
    if passed_match:
        results["passed_count"] = int(passed_match.group(1))

    return results


def identify_flaky_tests(results: dict) -> list:
    """Identify tests that appear to be flaky based on failure patterns."""
    flaky_tests = []

    # Tests that failed multiple times are likely flaky
    for test_name, failure_count in results["flaky_candidates"].items():
        if failure_count > 1:
            flaky_tests.append({
                "name": test_name,
                "failure_count": failure_count,
                "pattern": "Multiple failures"
            })

    # Tests associated with timeouts may be flaky
    for test in results["timeouts"]:
        flaky_tests.append({
            "name": test,
            "pattern": "Timeout",
            "root_cause": "Possible emulator/device slowness or test timing dependency"
        })

    # Tests associated with resource errors
    for test in results["resource_errors"]:
        flaky_tests.append({
            "name": test,
            "pattern": "Resource exhaustion",
            "root_cause": "Emulator/device running low on memory or file handles"
        })

    return flaky_tests


def report_flakiness(flaky_tests: list) -> None:
    """Print a human-readable report of identified flaky tests."""
    if not flaky_tests:
        print("✓ No flaky tests detected.")
        return

    print(f"\n⚠️  Detected {len(flaky_tests)} potentially flaky test(s):\n")

    for test in flaky_tests:
        print(f"  • {test['name']}")
        print(f"    Pattern: {test.get('pattern', 'Unknown')}")

        if "failure_count" in test:
            print(f"    Failures: {test['failure_count']}")

        if "root_cause" in test:
            print(f"    Root cause: {test['root_cause']}")

        print()

    print("\nRecommendation:")
    print("  1. Run the test locally multiple times: for i in {1..5}; do ./gradlew connectedDebugAndroidTest --tests <test>; done")
    print("  2. If confirmed flaky, add @Ignore with issue reference and document in .github/FLAKY_TESTS.md")
    print("  3. File a GitHub issue with reproduction steps and CI logs")


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <test-output.log>", file=sys.stderr)
        sys.exit(1)

    log_path = Path(sys.argv[1])
    if not log_path.exists():
        print(f"Error: File not found: {log_path}", file=sys.stderr)
        sys.exit(1)

    log_content = log_path.read_text()

    results = parse_gradle_test_output(log_content)
    flaky_tests = identify_flaky_tests(results)

    report_flakiness(flaky_tests)

    # Exit with non-zero if flaky tests detected, for CI automation
    if flaky_tests:
        sys.exit(1)


if __name__ == "__main__":
    main()
