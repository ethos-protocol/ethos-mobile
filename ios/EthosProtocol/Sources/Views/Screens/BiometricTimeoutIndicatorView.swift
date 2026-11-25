import SwiftUI

struct BiometricTimeoutIndicatorView: View {
    let indicator: BiometricTimeoutIndicatorService

    var body: some View {
        VStack {
            HStack {
                Image(systemName: "clock.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)

                Text("Session expires in \\(formatTime(indicator.remainingSeconds))")
                    .font(.caption)
                    .foregroundStyle(.orange)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    // Background
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color(.systemGray5))

                    // Progress fill
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.orange)
                        .frame(width: geometry.size.width * indicator.progressFraction)
                }
            }
            .frame(height: 4)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .background(Color(.systemBackground))
        .border(Color(.systemGray4), width: 0.5)
    }

    private func formatTime(_ seconds: Int) -> String {
        let minutes = seconds / 60
        let secs = seconds % 60
        if minutes > 0 {
            return "\(minutes)m \(secs)s"
        } else {
            return "\(secs)s"
        }
    }
}
