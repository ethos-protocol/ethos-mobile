#!/usr/bin/env python3
"""Fail when the documented iOS CI-only XCTest skip count grows."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = ROOT / "ios/EthosProtocol/Tests"
MAX_CI_SKIPS = 10
CI_SKIP = re.compile(
    r'try\??\s+XCTSkipIf\(\s*ProcessInfo\.processInfo\.environment\["CI"\]\s*!=\s*nil'
)


def main() -> int:
    skips = []
    for path in TEST_ROOT.rglob("*.swift"):
        source = path.read_text(encoding="utf-8")
        skips.extend((path.relative_to(ROOT), match.start()) for match in CI_SKIP.finditer(source))

    count = len(skips)
    if count > MAX_CI_SKIPS:
        print(
            f"Found {count} CI-based XCTest skips; documented maximum is {MAX_CI_SKIPS}. "
            "Update docs/ios-ci-test-skips.md and review the new skip before raising the limit."
        )
        return 1

    print(f"Found {count} CI-based XCTest skips (maximum {MAX_CI_SKIPS}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
