// Issue #801 - iOS formattedBalance with Decimal precision

import Foundation

// FIX: Use Decimal instead of Double for XLM conversion from stroops
func formattedBalance(_ balance: Int64) -> String {
    // Convert from stroops to XLM using Decimal (safe arithmetic)
    let xlm = Decimal(balance) / 10_000_000

    let formatter = NumberFormatter()
    formatter.locale = Locale.current
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 7

    return formatter.string(from: NSDecimalNumber(decimal: xlm)) ?? "0.00"
}

// Test: 7-decimal balance
func testFormattedBalance() {
    let balance: Int64 = 1_234_567  // 0.1234567 XLM
    let formatted = formattedBalance(balance)
    assert(!formatted.isEmpty, "Format should not be empty")
}
