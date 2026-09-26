# Startup Performance Tracing (#322)

This document explains how to measure, trace, and monitor app startup performance on both Android and iOS.

## Overview

Startup performance directly impacts user experience. A slow cold start frustrates users and leads to app abandonment. We've added structured startup instrumentation to both platforms to measure performance objectively and detect regressions before they ship.

## Android: Macrobenchmark Tests

### Running Locally

Macrobenchmarks measure real app startup latency on an emulator/device with repeatable methodology:

```bash
cd android

# Run all startup benchmarks (cold, warm, hot, with/without baseline profile)
./gradlew benchmark

# Run a specific benchmark
./gradlew benchmark -Pandroid.testInstrumentationRunnerArguments.class=com.ethosprotocol.StartupBenchmark
```

### Benchmark Types

**Cold Start** (`coldStart()`)
- App killed, app data cleared, no baseline profile
- Worst-case scenario (e.g., first install, force-stop recovery)
- Threshold: < 1500ms (goal: < 1000ms)

**Warm Start** (`warmStart()`)
- App process alive, activity recreated
- Common scenario (e.g., returning from background)
- Threshold: < 500ms (goal: < 300ms)

**Hot Start** (`hotStart()`)
- App in foreground, activity resumed
- Fastest path, mainly screen transition overhead
- Threshold: < 100ms

**Cold Start with Baseline Profile** (`coldStartWithBaselineProfile()`)
- Cold start after Google Play delivers optimized baseline profile
- Target: < 1200ms (improvement: 200-300ms from profile optimization)

### Understanding Results

Macrobenchmarks run each test 3 times and report:
- **Min/Median/Max**: Startup time across runs
- **Regression**: Whether result exceeded baseline by > 5%
- **Stddev**: Consistency of measurements (lower is better)

### CI Integration

Startup benchmarks run automatically on every PR in the `startup-benchmark` job. Results are uploaded as artifacts:

```
android-startup-benchmark-report/
  ├── StartupBenchmark_coldStart.json
  ├── StartupBenchmark_warmStart.json
  ├── StartupBenchmark_hotStart.json
  └── ...
```

**Baseline Thresholds** (from `StartupBenchmark.kt`):
- Cold start: 1500ms
- Warm start: 500ms
- Hot start: 100ms
- Cold + profile: 1200ms

If any benchmark exceeds its threshold, review recent changes for:
- Expensive operations in `EthosProtocolApplication.onCreate()`
- Blocking I/O in `MainActivity.onCreate()` or `@Composable` init blocks
- Database queries during app startup
- Unnecessary network requests before user input

### Further Reading

- [AndroidX Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark)
- [StartupBenchmark.kt](android/app/src/androidTest/java/com/ethosprotocol/StartupBenchmark.kt)

## iOS: os_signpost Instrumentation

### Viewing Traces in Xcode

os_signpost is a lightweight OS-level API that records named events and intervals. View them in Instruments:

```bash
cd ios/EthosProtocol

# Run the app in Xcode Profiler
xcodebuild -scheme EthosProtocol \
  -destination "generic/platform=iOS Simulator" \
  -enableCodeCoverage YES \
  build

# Then:
# 1. Product > Profile (Cmd+I)
# 2. Select "System Trace"
# 3. Click "Record"
# 4. Let app fully load
# 5. Stop recording
# 6. In Instruments, search for "Ethos" in the signpost timeline
```

### Measured Phases

Startup is divided into instrumented phases:

- **App Startup** (outer interval): Total cold-start duration
- **App Init Complete** (event): Synchronous app setup done
- **View Hierarchy** (nested interval): SwiftUI view tree construction
- **Data Fetch** (nested interval): Fetching vaults and auth state
- **App Ready** (event): Fully interactive

### Baseline Measurements

Typical iPhone 12, iOS 17, cold start:
- Total startup: 800-1200ms
- App init: 300-400ms
- View load: 200-300ms
- Data fetch: 100-200ms (network-dependent)
- UI render: 100-150ms

**Regression threshold**: > 20% increase in any phase should trigger investigation.

### Interpreting the Timeline

In Instruments > System Trace:

```
[====== App Startup (total ~1000ms) ======]
    [App Init Complete @300ms]
    [==== View Hierarchy ~200ms ====]
    [==== Data Fetch ~300ms ====]
    [App Ready @1000ms]
```

Each colored bar shows a phase; gaps between bars indicate idle time or the main thread blocked elsewhere.

### Integration in App Code

See `StartupTracing.swift` for example integration. Key calls:

```swift
StartupTracing.markAppStart()         // In app(_:didFinishLaunchingWithOptions:)
StartupTracing.markViewHierarchyStart()      // ContentView() init
StartupTracing.markViewHierarchyComplete()   // After view tree built
StartupTracing.markDataFetchStart()          // Before loading vaults
StartupTracing.markDataFetchComplete()       // After vaults loaded
StartupTracing.markAppReady()         // When fully interactive
```

### CI Notes

iOS startup tracing is currently manual (must profile locally in Xcode). To automate:

1. Implement `UIPerformanceMonitoring` to capture metrics programmatically
2. Export results to a JSON file
3. Add a CI job to run the profiler and verify thresholds

This is a future enhancement tracked in #322.

### Further Reading

- [Apple os_signpost](https://developer.apple.com/documentation/os/signpost)
- [StartupTracing.swift](ios/EthosProtocol/Sources/Services/StartupTracing.swift)

## Regression Detection

### Android CI

Benchmarks run on every PR. If a benchmark exceeds its baseline by > 5%, it's flagged in the CI output:

```
❌ Cold start regressed to 1800ms (baseline: 1500ms, +20%)
✅ Warm start: 450ms (baseline: 500ms)
```

To fix a regression:

1. Profile locally (`./gradlew benchmark`)
2. Compare traces to the previous commit (git bisect)
3. Identify the expensive operation
4. Optimize or defer non-critical work
5. Verify fix locally, re-submit PR

### iOS Manual Profiling

1. Create a branch with your changes
2. Profile on your device (not simulator) for realistic results
3. Compare traces to main
4. If any phase took > 20% longer, investigate before merging

## Future Enhancements

- [ ] Automate iOS startup capture in CI (currently manual)
- [ ] Generate historical charts of startup trends
- [ ] Add P95/P99 percentile thresholds (not just median)
- [ ] Profile specific device models (iPhone 12 vs 15)
- [ ] Track app size impact on startup (R8 shrinking effectiveness)

## Troubleshooting

### Benchmark runs slowly

Macrobenchmarks run 3+ times per test to reduce noise. This is normal and expected.
To speed up locally, edit `StartupBenchmark.kt` and change `measureRepeated` to `measureBlock` with fewer iterations.

### Instruments shows "No signpost data"

- Verify `StartupTracing.markAppStart()` is called early in app init
- Check that the app name in os_signpost (`"com.ethosprotocol"`) matches your scheme
- Ensure System Trace is selected (not just performance profiler)
- View the full timeline; signpost data appears in the trace midway through, not at the start

### Benchmark gets flaky results

On CI, macrobenchmarks can be affected by:
- Other processes running on the CI machine
- Emulator performance variation
- Network latency during data fetch

If a PR passes locally but fails CI (or vice versa), run it again—flakes are rare but do happen.
Consistent failures indicate real regressions; one-off failures can usually be retried.
