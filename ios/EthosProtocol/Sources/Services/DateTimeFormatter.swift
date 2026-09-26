import Foundation

struct DateTimeFormatter {

    static let shared = DateTimeFormatter()

    private let timeFormatter: Foundation.DateFormatter
    private let dateTimeFormatter: Foundation.DateFormatter
    private let dateFormatter: Foundation.DateFormatter

    init() {
        timeFormatter = Foundation.DateFormatter()
        timeFormatter.timeStyle = .short
        timeFormatter.dateStyle = .none

        dateTimeFormatter = Foundation.DateFormatter()
        dateTimeFormatter.timeStyle = .short
        dateTimeFormatter.dateStyle = .short

        dateFormatter = Foundation.DateFormatter()
        dateFormatter.timeStyle = .none
        dateFormatter.dateStyle = .short
    }

    func formatTime(_ date: Date) -> String {
        timeFormatter.string(from: date)
    }

    func formatDateTime(_ date: Date) -> String {
        dateTimeFormatter.string(from: date)
    }

    func formatDate(_ date: Date) -> String {
        dateFormatter.string(from: date)
    }

    func formatDurationInSeconds(_ seconds: UInt64) -> String {
        let days = seconds / 86_400
        let hours = (seconds % 86_400) / 3_600
        let minutes = (seconds % 3_600) / 60
        let secs = seconds % 60

        switch (days, hours, minutes) {
        case (let d, _, _) where d > 0:
            return String(format: NSLocalizedString("%dd %dh", comment: "Duration format with days and hours"), d, hours)
        case (_, let h, _) where h > 0:
            return String(format: NSLocalizedString("%dh %dm", comment: "Duration format with hours and minutes"), h, minutes)
        case (_, _, let m) where m > 0:
            return String(format: NSLocalizedString("%d:%02d", comment: "Duration format with minutes and seconds"), m, secs)
        default:
            return String(format: NSLocalizedString("0:%02d", comment: "Duration format with seconds only"), secs)
        }
    }
}
