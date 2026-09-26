import SwiftUI

@main
struct EthosProtocolApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    @StateObject private var authStore = AuthStore()
    @StateObject private var vaultStore = VaultStore()
    // #276: Session lock service — locks the UI after configurable inactivity.
    @StateObject private var sessionLock = SessionLockService()

    @Environment(\.scenePhase) private var scenePhase

    init() {
        BackgroundRefreshService.shared.registerBackgroundTask()
        CheckInSyncTask.shared.registerBackgroundTask()
        ICloudSyncService.shared.restoreFromICloud()

        if #available(iOS 16.1, *) {
            AppShortcutsProvider.registerShortcuts()
        }

        NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: NSUbiquitousKeyValueStore.default,
            queue: .main
        ) { _ in
            ICloudSyncService.shared.restoreFromICloud()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authStore)
                .environmentObject(vaultStore)
                .environmentObject(sessionLock)
                // #277: Privacy overlay — hides content in the app-switcher snapshot.
                .privacyOverlay()
                .task {
                    NotificationService.shared.registerNotificationCategories()
                    await NotificationService.shared.requestPermission()
                    BackgroundRefreshService.shared.scheduleAppRefresh()
                    // Configure minimum background fetch interval for silent notifications (15 minutes)
                    UIApplication.shared.setMinimumBackgroundFetchInterval(15 * 60)
                }
                .onOpenURL { url in
                    vaultStore.pendingDeepLink = UniversalLinkRouter.shared.parse(url: url)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    guard let url = activity.webpageURL else { return }
                    vaultStore.pendingDeepLink = UniversalLinkRouter.shared.parse(url: url)
                }
                // #440: Handle Spotlight search result taps — route the user directly to
                // the vault detail view for the tapped vault.
                .onContinueUserActivity(SpotlightService.activityType) { activity in
                    guard let vaultID = activity.userInfo?["vaultID"] as? String else { return }
                    vaultStore.pendingDeepLink = UniversalLinkRouter.shared.parse(
                        url: URL(string: "ethosprotocol://vault/\(vaultID)/view-details")!
                    )
                }
                // #276: Observe scene-phase transitions to drive session-lock timers.
                .onChange(of: scenePhase) { newPhase in
                    switch newPhase {
                    case .background:
                        sessionLock.handleBackground()
                    case .active:
                        sessionLock.handleForeground()
                    default:
                        break
                    }
                }
        }
    }
}
