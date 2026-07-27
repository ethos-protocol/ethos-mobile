import UserNotifications
import Foundation
import UIKit

final class NotificationService: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationService()
    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    func requestPermission() async {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        if granted { await registerForRemoteNotifications() }
    }

    @MainActor
    private func registerForRemoteNotifications() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    func handleDeviceToken(_ tokenData: Data) {
        let token = tokenData.map { String(format: "%02x", $0) }.joined()
        Task { try? await APIClient.shared.registerPushToken(token) }
    }

    // Schedule a local check-in reminder scaled to the vault's check-in interval.
    // For short intervals (< 24h), schedules two reminders: one at 10% of interval,
    // and another closer to expiry. For longer intervals, schedules one reminder
    // at 10% of interval (capped at 24h).
    func scheduleCheckInReminder(vaultID: String, vaultName: String, ttlRemaining: UInt64, checkInInterval: UInt64) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["checkin-primary-\(vaultID)", "checkin-secondary-\(vaultID)"])

        guard ttlRemaining > 0 else { return }

        // Scale primary reminder to 10% of check-in interval, capped at 24 hours
        let primaryLeadTime = min(checkInInterval / 10, 86_400)
        let primaryFireIn = max(Int(ttlRemaining) - Int(primaryLeadTime), 60)

        // For short intervals, add a secondary reminder 2 hours before expiry
        let hasSecondaryReminder = checkInInterval < 86_400 // < 24h
        let secondaryFireIn = max(Int(ttlRemaining) - 7_200, 60) // 2 hours before

        // Primary reminder
        let primaryContent = UNMutableNotificationContent()
        primaryContent.title = "Check-in Reminder"
        primaryContent.body = "Your vault expires soon. Tap to check in and keep it active."
        primaryContent.sound = .default
        primaryContent.userInfo = ["vault_id": vaultID]
        primaryContent.categoryIdentifier = "CHECK_IN"

        let primaryTrigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(primaryFireIn), repeats: false)
        let primaryRequest = UNNotificationRequest(identifier: "checkin-primary-\(vaultID)", content: primaryContent, trigger: primaryTrigger)
        center.add(primaryRequest)

        // Secondary reminder for short intervals
        if hasSecondaryReminder && secondaryFireIn > primaryFireIn {
            let secondaryContent = UNMutableNotificationContent()
            secondaryContent.title = "Check-in Urgent"
            secondaryContent.body = "Your vault expires in about 2 hours. Check in now to prevent loss of access."
            secondaryContent.sound = .default
            secondaryContent.userInfo = ["vault_id": vaultID]
            secondaryContent.categoryIdentifier = "CHECK_IN"

            let secondaryTrigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(secondaryFireIn), repeats: false)
            let secondaryRequest = UNNotificationRequest(identifier: "checkin-secondary-\(vaultID)", content: secondaryContent, trigger: secondaryTrigger)
            center.add(secondaryRequest)
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                 didReceive response: UNNotificationResponse,
                                 withCompletionHandler completionHandler: @escaping () -> Void) {
        let vaultID = response.notification.request.content.userInfo["vault_id"] as? String
        if response.actionIdentifier == "CHECK_IN_ACTION", let id = vaultID {
            Task { try? await APIClient.shared.checkIn(vaultID: id) }
        }
        completionHandler()
    }

    // Fires immediately to warn the user their vault TTL is under 24 hours (called from background refresh).
    func scheduleTTLWarning(vaultID: String, ttlRemaining: UInt64) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ["ttl-warning-\(vaultID)"])

        let content = UNMutableNotificationContent()
        content.title = "Vault Expiring Soon"
        content.body = "Your vault expires in less than 24 hours. Open the app to check in and keep it active."
        content.sound = .default
        content.userInfo = ["vault_id": vaultID]
        content.categoryIdentifier = "CHECK_IN"

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)
        let request = UNNotificationRequest(identifier: "ttl-warning-\(vaultID)", content: content, trigger: trigger)
        center.add(request)
    }

    /// Removes every pending local notification (used on sign-out, so a previously
    /// signed-in user's check-in/TTL reminders don't fire for whoever uses the app next).
    func removeAllPendingNotifications() {
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
    }

    func registerNotificationCategories() {
        // .authenticationRequired ensures iOS forces the device to be unlocked before this
        // action fires — otherwise anyone with the phone in hand could trigger a check-in
        // (resetting the vault's TTL) straight from the lock screen banner, bypassing the
        // BiometricService confirmation that guards the equivalent in-app action.
        let checkInAction = UNNotificationAction(identifier: "CHECK_IN_ACTION", title: "Check In", options: [.foreground, .authenticationRequired])
        let category = UNNotificationCategory(identifier: "CHECK_IN", actions: [checkInAction],
                                               intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }
}
