import WidgetKit
import SwiftUI
// The SPM package (Package.swift) compiles TTLWidget as a separate module
// that depends on the EthosProtocol library product, so APIClient/Vault
// need an explicit import there. The XcodeGen-generated app-extension
// target (project.yml) instead compiles Models/APIClient directly into
// this same module (no EthosProtocol product exists in that project), so
// the import must be skipped there — canImport(EthosProtocol) is false in
// that build and this block compiles out entirely.
#if canImport(EthosProtocol)
import EthosProtocol
#endif

// MARK: - Timeline Entry

struct VaultEntry: TimelineEntry {
    let date: Date
    let vaultID: String
    let vaultName: String
    let ttlRemaining: UInt64?
    let isExpiringSoon: Bool
}

// MARK: - Timeline Provider

struct TTLTimelineProvider: TimelineProvider {
    func placeholder(in context: Context) -> VaultEntry {
        VaultEntry(date: .now, vaultID: "vault-placeholder", vaultName: "My Vault", ttlRemaining: 86_400, isExpiringSoon: false)
    }

    func getSnapshot(in context: Context, completion: @escaping (VaultEntry) -> Void) {
        completion(VaultEntry(date: .now, vaultID: "vault-placeholder", vaultName: "My Vault", ttlRemaining: 86_400, isExpiringSoon: false))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<VaultEntry>) -> Void) {
        Task {
            let entry: VaultEntry
            do {
                let vaults = try await APIClient.shared.listAllVaults()
                // Show the vault with the lowest TTL remaining (most urgent)
                let critical = vaults
                    .filter { $0.status == .active }
                    .min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
                entry = VaultEntry(
                    date: .now,
                    vaultID: critical?.id ?? "",
                    vaultName: critical.map { String($0.id.prefix(12)) + "…" } ?? "No Active Vault",
                    ttlRemaining: critical?.ttlRemaining,
                    isExpiringSoon: critical?.isExpiringSoon ?? false
                )
            } catch {
                entry = VaultEntry(date: .now, vaultID: "", vaultName: "Unavailable", ttlRemaining: nil, isExpiringSoon: false)
            }

            // Compute refresh interval based on vault urgency: refresh more frequently as TTL approaches zero.
            // Scale from 15 min (normal) down to 1 min (critical), respecting WidgetKit's budget guidance.
            let nextUpdateMinutes = computeNextUpdateInterval(ttlRemaining: entry.ttlRemaining)
            let nextUpdate = Calendar.current.date(byAdding: .minute, value: nextUpdateMinutes, to: .now)!
            completion(Timeline(entries: [entry], policy: .after(nextUpdate)))
        }
    }

    // Compute the next-update interval (in minutes) based on TTL urgency.
    // Returns values between 1 and 15, scaling down as ttlRemaining approaches zero.
    func computeNextUpdateInterval(ttlRemaining: UInt64?) -> Int {
        guard let ttl = ttlRemaining else { return 15 }

        // Scale based on time remaining until expiry
        switch ttl {
        case 3_600...: return 15  // >= 1 hour: refresh every 15 min
        case 3_600..<21_600: return 10  // 1-6 hours: refresh every 10 min
        case 1_800..<3_600: return 5  // 30 min-1 hour: refresh every 5 min
        case 0..<1_800: return 2  // < 30 min: refresh every 2 min
        default: return 15
        }
    }
}

// MARK: - Widget View

struct TTLWidgetView: View {
    let entry: VaultEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Ethos-Protocol", systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(.blue)
            Text(entry.vaultName)
                .font(.headline)
                .lineLimit(1)
            if let ttl = entry.ttlRemaining {
                Text(formatDuration(ttl))
                    .font(.subheadline)
                    .foregroundStyle(entry.isExpiringSoon ? .orange : .secondary)
            } else {
                Text("—").font(.subheadline).foregroundStyle(.secondary)
            }
            if entry.isExpiringSoon {
                Label("Expiring soon", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
        .padding()
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
    }

    private func formatDuration(_ seconds: UInt64) -> String {
        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        if days > 0 { return "\(days)d \(hours)h remaining" }
        return "\(hours)h remaining"
    }
}

// MARK: - Widget Definition

struct TTLWidget: Widget {
    let kind = "TTLWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: TTLTimelineProvider()) { entry in
            TTLWidgetView(entry: entry)
        }
        .configurationDisplayName("TTL Vault Status")
        .description("Shows your most urgent vault's TTL countdown.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryCircular])
    }
}

// MARK: - Widget Bundle Entry Point (app extension @main)

@main
struct TTLWidgetBundle: WidgetBundle {
    var body: some Widget {
        TTLWidget()
    }
}
