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

            Section {
                NavigationLink(destination: SupportDebugView()) {
                    Label("Debug / Support", systemImage: "wrench.and.screwdriver")
                }
            } header: {
                Text("Advanced")
            }
        }
        .navigationTitle("Settings")
    }
}

// MARK: - SupportDebugView

/// Shows cache telemetry counters for debug and support use (#242).
struct SupportDebugView: View {
    @State private var telemetry = CacheTelemetry.shared.snapshot()

    var body: some View {
        Form {
            Section("Cache Telemetry") {
                LabeledContent("Hits", value: "\(telemetry.hits)")
                LabeledContent("Misses", value: "\(telemetry.misses)")
                LabeledContent("Stale (refused)", value: "\(telemetry.staleServed)")
                Button("Reset Counters") {
                    CacheTelemetry.shared.reset()
                    telemetry = CacheTelemetry.shared.snapshot()
                }
            }
        }
        .navigationTitle("Debug / Support")
    }
}
