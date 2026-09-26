package com.ethosprotocol.utils

import android.content.Context
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object DateTimeFormatter {

    fun formatTime(context: Context, timeMillis: Long): String =
        formatTime(timeMillis)

    fun formatTime(timeMillis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
            .format(Date(timeMillis))

    fun formatDateTime(context: Context, dateTimeMillis: Long): String =
        formatDateTime(dateTimeMillis)

    fun formatDateTime(dateTimeMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
            .format(Date(dateTimeMillis))

    fun formatDate(context: Context, dateMillis: Long): String =
        formatDate(dateMillis)

    fun formatDate(dateMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
            .format(Date(dateMillis))

    fun formatDurationInSeconds(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            days > 0 -> String.format(Locale.getDefault(), "%dd %dh", days, hours)
            hours > 0 -> String.format(Locale.getDefault(), "%dh %dm", hours, minutes)
            minutes > 0 -> String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
            else -> String.format(Locale.getDefault(), "0:%02d", secs)
        }
    }
}
