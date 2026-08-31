import SwiftUI

#if DEBUG
/// Debug-only screen listing the local notification delivery log (#235), for
/// QA/support to answer "did this notification actually get scheduled,
/// delivered, or suppressed?" without backend log correlation. Never shows
/// anything beyond vault ID / event type / timestamp — see
/// NotificationDeliveryLog's doc comment for what is deliberately excluded.
struct NotificationDebugView: View {
    @State private var events: [NotificationDeliveryEvent] = []

    var body: some View {
        List {
            if events.isEmpty {
                Text("No notification events logged yet.")
                    .foregroundStyle(.secondary)
            }
            ForEach(events) { event in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(event.kind.rawValue.capitalized)
                            .font(.subheadline.bold())
                            .foregroundStyle(color(for: event.kind))
                        Spacer()
                        Text(event.source.rawValue)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(event.eventType)
                        .font(.subheadline)
                    HStack {
                        CopyableIDView(fullID: event.vaultID, displayLength: 12)
                        Spacer()
                        Text(event.timestamp, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
            }
        }
        .navigationTitle("Notification Log")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Clear") {
                    NotificationDeliveryLog.shared.clear()
                    events = []
                }
            }
        }
        .task { events = NotificationDeliveryLog.shared.recentEvents() }
        .refreshable { events = NotificationDeliveryLog.shared.recentEvents() }
    }

    private func color(for kind: NotificationDeliveryEvent.Kind) -> Color {
        switch kind {
        case .scheduled: return .blue
        case .delivered: return .green
        case .suppressed: return .orange
        }
    }
}
#endif
