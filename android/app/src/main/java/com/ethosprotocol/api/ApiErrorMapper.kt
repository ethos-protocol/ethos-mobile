package com.ethosprotocol.api

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Marks a message that is already user-presentable (server status text, "No network
// connection") so ApiErrorMapper passes it through instead of replacing it with the
// generic fallback.
class ApiCallFailedException(message: String) : Exception(message)

object ApiErrorMapper {

    // Never swallow CancellationException — doing so would keep a viewModelScope.launch
    // body running (and applying side effects) after its scope was cancelled (e.g. the
    // screen was destroyed mid-request). Ktor's own timeout exceptions extend IOException,
    // not CancellationException, so real timeouts are unaffected by this rethrow.
    fun toApiResult(e: Throwable, onDebugLog: (Throwable) -> Unit = {}): ApiResult<Nothing> {
        if (e is CancellationException) throw e
        onDebugLog(e)
        return ApiResult.Error(friendlyMessage(e))
    }

    fun friendlyMessage(e: Throwable): String = when (e) {
        is ApiCallFailedException -> e.message ?: "Something went wrong. Please try again."
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        is java.net.SocketTimeoutException ->
            "The request timed out. Check your connection and try again."
        is UnknownHostException -> "Couldn't reach the server. Check your internet connection."
        is SSLException -> "A secure connection couldn't be established. Please try again."
        is ConnectException -> "Couldn't connect to the server. Please try again."
        is IOException -> "A network error occurred. Please try again."
        else -> "Something went wrong. Please try again."
    }
}
