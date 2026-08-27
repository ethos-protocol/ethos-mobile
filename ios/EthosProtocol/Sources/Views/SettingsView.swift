import SwiftUI

struct SettingsView: View {
    @State private var iCloudSyncEnabled = ICloudSyncService.shared.isSyncEnabled
    @State private var reLockTimeout = ReLockTimeoutOption.current

    var body: some View {
        Form {
            Section {
                Toggle("Sync vault associations to iCloud", isOn: $iCloudSyncEnabled)
                    .onChange(of: iCloudSyncEnabled) { _, newValue in
                        ICloudSyncService.shared.isSyncEnabled = newValue
                    }
                Text("Syncs which vaults are linked to your passkeys across your devices. Your passkey private keys are never uploaded.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("iCloud Backup")
            }

            Section {
                Picker("Re-lock After", selection: $reLockTimeout) {
                    ForEach(ReLockTimeoutOption.allCases) { option in
                        Text(option.label).tag(option)
                    }
                }
                .onChange(of: reLockTimeout) { _, newValue in
                    ReLockTimeoutOption.current = newValue
                }
                Text("Require Face ID again after the app has been in the background for this long.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Privacy")
            }

            // #231: In-app notification preferences (per-category toggles + quiet hours).
            Section {
                NavigationLink(destination: NotificationPreferencesView()) {
                    Label("Notification Preferences", systemImage: "bell.badge")
                }
            } header: {
                Text("Notifications")
            }
        }
        .navigationTitle("Settings")
    }
}
