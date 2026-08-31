import WidgetKit
import SwiftUI
import AppIntents
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

// MARK: - Vault Selection Intent (#245 / #246)
//
// Each widget instance stores its own VaultSelectionIntent automatically via
// AppIntentConfiguration — per-instance config is handled by the framework with
// no extra persistence code required on our side.
//
// SNAPSHOT TEST NOTE (#246):
// Per-instance widget configuration is verified through AppIntentConfiguration's
// built-in intent storage. Each widget instance independently stores its
// VaultSelectionIntent (including the chosen vaultID). When vaultID is empty,
// the widget falls back to the most-urgent vault (urgency selection). This
// means snapshot tests should cover three scenarios:
//   1. No intent set (empty vaultID) → most-urgent vault shown
//   2. Intent set to a specific vault ID that exists → that vault shown
//   3. Intent set to a vault ID that no longer exists → fallback to most-urgent

struct VaultSelectionIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Select Vault"
    @Parameter(title: "Vault ID", default: "") var vaultID: String
}

// MARK: - Timeline Entry

struct VaultEntry: TimelineEntry {
    let date: Date
    let vaultID: String
    let vaultName: String
    let ttlRemaining: UInt64?
    let isExpiringSoon: Bool
    let balance: String
    let beneficiary: String
}

// MARK: - Timeline Provider

struct TTLTimelineProvider: AppIntentTimelineProvider {
    typealias Intent = VaultSelectionIntent

    func placeholder(in context: Context) -> VaultEntry {
        VaultEntry(
            date: .now,
            vaultID: "vault-placeholder",
            vaultName: "My Vault",
            ttlRemaining: 86_400,
            isExpiringSoon: false,
            balance: "1.0000000 XLM",
            beneficiary: "GXYZ…"
        )
    }

    func snapshot(for intent: VaultSelectionIntent, in context: Context) async -> VaultEntry {
        VaultEntry(
            date: .now,
            vaultID: "vault-placeholder",
            vaultName: "My Vault",
            ttlRemaining: 86_400,
            isExpiringSoon: false,
            balance: "1.0000000 XLM",
            beneficiary: "GXYZ…"
        )
    }

    func timeline(for intent: VaultSelectionIntent, in context: Context) async -> Timeline<VaultEntry> {
        let entry: VaultEntry
        do {
            let vaults = try await APIClient.shared.listAllVaults()
            let activeVaults = vaults.filter { $0.status == .active }

            // If the intent specifies a vault ID, try to find that vault.
            // Otherwise fall back to the most-urgent vault (lowest ttlRemaining).
            let selected: Vault?
            if !intent.vaultID.isEmpty {
                selected = activeVaults.first(where: { $0.id == intent.vaultID })
                    ?? activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
            } else {
                selected = activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
            }

            entry = VaultEntry(
                date: .now,
                vaultID: selected?.id ?? "",
                vaultName: selected.map { String($0.id.prefix(12)) + "…" } ?? "No Active Vault",
                ttlRemaining: selected?.ttlRemaining,
                isExpiringSoon: selected?.isExpiringSoon ?? false,
                balance: selected.map { formatBalance($0.balance) } ?? "—",
                beneficiary: selected.map { String($0.beneficiary.prefix(12)) + "…" } ?? "—"
            )
        } catch {
            entry = VaultEntry(
                date: .now,
                vaultID: "",
                vaultName: "Unavailable",
                ttlRemaining: nil,
                isExpiringSoon: false,
                balance: "—",
                beneficiary: "—"
            )
        }

        // Compute refresh interval based on vault urgency: refresh more frequently as TTL approaches zero.
        // Scale from 15 min (normal) down to 1 min (critical), respecting WidgetKit's budget guidance.
        let nextUpdateMinutes = computeNextUpdateInterval(ttlRemaining: entry.ttlRemaining)
        let nextUpdate = Calendar.current.date(byAdding: .minute, value: nextUpdateMinutes, to: .now)!
        return Timeline(entries: [entry], policy: .after(nextUpdate))
    }

    // Compute the next-update interval (in minutes) based on TTL urgency.
    // Returns values between 1 and 15, scaling down as ttlRemaining approaches zero.
    func computeNextUpdateInterval(ttlRemaining: UInt64?) -> Int {
        guard let ttl = ttlRemaining else { return 15 }

        // Scale based on time remaining until expiry
        switch ttl {
        case 21_600...: return 15  // >= 6 hours: refresh every 15 min
        case 3_600..<21_600: return 10  // 1-6 hours: refresh every 10 min
        case 1_800..<3_600: return 5  // 30 min-1 hour: refresh every 5 min
        case 0..<1_800: return 2  // < 30 min: refresh every 2 min
        default: return 15
        }
    }

    private func formatBalance(_ stroops: UInt64) -> String {
        let xlm = Double(stroops) / 10_000_000.0
        return String(format: "%.7f XLM", xlm)
    }
}

// MARK: - Widget View

struct TTLWidgetView: View {
    let entry: VaultEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .systemSmall:
            smallView
        case .systemMedium:
            mediumView
        case .systemLarge:
            largeView
        case .accessoryRectangular, .accessoryCircular:
            compactView
        default:
            smallView
        }
    }

    // MARK: .systemSmall — vault name + TTL countdown only
    private var smallView: some View {
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

    // MARK: .systemMedium — TTL + balance
    private var mediumView: some View {
        VStack(alignment: .leading, spacing: 6) {
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
            HStack {
                Label(entry.balance, systemImage: "dollarsign.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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

    // MARK: .systemLarge — TTL + balance + beneficiary
    private var largeView: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Ethos-Protocol", systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(.blue)
            Text(entry.vaultName)
                .font(.title3.bold())
                .lineLimit(1)
            Divider()
            if let ttl = entry.ttlRemaining {
                LabeledContent("TTL") {
                    Text(formatDuration(ttl))
                        .foregroundStyle(entry.isExpiringSoon ? .orange : .primary)
                }
                .font(.subheadline)
            } else {
                LabeledContent("TTL") {
                    Text("—").foregroundStyle(.secondary)
                }
                .font(.subheadline)
            }
            LabeledContent("Balance") {
                Text(entry.balance)
                    .foregroundStyle(.secondary)
            }
            .font(.subheadline)
            LabeledContent("Beneficiary") {
                Text(entry.beneficiary)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .font(.subheadline)
            if entry.isExpiringSoon {
                Label("Expiring soon", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.top, 4)
            }
            Spacer()
        }
        .padding()
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
    }

    // MARK: .accessoryRectangular / .accessoryCircular — compact lock-screen view
    private var compactView: some View {
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
        AppIntentConfiguration(kind: kind, intent: VaultSelectionIntent.self, provider: TTLTimelineProvider()) { entry in
            TTLWidgetView(entry: entry)
        }
        .configurationDisplayName("TTL Vault Status")
        .description("Shows your vault's TTL countdown. Tap to configure which vault to display.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular, .accessoryCircular])
    }
}

// MARK: - Widget Bundle Entry Point (app extension @main)

@main
struct TTLWidgetBundle: WidgetBundle {
    var body: some Widget {
        TTLWidget()
    }
}
