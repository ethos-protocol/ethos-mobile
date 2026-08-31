import SwiftUI

/// Lists the account's registered passkeys and lets the user revoke one (#206) — e.g. after
/// losing the device it lives on — and add another (#207), reusing `AddPasskeyView`.
struct PasskeyManagementView: View {
    @StateObject private var store = PasskeyManagementStore()
    @State private var showAddPasskey = false
    @State private var pendingRevoke: PasskeyCredential?

    private static let lastUsedFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    var body: some View {
        List {
            if let error = store.error {
                Section { Text(error.message).font(.caption).foregroundStyle(.red) }
            }
            ForEach(store.credentials) { credential in
                VStack(alignment: .leading, spacing: 4) {
                    Text(credential.deviceLabel ?? "Unknown device")
                        .font(.body)
                    Text(lastUsedDescription(for: credential))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .swipeActions {
                    Button("Revoke", role: .destructive) { pendingRevoke = credential }
                }
            }
            if store.credentials.isEmpty && !store.isLoading {
                Text("No passkeys registered.")
                    .foregroundStyle(.secondary)
            }
        }
        .overlay { if store.isLoading && store.credentials.isEmpty { ProgressView() } }
        .navigationTitle("Passkeys")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(action: { showAddPasskey = true }) { Image(systemName: "plus") }
            }
        }
        .task { await store.load() }
        .refreshable { await store.load() }
        .sheet(isPresented: $showAddPasskey) {
            AddPasskeyView(onAdded: { _ in Task { await store.load() } })
        }
        // #206: revoking is security-sensitive (at least as much as disabling 2FA), so it's
        // confirmed explicitly before the biometric gate in RevokeCredentialCoordinator runs.
        .confirmationDialog(
            "Revoke this passkey? Any device using it will no longer be able to sign in.",
            isPresented: Binding(get: { pendingRevoke != nil }, set: { if !$0 { pendingRevoke = nil } }),
            titleVisibility: .visible
        ) {
            Button("Revoke", role: .destructive) {
                if let credential = pendingRevoke {
                    Task { await store.revokeCredential(credential) }
                }
                pendingRevoke = nil
            }
            Button("Cancel", role: .cancel) { pendingRevoke = nil }
        }
    }

    private func lastUsedDescription(for credential: PasskeyCredential) -> String {
        guard let lastUsedAt = credential.lastUsedAt else { return "Never used" }
        return "Last used \(Self.lastUsedFormatter.localizedString(for: lastUsedAt, relativeTo: Date()))"
    }
}
