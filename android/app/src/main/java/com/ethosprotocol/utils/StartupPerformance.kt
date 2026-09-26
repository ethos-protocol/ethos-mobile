package com.ethosprotocol.utils

import android.app.Activity
import android.os.SystemClock
import android.util.Log

object StartupPerformance {
    private const val TAG = "StartupPerformance"

    private var appStartTime: Long = 0
    private var firstFrameTime: Long = 0

    fun markAppStart() {
        appStartTime = SystemClock.uptimeMillis()
        Log.d(TAG, "App started at $appStartTime")
    }

    fun markFirstFrame(activity: Activity? = null) {
        firstFrameTime = SystemClock.uptimeMillis()
        val coldStartMs = firstFrameTime - appStartTime
        Log.d(TAG, "First frame rendered in ${coldStartMs}ms")

        activity?.window?.decorView?.viewTreeObserver?.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                    val totalMs = SystemClock.uptimeMillis() - appStartTime
                    Log.d(TAG, "Activity fully rendered in ${totalMs}ms (cold start benchmark)")
                    return true
                }
            }
        )
    }

    fun getFirstFrameTimeMs(): Long = if (firstFrameTime > 0) firstFrameTime - appStartTime else 0

    fun getColdStartTimeMs(): Long = if (appStartTime > 0) SystemClock.uptimeMillis() - appStartTime else 0
}
