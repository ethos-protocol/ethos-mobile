import UIKit

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        return true
    }

    /// Handles both remote notifications and silent notifications (content-available).
    /// Called when a remote notification arrives, including background push notifications.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        // Check if this is a silent notification (content-available)
        let isContentAvailable = userInfo["aps"] as? [String: Any]?["content-available"] as? Int ?? 0

        if isContentAvailable == 1 {
            SilentNotificationService.shared.fetchVaultDataInBackground(completion: completionHandler)
        } else {
            // Regular push notification — let NotificationService handle it
            completionHandler(.noData)
        }
    }
}
