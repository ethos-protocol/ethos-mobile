import SwiftUI

@main
struct EthosProtocolApp: App {
    @StateObject private var authStore = AuthStore()
    @StateObject private var vaultStore = VaultStore()
    // #276: Session lock service — locks the UI after configurable inactivity.
    @StateObject private var sessionLock = SessionLockService()

    @Environment(\.scenePhase) private var scenePhase

    init() {
        BackgroundRefreshService.shared.registerBackgroundTask()
        CheckInSyncTask.shared.registerBackgroundTask()
        ICloudSyncService.shared.restoreFromICloud()

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
                }
                .onOpenURL { url in
                    vaultStore.pendingDeepLink = UniversalLinkRouter.shared.parse(url: url)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    guard let url = activity.webpageURL else { return }
                    vaultStore.pendingDeepLink = UniversalLinkRouter.shared.parse(url: url)
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
