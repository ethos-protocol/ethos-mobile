package com.ethosprotocol.api

import com.ethosprotocol.BuildConfig
import java.util.Collections

object ApiDebugLog {
    private val entries = Collections.synchronizedList(mutableListOf<String>())

    fun record(method: String, path: String, status: Int) {
        if (!BuildConfig.DEBUG) return
        entries += "${System.currentTimeMillis()} $method $path $status"
        while (entries.size > 100) entries.removeAt(0)
    }

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }
}
