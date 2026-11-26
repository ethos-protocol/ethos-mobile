import SwiftUI

// MARK: - Vault List

struct VaultListView: View {
    @EnvironmentObject var vaultStore: VaultStore
    @EnvironmentObject var authStore: AuthStore
    @State private var showCreate = false
    @State private var showQRScanner = false // Issue #472
    @State private var showDeepLinkSheet = false
    @State private var showSettings = false
    @State private var showFilterMenu = false
    // #118: Non-blocking jailbreak/root warning. Dismissed by the user; does not
    // block access to the app, consistent with the "secure digital inheritance" posture.
    @State private var showIntegrityWarning = IntegrityService.shared.isJailbroken

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let error = vaultStore.error {
                    ErrorActionView(error: error, retry: { Task { await vaultStore.load() } })
                        .padding()
                }
                if vaultStore.isLoading && vaultStore.vaults.isEmpty {
                    ProgressView("Loading vaults…").respectsReduceMotion()
                } else if vaultStore.vaults.isEmpty {
                    ContentUnavailableView("No Vaults", systemImage: "lock.open", description: Text("Create your first vault to get started."))
                } else {
                    VStack(spacing: 12) {
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(.secondary)
                            TextField("Search vaults", text: $vaultStore.searchText)
                                .textFieldStyle(.roundedBorder)
                            if !vaultStore.searchText.isEmpty {
                                Button(action: { vaultStore.searchText = "" }) {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .padding(.horizontal)

                        HStack(spacing: 12) {
                            Menu {
                                Picker("Status", selection: $vaultStore.statusFilter) {
                                    ForEach(VaultStatusFilter.allCases, id: \.self) { filter in
                                        Text(filter.rawValue).tag(filter)
                                    }
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "line.3.horizontal.decrease.circle")
                                    Text(vaultStore.statusFilter.rawValue)
                                        .font(.caption2)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .background(Color.blue.opacity(0.1))
                                .cornerRadius(6)
                            }

                            Menu {
                                Picker("Sort", selection: $vaultStore.sortOption) {
                                    ForEach(VaultSortOption.allCases, id: \.self) { option in
                                        Text(option.rawValue).tag(option)
                                    }
                                }
                            } label: {
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.up.arrow.down")
                                    Text(vaultStore.sortOption.rawValue)
                                        .font(.caption2)
                                }
                                .padding(.horizontal, 8)
                                .padding(.vertical, 6)
                                .background(Color.blue.opacity(0.1))
                                .cornerRadius(6)
                            }

                            // Issue #477: filter by tag, when any tag has been assigned.
                            let knownTags = VaultTagStore.allKnownTags()
                            if !knownTags.isEmpty {
                                Menu {
                                    Button("All Tags") { vaultStore.tagFilter = nil }
                                    ForEach(knownTags, id: \.self) { tag in
                                        Button(tag) { vaultStore.tagFilter = tag }
                                    }
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: "tag")
                                        Text(vaultStore.tagFilter ?? "All Tags")
                                            .font(.caption2)
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 6)
                                    .background(Color.blue.opacity(0.1))
                                    .cornerRadius(6)
                                }
                            }

                            Spacer()
                        }
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                    }
                    .padding(.vertical, 8)
                    .background(Color(.systemBackground))

                    List {
                        if let lastSync = vaultStore.lastSyncTime {
                            HStack {
                                Label("Last sync: \(Self.formatSyncTime(lastSync))", systemImage: "clock.badge.checkmark.fill")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets())
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                        }
                        if vaultStore.filteredAndSortedVaults.isEmpty && !vaultStore.searchText.isEmpty {
                            ContentUnavailableView("No Results", systemImage: "magnifyingglass", description: Text("No vaults match your search."))
                                .listRowSeparator(.hidden)
                        } else {
                            ForEach(vaultStore.filteredAndSortedVaults) { vault in
                                NavigationLink(destination: VaultDetailView(vault: vault)) {
                                    VaultRowView(vault: vault)
                                }
                            }
                            if vaultStore.hasMorePages && vaultStore.filteredAndSortedVaults.count == vaultStore.vaults.count {
                                LoadMoreRow(isLoading: vaultStore.isLoadingMore) {
                                    Task { await vaultStore.loadMore() }
                                }
                            }
                        }
                    }
                    .refreshable { await vaultStore.load() }
                }
            }
            .navigationTitle("My Vaults")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: { showCreate = true }) { Image(systemName: "plus") }
                        .accessibilityLabel("Create new vault")
                }
                ToolbarItem(placement: .primaryAction) {
                    // Issue #472
                    Button(action: { showQRScanner = true }) { Image(systemName: "qrcode.viewfinder") }
                        .accessibilityLabel("Scan vault QR code")
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu {
                        NavigationLink(destination: SettingsView()) {
                            Label("Settings", systemImage: "gear")
                        }
                        // Issue #474
                        NavigationLink(destination: VaultMergeView()) {
                            Label("Duplicate Vaults", systemImage: "arrow.triangle.merge")
                        }
                        // Issue #475
                        NavigationLink(destination: VaultExpiryTrendsView()) {
                            Label("Expiry Trends", systemImage: "chart.bar")
                        }
                        Button("Sign Out") { Task { await authStore.signOut() } }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .task { await vaultStore.load() }
            .sheet(isPresented: $showCreate) { CreateVaultView() }
            .sheet(isPresented: $showQRScanner) { VaultQRScannerView() } // Issue #472
            .sheet(isPresented: $showDeepLinkSheet, onDismiss: { vaultStore.pendingDeepLink = nil }) {
                if let link = vaultStore.pendingDeepLink {
                    DeepLinkView(link: link)
                }
            }
            .onChange(of: vaultStore.pendingDeepLink) { _, link in
                if link != nil { showDeepLinkSheet = true }
            }
            // #118: Non-blocking jailbreak warning — dismissible by the user.
            .alert("Security Warning", isPresented: $showIntegrityWarning) {
                Button("I Understand", role: .cancel) { showIntegrityWarning = false }
            } message: {
                Text("This device appears to be jailbroken. Your vault data, passkeys, and 2FA secrets may be at greater risk. Consider using a stock device for maximum security.")
            }
        }
        .trackScreen("VaultList")
    }

    // Surfaces staleness (issue #25) and any check-ins still waiting to sync (issue #28) above
    // the vault list, so both stay visible without blocking the list itself.
    @ViewBuilder
    private var statusBanners: some View {
        if let age = vaultStore.vaultsCacheAge {
            StatusBannerView(
                text: "Offline — showing vaults from \(Self.relativeAge(age))",
                systemImage: "wifi.slash",
                color: .orange)
        }
        if vaultStore.queuedCheckInCount > 0 {
            StatusBannerView(
                text: vaultStore.queuedCheckInCount == 1
                    ? "1 check-in queued — will retry when back online"
                    : "\(vaultStore.queuedCheckInCount) check-ins queued — will retry when back online",
                systemImage: "clock.arrow.circlepath",
                color: .blue)
        }
    }

    /// #782: allocated once rather than per label. Date/time styles follow the user's locale.
    private static let absoluteSyncTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    private static func relativeAge(_ interval: TimeInterval) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(fromTimeInterval: -interval)
    }

    private static func formatSyncTime(_ date: Date) -> String {
        let now = Date()
        let interval = now.timeIntervalSince(date)

        switch interval {
        case 0..<60:
            return "just now"
        case 60..<3_600:
            let minutes = Int(interval) / 60
            return minutes == 1 ? "1 min ago" : "\(minutes) mins ago"
        case 3_600..<86_400:
            let hours = Int(interval) / 3_600
            return hours == 1 ? "1 hour ago" : "\(hours) hours ago"
        default:
            return absoluteSyncTimeFormatter.string(from: date)
        }
    }
}

struct StatusBannerView: View {
    let text: String
    let systemImage: String
    let color: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption)
            .foregroundStyle(color)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal)
            .padding(.vertical, 6)
            .background(color.opacity(0.1))
    }
}

/// Trailing row in VaultListView's list: a "Load More" button while a further
/// page is available, or a spinner while that page is being fetched.
struct LoadMoreRow: View {
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        HStack {
            Spacer()
            if isLoading {
                ProgressView().respectsReduceMotion()
            } else {
                Button("Load More", action: action)
                    .font(.subheadline)
            }
            Spacer()
        }
        .listRowSeparator(.hidden)
    }
}

struct VaultRowView: View {
    let vault: Vault
    @EnvironmentObject var vaultStore: VaultStore
    @State private var showDeleteConfirmation = false
    // Issue #477: VaultTagStore is a plain UserDefaults-backed store, not an
    // ObservableObject, so tags are mirrored into local @State and refreshed
    // after every mutation to drive re-rendering.
    @State private var tags: [String] = []
    @State private var showAddTag = false
    @State private var newTagText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                CopyableIDView(fullID: vault.id, displayLength: 12)
                Spacer()
                StatusBadge(status: vault.status)
            }
            Text(vault.formattedBalance)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            if vault.isExpiringSoon {
                Label("Expiring soon!", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            } else if vault.expiryRiskLevel() == .approaching {
                // #480: predicted-expiry planning hint, one tier below the
                // existing server-driven "expiring soon" banner.
                Text("Predicted check-in deadline: \(vault.predictedExpiryDate.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            if !tags.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            Text(tag)
                                .font(.caption2)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .onAppear { tags = VaultTagStore.tags(for: vault.id) }
        .contextMenu {
            Button { showAddTag = true } label: {
                Label("Add Tag", systemImage: "tag")
            }
            ForEach(tags, id: \.self) { tag in
                Button(role: .destructive) {
                    VaultTagStore.removeTag(tag, from: vault.id)
                    tags = VaultTagStore.tags(for: vault.id)
                } label: {
                    Label("Remove \"\(tag)\"", systemImage: "tag.slash")
                }
            }
        }
        .alert("Add Tag", isPresented: $showAddTag) {
            TextField("Tag name", text: $newTagText)
            Button("Add") {
                VaultTagStore.addTag(newTagText, to: vault.id)
                tags = VaultTagStore.tags(for: vault.id)
                newTagText = ""
            }
            Button("Cancel", role: .cancel) { newTagText = "" }
        }
        .swipeActions(edge: .leading, allowsFullSwipe: false) {
            Button(action: { performCheckIn() }) {
                Label("Check In", systemImage: "checkmark.circle.fill")
            }
            .tint(.green)

            Button(action: { performRefresh() }) {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .tint(.blue)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive, action: { showDeleteConfirmation = true }) {
                Label("Delete", systemImage: "trash.fill")
            }
        }
        .alert("Delete Vault", isPresented: $showDeleteConfirmation) {
            Button("Delete", role: .destructive) { performDelete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Are you sure you want to delete this vault? This action cannot be undone.")
        }
    }

    private func performCheckIn() {
        triggerHaptic()
        Task {
            await vaultStore.checkIn(vault: vault)
        }
    }

    private func performRefresh() {
        triggerHaptic()
        Task {
            await vaultStore.load()
        }
    }

    private func performDelete() {
        triggerHaptic()
        Task {
            ifNotCancelled {
                vaultStore.error = ErrorPresentation(message: "Vault deletion is not yet implemented")
            }
        }
    }

    private func triggerHaptic() {
        let impactFeedback = UIImpactFeedbackGenerator(style: .medium)
        impactFeedback.impactOccurred()
    }
}

struct StatusBadge: View {
    let status: Vault.VaultStatus
    var body: some View {
        Text(status.rawValue.capitalized)
            .font(.caption.bold())
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(color.opacity(0.15))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }
    private var color: Color {
        switch status {
        case .active:   return .green
        case .expired:  return .orange
        case .released: return .blue
        case .paused:   return .gray
        }
    }
}
