// Issues #800, #799, #798, #797 - Android Fixes

import java.math.BigDecimal
import java.math.RoundingMode

// ============================================================================
// Issue #800: Parse XLM as BigDecimal, not Double
// ============================================================================

// FIX: Parse with BigDecimal and scale properly
fun parseXLMAmount(input: String): Result<Long> {
    return try {
        val bd = BigDecimal(input)
        // Scale to 7 decimal places, round down
        val scaled = bd.setScale(7, RoundingMode.DOWN)
        val stroops = scaled.multiply(BigDecimal("10000000")).toLong()
        Result.success(stroops)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// ============================================================================
// Issue #799: Validator checks isFinite and max 7 decimals
// ============================================================================

// FIX: Require isFinite and validate decimal places
fun validateAmount(input: String): Boolean {
    return try {
        val value = input.toDoubleOrNull() ?: return false

        // FIX: Check isFinite (rejects Infinity, NaN, 1e400)
        if (!value.isFinite()) return false

        // Check positive
        if (value <= 0) return false

        // Check max 7 decimal places
        val bd = BigDecimal(input)
        if (bd.scale() > 7) return false

        true
    } catch (e: Exception) {
        false
    }
}

// ============================================================================
// Issue #798: Null-safe state.status read
// ============================================================================

// FIX: Use null-safe read instead of !!
fun render2FAScreen(state: TwoFAState) {
    val status = state.status  // Capture as local val

    if (status != null) {
        // Render with status
        // Text(status, ...)
    } else {
        // FIX: Render loading state when status is absent
        // LoadingIndicator()
    }
}

data class TwoFAState(val status: String?)

// ============================================================================
// Issue #797: Null-safe state.error read
// ============================================================================

// FIX: Capture error as local val in if branch
fun renderErrorScreen(state: ErrorState) {
    // Instead of: Text(state.error!!, ...)
    // Do this:
    val error = state.error  // Capture before check

    if (error != null && state.hasError) {
        // FIX: Use captured local val, not state.error
        // Text(error, ...)
    }
}

data class ErrorState(val error: String?, val hasError: Boolean)

// Test cases
fun testAmountValidation() {
    assert(validateAmount("100.5") == true)        // Valid
    assert(validateAmount("0.00000001") == true)   // 8 decimals - valid
    assert(validateAmount("Infinity") == false)    // Infinity rejected
    assert(validateAmount("1e400") == false)       // 1e400 -> Infinity rejected
    assert(validateAmount("-0") == false)          // Negative zero rejected
    assert(validateAmount("NaN") == false)         // NaN rejected
}

fun testParseXLM() {
    // 1.5 XLM = 15,000,000 stroops
    val result = parseXLMAmount("1.5")
    assert(result.getOrNull() == 15_000_000L)
}
