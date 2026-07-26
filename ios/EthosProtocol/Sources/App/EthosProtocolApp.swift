import SwiftUI

@main
struct EthosProtocolApp: App {
    @StateObject private var authStore = AuthStore()
    @StateObject private var vaultStore = VaultStore()

    init() {
        BackgroundRefreshService.shared.registerBackgroundTask()
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
                .task {
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
        }
    }
}
