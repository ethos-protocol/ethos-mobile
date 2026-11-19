package com.ethosprotocol.services

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures uncaught exceptions to a local report file and then hands them to the previous
 * handler (#725). Reports are written on-device only; nothing is uploaded here.
 */
@Singleton
class CrashReporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val reportDir: File get() = File(context.filesDir, "crash_reports")

    fun initialize(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Writes a report for [throwable] and returns the file it was written to. */
    internal fun writeReport(threadName: String, throwable: Throwable): File {
        val dir = reportDir.apply { mkdirs() }
        val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText("thread: $threadName\n\n$trace")
        return file
    }
}
