# Artifact Storage and Versioning

This document describes how build artifacts (iOS `.ipa`, Android `.aab`/`.apk`,
and Android R8 mapping files) are archived to Amazon S3, how they are versioned,
and how they are retained and cleaned up.

- [Overview](#overview)
- [Workflow](#workflow)
- [S3 bucket structure](#s3-bucket-structure)
- [Versioning scheme](#versioning-scheme)
- [Retention policies](#retention-policies)
- [Setup and required secrets](#setup-and-required-secrets)
- [Downloading an artifact](#downloading-an-artifact)
- [Rolling back to a previous release](#rolling-back-to-a-previous-release)
- [Managing lifecycle rules manually](#managing-lifecycle-rules-manually)
- [Troubleshooting](#troubleshooting)

---

## Overview

Build artifacts are archived to S3 after every release tag (`vX.Y.Z`) and after
every push to `main`. This enables:

- **Rollbacks** — download a previous signed build without re-running CI.
- **Auditability** — every production release is preserved with its SHA-256
  checksum, git SHA, and a metadata manifest.
- **R8 de-obfuscation** — Android mapping files are kept alongside the release
  AABs so crash reports can be de-obfuscated at any time during the retention
  window.

Artifacts are also uploaded as GitHub Actions artifacts (14-day retention) as an
inspection fallback, but S3 is the primary, durable store.

---

## Workflow

**File**: `.github/workflows/artifact-storage.yml`

| Trigger | Behaviour |
|---------|-----------|
| Push `vX.Y.Z` tag | Builds signed `.ipa` and `.aab`/`.apk`; uploads to `releases/` prefix in S3 |
| Push to `main` | Builds artifacts (signed if secrets are configured); uploads to `snapshots/` prefix |
| Manual dispatch | Choose platform (`ios` / `android` / `both`), optional version label override, and dry-run mode |

The workflow has three jobs:

1. **`version`** — computes the version label and S3 key prefixes.
2. **`ios-archive`** — builds the iOS `.ipa` via fastlane `ios archive`, computes
   a SHA-256 checksum, writes a `metadata.json` manifest, and uploads to S3.
3. **`android-archive`** — assembles the release AAB and APK via Gradle, computes
   checksums, writes `metadata.json`, and uploads to S3 (including the R8 mapping
   file).
4. **`apply-retention-policy`** — runs `scripts/manage_artifact_retention.py` to
   keep the S3 lifecycle rules authoritative after every release.

---

## S3 bucket structure

```
s3://<ARTIFACT_BUCKET>/
  ios/
    releases/
      v1.4.0/
        EthosProtocol-v1.4.0.ipa
        EthosProtocol-v1.4.0.ipa.sha256
        metadata.json
      v1.3.0/
        ...
    snapshots/
      main/
        20261005-abc1234/
          EthosProtocol-main-20261005-abc1234.ipa
          EthosProtocol-main-20261005-abc1234.ipa.sha256
          metadata.json
  android/
    releases/
      v1.4.0/
        app-release-v1.4.0.aab
        app-release-v1.4.0.aab.sha256
        app-release-v1.4.0.apk
        app-release-v1.4.0.apk.sha256
        metadata.json
      v1.3.0/
        ...
    snapshots/
      main/
        20261005-abc1234/
          ...
  r8-mappings/
    v1.4.0/
      mapping-v1.4.0.txt
    v1.3.0/
      ...
    snapshots/
      main/
        20261005-abc1234/
          mapping-main-20261005-abc1234.txt
```

Each directory also contains a `metadata.json` with:

```json
{
  "version": "v1.4.0",
  "platform": "android",
  "artifacts": {
    "aab": { "file": "app-release-v1.4.0.aab", "sha256": "abc..." },
    "apk": { "file": "app-release-v1.4.0.apk", "sha256": "def..." }
  },
  "signed": true,
  "git_ref": "v1.4.0",
  "git_sha": "a1b2c3d4e5f6...",
  "github_run_id": "12345678",
  "github_run_url": "https://github.com/ethos-protocol/ethos-mobile/actions/runs/12345678"
}
```

---

## Versioning scheme

| Build type | Version label | S3 key pattern |
|------------|---------------|----------------|
| Release tag `vX.Y.Z` | `vX.Y.Z` | `<platform>/releases/vX.Y.Z/<artifact>` |
| Main branch push | `main-YYYYMMDD-<sha7>` | `<platform>/snapshots/main/YYYYMMDD-<sha7>/<artifact>` |
| Other branch push | `<branch>-YYYYMMDD-<sha7>` | `<platform>/snapshots/<branch>/YYYYMMDD-<sha7>/<artifact>` |
| Manual with override | `<override>` | `<platform>/snapshots/<branch>/YYYYMMDD-<sha7>/<artifact>` |

The `sha7` is the first 7 characters of the full commit SHA. Branch names are
sanitised (slashes and non-alphanumeric characters replaced with `-`).

---

## Retention policies

Managed by `scripts/manage_artifact_retention.py` and applied to the bucket via
`PutBucketLifecycleConfiguration` after every release run.

| Prefix | Default retention | Override variable |
|--------|-------------------|-------------------|
| `ios/releases/` | 180 days | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `android/releases/` | 180 days | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `r8-mappings/` | 180 days | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `ios/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |
| `android/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |
| `r8-mappings/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |

> **Note:** R8 mapping files are retained for the same duration as the release
> artifacts they correspond to. Crash reports from a production release can
> arrive months after the release date; removing the mapping file would make
> those reports unreadable.

To change the defaults, set the repository variables
`ARTIFACT_RELEASE_RETENTION_DAYS` and `ARTIFACT_SNAPSHOT_RETENTION_DAYS` in
**Settings → Secrets and variables → Actions → Variables**.

---

## Setup and required secrets

### IAM permissions

Create a dedicated IAM user (or role) with the following policy on the artifact
bucket. Replace `ethos-protocol-artifacts` with your actual bucket name.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ArtifactUpload",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::ethos-protocol-artifacts",
        "arn:aws:s3:::ethos-protocol-artifacts/*"
      ]
    },
    {
      "Sid": "LifecycleManagement",
      "Effect": "Allow",
      "Action": [
        "s3:PutLifecycleConfiguration",
        "s3:GetLifecycleConfiguration"
      ],
      "Resource": "arn:aws:s3:::ethos-protocol-artifacts"
    }
  ]
}
```

### Required secrets (Settings → Secrets and variables → Actions)

| Secret | Description |
|--------|-------------|
| `AWS_ARTIFACT_ACCESS_KEY_ID` | IAM access key ID for the artifact uploader |
| `AWS_ARTIFACT_SECRET_ACCESS_KEY` | Paired IAM secret access key |
| `ARTIFACT_BUCKET` | S3 bucket name (e.g. `ethos-protocol-artifacts`) |

### Optional variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ARTIFACT_BUCKET_REGION` | `us-east-1` | AWS region for the bucket |
| `ARTIFACT_RELEASE_RETENTION_DAYS` | `180` | Days to keep release artifacts |
| `ARTIFACT_SNAPSHOT_RETENTION_DAYS` | `30` | Days to keep snapshot artifacts |

### iOS-specific secrets (already used by ios-app-store-release.yml)

`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`, `MATCH_GIT_URL`,
`MATCH_PASSWORD`, `MATCH_GIT_BASIC_AUTHORIZATION`, `APPLE_TEAM_IDENTIFIER`,
`ETHOS_CERT_PINS`

### Android-specific secrets (already used by android-play-store-release.yml)

`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`, `ETHOS_CERT_PINS`

---

## Downloading an artifact

### From S3 (recommended — full history)

```bash
# Download the iOS .ipa for v1.4.0
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/EthosProtocol-v1.4.0.ipa \
  EthosProtocol-v1.4.0.ipa

# Verify the checksum
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/EthosProtocol-v1.4.0.ipa.sha256 \
  - | shasum -a 256 -c

# Download the Android AAB and its R8 mapping
aws s3 cp \
  s3://ethos-protocol-artifacts/android/releases/v1.4.0/app-release-v1.4.0.aab .
aws s3 cp \
  s3://ethos-protocol-artifacts/r8-mappings/v1.4.0/mapping-v1.4.0.txt .

# List all releases
aws s3 ls s3://ethos-protocol-artifacts/android/releases/
```

### From GitHub Actions (14-day window only)

Open the workflow run in the GitHub Actions UI and download the artifact named
`ios-ipa-<version>` or `android-release-<version>`.

---

## Rolling back to a previous release

1. Download the artifact from S3 as described above and verify its checksum.
2. **iOS** — use Xcode Organizer or `xcrun altool` / Transporter to upload the
   `.ipa` to App Store Connect (the build is already signed):
   ```bash
   xcrun altool --upload-package EthosProtocol-v1.3.0.ipa \
     --type ios \
     --apple-id <your-apple-id> \
     --asc-provider <team-id>
   ```
3. **Android** — upload the `.aab` to the Play Console's internal track via the
   Play Console UI, or use `bundletool`. Promote it through the tracks once
   verified.

> If the rollback target is more than 180 days old it may have been expired by
> the lifecycle policy. In that case, rebuild from the corresponding git tag:
> `git checkout v1.3.0` and trigger the `artifact-storage` workflow manually.

---

## Managing lifecycle rules manually

To inspect or update the lifecycle rules without triggering a full CI run:

```bash
# Show current rules
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --show-current

# Apply default rules (180 / 30 days) — dry run first
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --dry-run

# Apply with custom retention
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --release-days 365 \
  --snapshot-days 14
```

AWS credentials must be configured locally (`aws configure` or environment
variables) before running the script.

---

## Troubleshooting

**"AccessDenied" on upload**

Verify `AWS_ARTIFACT_ACCESS_KEY_ID` / `AWS_ARTIFACT_SECRET_ACCESS_KEY` are set
in repository secrets and that the IAM user has `s3:PutObject` on the bucket.

**"NoSuchBucket" error**

The bucket named in `ARTIFACT_BUCKET` does not exist or the IAM user does not
have `s3:ListBucket`. Create the bucket in the AWS Console in the region
matching `ARTIFACT_BUCKET_REGION` and ensure versioning is enabled for the
`NoncurrentVersionExpiration` lifecycle rules to take effect.

**"Signing credentials absent — producing unsigned release artifacts"**

The iOS `ASC_KEY_ID` / `MATCH_GIT_URL` secrets or the Android
`ANDROID_KEYSTORE_BASE64` secret are not set. Snapshot builds on `main` can be
unsigned; release tag builds must be signed. Configure the signing secrets
following [docs/ios-app-store-release.md](ios-app-store-release.md) and
[docs/android-play-store-release.md](android-play-store-release.md).

**iOS archive uses `.xcarchive.zip` instead of `.ipa`**

This happens when signing credentials are absent (see above). The `.xcarchive.zip`
contains the compiled app but cannot be installed directly. For a proper `.ipa`
ensure the signing secrets are configured.

**Lifecycle rules not applied after a snapshot push**

The `apply-retention-policy` job only runs after a release tag build or a manual
dispatch. This is intentional — snapshot pushes don't update the rules to avoid
unnecessary API calls. Run the workflow manually or trigger a release tag to
force a rule update.
