package com.ethosprotocol

import com.ethosprotocol.services.UsernameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameValidatorTest {

    @Test
    fun isValid_acceptsSimpleAlphanumeric() {
        assertTrue(UsernameValidator.isValid("alice123"))
    }

    @Test
    fun isValid_acceptsInteriorDotsUnderscoresHyphens() {
        assertTrue(UsernameValidator.isValid("alice.b_c-d"))
    }

    @Test
    fun isValid_rejectsBlank() {
        assertFalse(UsernameValidator.isValid(""))
        assertFalse(UsernameValidator.isValid("   "))
    }

    @Test
    fun isValid_rejectsTooShort() {
        assertFalse(UsernameValidator.isValid("ab"))
    }

    @Test
    fun isValid_rejectsTooLong() {
        assertFalse(UsernameValidator.isValid("a".repeat(UsernameValidator.MAX_LENGTH + 1)))
    }

    @Test
    fun isValid_acceptsMaxLength() {
        assertTrue(UsernameValidator.isValid("a".repeat(UsernameValidator.MAX_LENGTH)))
    }

    @Test
    fun isValid_rejectsLeadingOrTrailingSpecialCharacter() {
        assertFalse(UsernameValidator.isValid("-alice"))
        assertFalse(UsernameValidator.isValid("alice-"))
        assertFalse(UsernameValidator.isValid(".alice"))
    }

    @Test
    fun isValid_rejectsDisallowedCharacters() {
        assertFalse(UsernameValidator.isValid("alice bob"))
        assertFalse(UsernameValidator.isValid("alice@bob"))
        assertFalse(UsernameValidator.isValid("alice/bob"))
    }

    @Test
    fun isValid_treatsSurroundingWhitespaceAsTrimmed() {
        assertTrue(UsernameValidator.isValid("  alice123  "))
    }

    @Test
    fun sanitize_trimsWhitespace() {
        assertEquals("alice", UsernameValidator.sanitize("  alice  "))
    }
}
