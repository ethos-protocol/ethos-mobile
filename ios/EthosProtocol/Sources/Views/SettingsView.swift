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
                NavigationLink("Active Sessions") { SessionsView() }
            } header: {
                Text("Security")
            }
        }
        .navigationTitle("Settings")
    }
}

/// Shows every device currently holding a valid JWT for this account (#208), with
/// biometric-gated "Sign out this device" / "Sign out all other devices" actions.
struct SessionsView: View {
    @StateObject private var store = SessionsStore()
    @State private var pendingRevocation: Session?
    @State private var showRevokeAllConfirmation = false

    var body: some View {
        List {
            if let error = store.error {
                Section { Text(error.message).foregroundStyle(.red).font(.caption) }
            }
            Section {
                ForEach(store.sessions) { session in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(session.deviceName).font(.headline)
                            if session.isCurrent {
                                Text("This Device")
                                    .font(.caption2.bold())
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(.blue.opacity(0.15))
                                    .foregroundStyle(.blue)
                                    .clipShape(Capsule())
                            }
                        }
                        Text("Last active \(session.lastActiveAt.formatted(.relative(presentation: .named)))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .swipeActions {
                        Button("Sign Out", role: .destructive) { pendingRevocation = session }
                    }
                }
            } header: {
                Text("Signed-In Devices")
            }

            if store.sessions.contains(where: { !$0.isCurrent }) {
                Section {
                    Button("Sign Out All Other Devices", role: .destructive) {
                        showRevokeAllConfirmation = true
                    }
                }
            }
        }
        .overlay { if store.isLoading && store.sessions.isEmpty { ProgressView() } }
        .navigationTitle("Active Sessions")
        .task { await store.load() }
        .refreshable { await store.load() }
        .confirmationDialog(
            "Sign out this device?",
            isPresented: Binding(get: { pendingRevocation != nil }, set: { if !$0 { pendingRevocation = nil } }),
            titleVisibility: .visible
        ) {
            Button("Sign Out", role: .destructive) {
                guard let session = pendingRevocation else { return }
                pendingRevocation = nil
                Task {
                    do {
                        try await BiometricService.shared.authenticate(reason: "Sign out \(session.deviceName)")
                        await store.revoke(session)
                    } catch {
                        store.error = ErrorPresentation(error)
                    }
                }
            }
            Button("Cancel", role: .cancel) { pendingRevocation = nil }
        }
        .confirmationDialog(
            "Sign out every other device?",
            isPresented: $showRevokeAllConfirmation,
            titleVisibility: .visible
        ) {
            Button("Sign Out All Other Devices", role: .destructive) {
                Task {
                    do {
                        try await BiometricService.shared.authenticate(reason: "Sign out all other devices")
                        await store.revokeAllOthers()
                    } catch {
                        store.error = ErrorPresentation(error)
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }
}
