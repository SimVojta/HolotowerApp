package com.holotower.app.data.network

import android.os.SystemClock

object ForegroundChallengePolicy {
    private const val RECHECK_AFTER_IDLE_MS = 8 * 60 * 1000L

    @Volatile
    private var lastBackgroundedAtElapsedMs: Long = 0L

    fun onAppBackgrounded() {
        lastBackgroundedAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun backgroundToken(): Long = lastBackgroundedAtElapsedMs

    fun shouldRecheckFor(backgroundToken: Long): Boolean {
        if (backgroundToken <= 0L) return false
        return SystemClock.elapsedRealtime() - backgroundToken >= RECHECK_AFTER_IDLE_MS
    }
}
