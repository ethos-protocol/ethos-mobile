// Issues #802, #803, #804, #801 - Mobile App Fixes

// ============================================================================
// Issue #802: Kotlin String.format with explicit Locale
// ============================================================================

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// FIX: Format XLM values with Locale.US for machine-readable, device locale for display
fun formatXLMValue(value: Double, forDisplay: Boolean = false): String {
    val locale = if (forDisplay) Locale.getDefault() else Locale.US
    return String.format(locale, "%.7f", value)
}

fun formatBalance(balance: Long): String {
    val xlm = balance / 10_000_000.0
    return String.format(Locale.US, "%.7f", xlm)
}

fun formatCurrency(amount: Double, symbol: String = "$"): String {
    return String.format(Locale.getDefault(), "%s%.2f", symbol, amount)
}

fun formatPercentage(percent: Double): String {
    return String.format(Locale.US, "%.2f%%", percent)
}

// ============================================================================
// Issue #804: AssetCache with ConcurrentHashMap
// ============================================================================

object AssetCache {
    // FIX: Use ConcurrentHashMap instead of mutableMapOf
    private val statusColorCache = ConcurrentHashMap<String, Int>()
    private val statusIconCache = ConcurrentHashMap<String, String>()

    fun getStatusColor(status: String): Int? = statusColorCache[status]

    fun setStatusColor(status: String, color: Int) {
        statusColorCache[status] = color
    }

    fun getStatusIcon(status: String): String? = statusIconCache[status]

    fun setStatusIcon(status: String, icon: String) {
        statusIconCache[status] = icon
    }

    fun clear() {
        statusColorCache.clear()
        statusIconCache.clear()
    }
}

// ============================================================================
// Issue #803: Swift String(format:) with explicit Locale
// ============================================================================

/*
// Swift implementation (would be in Models.swift and similar files)

import Foundation

// FIX: Format with explicit locales
func formatXLMValue(_ value: Double, forDisplay: Bool = false) -> String {
    let locale = forDisplay ? Locale.current : Locale(identifier: "en_US_POSIX")
    let formatter = NumberFormatter()
    formatter.locale = locale
    formatter.minimumFractionDigits = 7
    formatter.maximumFractionDigits = 7
    return formatter.string(from: NSNumber(value: value)) ?? "0.0000000"
}

func formatBalance(_ balance: Int64) -> String {
    let xlm = Decimal(balance) / 10_000_000
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.minimumFractionDigits = 7
    formatter.maximumFractionDigits = 7
    return formatter.string(from: NSDecimalNumber(decimal: xlm)) ?? "0.0000000"
}

func formattedBalance(_ balance: Int64) -> String {
    // FIX: Use Decimal instead of Double for precision
    let xlm = Decimal(balance) / 10_000_000
    let formatter = NumberFormatter()
    formatter.locale = Locale.current
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 7
    return formatter.string(from: NSDecimalNumber(decimal: xlm)) ?? "0.00"
}

// FIX: Replace all String(format:) with NumberFormatter for display
func formatCurrency(_ amount: Double, symbol: String = "$") -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale.current
    formatter.currencySymbol = symbol
    formatter.minimumFractionDigits = 2
    formatter.maximumFractionDigits = 2
    return formatter.string(from: NSNumber(value: amount)) ?? "\(symbol)0.00"
}
*/

// ============================================================================
// Issue #801: iOS formattedBalance with Decimal (companion Kotlin version)
// ============================================================================

fun formattedBalance(balanceStroops: Long): String {
    // FIX: Use Decimal arithmetic for safety (Kotlin equivalent)
    val xlm = balanceStroops.toBigDecimal() / 10_000_000.toBigDecimal()
    return String.format(Locale.getDefault(), "%.2f XLM", xlm)
}

// Test cases
fun testFormattedBalance() {
    // Test 7-decimal balance
    val balance = 1_234_567L  // 0.1234567 XLM
    val formatted = formattedBalance(balance)
    println("Formatted: $formatted")  // Should be "0.12 XLM"
}
