package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import android.util.Base64
import com.ethosprotocol.services.PendingActionDatabaseKeyProvider
import com.ethosprotocol.services.KeyWrapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Covers #726: the DB key is stable across calls and never stored in plain form. */
@RunWith(RobolectricTestRunner::class)
class PendingActionDatabaseKeyProviderTest {

    /** Reversible stand-in for the Keystore wrapper; the real one needs AndroidKeyStore. */
    private object XorWrapper : KeyWrapper {
        private const val MASK = 0x5A
        override fun wrap(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor MASK).toByte() }
        override fun unwrap(wrapped: ByteArray) = wrap(wrapped)
    }

    private lateinit var prefs: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(PendingActionDatabaseKeyProvider.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun provider() = PendingActionDatabaseKeyProvider(prefs, XorWrapper)

    @Test
    fun `getKey returns a 32-byte key that is stable across calls`() {
        val first = provider().getKey()
        val second = provider().getKey()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun `raw key is not written to preferences`() {
        val key = provider().getKey()
        val stored = prefs.getString(PendingActionDatabaseKeyProvider.KEY_WRAPPED, null)

        assertEquals(true, stored != null)
        assertNotEquals(Base64.encodeToString(key, Base64.NO_WRAP), stored)
        prefs.all.values.forEach { value ->
            assertFalse(value.toString().contains(Base64.encodeToString(key, Base64.NO_WRAP)))
        }
    }
}
