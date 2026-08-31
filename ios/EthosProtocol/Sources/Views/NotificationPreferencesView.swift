import SwiftUI

/// #231 — In-app notification preferences screen.
/// Allows the user to control per-category notification toggles and optional
/// quiet hours. Preferences are persisted locally via UserDefaults and synced
/// server-side so they survive reinstall (tied into push token registration).
struct NotificationPreferencesView: View {
    @State private var preferences = NotificationPreferences.current

    var body: some View {
        Form {
            Section {
                Toggle("TTL Expiry Warnings", isOn: $preferences.ttlWarningsEnabled)
                    .onChange(of: preferences.ttlWarningsEnabled) { _, _ in save() }
                Toggle("Check-in Reminders", isOn: $preferences.checkInRemindersEnabled)
                    .onChange(of: preferences.checkInRemindersEnabled) { _, _ in save() }
                Text("Control which push notifications Ethos-Protocol sends you. Changes are synced with the server so they survive reinstall.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Notification Types")
            }

            Section {
                Toggle("Enable Quiet Hours", isOn: $preferences.quietHoursEnabled)
                    .onChange(of: preferences.quietHoursEnabled) { _, _ in save() }

                if preferences.quietHoursEnabled {
                    Stepper("Start: \(formattedHour(preferences.quietHoursStart))",
                            value: $preferences.quietHoursStart,
                            in: 0...23)
                        .onChange(of: preferences.quietHoursStart) { _, _ in save() }

                    Stepper("End: \(formattedHour(preferences.quietHoursEnd))",
                            value: $preferences.quietHoursEnd,
                            in: 0...23)
                        .onChange(of: preferences.quietHoursEnd) { _, _ in save() }

                    Text("Notifications will be suppressed between \(formattedHour(preferences.quietHoursStart)) and \(formattedHour(preferences.quietHoursEnd)).")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Quiet Hours")
            } footer: {
                Text("Notifications received during quiet hours are held and delivered once quiet hours end.")
            }
        }
        .navigationTitle("Notification Preferences")
    }

    private func save() {
        NotificationPreferences.current = preferences
        // Persist server-side so preferences survive reinstall (best-effort).
        Task {
            try? await APIClient.shared.updateNotificationPreferences(preferences)
        }
    }

    private func formattedHour(_ hour: Int) -> String {
        let components = DateComponents(hour: hour)
        let date = Calendar.current.date(from: components) ?? Date()
        let formatter = DateFormatter()
        formatter.dateFormat = "h a"
        return formatter.string(from: date)
    }
}
