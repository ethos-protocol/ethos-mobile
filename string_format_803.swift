// Issue #803 - Swift String(format:) with explicit Locale

import Foundation

// FIX: Format XLM values with explicit locales
func formatXLMValue(_ value: Double, forDisplay: Bool = false) -> String {
    let locale = forDisplay ? Locale.current : Locale(identifier: "en_US_POSIX")
    let formatter = NumberFormatter()
    formatter.locale = locale
    formatter.minimumFractionDigits = 7
    formatter.maximumFractionDigits = 7
    return formatter.string(from: NSNumber(value: value)) ?? "0.0000000"
}

// FIX: Replace String(format:) with NumberFormatter for display
func formatCurrency(_ amount: Double, symbol: String = "$") -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale.current
    formatter.currencySymbol = symbol
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    return formatter.string(from: NSNumber(value: amount)) ?? "\(symbol)0.00"
}

func formatPercentage(_ percent: Double) -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    return formatter.string(from: NSNumber(value: percent)) ?? "0.00"
}

func formatBalance(_ balance: Int64) -> String {
    let xlm = Decimal(balance) / 10_000_000
    let formatter = NumberFormatter()
    formatter.locale = Locale.current
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 7
    return formatter.string(from: NSDecimalNumber(decimal: xlm)) ?? "0.00"
}
