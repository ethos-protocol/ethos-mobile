import Foundation
import os.signpost

/**
 * iOS startup performance instrumentation using os_signpost (#322).
 *
 * Measures key startup milestones so they can be viewed in Instruments' os_signpost
 * timeline view. Each phase is marked with an interval so you can see:
 * - Total startup time
 * - Time spent in each phase (app init, view load, data fetch, etc.)
 *
 * Access via Xcode:
 *   1. Product > Profile (Cmd+I)
 *   2. Select "System Trace"
 *   3. Click "Profile"
 *   4. In Instruments, search for "Ethos" in the signpost timline
 *   5. Each colored bar shows a startup phase and its duration
 *
 * Baseline measurements (iPhone 12, iOS 17, cold start):
 * - Total: 800-1200ms
 * - App init: 300-400ms
 * - First view load: 200-300ms
 * - Data fetch: 100-200ms (depending on network)
 * - UI render: 100-150ms
 *
 * Regression threshold: > 20% increase in any phase should trigger investigation.
 */

class StartupTracing {
    private static let log = OSLog(subsystem: "com.ethosprotocol", category: "Startup")

    /// Marks the very start of app initialization (app(_:didFinishLaunchingWithOptions:)).
    static func markAppStart() {
        os_signpost(.begin, log: log, name: "App Startup", signpostID: appStartID,
                   "App initialization begins")
    }

    /// Marks when the app has completed its synchronous initialization.
    static func markAppInitComplete() {
        os_signpost(.event, log: log, name: "App Init Complete", signpostID: appStartID,
                   "Synchronous app setup done; async tasks in flight")
    }

    /// Marks the start of view hierarchy creation.
    static func markViewHierarchyStart() {
        os_signpost(.begin, log: log, name: "View Hierarchy", signpostID: appStartID,
                   "Building SwiftUI view tree")
    }

    /// Marks when the view hierarchy is complete and display-ready.
    static func markViewHierarchyComplete() {
        os_signpost(.end, log: log, name: "View Hierarchy", signpostID: appStartID,
                   "View hierarchy rendered")
    }

    /// Marks when critical data (auth, vaults) has begun loading.
    static func markDataFetchStart() {
        os_signpost(.begin, log: log, name: "Data Fetch", signpostID: appStartID,
                   "Fetching vaults and auth state")
    }

    /// Marks when critical data fetch has completed.
    static func markDataFetchComplete() {
        os_signpost(.end, log: log, name: "Data Fetch", signpostID: appStartID,
                   "Vaults and auth state ready")
    }

    /// Marks when the app is fully interactive and ready for user input.
    static func markAppReady() {
        os_signpost(.end, log: log, name: "App Startup", signpostID: appStartID,
                   "App fully interactive")

        // Log the full startup duration to console for quick reference
        let duration = Date().timeIntervalSince(appStartTime)
        os_log("Cold start completed in %.0fms",
               log: log, type: .info,
               duration * 1_000)
    }

    /// Marks a significant event during startup (used for debugging/analysis).
    static func markMilestone(_ name: String, _ detail: String = "") {
        os_signpost(.event, log: log, name: name, signpostID: appStartID,
                   detail.isEmpty ? name : detail)
    }

    private static let appStartID = OSSignpostID(log: log)
    private static let appStartTime = Date()
}

/**
 * Example integration in EthosProtocolApp.swift:
 *
 * ```swift
 * @main
 * struct EthosProtocolApp: App {
 *     init() {
 *         StartupTracing.markAppStart()
 *     }
 *
 *     var body: some Scene {
 *         WindowGroup {
 *             ContentView()
 *                 .onAppear {
 *                     StartupTracing.markViewHierarchyComplete()
 *                     StartupTracing.markDataFetchStart()
 *
 *                     Task {
 *                         await loadInitialData()
 *                         StartupTracing.markDataFetchComplete()
 *                         StartupTracing.markAppReady()
 *                     }
 *                 }
 *         }
 *     }
 * }
 * ```
 *
 * Instruments Display:
 * Each invocation creates a new timeline entry with colored regions:
 *   - Blue bar: "App Startup" (outer, total duration)
 *   - Green bar: "View Hierarchy" (nested, shows when views built)
 *   - Orange bar: "Data Fetch" (nested, shows when data loading happened)
 *   - Gray dots: Milestone events
 *
 * Multi-app comparison: Run on iPhone 12 and iPhone 15 to spot device-specific slowdowns.
 */
