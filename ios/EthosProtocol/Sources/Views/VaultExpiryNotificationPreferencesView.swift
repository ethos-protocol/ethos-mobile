import SwiftUI

/// View for managing per-vault expiry notification preferences.
/// Allows users to customize when they want to be notified before vault expiry.
struct VaultNotificationPreferencesView: View {
    let vaultID: String
    @Environment(\.dismiss) private var dismiss
    @State private var preferences: VaultNotificationPreferences
    @State private var hasChanges = false

    init(vaultID: String) {
        self.vaultID = vaultID
        self._preferences = State(initialValue: VaultNotificationPreferences.load(for: vaultID))
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    Text("Receive notifications before your vault expires. Select all the time periods when you'd like to be notified.")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    NotificationIntervalToggle(
                        label: "1 day before",
                        value: 1,
                        isSelected: preferences.notificationDays.contains(1)
                    ) { selected in
                        updateNotificationInterval(days: 1, selected: selected)
                    }

                    NotificationIntervalToggle(
                        label: "3 days before",
                        value: 3,
                        isSelected: preferences.notificationDays.contains(3)
                    ) { selected in
                        updateNotificationInterval(days: 3, selected: selected)
                    }

                    NotificationIntervalToggle(
                        label: "7 days before",
                        value: 7,
                        isSelected: preferences.notificationDays.contains(7)
                    ) { selected in
                        updateNotificationInterval(days: 7, selected: selected)
                    }
                }
                .padding(.vertical, 8)
            } header: {
                Text("Notification Intervals")
            } footer: {
                Text(preferences.notificationDays.isEmpty ?
                    "You won't receive any expiry notifications. Enable at least one interval above to stay informed." :
                    "You'll be notified \(formatIntervals()).")
                    .font(.caption)
                    .foregroundStyle(preferences.notificationDays.isEmpty ? .orange : .secondary)
            }

            Section {
                Text("These preferences are saved locally on this device. If you reinstall the app, you'll need to reconfigure them.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Expiry Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") {
                    preferences.save()
                    dismiss()
                }
                .fontWeight(.semibold)
            }
        }
    }

    private func updateNotificationInterval(days: Int, selected: Bool) {
        if selected {
            if !preferences.notificationDays.contains(days) {
                preferences.notificationDays.append(days)
                preferences.notificationDays.sort().reverse()
            }
        } else {
            preferences.notificationDays.removeAll { $0 == days }
        }
        hasChanges = true
    }

    private func formatIntervals() -> String {
        let sorted = preferences.notificationDays.sorted()
        let formatted = sorted.map { days in
            days == 1 ? "1 day" : "\(days) days"
        }

        if formatted.isEmpty {
            return "never"
        } else if formatted.count == 1 {
            return formatted[0]
        } else if formatted.count == 2 {
            return "\(formatted[0]) and \(formatted[1]) before expiry"
        } else {
            let allButLast = formatted.dropLast().joined(separator: ", ")
            return "\(allButLast), and \(formatted.last!) before expiry"
        }
    }
}

private struct NotificationIntervalToggle: View {
    let label: String
    let value: Int
    let isSelected: Bool
    let onChanged: (Bool) -> Void

    var body: some View {
        Button(action: { onChanged(!isSelected) }) {
            HStack {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? .blue : .gray)
                    .font(.system(size: 20))

                Text(label)
                    .foregroundStyle(.primary)

                Spacer()
            }
            .contentShape(Rectangle())
        }
    }
}

#Preview {
    NavigationStack {
        VaultNotificationPreferencesView(vaultID: "test-vault-123")
    }
}
