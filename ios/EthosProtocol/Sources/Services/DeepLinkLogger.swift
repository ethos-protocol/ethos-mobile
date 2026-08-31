import Foundation

/// Logs deep-link usage for analytics and observability
final class DeepLinkLogger {
    static let shared = DeepLinkLogger()
    private init() {}

    /// Analytics event for deep-link tracking
    enum DeepLinkEvent: String, Codable {
        case vaultInvitation = "deep_link_vault_invitation"
        case beneficiaryAcceptance = "deep_link_beneficiary_acceptance"
        case vaultActionCheckIn = "deep_link_vault_action_check_in"
        case vaultActionWithdraw = "deep_link_vault_action_withdraw"
        case vaultActionViewDetails = "deep_link_vault_action_view_details"
        case vaultActionManageBeneficiary = "deep_link_vault_action_manage_beneficiary"
        /// #258: Account recovery via emailed link.
        case recoveryLink = "deep_link_recovery_link"
    }

    private var eventLog: [DeepLinkLogEntry] = []
    private let queue = DispatchQueue(label: "com.ethos-protocol.deep-link-logger", attributes: .concurrent)

    /// Logs a deep-link parse event
    /// - Parameter event: The type of deep-link event that was parsed
    func log(event: DeepLinkEvent) {
        let entry = DeepLinkLogEntry(event: event, timestamp: Date())
        queue.async(flags: .barrier) {
            self.eventLog.append(entry)
        }
    }

    /// Retrieves all logged events
    /// - Returns: Array of logged deep-link events
    func getLoggedEvents() -> [DeepLinkLogEntry] {
        queue.sync {
            self.eventLog
        }
    }

    /// Clears all logged events
    func clearLog() {
        queue.async(flags: .barrier) {
            self.eventLog.removeAll()
        }
    }

    /// Gets the count of logged events
    /// - Returns: Number of events logged
    func getEventCount() -> Int {
        queue.sync {
            self.eventLog.count
        }
    }
}

/// A single entry in the deep-link event log
struct DeepLinkLogEntry: Codable {
    let event: DeepLinkLogger.DeepLinkEvent
    let timestamp: Date
}
