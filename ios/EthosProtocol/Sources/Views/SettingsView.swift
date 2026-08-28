import SwiftUI

struct SettingsView: View {
    @State private var iCloudSyncEnabled = ICloudSyncService.shared.isSyncEnabled
    @State private var reLockTimeout = ReLockTimeoutOption.current
    @State private var showAddPasskey = false

    var body: some View {
        Form {
            Section {
                // #207: authenticated "Add another passkey" entry point — distinct from the
                // initial account-registration flow (RegisterView), for a signed-in user
                // adding a second device without going through account recovery.
                Button("Add Another Passkey") { showAddPasskey = true }
            } header: {
                Text("Passkeys")
            }

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
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $showAddPasskey) {
            AddPasskeyView(onAdded: { _ in })
        }
    }
}
