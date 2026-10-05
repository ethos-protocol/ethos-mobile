# Android Firebase App Distribution (#462)

Automates beta testing distribution for the Android app via Firebase App Distribution.
Each push to `main` builds a debug APK (or optionally a signed release APK), uploads it
to Firebase, notifies configured tester groups, and attaches build notes generated from
conventional commits since the last tag.

- Workflow: [`.github/workflows/android-firebase-distribution.yml`](../.github/workflows/android-firebase-distribution.yml)
- Lanes: `android/fastlane/Fastfile` → `android firebase_beta` / `android firebase_distribute`
- Release notes: [`.github/scripts/generate_release_notes.py`](../.github/scripts/generate_release_notes.py) `--platform android`
- Plugin: [`fastlane-plugin-firebase_app_distribution`](https://github.com/fastlane/fastlane-plugin-firebase_app_distribution) 0.10.1

For the Google Play production pipeline see [android-play-store-release.md](android-play-store-release.md).

## How a beta distribution flows

```
push to main ──► firebase-distribute job (ubuntu-24.04, JDK 17)
                  1. resolve options (dry_run, build_type, tester groups)
                  2. assembleDebug (or assembleRelease if build_type=release)
                  3. firebase_app_distribution:
                       apk_path: build/fastlane/firebase_beta/app-debug.apk
                       groups: "beta-testers,..."   ← FIREBASE_TESTER_GROUPS variable
                       release_notes: <generated from commits>
                  4. upload APK as a workflow artifact (14-day retention)
```

Testers in the named groups receive an email from Firebase with a direct download link
and installation instructions.

## Secrets and variables

### Required secrets

| Secret | Description |
| --- | --- |
| `FIREBASE_APP_ID` | Firebase app ID (e.g. `1:123456789:android:abcdef012345`) |
| `FIREBASE_TOKEN` | Firebase CI token from `firebase login:ci` |

### Optional secrets (release APK signing only)

| Secret | Description |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded upload keystore (`.jks`/`.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |
| `SENTRY_DSN` | Compiled into release builds when set |

### Repository variables

| Variable | Default | Description |
| --- | --- | --- |
| `FIREBASE_TESTER_GROUPS` | `beta-testers` | Comma-separated Firebase group aliases |

## Getting the Firebase App ID and token

### Firebase App ID

1. Open the [Firebase Console](https://console.firebase.google.com) and select your project.
2. Go to **Project settings** (gear icon) **> Your apps**.
3. Under the Android app (`com.ethosprotocol`), copy the **App ID**
   (format: `1:<project-number>:android:<hash>`).
4. Add it as the repository secret `FIREBASE_APP_ID`.

### Firebase CI token

```bash
firebase login:ci
```

Copy the token printed to stdout and add it as the repository secret `FIREBASE_TOKEN`.

> The token never expires but can be revoked from the Firebase Console. Rotate it
> whenever a team member with Firebase access leaves.

## Setting up tester groups

1. In the Firebase Console, go to **App Distribution > Testers & Groups**.
2. Create groups (e.g. `beta-testers`, `qa-team`, `partners`).
3. Invite testers by email. They receive a one-time invitation to install the Firebase
   App Distribution app (required for iOS; Android installs directly from the email link).
4. Set `FIREBASE_TESTER_GROUPS` to the comma-separated group **aliases** shown in the console
   (e.g. `beta-testers,qa-team`).

## Build notes from commits

Release notes are generated from conventional commits (`feat:`, `fix:`, `perf:`) since the
last `vX.Y.Z` tag via `.github/scripts/generate_release_notes.py --platform android`.
Firebase App Distribution accepts up to 16,384 characters; notes are truncated automatically
if they exceed this limit (the script already applies the Google Play 500-char limit, so
truncation only occurs for manual override text).

To override build notes:

- Set the `release_notes` input on a manual workflow run, or
- Commit a hand-written override to `android/fastlane/release_notes/<version>/en-US.txt`.

## Fastlane lanes

### `android firebase_beta`

Builds an APK and distributes it. Called by the CI workflow.

```sh
# Dry run (no build, no upload):
bundle exec fastlane android firebase_beta dry_run:true

# Distribute debug APK to default tester groups:
bundle exec fastlane android firebase_beta

# Distribute to specific groups with custom notes:
bundle exec fastlane android firebase_beta \
  groups:"qa-team,partners" \
  release_notes_override:"This build fixes the login screen crash."

# Distribute a release (signed) APK:
bundle exec fastlane android firebase_beta build_type:release
```

### `android firebase_distribute`

Distributes an APK that was already built in a previous step. Useful when a CI job builds
the APK separately (e.g. an existing `android-ci.yml` artifact) and you only want to
run the Firebase upload.

```sh
bundle exec fastlane android firebase_distribute \
  apk_path:"android/app/build/outputs/apk/debug/app-debug.apk" \
  groups:"beta-testers"
```

## Workflow inputs (manual dispatch)

| Input | Default | Effect |
| --- | --- | --- |
| `dry_run` | **true** | Validates config, prints what would be distributed; uploads nothing. |
| `build_type` | `debug` | `debug` or `release`. Release requires signing secrets. |
| `groups` | *(from variable)* | Override the tester groups for this run only. |
| `release_notes` | *(generated)* | Override build notes for this run only. |

## Maintaining the `fastlane-plugin-firebase_app_distribution` version

The plugin version is pinned in the repo-root `Gemfile`:

```ruby
gem "fastlane-plugin-firebase_app_distribution", "0.10.1"
```

To upgrade: bump the version, run `bundle update fastlane-plugin-firebase_app_distribution`
from the repo root, commit both `Gemfile` and `Gemfile.lock`.

## Maintainer checklist (first-time setup)

- [ ] Add `FIREBASE_APP_ID` and `FIREBASE_TOKEN` as repository secrets.
- [ ] Create at least one tester group in the Firebase Console and add testers.
- [ ] Set `FIREBASE_TESTER_GROUPS` as a repository variable (e.g. `beta-testers`).
- [ ] Ensure `google-services.json` is present in `android/app/` (already required for FCM).
- [ ] Run the workflow manually with `dry_run: true` and verify the step summary.
- [ ] Run with `dry_run: false` to upload the first real beta build.
- [ ] Verify testers receive the Firebase distribution email.
