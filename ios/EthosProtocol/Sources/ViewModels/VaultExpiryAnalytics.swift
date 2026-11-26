import Foundation

/// Issue #475: expiry trend analysis across the current vault list.
///
/// There's no retained history for vaults once they expire/release (the
/// server only returns the caller's current vaults), so this analyzes the
/// currently-visible vault list rather than a true historical time series.
/// "Lifespan" here is each vault's configured `checkInInterval` — how long
/// it can go between check-ins before releasing to the beneficiary — since
/// that's the only duration this client actually has, not a creation-to-
/// expiry span (vault creation dates aren't retained either).
enum VaultExpiryAnalytics {
    struct LifespanBucket: Identifiable {
        var id: String { label }
        let label: String
        let count: Int
    }

    struct UpcomingExpiry: Identifiable {
        var id: String { vaultID }
        let vaultID: String
        let predictedDate: Date
    }

    /// Distribution of vaults by check-in interval, bucketed into human-sized
    /// ranges (≤1 day, ≤1 week, ≤1 month, >1 month).
    static func lifespanDistribution(for vaults: [Vault]) -> [LifespanBucket] {
        let day: UInt64 = 86_400
        let week = day * 7
        let month = day * 30

        var buckets: [(String, Range<UInt64>)] = [
            ("≤ 1 day", 0..<(day + 1)),
            ("≤ 1 week", (day + 1)..<(week + 1)),
            ("≤ 1 month", (week + 1)..<(month + 1)),
            ("> 1 month", (month + 1)..<UInt64.max),
        ]
        var counts = [String: Int](minimumCapacity: buckets.count)
        for vault in vaults {
            if let bucket = buckets.first(where: { $0.1.contains(vault.checkInInterval) }) {
                counts[bucket.0, default: 0] += 1
            }
        }
        return buckets.map { LifespanBucket(label: $0.0, count: counts[$0.0] ?? 0) }
    }

    /// Mean check-in interval across all vaults, in seconds. Zero if there are no vaults.
    static func averageLifespanSeconds(for vaults: [Vault]) -> Double {
        guard !vaults.isEmpty else { return 0 }
        let total = vaults.reduce(0.0) { $0 + Double($1.checkInInterval) }
        return total / Double(vaults.count)
    }

    /// Each vault's predicted next deadline — `ttlRemaining`, anchored to the
    /// instant it was observed (`ttlFetchedAt`), when the server has supplied a
    /// snapshot, else `lastCheckIn + checkInInterval` — sorted soonest first.
    ///
    /// This is deliberately *not* re-derived here: it delegates to
    /// `Vault.predictedExpiryDate`, the single projection that also backs the
    /// vault detail view. #789 fixed that property to anchor the TTL to
    /// `ttlFetchedAt` rather than to "now", so an offline re-render can no
    /// longer drift the deadline forward; computing it separately here (the
    /// old `Date().addingTimeInterval(ttl)` duplicate) reintroduced exactly
    /// that bug for the trends view (#831).
    ///
    /// Ties are broken by vault ID so the ordering — and therefore the list the
    /// trends view renders — is deterministic across evaluations. At most
    /// `limit` entries are returned; a limit of zero (or less) returns none.
    static func upcomingExpirations(for vaults: [Vault], limit: Int = 5) -> [UpcomingExpiry] {
        vaults
            .map { UpcomingExpiry(vaultID: $0.id, predictedDate: $0.predictedExpiryDate) }
            .sorted { lhs, rhs in
                if lhs.predictedDate != rhs.predictedDate {
                    return lhs.predictedDate < rhs.predictedDate
                }
                return lhs.vaultID < rhs.vaultID
            }
            .prefix(max(0, limit))
            .map { $0 }
    }
}
