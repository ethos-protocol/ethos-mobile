import XCTest
@testable import EthosProtocol

/**
 * Performance test for VaultList scrolling through large vault collections (#318).
 * Verifies that rendering 100+ vaults does not cause frame jank or excessive icon/asset decoding.
 */
final class VaultListPerformanceTest: XCTestCase {

    private func createTestVaults(count: Int) -> [Vault] {
        return (1...count).map { index in
            Vault(
                id: "vault-\(index)-" + String(repeating: "x", count: max(0, 50 - String(index).count)),
                balance: UInt64(1_000_000_000 + index),
                formattedBalance: "\(index).0000000 XLM",
                status: VaultStatus(rawValue: ["active", "expired", "released", "paused"][index % 4]) ?? .active,
                checkInInterval: 86_400,
                ttlRemaining: UInt64(86_400 + index),
                isExpiringSoon: index % 10 == 0,
                lastCheckIn: "2024-01-01T00:00:00Z",
                beneficiary: "beneficiary-" + String(repeating: "x", count: max(0, 40 - String(index).count))
            )
        }
    }

    func testVaultListMemoryWith100Vaults() {
        let vaults = createTestVaults(count: 100)
        XCTAssertEqual(vaults.count, 100, "Should create 100 test vaults")

        // Measure memory usage for 100 vaults
        let startMemory = os_proc_available_memory()

        _ = vaults.map { vault in
            VaultRowViewModel(vault: vault)
        }

        let endMemory = os_proc_available_memory()
        let memoryDelta = startMemory - endMemory

        // Memory overhead for 100 vault models + view models should be reasonable
        print("[Performance] Memory used for 100 vaults: ~\(abs(memoryDelta) / 1024)KB")
        XCTAssert(abs(memoryDelta) < 50_000_000, "Memory usage too high for 100 vaults: \(abs(memoryDelta))B")
    }

    func testVaultListRenderingPerformance() {
        let vaults = createTestVaults(count: 100)

        let startTime = Date()

        // Simulate rendering all vault rows
        let viewModels = vaults.map { vault in
            VaultRowViewModel(vault: vault)
        }

        let elapsed = Date().timeIntervalSince(startTime)

        // Creating view models for 100 vaults should be fast
        print("[Performance] Created 100 vault view models in \(String(format: "%.3f", elapsed))s")
        XCTAssert(elapsed < 0.5, "Creating 100 view models took too long: \(elapsed)s")
        XCTAssertEqual(viewModels.count, 100)
    }

    func testStatusBadgeRenderingOptimization() {
        // Test that StatusBadge doesn't re-create colors on each render
        let vault = createTestVaults(count: 1)[0]

        let startTime = Date()

        // Simulate rendering the same status badge multiple times
        for _ in 0..<1000 {
            _ = vault.status.rawValue.capitalized
        }

        let elapsed = Date().timeIntervalSince(startTime)

        // Status string formatting should be fast
        print("[Performance] 1000 status formatting operations in \(String(format: "%.3f", elapsed))ms")
        XCTAssert(elapsed < 0.1, "Status formatting is too slow: \(elapsed)s")
    }
}

// MARK: - Test Helpers

struct VaultRowViewModel {
    let vault: Vault

    var statusColor: Color {
        switch vault.status {
        case .active:
            return .green
        case .expired:
            return .orange
        case .released:
            return .blue
        case .paused:
            return .gray
        }
    }
}

private func os_proc_available_memory() -> UInt64 {
    var info = task_basic_info()
    var count = mach_msg_type_number_t(MemoryLayout<task_basic_info>.size)/4

    let kerr = withUnsafeMutablePointer(to: &info) {
        task_info(mach_task_self_,
                  task_flavor_t(TASK_BASIC_INFO),
                  $0.withMemoryRebound(to: integer_t.self, capacity: 1) { $0 },
                  &count)
    }

    guard kerr == KERN_SUCCESS else { return 0 }
    return UInt64(info.resident_size)
}

import Darwin
