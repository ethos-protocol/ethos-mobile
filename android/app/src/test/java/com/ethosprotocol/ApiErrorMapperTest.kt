package com.ethosprotocol

import com.ethosprotocol.api.ApiCallFailedException
import com.ethosprotocol.api.ApiErrorMapper
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ApiErrorMapperTest {

    @Test
    fun `socket timeout maps to friendly timeout message`() {
        val message = ApiErrorMapper.friendlyMessage(SocketTimeoutException("timeout"))
        assertEquals("The request timed out. Check your connection and try again.", message)
    }

    @Test
    fun `unknown host maps to friendly unreachable message`() {
        val message = ApiErrorMapper.friendlyMessage(UnknownHostException("api.ethosprotocol.app"))
        assertEquals("Couldn't reach the server. Check your internet connection.", message)
    }

    @Test
    fun `ssl exception maps to friendly tls message`() {
        val message = ApiErrorMapper.friendlyMessage(SSLException("handshake failure"))
        assertEquals("A secure connection couldn't be established. Please try again.", message)
    }

    @Test
    fun `connect exception maps to friendly connect message`() {
        val message = ApiErrorMapper.friendlyMessage(ConnectException("Connection refused"))
        assertEquals("Couldn't connect to the server. Please try again.", message)
    }

    @Test
    fun `generic IOException maps to friendly network message`() {
        val message = ApiErrorMapper.friendlyMessage(IOException("broken pipe"))
        assertEquals("A network error occurred. Please try again.", message)
    }

    @Test
    fun `unrecognized exception maps to generic fallback`() {
        val message = ApiErrorMapper.friendlyMessage(IllegalStateException("boom"))
        assertEquals("Something went wrong. Please try again.", message)
    }

    @Test
    fun `ApiCallFailedException message passes through unchanged`() {
        val message = ApiErrorMapper.friendlyMessage(ApiCallFailedException("Unauthorized"))
        assertEquals("Unauthorized", message)
    }

    @Test(expected = CancellationException::class)
    fun `toApiResult rethrows cancellation instead of mapping it`() {
        ApiErrorMapper.toApiResult(CancellationException("job was cancelled"))
    }

    @Test
    fun `toApiResult logs raw exception but returns friendly message`() {
        var logged: Throwable? = null
        val result = ApiErrorMapper.toApiResult(SocketTimeoutException("timeout")) { logged = it }

        assertTrue(result is com.ethosprotocol.api.ApiResult.Error)
        assertEquals(
            "The request timed out. Check your connection and try again.",
            (result as com.ethosprotocol.api.ApiResult.Error).message
        )
        assertTrue(logged is SocketTimeoutException)
    }
}
