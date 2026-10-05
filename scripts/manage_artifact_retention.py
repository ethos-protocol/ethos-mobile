#!/usr/bin/env python3
"""
manage_artifact_retention.py — Apply S3 lifecycle rules to the artifact bucket.

Manages expiry for three key prefixes:
  ios/releases/       — release builds (long retention, default 180 days)
  android/releases/   — release builds (long retention, default 180 days)
  r8-mappings/        — ProGuard/R8 mapping files (same as releases, needed for crash de-obfuscation)
  ios/snapshots/      — CI snapshot builds (short retention, default 30 days)
  android/snapshots/  — CI snapshot builds (short retention, default 30 days)

Usage
-----
  python3 scripts/manage_artifact_retention.py \
      --bucket ethos-protocol-artifacts \
      --release-days 180 \
      --snapshot-days 30

  # Dry run — prints the lifecycle config that would be applied, but does nothing:
  python3 scripts/manage_artifact_retention.py \
      --bucket ethos-protocol-artifacts \
      --dry-run

AWS credentials are resolved by boto3's standard chain
(environment → ~/.aws/credentials → IAM role).

The script is idempotent: it replaces the bucket's entire lifecycle configuration
each run so the rules stay authoritative with no manual Console drift.
"""

import argparse
import json
import sys

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    print(
        "::error::boto3 is not installed. Run: pip install boto3",
        file=sys.stderr,
    )
    sys.exit(1)


def build_lifecycle_rules(release_days: int, snapshot_days: int) -> list:
    """Return the S3 lifecycle rules for the artifact bucket."""

    rules = [
        # ── Release artifacts ────────────────────────────────────────────────
        {
            "ID": "expire-ios-releases",
            "Status": "Enabled",
            "Filter": {"Prefix": "ios/releases/"},
            "Expiration": {"Days": release_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": release_days},
        },
        {
            "ID": "expire-android-releases",
            "Status": "Enabled",
            "Filter": {"Prefix": "android/releases/"},
            "Expiration": {"Days": release_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": release_days},
        },
        # R8 mapping files must survive as long as the release artifacts they
        # correspond to — crash reports from a release build can arrive long
        # after the build was published.
        {
            "ID": "expire-r8-mappings",
            "Status": "Enabled",
            "Filter": {"Prefix": "r8-mappings/"},
            "Expiration": {"Days": release_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": release_days},
        },
        # ── Snapshot artifacts ───────────────────────────────────────────────
        {
            "ID": "expire-ios-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "ios/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
        {
            "ID": "expire-android-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "android/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
        # R8 mapping snapshots can be cleaned up on the same cadence as the
        # snapshot artifacts they correspond to.
        {
            "ID": "expire-r8-mapping-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "r8-mappings/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
    ]
    return rules


def apply_lifecycle(bucket: str, release_days: int, snapshot_days: int, dry_run: bool) -> None:
    rules = build_lifecycle_rules(release_days, snapshot_days)
    config = {"Rules": rules}

    print(f"Lifecycle configuration to apply to s3://{bucket}:")
    print(json.dumps(config, indent=2))

    if dry_run:
        print("\nDry run — no changes made.")
        return

    s3 = boto3.client("s3")
    try:
        s3.put_bucket_lifecycle_configuration(
            Bucket=bucket,
            LifecycleConfiguration=config,
        )
        print(f"\n✓ Lifecycle rules applied to s3://{bucket}")
        print(f"  releases  → expire after {release_days} days")
        print(f"  snapshots → expire after {snapshot_days} days")
    except ClientError as exc:
        error_code = exc.response["Error"]["Code"]
        error_msg = exc.response["Error"]["Message"]
        print(
            f"::error::Failed to apply lifecycle rules to s3://{bucket}: "
            f"{error_code} — {error_msg}",
            file=sys.stderr,
        )
        sys.exit(1)


def fetch_current_lifecycle(bucket: str) -> None:
    """Print the bucket's current lifecycle config (for debugging)."""
    s3 = boto3.client("s3")
    try:
        resp = s3.get_bucket_lifecycle_configuration(Bucket=bucket)
        rules = resp.get("Rules", [])
        print(f"Current lifecycle rules on s3://{bucket} ({len(rules)} rule(s)):")
        print(json.dumps(rules, indent=2, default=str))
    except ClientError as exc:
        if exc.response["Error"]["Code"] == "NoSuchLifecycleConfiguration":
            print(f"No lifecycle configuration on s3://{bucket}")
        else:
            raise


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Apply S3 lifecycle retention rules for the artifact bucket."
    )
    parser.add_argument(
        "--bucket",
        required=True,
        help="S3 bucket name (e.g. ethos-protocol-artifacts)",
    )
    parser.add_argument(
        "--release-days",
        type=int,
        default=180,
        help="Days to retain release artifacts (default: 180)",
    )
    parser.add_argument(
        "--snapshot-days",
        type=int,
        default=30,
        help="Days to retain snapshot artifacts (default: 30)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the lifecycle config that would be applied without modifying the bucket",
    )
    parser.add_argument(
        "--show-current",
        action="store_true",
        help="Print the bucket's current lifecycle config and exit",
    )

    args = parser.parse_args()

    if args.release_days < 1 or args.snapshot_days < 1:
        print("::error::Retention days must be ≥ 1", file=sys.stderr)
        sys.exit(1)

    if args.release_days < args.snapshot_days:
        print(
            f"::warning::release-days ({args.release_days}) < snapshot-days ({args.snapshot_days}); "
            "releases would expire before snapshots — is this intentional?",
        )

    if args.show_current:
        fetch_current_lifecycle(args.bucket)
        return

    apply_lifecycle(args.bucket, args.release_days, args.snapshot_days, args.dry_run)


if __name__ == "__main__":
    main()
