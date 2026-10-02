# Performance Optimization Guide

> **Audience:** Engineers profiling or optimizing the iOS and Android clients.
> **Scope:** Startup, rendering, networking, storage, and background work.
> **Not in scope:** Building a release — see [ios-app-store-release.md](ios-app-store-release.md).

Performance work here is measurement-driven. The app already instruments itself with
`PerformanceMonitor` on both platforms; this guide covers what those tools can and cannot
tell you, how to get a trustworthy number, and the practices that matter for a vault app
in particular.

Read [§8](#8-known-gaps) before trusting any existing metric. Several current
measurements do not mean what their names suggest.

## Table of contents

1. [Performance budgets](#1-performance-budgets)
2. [Profiling tools](#2-profiling-tools)
3. [Optimization best practices](#3-optimization-best-practices)
4. [Benchmarking methodology](#4-benchmarking-methodology)
5. [Performance checklist for PRs](#5-performance-checklist-for-prs)
6. [Platform build configuration](#6-platform-build-configuration)
7. [Existing performance documentation](#7-existing-performance-documentation)
8. [Known gaps](#8-known-gaps)
9. [References](#9-references)

---

## 1. Performance budgets

Budgets are shared by both platforms so a fix on one is measurable on the other. The
values below match the README's alert-threshold table; the operative source is
`PerformanceThresholds` on each platform, and the cold-start row currently comes from
`APMConfiguration`, which is dead code — see [§8.2](#82-measurements-that-do-not-mean-what-they-say).

| Metric | Slow (warn) | Critical (alert) | Source |
|--------|-------------|------------------|--------|
| API response | > 2 000 ms | > 5 000 ms | `PerformanceThresholds` |
| Screen transition | > 500 ms | > 2 000 ms | `PerformanceThresholds` |
| App cold start | > 3 000 ms | — | `APMConfiguration` only |
| Widget refresh | 15 min urgent / 60 min normal | — | `VaultStatusWidget.kt` |
| Build size delta | +5% vs. base | — | `track_build_size.py` |

- **MUST** treat a budget breach as a defect with an owner, not as a warning to
  acknowledge. A slow path in a vault app is a security-relevant path — it usually
  means a lock-screen or session check is running off the main thread.
- **MUST** record a baseline before optimizing. Without a before-number, an
  optimization cannot be distinguished from a change in test conditions.
- **SHOULD** keep budgets in one place. Today thresholds are defined twice per
  platform (`PerformanceThresholds` and the unused `APMConfiguration`) — see
  [§8](#8-known-gaps).

---

## 2. Profiling tools

### 2.1 In-app instrumentation (both platforms)

| Capability | iOS | Android |
|------------|-----|---------|
| Engine | `os_signpost` via `OSLog`, subsystem `com.ethosprotocol` | Firebase Performance (`firebase-perf-ktx`) + in-memory buffers |
| Singleton | `PerformanceMonitor.shared` | `PerformanceMonitor` object |
| Thresholds | `PerformanceThresholds.defaults` (2000 / 500 ms) | `PerformanceThresholds` (2000 / 500 ms) |
| Retention | 100 API, 50 screen | 100 API, 50 screen |
| Percentiles | p50 / p95 / p99 | p50 / p95 / p99 |
| Screen hook | `.trackScreen("Name")` view modifier | `TrackScreen("Name")` composable |
| Reset | `PerformanceMonitor.shared.reset()` | `PerformanceMonitor.reset()` |
| Console | Instruments | Firebase Console → Performance |

Both platforms time every API call and every instrumented screen, with no sampling.
Retention buffers are bounded, so the aggregate percentiles cover a rolling window
only — a cold start measured minutes after launch is not in the buffer.

- **MUST** run `reset()` before measuring, or percentiles will mix runs.
- **MUST** instrument any new screen or network path you add. The existing coverage
  is a handful of screens (`trackScreen` appears in `Views.swift` and `SettingsView.swift`;
  `TrackScreen` in `Screens.kt` and `NotificationPreferencesScreen.kt`) and coverage
  gaps are invisible — an untraced screen is not a fast screen.
- **NEVER** log a full request or response body from a trace. Traces go to a third-party
  console on Android. See the security guidelines, "Data protection and logging".

### 2.2 iOS Instruments

The `os_signpost` intervals appear natively in Instruments with no extra setup.

- **Time Profiler** — main-thread CPU. The first tool to reach for on a jank or
  startup investigation.
- **Allocations** — retain/release cycles, and whether a heavy object is actually
  released. The `filteredAndSortedVaults` pattern in [§3.2](#32-lists-and-recomposition)
  is exactly the kind of allocation churn this reveals.
- **Leaks** — the browser-style cycle graph. `VaultRowView` observing a store
  observable is a likely candidate.
- **SwiftUI instrument** (Instruments 15+) — body evaluation counts. This is the
  fastest way to confirm a recomposition problem before changing code.
- **Animation Hitches** — frame timing for scroll and transition jank.
- **Network** — request waterfall, including time spent waiting on the token-refresh
  single-flight.

`StartupTracing.swift` provides a ready-made signpost interval scheme for cold start.
Its intervals are defined but have **no call sites** today — see
[§8](#8-known-gaps).

### 2.3 Android profilers

- **Android Studio Profiler** — CPU, memory, network, energy.
- **Macrobenchmark** — cold, warm, and hot start plus frame timing, run on a physical
  device. `StartupBenchmark.kt` in `src/androidTest` is a starting point, but it is
  **not currently runnable** — see [§8](#8-known-gaps).
- **Baseline Profiles** — no `baseline-prof.txt` exists. This is the highest-value
  missing tool: it lets ART AOT-compile the startup path and typically cuts cold start
  by 20-30% with no source changes.
- **Battery Historian / `adb shell dumpsys batterystats`** — background work cost.
- **Macrobenchmark `FrameTimingMetric`** — no jank measurement exists anywhere in the
  repo. Any "the list scrolls fine" claim today is an impression, not a measurement.

### 2.4 Build-size tracking

`.github/scripts/track_build_size.py` reports APK and IPA size and compares against a
base when given a `--base-size`. Both CI steps currently pass only the platform
argument, so the 5% regression check never evaluates, and there is **no absolute
budget** — only a disabled relative one. See [§8](#8-known-gaps).

### 2.5 Profiling ground rules

- **MUST** profile on a physical device. Simulators and emulators report CPU and
  memory behaviour that does not match real hardware, and both platforms' performance
  characteristics differ most exactly where the emulator is least representative.
- **MUST** release-build when measuring anything user-facing. R8 removes `Log.v/d/i`
  on Android (`proguard-rules.pro`), and debug logging distorts iOS timings enough to
  flip a verdict. Debug and release are different programs.
- **MUST** disable animations or note their presence — a transition mid-measurement
  invalidates the number.
- **MUST** run at least three times and report the median. First-run costs (cold disk
  cache, JIT, first network resolve) make a single run meaningless.
- **MUST** record device, OS version, build type, and network conditions alongside any
  number you report. A measurement without those is not reproducible.
- **NEVER** optimize against a synthetic model. `VaultListPerformanceTest` on iOS
  builds 100 `Vault` structs in memory and asserts available-memory delta; that tests
  model construction, not the list, not layout, and not scrolling. Prefer a real
  screen with a real dataset.

---

## 3. Optimization best practices

Ordered by how often they matter for this app. Startup and rendering dominate; the
lower sections matter mainly when a screen is added to a vault-heavy workflow.

### 3.1 Startup

Everything before the first frame is dead time the user is staring at a launch screen.

- **MUST** keep `Application.onCreate()` on the iOS equivalent of the main thread to
  work that is genuinely required (DI graph, crash reporter init). Everything else is
  deferred. `EthosProtocolApplication` already defers widget updates by 2s and the
  version check by 3s — that is the pattern to copy.
- **MUST** not perform network I/O on the main thread during startup. Note that Hilt
  injects `ApiClient` — and therefore constructs a Ktor engine with `ContentNegotiation`
  — into `AppVersionChecker` at graph-build time. Deferring the *call* does not defer
  the *client construction*. Verify with a trace before assuming a deferral worked.
- **MUST** not read iCloud or disk synchronously in an `App` initializer.
  `EthosProtocolApp.init` calls `ICloudSyncService.shared.restoreFromICloud()` on the
  main thread before first frame; that is a file reconciliation during launch. Move it
  behind the first frame and gate dependent UI on its result.
- **SHOULD** keep `@StateObject` / `Hilt` construction cheap. The three root
  `@StateObject`s in `EthosProtocolApp` are created eagerly, so each initializer's cost
  lands on startup.
- **SHOULD** consider an Android `SplashScreen` keep-on-screen condition tied to real
  first content, so the launch experience reflects the actual measured start rather
  than a fixed timeout.
- **SHOULD** add a Baseline Profile once `src/benchmark` exists. It is the single
  largest cold-start win available without touching feature code.

Measure cold start as: process launch → first frame the user can interact with. That
is distinct from "time to first API response" and from the current
`getFirstFrameTimeMs()`, which is documented in [§8](#8-known-gaps).

### 3.2 Lists and recomposition

- **MUST** pass the minimum data a row needs, not the whole store. `VaultRowView`
  takes the entire `VaultStore` as an `@EnvironmentObject`, so any mutation to any
  vault invalidates every visible row. Pass the row's own value.
- **MUST** memoize derived collections. `VaultStore.filteredAndSortedVaults` is an
  unmemoized computed property performing a filter plus sort, and it is read three
  times per list body — three full passes per render. Hoist it into a `@Published`
  value recomputed in `didSet`, or compute once into a local `let` and reuse it.
- **MUST** not write to `UserDefaults` on every keystroke. `searchText.didSet` calls
  `saveSearchPreferences()` synchronously, so every character in the search field
  triggers un-debounced disk I/O on the main thread. Debounce the persistence, or
  write on `didSet` only when the value is committed.
- **MUST** use lazy containers with stable keys. Both platforms do this correctly
  today — iOS uses a lazy `List`, Android uses a keyed `LazyColumn` with page size 20.
  Preserve that when adding rows.
- **SHOULD** isolate expensive derived state on Android with `derivedStateOf` so a
  derived list is recomputed when its inputs change rather than on every recomposition.
  There is no `derivedStateOf` usage in the repo.
- **SHOULD** move 1 Hz ticking `@State` off the render path. The vault detail screen
  updates a `@State` value every second, which invalidates the body even when nothing
  visible changes; scope the timer to the specific subview that displays it.
- **SHOULD** mark UI state classes `@Immutable` (Android already does for
  `VaultUiState`) and expose them via `StateFlow` collected with lifecycle awareness.

### 3.3 Networking

- **MUST** set explicit timeouts on the iOS `URLSession`. `APIClient` uses
  `.default`, so `timeoutIntervalForRequest` is 60 seconds and
  `timeoutIntervalForResource` is 7 days. On a captive-portal network a request can
  hang for a minute, and every request that calls `ensureFreshToken()` waits behind
  it. Set both, plus `httpMaximumConnectionsPerHost`, and size `URLCache` explicitly.
- **MUST** cap retry backoff. `RetryPolicy` on Android caps the delay at 30 seconds;
  the iOS implementation has the same jittered exponential backoff with **no maximum
  cap**, so a long outage produces unbounded waits. Match the cap.
- **MUST** bound the offline cache size check. `OfflineSupport.save` calls
  `enforceSizeCap()` on every successful write, and that method enumerates the cache
  directory and calls `resourceValues` per entry — a directory listing plus N `stat`
  calls on every cache write, inline on the caller's path. Enforce the 20 MB cap
  incrementally (track size on write) or amortize it, rather than rescanning.
- **MUST** not serialize unrelated requests behind a single token refresh. Every
  request calls `ensureFreshToken()` first, and concurrent refreshes funnel through
  one coordinator — so a slow refresh stalls unrelated calls. This is the head-of-line
  blocking problem; a request that does not need the token should not wait for it.
- **SHOULD** deduplicate concurrent GETs. Android has single-flight for GETs
  (`ApiClient`); iOS does not. Two screens fetching the same vault list at once issue
  two requests.
- **SHOULD** avoid re-serializing a response in order to cache it. A second full JSON
  encode per successful GET is pure overhead when the raw `Data` is already in hand.
- **SHOULD** keep retry GET-only. Retrying a mutation without an idempotency key can
  double-spend; see "Key management guidelines" in the security guidelines.
- **SHOULD** consider `waitsForConnectivity` and connection-pool tuning. Neither is
  configured on either client.

### 3.4 Storage

- **MUST** not compute a count by loading and decoding the whole store.
  `PendingActionStore.count` reads and JSON-decodes the entire file to return an `Int`,
  and it backs the notification badge — so every badge update is a full deserialization.
  Maintain a counter alongside the data.
- **MUST** not read-modify-write the entire file on every insert or delete without
  considering the cost. `PendingActionStore` does exactly that, with `.prettyPrinted`
  encoding (larger bytes on every write) and `.atomic` (temp file plus rename, doubled
  I/O), and it runs on a private serial queue via `queue.sync` — meaning a user action
  on the main thread blocks on a synchronous file read and write.
- **MUST** index columns used in `ORDER BY`. The pending-action queue is drained with
  `SELECT * FROM pending_actions ORDER BY queuedAt ASC` with a single `UNIQUE` index
  on `dedupeKey` and no index on `queuedAt` — a full scan and sort on every sync.
- **MUST** not query the same table twice in a row. The sync worker calls
  `dao.getAll()` three times per run, two of them back-to-back, each materializing the
  full table.
- **MUST** not `fallbackToDestructiveMigration()` on a queue holding financial user
  intent. `PendingActionDatabase` does, which means any schema change silently wipes
  every queued action. Add a real `Migration`; the cost of a migration is trivial next
  to the cost of the data loss. See also `exportSchema = false`, which prevents
  Room auto-migration and blocks `MigrationTestHelper` entirely.
- **MUST** not dedupe client-side what a unique index already prevents. The worker's
  `compactConflicts` groups and deletes duplicates that cannot exist, given the
  `UNIQUE(dedupeKey)` index and `OnConflictStrategy.REPLACE`.
- **MUST** cap the pending queue size with oldest-first eviction. The offline guide
  claims a 50-item cap; no cap exists on either platform. An unbounded queue of
  serialized mutations is both a disk and a sync-cost problem.
- **SHOULD** carry an idempotency key on every queued mutation on both platforms.
  Android's `PendingAction` has one; iOS's does not — so a retried iOS queued
  action has no stable key.
- **SHOULD** stop the sync drain from retrying the whole batch when one item fails. A
  single retryable failure returns `Result.retry()` and re-attempts every item from
  the top, with no per-item attempt counter, so one bad row can block the queue
  indefinitely while repeatedly redoing successful work.

### 3.5 Background work

- **MUST** respect the OS refresh floors. See
  [widget-refresh-budget.md](widget-refresh-budget.md) for the WidgetKit and Android
  ceilings, and [background-task-scheduling.md](background-task-scheduling.md) for
  what each client actually registers.
- **MUST** understand `ExistingPeriodicWorkPolicy.UPDATE` before using it for periodic
  work. `VaultWidgetUpdateWorker` uses `UPDATE`, which resets the period clock on every
  enqueue — and because the interval is recomputed and re-enqueued on every widget
  update, frequent updates can perpetually postpone execution. This is not mentioned
  in any existing doc.
- **MUST** not ship a background schedule whose worker class does not exist.
  `BackgroundTaskScheduler` references `SyncWorker` and `AnalyticsFlushWorker`, neither
  of which is defined, so the 15-minute sync and analytics flush it documents never
  run.
- **MUST** delete dead code that duplicates live scheduling logic.
  `BackgroundTaskConfiguration.computeWidgetRefreshInterval` has no call sites and
  duplicates `TTLTimelineProvider.computeNextUpdateInterval` switch-for-switch. Two
  copies of an interval policy will drift.
- **SHOULD** keep deferred startup work deferred, and cite the issue as
  `EthosProtocolApplication` does.
- **SHOULD** keep widget and background network work off the main thread and bounded.
- **SHOULD** align urgency tiers across platforms. iOS refreshes at 15/10/5/2 minutes
  by urgency; Android uses 15 or 60 only. The widget budget doc recommends a 2-minute
  Android tier that the code does not implement.

### 3.6 Memory and images

- **MUST** not add a bitmap image pipeline without downsampling and caching. Both
  platforms are currently vector-only (SF Symbols, Material icons) with no bitmap
  assets, no Coil/Glide, and no `AsyncImage`. That is a good position and the cheapest
  one to maintain — introducing remote images is a real project, not an incidental
  change.
- **MUST** purge caches on sign-out and under memory pressure, not only on launch.
- **NEVER** add a global cache keyed on theme-dependent values without theme
  invalidation. `utils/AssetCache.kt` is an unreferenced `object` that caches colors
  globally; if wired up as-is it would serve stale colors after a theme switch. Delete
  it or fix it before use.
- **SHOULD** delete unused code rather than leaving it "for later" — see the dead
  `AssetCache`, `BackgroundTaskConfiguration` functions, and the Android
  `TokenRefreshManager`, whose `extendExpiry` rewrites only the local `expiresAt` with
  no server call and would be a session-lifetime bug if wired up.

### 3.7 Concurrency and main-thread discipline

- **MUST** keep file and database I/O off the main thread. `PendingActionStore` uses
  `queue.sync`, which blocks the caller.
- **MUST** cancel work that is no longer needed. A WebSocket subscription created per
  vault in `ui/ViewModels` is re-opened on every `load`/`loadMore`, accumulating
  listeners against the shared `VaultEventSocket`. Guard with a distinct-until-changed
  flow or cancel the previous job.
- **SHOULD** preserve the token-refresh single-flight mutex when refactoring. It
  prevents a refresh stampede under concurrency.
- **SHOULD** keep dependency graphs scoped correctly — the Room database and DAO are
  correctly `@Singleton`-scoped, and new dependencies should follow.

---

## 4. Benchmarking methodology

A benchmark that cannot fail is not a benchmark. This section defines what a usable one
looks like in this repo, because the current attempts do not meet the bar — see
[§8](#8-known-gaps).

### 4.1 What a benchmark must do

1. **Assert.** A test that prints a number and passes unconditionally does not gate
   anything. It is a profiler run wearing a test's clothes.
2. **Have a stored baseline.** Compare against a committed number, not against nothing.
3. **Use a fixed workload.** Same input size, same shape, every run. A benchmark over
   synthetic data measures the synthetic generator.
4. **Run the real code path.** Through the real UI, the real serializer, the real
   database. A benchmark that calls a model constructor measures the model.
5. **Be deterministic enough to fail on a real regression** and stable enough not to
   fail every other day.

### 4.2 iOS: XCTest `measure`

There is currently **no iOS benchmark of any kind** — no `measure {}`, no
`XCTMetric`, no `XCTClockMetric` anywhere in the repo. When adding one, use the
framework's timing rather than hand-rolled `os_proc_available_memory()` arithmetic.

```swift
final class VaultListPerformanceTests: XCTestCase {

    /// Gate: median scroll-to-interactive must stay under the 500 ms screen budget.
    func testVaultListRowInitialisationBudget() {
        let vaults = Fixtures.vaults(count: 100)
        measure(metrics: [XCTClockMetric(), XCTMemoryMetric()]) {
            _ = vaults.map(VaultRowViewModel.init(vault:))
        }
    }
}
```

- **MUST** use `XCTClockMetric` for time and `XCTMemoryMetric` for memory. Hand-rolled
  memory deltas are not reliable — `os_proc_available_memory()` is a coarse system
  value that moves for reasons unrelated to your allocations, and a 50 MB tolerance
  around it asserts almost nothing.
- **MUST** assert against a committed baseline with an explicit tolerance. Typical
  tolerance is 5-10%; tighter produces false failures on shared CI runners.
- **MUST** set `measureOptions` to control iteration count, or the first iteration's
  lazy-allocation cost will dominate the result.
- **SHOULD** use a real fixture file rather than a loop generating structs in a
  `@Testable` unit test — `UITests/VaultListPerformanceTest.swift` can drive the actual
  screen.
- **NOTE** `xctrace` and XCTest performance tests are not currently run in CI
  (`ios-ci.yml`). A benchmark that only a developer can run by hand will not be
  maintained. See [§5](#5-performance-checklist-for-prs).

### 4.3 Android: Macrobenchmark

`StartupBenchmark.kt` exists in `src/androidTest` and is a good sketch of what is
wanted — cold/warm/hot start and a baseline-profile-guided scenario — but it cannot run
today. Five independent blockers, all in [§8](#8-known-gaps). In summary: wrong source
set, the `androidx.benchmark` Gradle plugin is never applied,
`benchmark-macro-junit4` is absent from `libs.versions.toml`, `assertNoRegression` is
defined but never called, and the CI step titled "Parse benchmark results" parses
nothing and always exits 0.

To make it real, the setup is:

```kotlin
// android/app/build.gradle.kts
plugins {
    alias(libs.plugins.androidxBenchmark)
}
android {
    buildTypes {
        create("benchmark") {
            // Macrobenchmark needs a non-debuggable variant matching the release
            // profile; a debuggable build measures the wrong thing entirely.
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}
```

```kotlin
// android/app/src/androidTest/... — or src/benchmark, see §8
@Test
fun startupCold() = MacrobenchmarkRule().measureRepeated(
    packageName = TARGET_PACKAGE,
    metrics = listOf(StartupTimingMetric()),
    iterations = 10,
    startupMode = StartupMode.COLD,
    setupBlock = { pressHome(); device.waitForIdle() }
) {
    startActivityAndWait()
}
```

- **MUST** run Macrobenchmark against a **physical device** or a properly configured
  emulator with a fixed API level. Numbers from different device classes are not
  comparable.
- **MUST** set `iterations` to at least 10 and compare **median** or
  `runWithTimingError`, not mean. Startup measurements are heavily right-skewed and a
  mean will be dominated by a single scheduler hiccup.
- **MUST** add `FrameTimingMetric` to any scroll or animation benchmark. There is no
  jank measurement in the repo at all, so "scrolling is fine" is currently an
  impression.
- **MUST** call the regression assertion. A benchmark that records and does not assert
  is a reporting tool, and should be named as one.
- **MUST** build the `benchmark` build type, not `assembleRelease`, when running
  Macrobenchmark.
- **SHOULD** generate a Baseline Profile with
  `androidx.benchmark` macrobenchmark's profileinstaller, and commit
  `src/main/baseline-prof.txt`. None exists today; this is the cheapest available
  cold-start win.
- **SHOULD** keep the baseline file in the repo and treat a large diff as a reviewed
  change, not as build noise.

### 4.4 Choosing a threshold

- **MUST** derive thresholds from the budgets in [§1](#1-performance-budgets), not from
  whatever the current measurement happens to be. A threshold that encodes today's
  number as "good" ratchets performance backwards.
- **MUST** leave headroom. Set the gate below the user-facing budget — a gate at the
  budget lets the budget be the average experience rather than the worst case.
- **SHOULD** re-baseline deliberately, in its own commit with a written justification,
  so a threshold change is visible in review history rather than buried in a
  refactor.
- **NEVER** set a threshold to make a failing benchmark pass without understanding the
  cause. That converts a real signal into noise.

### 4.5 Before and after

Every performance change in a PR should state:

1. The metric, the platform, and the device/OS used.
2. The **before** number, from the same conditions.
3. The **after** number.
4. The build type (debug/release) and whether animations were disabled.
5. The number of iterations and whether the reported figure is median, p95, or mean.

If you cannot supply a before-number, say so in the PR. "Feels faster" is not evidence,
and an unreproducible number is worse than an honest absence of one.

### 4.6 Worked example

```markdown
- Metric: cold start to first frame
- Device: Pixel 7, Android 14, release build, animations on
- Iterations: 10, median

| Build | Median cold start |
|-------|-------------------|
| Base (`a64ce28`) | 1 840 ms |
| This PR (deferred iCloud restore) | 1 210 ms |

p99 was 3 100 ms before and 2 400 ms after. The 2 000 ms critical screen budget
is not breached in either case. Measured with `StartupPerformance.getColdStartTimeMs()`
after fixing the first-frame capture order — see §8.
```

---

## 5. Performance checklist for PRs

Copy into the PR description when the change could plausibly affect performance. If a
section does not apply, say so rather than deleting it.

### Applies to every PR

- [ ] No network, disk, or database I/O added on the main thread or in an initializer.
- [ ] No new blocking call (`queue.sync`, `Thread.sleep`, `runBlocking`, `DispatchQueue.sync`).
- [ ] No new unbounded loop, collection growth, or retry without a cap.
- [ ] No new polling timer; any new periodic work respects the OS floors in
      [widget-refresh-budget.md](widget-refresh-budget.md).
- [ ] Any new work that can be deferred is deferred past the first frame.
- [ ] Any new screen or network path is instrumented with `.trackScreen(...)` /
      `TrackScreen(...)` and the relevant `PerformanceMonitor` trace.
- [ ] Any new log statement is excluded from release impact — Android R8 strips
      `Log.v/d/i`, but `print`/`NSLog` are not covered.

### If the PR adds or changes a screen (additional)

- [ ] The list is lazy and keyed; the row receives only the data it renders, not a
      whole store observable.
- [ ] Derived collections are memoized and computed once per render, not read
      repeatedly from a computed property.
- [ ] No `UserDefaults` or `SharedPreferences` write on every keystroke or per frame.
- [ ] Fast-changing state (ticks, progress) is scoped to the smallest subview that
      displays it.
- [ ] Checked with a real dataset at the size a real user has (100+ vaults), not a
      3-item sample.
- [ ] Checked with animations both on and off, on a physical device.

### If the PR touches networking or storage (additional)

- [ ] Explicit timeouts on the iOS `URLSession` (request **and** resource).
- [ ] Retry backoff is capped; a long outage cannot produce unbounded waits.
- [ ] New cache or store reads are not full-collection loads; counts are not computed
      by loading the whole store.
- [ ] Any new query is indexed on the columns it filters or orders by.
- [ ] No duplicate identical queries in one code path.
- [ ] The sync drain still makes progress when one item fails (per-item retry, not
      whole-batch).
- [ ] Pending queue growth is capped.

### If the PR claims a performance improvement (additional)

- [ ] A **before** number is included, measured under the same conditions.
- [ ] At least 3 iterations (10 for Macrobenchmark); the reported figure is median or
      p95, not mean.
- [ ] Measured on a physical device, in a release build.
- [ ] Device, OS version, build type, and network conditions are recorded.
- [ ] The change is a real optimization, not a threshold raise. If a baseline was
      moved, that is stated explicitly and justified.

### Reviewer sign-off

- [ ] Any "must" above is satisfied or explicitly waived in the PR description.
- [ ] No new benchmark or timing assertion that cannot fail, cannot run in CI, or
      asserts against a number with no baseline behind it.
- [ ] Build-size impact is within budget, or the increase is intentional and explained.

---

## 6. Platform build configuration

### 6.1 Android

| Setting | Value | Note |
|---------|-------|------|
| AGP / Kotlin / KSP | 8.7.3 / 2.1.0 / 2.1.0-1.0.29 | `libs.versions.toml` |
| `isMinifyEnabled` (release) | `true` | `build.gradle.kts` |
| `isShrinkResources` | not set | Enabling it is a straightforward size win |
| Staging variant | `initWith(release)` | Inherits minification — good, catches dead-stripping early |
| Log stripping | `Log.v/d/i` removed at compile time | `proguard-rules.pro`, `-assumenosideeffects` |
| Benchmark build type | **absent** | Plugin never applied; see [§4.3](#43-android-macrobenchmark) |
| Baseline profile | **absent** | No `baseline-prof.txt` |

- **MUST** keep `Log.w`/`Log.e` in mind as the retained set when reasoning about
  release log volume — they are deliberately not stripped.
- **SHOULD** set `isShrinkResources = true` for release once the resource shrinker is
  validated against the Paparazzi screenshot suite, which would catch any layout a
  shrinker wrongly removes.
- **NEVER** disable minification to work around a build problem. The log-stripping
  rule is also a secret-leak control; see
  the security guidelines, "Data protection and logging".

### 6.2 iOS

| Setting | Value | Note |
|---------|-------|------|
| Configurations | `Debug: debug`, `Release: release`, `Staging: release` | Staging inherits release optimizations — good |
| `SWIFT_OPTIMIZATION_LEVEL` | **not set** | Relies on Xcode defaults for `release` |
| `SWIFT_COMPILATION_MODE` | **not set** | Whole-module optimization is not explicitly requested |
| Dependencies | system frameworks only | `Package.swift` has `dependencies: []` |
| Widget target | recompiles `Models` + 7 service files | `ios/EthosProtocol/project.yml`; inflates the extension with `APIClient` |

- **SHOULD** pin `SWIFT_OPTIMIZATION_LEVEL` and `SWIFT_COMPILATION_MODE` explicitly for
  release rather than depending on undocumented defaults, so a toolchain change cannot
  silently alter binary characteristics.
- **SHOULD** raise `-Owhole-module-optimization` for release. It is free at runtime and
  the reason it is often off (longer builds) does not apply to a shipped binary.
- **NOTE** the widget's dual-target-membership trick compiles `APIClient` — with
  retry, pinning, and the offline cache — into the extension. That is real binary size
  for a widget that mostly reads cached data.
- **NOTE** `iOS` is not subject to R8. The equivalent win is already had: no
  third-party dependencies, so there is little to strip.

---

## 7. Existing performance documentation

Read these before assuming a performance question is unanswered.

| Document | Covers | Read it when |
|----------|--------|--------------|
| [widget-refresh-budget.md](widget-refresh-budget.md) | OS refresh ceilings for both widget platforms | Adding or changing any refresh or timeline interval |
| [background-task-scheduling.md](background-task-scheduling.md) | What each client actually registers for background work | Touching BGTaskScheduler or WorkManager |
| [offline-first-guide.md](offline-first-guide.md) | Offline queue and cache design | Touching the pending-action queue or the response cache |
| [multidex.md](multidex.md) | Dex limits and method-count considerations | Adding dependencies that add methods |
| [proguard-configuration.md](proguard-configuration.md) | R8 rules and keep rules | Changing minification or adding reflective code |
| [ui-test-architecture.md](ui-test-architecture.md) | XCUITest layout and why it is advisory in CI | Adding UI tests |
| [manual-qa-checklist.md](manual-qa-checklist.md) | Manual pre-release passes | Before a release; has a performance regression pass |
| README § *App Performance Monitoring* | APM usage and alert thresholds | Wiring up instrumentation |

> [!NOTE]
> Several of these documents have drifted from the code they describe — see
> [§8](#8-known-gaps). Treat the code as authoritative and the doc as a starting point.

---

## 8. Known gaps

Documented so nobody mistakes current behaviour for a target, and so new work does not
copy a known defect. This is not a disclosure list; report anything new through the
private channel in [SECURITY.md](../SECURITY.md).

### 8.1 The build is currently broken

Two files contain committed truncation markers, so nothing below was verified by
running it:

- `android/app/build.gradle.kts` — the `dependencies {}` block is gone, along with
  most of the release security pins.
- `android/app/src/main/java/com/ethosprotocol/api/Infrastructure.kt` — truncated
  mid-expression.

Several types referenced by live code (`TokenProvider`, `EncryptedTokenProvider`,
`OfflineCache`, `SyncWorker`, `AnalyticsFlushWorker`) are absent. Every finding in this
guide is static analysis against the source text. **No benchmark was run and no profile
was captured**, because no build succeeds at this commit. Fix the build before acting on
any number here.

### 8.2 Measurements that do not mean what they say

| Measurement | Actual behaviour | Location |
|-------------|------------------|----------|
| "Screen load time" | Measures **visible lifetime** (`.onAppear` → `.onDisappear`), not time-to-interactive | `ScreenTracingModifier.swift`, `ScreenTracingEffect.kt` |
| `getFirstFrameTimeMs()` | Records the pre-draw timestamp *before* registering the pre-draw listener; the real value is logged and discarded | `StartupPerformance.kt` |
| `APMConfiguration` | **Dead code** — zero references on either platform; `PerformanceThresholds` is the operative set | iOS + Android |
| Startup threshold (3 000 ms) | Published in the README, evaluated on neither platform | `APMConfiguration` |
| `StartupTracing` (iOS) | Signpost intervals defined, **never called** | `StartupTracing.swift` |
| Screen coverage | 3-4 screens per platform, added manually — the README's "no user action required" overstates it | `Views.swift`, `Screens.kt` |
| Log subsystem | Three subsystem strings in one app; filtering by the documented one hides the others | `PerformanceMonitor` vs `BackgroundRefreshService` |

The screen-lifetime metric is the most consequential: a screen read for 30 seconds is
recorded as a 30 000 ms *critical* load against a 500 ms threshold, which makes the
metric unusable and trains reviewers to ignore it. Fix the semantics, or rename the
metric to `screenVisibleMs` so it stops reporting as a load time.

### 8.3 Benchmarking that cannot run

| Item | Blocker |
|------|---------|
| iOS benchmarks | None exist at all — no `measure {}`, no `XCTMetric` |
| `StartupBenchmark.kt` | In `src/androidTest`, not `src/benchmark`; also runs inside the normal `connectedDebugAndroidTest` job where `MacrobenchmarkRule` cannot work |
| `androidx.benchmark` plugin | Declared nowhere and never applied, so there is no `benchmark` build type and `connectedBenchmarkDebugAndroidTest` does not exist |
| `benchmark-macro-junit4` | Absent from `libs.versions.toml`; required for `MacrobenchmarkRule` |
| `assertNoRegression` | Defined at `StartupBenchmark.kt:201`, called zero times |
| CI "Parse benchmark results" | Parses nothing and always exits 0 |
| Baseline profile | No `baseline-prof.txt`, so the `Partial` compilation scenario cannot pass |
| Frame/jank timing | No `FrameTimingMetric` or equivalent anywhere |

Either complete the setup or delete the file and its CI job. As written it costs a CI
runner and gates nothing.

### 8.4 Build-size tracking that cannot succeed

Both CI invocations of `track_build_size.py` fail on a path bug, and neither passes a
base size, so the 5% regression check is permanently disabled:

- Android: the job sets `working-directory: android`, then the script's hardcoded
  `android/app/build/outputs/...` resolves to `android/android/app/...` →
  `sys.exit(1)`.
- iOS: the job sets `working-directory: ios/EthosProtocol`, and the script globs
  `ios/EthosProtocol/Xcode/...` → `ios/EthosProtocol/ios/EthosProtocol/...`. The job
  also builds a **simulator Debug** archive while the script looks for
  `Release-iphoneos`, so the two can never agree even after the path is fixed.
- There is **no absolute size budget** anywhere — only the disabled relative check.

### 8.5 Performance defects worth fixing first

Ordered roughly by user impact per unit of effort.

1. `OfflineSupport.enforceSizeCap` — directory scan plus N `stat` calls on every cache
   write, inline on the caller's path.
2. `Stores.filteredAndSortedVaults` — unmemoized filter+sort read three times per list
   render.
3. `Stores.searchText.didSet` — synchronous `UserDefaults` write per keystroke.
4. `APIClient` — no request/resource timeout on the iOS `URLSession`.
5. `PendingActionSyncWorker` — `getAll()` called three times per run, two back-to-back.
6. `PendingActionStore.count` — full file read and JSON decode to return an `Int`.
7. `PendingAction` — `ORDER BY queuedAt` with no index on `queuedAt`.
8. `RetryPolicy` (iOS) — exponential backoff with no maximum cap.
9. `ui/ViewModels` — a WebSocket listener accumulated per `load`/`loadMore`.
10. `VaultStatusWidget` — `ExistingPeriodicWorkPolicy.UPDATE` can perpetually postpone
    periodic work.
11. `VaultRowView` — observes the whole store, invalidating every visible row.
12. 1 Hz `@State` tick invalidating the whole vault-detail body.

### 8.6 Correctness and data-loss defects found while auditing

These are not performance issues but were found in the same code, and two of them make
performance instrumentation untrustworthy. They are listed so they are not lost; each
deserves its own issue.

- `PendingActionSyncWorker` dispatches only 2 of the 4 `PendingActionType` cases —
  `DEPOSIT` and `WITHDRAW` are never sent, and the worker returns success, so those
  rows sit in the queue forever.
- `PendingActionDatabase` uses `fallbackToDestructiveMigration()` with
  `exportSchema = false`: a schema change silently wipes every queued financial action.
- Widget interval unit tests assert a 5-tier/2-minute schedule that the implementation
  (a 15/60-minute split) does not produce — the tests and the code disagree.
- `docs/offline-first-guide.md` claims a 50-item queue cap that exists on neither
  platform, and claims idempotency keys on all queued mutations, which is false for
  iOS.

### 8.7 Does not exist anywhere in the repo

Stated explicitly so nobody assumes it is covered: iOS XCTest performance tests;
Android `src/benchmark` source set; `baseline-prof.txt`; the `androidx.benchmark` Gradle
plugin; `benchmark-macro-junit4`; `FrameTimingMetric` or any jank measurement;
`xctrace` automation in CI; JMH; an absolute build-size budget; any real battery
measurement; a network payload-size budget; a pending-queue size cap; Android APM
coverage beyond 3-4 screens; any `derivedStateOf` usage.

---

## 9. References

- The security guidelines (`docs/security-guidelines.md`) — logging redaction, and the
  interaction between diagnostics and secret handling
- [offline-first-guide.md](offline-first-guide.md) — offline queue and cache design
- [widget-refresh-budget.md](widget-refresh-budget.md) — OS refresh ceilings
- [background-task-scheduling.md](background-task-scheduling.md) — background work
- [proguard-configuration.md](proguard-configuration.md) — R8 configuration
- [manual-qa-checklist.md](manual-qa-checklist.md) — manual pre-release passes
- [docs/adr/](adr/) — architecture decision records
- [Android Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Android Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [XCTest performance tests](https://developer.apple.com/documentation/xctest)
- [Instruments](https://developer.apple.com/instruments/)
- [RAIL performance model](https://developer.android.com/topic/performance/vitals/slow-rendering)
