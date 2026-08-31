#!/usr/bin/env python3
"""
Validates that Stellar address test fixtures are synchronized across platforms.

This script ensures:
1. All valid addresses in shared/stellar-address-fixtures.json are tested on both iOS and Android
2. All invalid addresses in shared/stellar-address-fixtures.json are tested on both iOS and Android
3. No platform diverges from the canonical fixture list

This prevents silent drift in platform-specific test files.
"""

import json
import re
import sys
from pathlib import Path

WORKSPACE_ROOT = Path(__file__).parent.parent.parent
FIXTURES_FILE = WORKSPACE_ROOT / "shared" / "stellar-address-fixtures.json"
ANDROID_TEST_FILE = WORKSPACE_ROOT / "android" / "app" / "src" / "test" / "java" / "com" / "ethosprotocol" / "StellarAddressTest.kt"
IOS_TEST_FILE = WORKSPACE_ROOT / "ios" / "EthosProtocol" / "Tests" / "EthosProtocolTests.swift"


def load_fixtures():
    """Load canonical fixtures from JSON."""
    with open(FIXTURES_FILE, 'r') as f:
        return json.load(f)


def extract_android_test_addresses():
    """Extract all test addresses from Android test file."""
    with open(ANDROID_TEST_FILE, 'r') as f:
        content = f.read()
    
    valid_addrs = set()
    invalid_addrs = set()
    
    # Find all string literals that look like Stellar addresses
    # Pattern: strings between quotes that start with G or M and are 56 or 69 chars
    pattern = r'"([GM][A-Z2-7]{54,68})"'
    for match in re.finditer(pattern, content):
        addr = match.group(1)
        # Determine if it's a valid test based on context
        # Look for assertTrue vs assertFalse nearby
        start = max(0, match.start() - 200)
        context = content[start:match.start()]
        if "assertTrue" in context:
            valid_addrs.add(addr)
        elif "assertFalse" in context:
            invalid_addrs.add(addr)
    
    # Also catch empty string and whitespace tests
    if 'assertFalse(StellarAddress.isValidPublicKey(""))' in content:
        invalid_addrs.add("")
    if 'assertFalse(StellarAddress.isValidPublicKey("   "))' in content:
        invalid_addrs.add("   ")
    
    return valid_addrs, invalid_addrs


def extract_ios_test_addresses():
    """Extract all test addresses from iOS test file."""
    with open(IOS_TEST_FILE, 'r') as f:
        content = f.read()
    
    valid_addrs = set()
    invalid_addrs = set()
    
    # Find all string literals that look like Stellar addresses
    pattern = r'"([GM][A-Z2-7]{54,68})"'
    for match in re.finditer(pattern, content):
        addr = match.group(1)
        # Determine if it's a valid test based on context
        start = max(0, match.start() - 200)
        context = content[start:match.start()]
        if "XCTAssertTrue" in context:
            valid_addrs.add(addr)
        elif "XCTAssertFalse" in context:
            invalid_addrs.add(addr)
    
    # Also catch empty string test
    if 'XCTAssertFalse(StellarAddress.isValidPublicKey(""))' in content:
        invalid_addrs.add("")
    
    return valid_addrs, invalid_addrs


def validate_fixtures():
    """Validate that both platforms test all canonical fixtures."""
    fixtures = load_fixtures()
    
    # Collect all expected addresses from fixtures
    expected_valid = set()
    expected_invalid = set()
    
    for addr_obj in fixtures["valid"]["publicKeys"]:
        expected_valid.add(addr_obj["address"])
    
    for addr_obj in fixtures["valid"]["muxedAccounts"]:
        expected_valid.add(addr_obj["address"])
    
    for addr_obj in fixtures["invalid"]:
        expected_invalid.add(addr_obj["address"])
    
    # Extract addresses from test files
    android_valid, android_invalid = extract_android_test_addresses()
    ios_valid, ios_invalid = extract_ios_test_addresses()
    
    errors = []
    
    # Check valid addresses
    missing_android_valid = expected_valid - android_valid
    missing_ios_valid = expected_valid - ios_valid
    if missing_android_valid:
        errors.append(f"❌ Android missing valid fixtures: {missing_android_valid}")
    if missing_ios_valid:
        errors.append(f"❌ iOS missing valid fixtures: {missing_ios_valid}")
    
    # Check invalid addresses
    missing_android_invalid = expected_invalid - android_invalid
    missing_ios_invalid = expected_invalid - ios_invalid
    if missing_android_invalid:
        errors.append(f"❌ Android missing invalid fixtures: {missing_android_invalid}")
    if missing_ios_invalid:
        errors.append(f"❌ iOS missing invalid fixtures: {missing_ios_invalid}")
    
    # Check for unexpected addresses (platforms diverged)
    extra_android_valid = android_valid - expected_valid
    extra_ios_valid = ios_valid - expected_valid
    if extra_android_valid:
        errors.append(f"⚠️  Android has extra valid fixtures (diverged): {extra_android_valid}")
    if extra_ios_valid:
        errors.append(f"⚠️  iOS has extra valid fixtures (diverged): {extra_ios_valid}")
    
    extra_android_invalid = android_invalid - expected_invalid
    extra_ios_invalid = ios_invalid - expected_invalid
    if extra_android_invalid:
        errors.append(f"⚠️  Android has extra invalid fixtures (diverged): {extra_android_invalid}")
    if extra_ios_invalid:
        errors.append(f"⚠️  iOS has extra invalid fixtures (diverged): {extra_ios_invalid}")
    
    if errors:
        print("Stellar Address Test Fixture Validation Failed")
        print("=" * 60)
        for error in errors:
            print(error)
        print("\nTo fix:")
        print("1. Update shared/stellar-address-fixtures.json with canonical fixtures")
        print("2. Ensure both Android (StellarAddressTest.kt) and iOS (EthosProtocolTests.swift)")
        print("   test files include all fixtures from the JSON file")
        return False
    
    print("✅ Stellar Address Test Fixtures Valid")
    print(f"   Valid fixtures: {len(expected_valid)} (tested on both platforms)")
    print(f"   Invalid fixtures: {len(expected_invalid)} (tested on both platforms)")
    return True


if __name__ == "__main__":
    try:
        success = validate_fixtures()
        sys.exit(0 if success else 1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)
