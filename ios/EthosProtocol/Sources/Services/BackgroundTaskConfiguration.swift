import Foundation

/**
 * Centralized documentation and configuration of all periodic background work for iOS (#321).
 *
 * ## iOS Background Tasks Summary
 *
 * ### Periodic Work Items
 *
 * **TTLWidget Timeline Refresh**
 * - File: Sources/Widget/TTLWidget.swift
 * - Purpose: Fetch all active vaults and update widget display with most-urgent vault
 * - Interval: Dynamic, adaptive based on vault urgency:
 *   - >= 6 hours remaining: 15 min
 *   - 1-6 hours remaining: 10 min
 *   - 30 min-1 hour remaining: 5 min
 *   - < 30 min remaining: 2 min
 * - Constraints: Requires network connectivity
 * - Battery impact: HIGH when urgent (2 min interval), LOW when normal (15 min interval)
 * - Coordination: Urgency threshold (6 hours for 15 min, < 30 min for 2 min) is coordinated with
 *   Vault.isExpiringSoon for consistent behavior across Android/iOS
 *
 * **Background App Refresh (not yet implemented)**
 * - Purpose: Periodically sync vault state when app is backgrounded
 * - Interval: OS-managed (typically 15+ minutes minimum, further controlled by iOS power management)
 * - Constraints: Requires network, respects power saving mode
 * - Battery impact: MEDIUM to HIGH depending on OS scheduling
 *
 * ### Comparison with Android
 *
 * - Android: VaultWidgetUpdateWorker (15/60 min adaptive)
 * - iOS: TTLWidget (2-15 min adaptive)
 *
 * iOS provides more aggressive updates (2 min minimum vs 15 min WorkManager floor), but both
 * platforms use the same urgency-based scaling to balance freshness vs. battery life.
 *
 * ### Future Consolidation Opportunities
 *
 * 1. **Implement Background App Refresh**
 *    - Add BGProcessingTaskRequest to periodically sync vaults when app is backgrounded
 *    - Use same urgency-based intervals as TTLWidget for consistency
 *    - Coordinate with TTLWidget to avoid duplicate wake-ups
 *
 * 2. **Batch Push Notification Sync with Widget Refresh**
 *    - Currently TTLWidget fetches independent of push subscriptions
 *    - Could register/re-register for WebSocket updates during widget refresh
 *    - Would reduce separate wake-ups for notification management
 *
 * 3. **Per-Vault Check-In Reminders**
 *    - Add local notifications timed to vault TTL (similar to Android CheckInReminderWorker)
 *    - Trigger during TTLWidget refresh or Background App Refresh
 *    - Share urgency calculation with widget refresh logic
 *
 * ### Battery Impact Measurement
 *
 * To measure battery impact (same methodology as Android, #321):
 * - Baseline: Measure current app wake-ups during 4 hours of normal use
 *   - Go to Settings > Battery > Battery Health > Usage by app
 *   - Note CPU time and frequency of screen wakes
 * - After consolidation: Measure same scenario with new implementation
 * - Target: Reduce wake-ups by 20-30% while maintaining acceptable freshness (< 5 min staleness)
 *
 * ### WidgetKit Refresh Budget
 *
 * WidgetKit uses a "budget" system where each device allocates a fixed number of
 * timeline refreshes per hour per widget. The actual rate depends on:
 * - Device battery level (lower battery = fewer refreshes)
 * - User app usage patterns (less-used apps get lower priority)
 * - OS power management settings
 *
 * Our adaptive intervals work within this budget by requesting more frequent updates
 * only when urgent (TTL < 30 min), so we rarely hit the budget ceiling.
 */

// MARK: - Urgency Calculations (Shared with Widget)

/// Determines widget refresh interval based on vault urgency.
/// Mirrors Android's VaultWidgetUpdateWorker.determineUpdateInterval().
/// Returns interval in minutes.
func computeWidgetRefreshInterval(ttlRemainingSecs: UInt64?) -> Int {
    guard let ttl = ttlRemainingSecs else { return 15 }

    switch ttl {
    case 21_600...: return 15  // >= 6 hours: refresh every 15 min (normal)
    case 3_600..<21_600: return 10  // 1-6 hours: refresh every 10 min
    case 1_800..<3_600: return 5  // 30 min-1 hour: refresh every 5 min
    case 0..<1_800: return 2  // < 30 min: refresh every 2 min (urgent)
    default: return 15
    }
}

/// Determines Background App Refresh interval based on vault urgency.
/// Returns interval in seconds (converted to minutes for BGProcessingTaskRequest).
func computeBackgroundRefreshInterval(ttlRemainingSecs: UInt64?) -> TimeInterval {
    let minutes = computeWidgetRefreshInterval(ttlRemainingSecs: ttlRemainingSecs)
    return TimeInterval(minutes * 60)
}
